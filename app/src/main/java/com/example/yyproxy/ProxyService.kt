package com.example.yyproxy

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 前台代理服务。
 *
 * 它的职责已经被收敛成“配置编排层”：
 * 1. 从持久化配置中读取启用的规则。
 * 2. 为每条规则启动或停止一个 NIO 端口转发器。
 * 3. 通过前台通知让系统尽量长期保活服务。
 * 4. 如果开启了自动热点选项，则定期检查并请求无障碍自动化流程。
 */
class ProxyService : Service() {

    private val channelId = "ProxyServiceChannel"
    private val autoHotspotSupport = AutoHotspotSupport.forDevice(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdk = Build.VERSION.SDK_INT
    )
    @Volatile
    private var isShuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private val hotspotCheckInterval = 10 * 60 * 1000L // 10 minutes
    private val automationStepTimeoutMs = 20 * 1000L

    private val hotspotCheckRunnable = object : Runnable {
        override fun run() {
            checkAndEnableHotspot(AutomationTrigger.PERIODIC_CHECK)
            handler.postDelayed(this, hotspotCheckInterval)
        }
    }

    private val automationStepTimeoutRunnable = object : Runnable {
        override fun run() {
            hotspotAutomationCoordinator.onStepTimeout()
            syncAutomationStepTimeout()
        }
    }

