package com.example.yyproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台代理服务。
 *
 * 它的职责已经被收敛成“配置编排层”：
 * 1. 从持久化配置中读取启用的规则。
 * 2. 为每条规则启动或停止一个 NIO 端口转发器。
 * 3. 通过前台通知让系统尽量长期保活服务。
 * 4. 如果开启了自动热点选项，则定期检查并开启移动热点。
 */
class ProxyService : Service() {

    private val channelId = "ProxyServiceChannel"
    private val autoHotspotSupport = AutoHotspotSupport.forSdk(Build.VERSION.SDK_INT)
    private val handler = Handler(Looper.getMainLooper())
    private val hotspotCheckInterval = 10 * 60 * 1000L // 10 minutes

    private val hotspotCheckRunnable = object : Runnable {
        override fun run() {
            checkAndEnableHotspot()
            handler.postDelayed(this, hotspotCheckInterval)
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        // 每次被启动都重新对齐一次配置，让 UI 改动能即时同步到前台服务。
        updateTasks()
        refreshNotification()

        // 启动或更新热点检查任务
        handler.removeCallbacks(hotspotCheckRunnable)
        if (ProxySettings.isAutoHotspotEnabled(this) && autoHotspotSupport.canProgrammaticallyEnable) {
            handler.post(hotspotCheckRunnable)
        }
        
        return START_STICKY
    }

    private fun checkAndEnableHotspot() {
        if (!ProxySettings.isAutoHotspotEnabled(this) || !autoHotspotSupport.canProgrammaticallyEnable) {
            return
        }

        when (HotspotManager.enableHotspot(this)) {
            HotspotManager.EnableResult.ENABLED ->
                Log.d("ProxyService", "Auto hotspot enabled successfully")
            HotspotManager.EnableResult.ALREADY_ENABLED -> Unit
            HotspotManager.EnableResult.PERMISSION_REQUIRED ->
                Log.w("ProxyService", "Auto hotspot is enabled in app settings but WRITE_SETTINGS is not granted")
            HotspotManager.EnableResult.NOT_SUPPORTED ->
                Log.w("ProxyService", "Auto hotspot is not supported on this Android version")
            HotspotManager.EnableResult.FAILED ->
                Log.w("ProxyService", "Auto hotspot enable attempt failed")
        }
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
        handler.removeCallbacks(hotspotCheckRunnable)
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
        private const val NOTIFICATION_ID = 1
    }
}