    private val hotspotAutomationCoordinator = HotspotAutomationCoordinator(
        settingsLauncher = object : HotspotSettingsLauncher {
            override fun launchHotspotSettings() {
                val intent = Intent(HOTSPOT_SETTINGS_ACTION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) == null) {
                    handleHotspotSettingsLaunchFailure("No activity can handle hotspot settings intent")
                    return
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    handleHotspotSettingsLaunchFailure("Hotspot settings activity is unavailable", e)
                } catch (e: RuntimeException) {
                    handleHotspotSettingsLaunchFailure("Failed to launch hotspot settings", e)
                }
            }
        },
        hotspotStateReader = { HotspotManager.getHotspotState(this) == HotspotManager.HotspotState.ENABLED },
        accessibilityEnabled = { AccessibilityServiceState.isAutomationServiceEnabled(this) }
    )

    private val runtimeBridge = object : UiAutomationCoordinatorBridge {
        override fun handleWindowUpdate(
            service: AccessibilityService,
            root: AccessibilityNodeInfo,
            packageName: String,
            className: String
        ) {
            if (packageName != SETTINGS_PACKAGE_NAME) {
                return
            }
            val rootSnapshot = AccessibilityNodeInfo.obtain(root)
            val posted = handler.post {
                try {
                    if (isShuttingDown) {
                        return@post
                    }
                    handleAutomationWindowUpdate(service = service, root = rootSnapshot, className = className)
                    syncAutomationStepTimeout()
                } finally {
                    rootSnapshot.recycle()
                }
            }
            if (!posted) {
                rootSnapshot.recycle()
            }
        }
    }

    private val taskCoordinator = ProxyTaskCoordinator(
        object : ProxyTaskFactory {
            override fun start(config: ProxyConfig): ProxyTaskHandle {
                val forwarder = NioPortForwarder(
                    localPort = config.localPort,
                    remoteHost = config.remoteHost,
                    remotePort = config.remotePort
                ).also { it.start() }
                Log.d(
                    "ProxyService",
                    "Listening on ${forwarder.localPort} for ${config.name} -> ${config.remoteHost}:${config.remotePort}"
                )
                return object : ProxyTaskHandle {
                    override fun stop() {
                        forwarder.close()
                    }
                }
            }
        }
    )

    override fun onCreate() {
        super.onCreate()
        isShuttingDown = false
        createNotificationChannel()
        HotspotAutomationRuntime.coordinator = runtimeBridge
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        // 每次被启动都重新对齐一次配置，让 UI 改动能即时同步到前台服务。
        updateTasks()
        refreshNotification()

        // 启动或更新热点检查任务
        handler.removeCallbacks(hotspotCheckRunnable)
        if (intent?.action == ACTION_REFRESH) {
            Log.d("ProxyService", "Manual refresh: triggering immediate hotspot check")
            checkAndEnableHotspot(AutomationTrigger.MANUAL_REFRESH)
        }
        
        if (ProxySettings.isAutoHotspotEnabled(this) && autoHotspotSupport.isSupported) {
            handler.post(hotspotCheckRunnable)
        }
        syncAutomationStepTimeout()

        return START_STICKY
    }

    private fun checkAndEnableHotspot(trigger: AutomationTrigger) {
        val hotspotState = HotspotManager.getHotspotState(this)
        val accessibilityEnabled = AccessibilityServiceState.isAutomationServiceEnabled(this)
        val isSupported = autoHotspotSupport.isSupported
        
        // Manual refresh always tries if supported and accessibility is on.
        // Periodic check only tries if auto-hotspot is enabled.
        val shouldAllowByPolicy = if (trigger == AutomationTrigger.MANUAL_REFRESH) {
            isSupported
        } else {
            ProxySettings.isAutoHotspotEnabled(this) && isSupported
        }

        val shouldRequest = shouldAllowByPolicy &&
            accessibilityEnabled &&
            hotspotState == HotspotManager.HotspotState.DISABLED

        Log.d("ProxyService", "Hotspot check ($trigger): state=$hotspotState, accessibility=$accessibilityEnabled, policy=$shouldAllowByPolicy -> shouldRequest=$shouldRequest")

        if (!shouldRequest) {
            syncAutomationStepTimeout()
            return
        }

        if (hotspotAutomationCoordinator.requestAutomation(trigger)) {
            Log.i("ProxyService", "Successfully requested hotspot automation (trigger: $trigger)")
        } else {
            Log.w("ProxyService", "Failed to request hotspot automation (already active or state mismatch)")
        }
        syncAutomationStepTimeout()
    }

    private fun handleHotspotSettingsLaunchFailure(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w("ProxyService", message)
        } else {
            Log.w("ProxyService", message, error)
        }
        handler.post {
            hotspotAutomationCoordinator.onStepTimeout()
            syncAutomationStepTimeout()
        }
    }

    private fun handleAutomationWindowUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        className: String
    ) {
        when (hotspotAutomationCoordinator.snapshot().stage) {
            AutomationStage.OPENING_HOTSPOT_SETTINGS,
            AutomationStage.ENABLING_HOTSPOT,
            AutomationStage.VERIFYING -> {
                if (isHotspotMainPage(className = className, root = root)) {
                    when (hotspotAutomationCoordinator.snapshot().stage) {
                        AutomationStage.OPENING_HOTSPOT_SETTINGS,
                        AutomationStage.VERIFYING -> handleHotspotMainPageUpdate(
                            service = service,
                            root = root,
                            className = className
                        )
                        AutomationStage.ENABLING_HOTSPOT -> handleEnableHotspotPageUpdate(
                            service = service,
                            root = root,
                            className = className
                        )
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }

    private fun handleHotspotMainPageUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        className: String
    ) {
        if (!isHotspotMainPage(className = className, root = root)) {
            return
        }
        withBestSwitch(
            root = root,
            viewId = HOTSPOT_MAIN_SWITCH_VIEW_ID,
            rowTextHints = HOTSPOT_MAIN_ROW_TEXT_HINTS,
            allowSingleFallback = rootContainsAnyText(root, HOTSPOT_MAIN_PAGE_TEXT_HINTS)
        ) { mainSwitch ->
            hotspotAutomationCoordinator.onHotspotMainPage(mainSwitchChecked = mainSwitch.isChecked)
            when (hotspotAutomationCoordinator.snapshot().stage) {
                AutomationStage.ENABLING_HOTSPOT -> handleEnableHotspotPageUpdate(
                    service = service,
                    root = root,
                    className = className
                )
                AutomationStage.COMPLETED -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                else -> Unit
            }
        }
    }

    private fun handleEnableHotspotPageUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        className: String
    ) {
        if (!isHotspotMainPage(className = className, root = root)) {
            return
        }
        withBestSwitch(
            root = root,
            viewId = HOTSPOT_MAIN_SWITCH_VIEW_ID,
            rowTextHints = HOTSPOT_MAIN_ROW_TEXT_HINTS,
            allowSingleFallback = rootContainsAnyText(root, HOTSPOT_MAIN_PAGE_TEXT_HINTS)
        ) { mainSwitch ->
            if (!mainSwitch.isChecked) {
                mainSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                hotspotAutomationCoordinator.onHotspotSwitchClicked()
                return@withBestSwitch
            }
            hotspotAutomationCoordinator.onHotspotMainPage(mainSwitchChecked = true)
            if (hotspotAutomationCoordinator.snapshot().stage == AutomationStage.COMPLETED) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun isHotspotMainPage(className: String, root: AccessibilityNodeInfo): Boolean {
        val hasHotspotSwitchNodes = hasNodeWithViewId(root, HOTSPOT_MAIN_SWITCH_VIEW_ID)
        if (!hasHotspotSwitchNodes) {
            return false
        }

        val classMatched = containsAnyIgnoreCase(className, HOTSPOT_MAIN_PAGE_CLASS_HINTS)
        val pageTextMatched = rootContainsAnyText(root, HOTSPOT_MAIN_PAGE_TEXT_HINTS)
        return classMatched || pageTextMatched
    }

    private inline fun withBestSwitch(
        root: AccessibilityNodeInfo,
        viewId: String,
        rowTextHints: List<String>,
        allowSingleFallback: Boolean,
        action: (AccessibilityNodeInfo) -> Unit
    ): Boolean {
        val candidates = root.findAccessibilityNodeInfosByViewId(viewId)
        if (candidates.isEmpty()) {
            return false
        }
        return try {
            val switchNode = selectSwitchByContext(
                candidates = candidates,
                rowTextHints = rowTextHints,
                allowSingleFallback = allowSingleFallback
            ) ?: return false
            action(switchNode)
            true
        } finally {
            recycleNodes(candidates)
        }
    }

    private fun hasNodeWithViewId(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return try {
            nodes.isNotEmpty()
        } finally {
            recycleNodes(nodes)
        }
    }

    private fun selectSwitchByContext(
        candidates: List<AccessibilityNodeInfo>,
        rowTextHints: List<String>,
        allowSingleFallback: Boolean
    ): AccessibilityNodeInfo? {
        val bestCandidate = candidates
            .map { node ->
                val contextText = buildNodeContextText(node)
                val hintMatches = rowTextHints.count { hint ->
                    contextText.contains(hint.lowercase(Locale.ROOT))
                }
                val score = (hintMatches * 10) + if (node.isCheckable || node.isClickable) 1 else 0
                node to score
            }
            .maxByOrNull { it.second }

        if (bestCandidate != null && bestCandidate.second > 0) {
            return bestCandidate.first
        }
        if (allowSingleFallback && candidates.size == 1) {
            return candidates.first()
        }
        return null
    }

    private fun buildNodeContextText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        appendNodeText(parts, node, maxDepth = 1, recycleNode = false)
        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth < CONTEXT_ANCESTOR_DEPTH) {
            val currentAncestor = ancestor
            var nextAncestor: AccessibilityNodeInfo? = null
            try {
                appendNodeText(parts, currentAncestor, maxDepth = 1, recycleNode = false)
                nextAncestor = currentAncestor.parent
            } finally {
                currentAncestor.recycle()
            }
            ancestor = nextAncestor
            depth += 1
        }
        return parts.joinToString(" ").lowercase(Locale.ROOT)
    }

    private fun appendNodeText(
        parts: MutableList<String>,
        node: AccessibilityNodeInfo?,
        maxDepth: Int,
        recycleNode: Boolean
    ) {
        if (node == null || maxDepth < 0) {
            return
        }
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            if (maxDepth == 0) {
                return
            }
            for (index in 0 until node.childCount) {
                appendNodeText(parts, node.getChild(index), maxDepth - 1, recycleNode = true)
            }
        } finally {
            if (recycleNode) {
                node.recycle()
            }
        }
    }

    private fun rootContainsAnyText(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        val parts = mutableListOf<String>()
        appendNodeText(parts, root, maxDepth = ROOT_TEXT_SCAN_DEPTH, recycleNode = false)
        val allText = parts.joinToString(" ").lowercase(Locale.ROOT)
        return hints.any { hint -> allText.contains(hint.lowercase(Locale.ROOT)) }
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        nodes.forEach { it.recycle() }
    }

    private fun containsAnyIgnoreCase(value: String, hints: List<String>): Boolean {
        return hints.any { hint -> value.contains(hint, ignoreCase = true) }
    }

    private fun syncAutomationStepTimeout() {
        handler.removeCallbacks(automationStepTimeoutRunnable)
        if (isAutomationRunActive(hotspotAutomationCoordinator.snapshot().stage)) {
            handler.postDelayed(automationStepTimeoutRunnable, automationStepTimeoutMs)
        }
    }

    private fun isAutomationRunActive(stage: AutomationStage): Boolean {
        return stage != AutomationStage.IDLE &&
            stage != AutomationStage.COMPLETED &&
            stage != AutomationStage.FAILED
    }

    private fun updateTasks() {
        val configs = ProxySettings.loadProxies(this)
        taskCoordinator.sync(configs) { proxyId, status ->
            ProxyRuntimeStatusStore.saveStatus(this, proxyId, status)
            if (status.state == ProxyRuntimeState.ERROR) {
                Log.e("ProxyService", "Rule $proxyId failed: ${status.message}")
            }
        }
        sendBroadcast(Intent(ACTION_PROXY_STATUS_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        // Service 销毁时兜底关闭所有正在运行的转发器，防止残留端口监听。
        isShuttingDown = true
        handler.removeCallbacks(hotspotCheckRunnable)
        handler.removeCallbacks(automationStepTimeoutRunnable)
        if (HotspotAutomationRuntime.coordinator === runtimeBridge) {
            HotspotAutomationRuntime.coordinator = null
        }
        taskCoordinator.stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 前台通知必须归属于通知渠道。
            val serviceChannel = NotificationChannel(
                channelId,
                "Proxy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 通知里显示当前活跃的代理规则数，方便用户快速确认服务状态。
        val runningCount = taskCoordinator.runningCount
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Proxy Forwarder")
            .setContentText("$runningCount proxies active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PROXY_STATUS_CHANGED = "com.example.yyproxy.ACTION_PROXY_STATUS_CHANGED"
        const val ACTION_REFRESH = "com.example.yyproxy.ACTION_REFRESH"
        private const val NOTIFICATION_ID = 1
        private const val SETTINGS_PACKAGE_NAME = "com.android.settings"
        private const val HOTSPOT_SETTINGS_ACTION = "com.android.settings.WIFI_TETHER_SETTINGS"
        private const val HOTSPOT_MAIN_SWITCH_VIEW_ID = "com.android.settings:id/switch_widget"
        private const val AUTO_TURNOFF_SWITCH_VIEW_ID = "android:id/switch_widget"
        private const val CONTEXT_ANCESTOR_DEPTH = 3
        private const val ROOT_TEXT_SCAN_DEPTH = 5

        private val HOTSPOT_MAIN_PAGE_CLASS_HINTS = listOf(
            "WifiTetherSettingsActivity",
            "WifiTetherSettings",
            "WifiApSettingsActivity"
        )
        private val HOTSPOT_MAIN_PAGE_TEXT_HINTS = listOf(
            "wi-fi hotspot",
            "mobile hotspot",
            "hotspot and tethering",
            "tethering",
            "移动热点",
            "热点和网络共享"
        )
        private val HOTSPOT_MAIN_ROW_TEXT_HINTS = listOf(
            "mobile hotspot",
            "hotspot",
            "移动热点"
        )
        private val AUTO_TURNOFF_PAGE_CLASS_HINTS = listOf(
            "WifiApTimeoutSettings",
            "WifiApAutoDisableSettings",
            "WifiTetherSettingsActivity"
        )
        private val AUTO_TURNOFF_ROW_TEXT_HINTS = listOf(
            "turn off hotspot automatically",
            "turn off automatically",
            "自动关闭热点",
            "自动关闭"
        )
    }
}
