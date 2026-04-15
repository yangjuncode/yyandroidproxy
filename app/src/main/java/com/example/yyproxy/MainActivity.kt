package com.example.yyproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.yyproxy.ui.theme.YyproxyTheme
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 应用主界面入口。
 *
 * 这里主要负责挂载 Compose UI，不承载复杂业务逻辑；
 * 配置读写和服务拉起都由下方的 Composable/工具函数完成。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YyproxyTheme {
                ProxyApp()
            }
        }
    }
}

/**
 * 顶层应用状态容器。
 *
 * 它持有“当前规则列表”“当前正在编辑的规则”“是否处于新增模式”等界面状态，
 * 并把这些状态流转给列表页和编辑页。
 */
@Composable
fun ProxyApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val platformSupport = remember { ProxyPlatformSupport.forSdk(Build.VERSION.SDK_INT) }
    val autoHotspotSupport = remember {
        AutoHotspotSupport.forDevice(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdk = Build.VERSION.SDK_INT
        )
    }
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityServiceState.isAutomationServiceEnabled(context))
    }
    // 首次进入页面时，从本地持久化里恢复用户之前保存的规则。
    var proxies by remember { mutableStateOf(ProxySettings.loadProxies(context)) }
    var runtimeStatuses by remember { mutableStateOf(ProxyRuntimeStatusStore.loadStatuses(context)) }
    var autoHotspotEnabled by remember {
        mutableStateOf(
            ProxySettings.isAutoHotspotEnabled(context) && autoHotspotSupport.isSupported
        )
    }
    var pendingAccessibilityEnable by remember { mutableStateOf(false) }

    // editingProxy 不为空表示正在编辑已有规则。
    var editingProxy by remember { mutableStateOf<ProxyConfig?>(null) }
    // isAdding 为 true 表示当前正在新建一条规则。
    var isAdding by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    val applyAutoHotspotResult: (AutoHotspotToggleResult) -> Unit = { result ->
        autoHotspotEnabled = result.enabled
        pendingAccessibilityEnable = result.pendingAccessibilityEnable
        if (result.persistChange) {
            ProxySettings.setAutoHotspot(context, result.enabled)
        }
        if (result.restartService) {
            startService(context)
        }
        if (result.openAccessibilitySettings) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Please enable accessibility service to allow auto hotspot automation",
                Toast.LENGTH_LONG
            ).show()
        }
        if (result.showUnsupportedMessage) {
            Toast.makeText(
                context,
                "Auto hotspot automation is supported only on compatible Samsung Android 10 devices",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        startService(context)
        if (platformSupport.requiresNotificationPermission && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiveContext: Context?, intent: Intent?) {
                if (intent?.action == ProxyService.ACTION_PROXY_STATUS_CHANGED) {
                    proxies = ProxySettings.loadProxies(context)
                    runtimeStatuses = ProxyRuntimeStatusStore.loadStatuses(context)
                }
            }
        }
        val filter = IntentFilter(ProxyService.ACTION_PROXY_STATUS_CHANGED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        fun handleAppVisible() {
            hasNotificationPermission = isNotificationPermissionGranted(context)
            runtimeStatuses = ProxyRuntimeStatusStore.loadStatuses(context)
            accessibilityEnabled = AccessibilityServiceState.isAutomationServiceEnabled(context)
            val result = AutoHotspotToggleCoordinator.onResume(
                currentlyEnabled = autoHotspotEnabled,
                pendingAccessibilityEnable = pendingAccessibilityEnable,
                accessibilityEnabled = accessibilityEnabled
            )
            applyAutoHotspotResult(result)
            applyAutoHotspotResult(
                AutoHotspotToggleCoordinator.onAppVisible(
                    currentlyEnabled = autoHotspotEnabled,
                    support = autoHotspotSupport,
                    pendingAccessibilityEnable = pendingAccessibilityEnable,
                    accessibilityEnabled = accessibilityEnabled
                )
            )
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                handleAppVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handleAppVisible()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 保存时统一更新列表、持久化配置，并触发 Service 重新同步运行中的规则。
    val onSave = { updatedProxy: ProxyConfig ->
        val newList = if (isAdding) {
            proxies + updatedProxy
        } else {
            proxies.map { if (it.id == updatedProxy.id) updatedProxy else it }
        }
        proxies = newList
        ProxySettings.saveProxies(context, newList)
        editingProxy = null
        isAdding = false
        startService(context)
    }

    if (editingProxy != null || isAdding) {
        // 编辑态下拦截系统返回键，避免 Compose 页面还在但状态没有回退。
        BackHandler {
            editingProxy = null
            isAdding = false
        }
        ProxyEditScreen(
            proxy = editingProxy ?: ProxyConfig(),
            onSave = onSave,
            onCancel = {
                editingProxy = null
                isAdding = false
            }
        )
    } else {
        ProxyListScreen(
            proxies = proxies,
            runtimeStatuses = runtimeStatuses,
            notificationPermissionRequired = platformSupport.requiresNotificationPermission && !hasNotificationPermission,
            onRequestNotificationPermission = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onAdd = { isAdding = true },
            onEdit = { editingProxy = it },
            onDelete = { proxy ->
                val newList = proxies.filter { it.id != proxy.id }
                proxies = newList
                ProxySettings.saveProxies(context, newList)
                ProxyRuntimeStatusStore.removeStatus(context, proxy.id)
                startService(context)
            },
            onToggle = { proxy, enabled ->
                val newList = proxies.map { if (it.id == proxy.id) it.copy(isEnabled = enabled) else it }
                proxies = newList
                ProxySettings.saveProxies(context, newList)
                startService(context)
            },
            autoHotspotEnabled = autoHotspotEnabled,
            autoHotspotSupported = autoHotspotSupport.isSupported,
            accessibilityEnabled = accessibilityEnabled,
            onAutoHotspotToggle = { enabled ->
                accessibilityEnabled = AccessibilityServiceState.isAutomationServiceEnabled(context)
                val result = AutoHotspotToggleCoordinator.onToggleRequested(
                    requestedEnabled = enabled,
                    support = autoHotspotSupport,
                    accessibilityEnabled = accessibilityEnabled
                )
                applyAutoHotspotResult(result)
            }
        )
    }
}

/**
 * 按当前 Android 版本要求启动前台服务。
 *
 * 所有修改配置的动作最终都会调用这里，让后台代理尽快与最新配置对齐。
 */
fun startService(context: Context) {
    val intent = Intent(context, ProxyService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

/**
 * 获取当前设备上所有可用的 IPv4 地址。
 *
 * 页面里把这些地址展示给用户，方便他在局域网其它设备上连接手机的监听端口。
 */
fun getLocalIpAddresses(): List<String> {
    val ips = mutableListOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    ips.add(address.hostAddress ?: "")
                }
            }
        }
    } catch (e: Exception) {
        // 这里保留简单打印，主要用于开发期观察网络接口枚举是否异常。
        e.printStackTrace()
    }
    return if (ips.isEmpty()) listOf("No IP found") else ips
}

/**
 * 代理规则列表页。
 *
 * 页面上半部分显示本机 IP，下半部分显示所有规则卡片，并提供新增/刷新入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyListScreen(
    proxies: List<ProxyConfig>,
    runtimeStatuses: Map<String, ProxyRuntimeStatus>,
    notificationPermissionRequired: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ProxyConfig) -> Unit,
    onDelete: (ProxyConfig) -> Unit,
    onToggle: (ProxyConfig, Boolean) -> Unit,
    autoHotspotEnabled: Boolean,
    autoHotspotSupported: Boolean,
    accessibilityEnabled: Boolean,
    onAutoHotspotToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var ipAddresses by remember { mutableStateOf(getLocalIpAddresses()) }

    // 监听网络变化，保证 Wi‑Fi/移动网络切换后页面上的 IP 信息能自动刷新。
    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ipAddresses = getLocalIpAddresses()
            }
            override fun onLost(network: Network) {
                ipAddresses = getLocalIpAddresses()
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                ipAddresses = getLocalIpAddresses()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, networkCallback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Proxy Forwarder") },
                actions = {
                    IconButton(onClick = { ipAddresses = getLocalIpAddresses() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh IP")
                    }
                }
            ) 
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add Proxy")
            }
        }
        ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (notificationPermissionRequired) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Allow notifications on Android 13+",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Foreground service notifications stay visible only after you grant notification permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestNotificationPermission) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // 顶部 IP 信息区，告诉用户当前设备在局域网里可以用哪些地址访问本机监听端口。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Your Device IP Addresses:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ipAddresses.forEach { ip ->
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Auto Enable Hotspot",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = if (autoHotspotSupported) {
                                    if (accessibilityEnabled) {
                                        "Accessibility automation ready (checks every 10 mins)"
                                    } else {
                                        "Accessibility service required"
                                    }
                                } else {
                                    "Supported only on compatible Samsung Android 10 devices"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = autoHotspotEnabled,
                            onCheckedChange = onAutoHotspotToggle
                        )
                    }
                }
            }

            // 规则列表区，每一项都可以点击进入编辑。
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(proxies) { proxy ->
                    ProxyItem(
                        proxy = proxy,
                        runtimeStatus = runtimeStatuses[proxy.id],
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onToggle = onToggle
                    )
                }
            }
        }
    }
}

/**
 * 单条代理规则卡片。
 *
 * 点击整张卡片进入编辑，右侧开关控制启停，删除按钮直接移除规则。
 */
@Composable
fun ProxyItem(
    proxy: ProxyConfig,
    runtimeStatus: ProxyRuntimeStatus?,
    onEdit: (ProxyConfig) -> Unit,
    onDelete: (ProxyConfig) -> Unit,
    onToggle: (ProxyConfig, Boolean) -> Unit
) {
    val statusLabel = when {
        !proxy.isEnabled -> "Stopped"
        runtimeStatus == null -> "Pending sync"
        runtimeStatus.state == ProxyRuntimeState.RUNNING -> "Running"
        runtimeStatus.state == ProxyRuntimeState.ERROR -> "Start failed"
        else -> "Stopped"
    }
    val statusColor = when (runtimeStatus?.state) {
        ProxyRuntimeState.ERROR -> MaterialTheme.colorScheme.error
        ProxyRuntimeState.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onEdit(proxy) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧文本区展示名称和核心路由信息，帮助用户快速确认转发方向。
            Column(modifier = Modifier.weight(1f)) {
                Text(text = proxy.name.ifEmpty { "Unnamed Proxy" }, style = MaterialTheme.typography.titleMedium)
                Text(text = "${proxy.proxyType}: ${proxy.localPort} -> ${proxy.remoteHost}:${proxy.remotePort}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Status: $statusLabel", style = MaterialTheme.typography.bodySmall, color = statusColor)
                runtimeStatus?.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            // 开关直接控制是否启用该规则。
            Switch(checked = proxy.isEnabled, onCheckedChange = { onToggle(proxy, it) })
            IconButton(onClick = { onDelete(proxy) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (!ProxyPlatformSupport.forSdk(Build.VERSION.SDK_INT).requiresNotificationPermission) {
        return true
    }

    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 代理规则编辑页。
 *
 * 既负责新增规则，也负责编辑已有规则；两种场景共用同一套表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditScreen(
    proxy: ProxyConfig,
    onSave: (ProxyConfig) -> Unit,
    onCancel: () -> Unit
) {
    // 把传入的 proxy 拆成一组可编辑的 Compose 状态，用户输入过程中不会立刻污染原对象。
    var name by remember { mutableStateOf(proxy.name) }
    var remoteHost by remember { mutableStateOf(proxy.remoteHost) }
    var remotePort by remember { mutableStateOf(proxy.remotePort.toString()) }
    var localPort by remember { mutableStateOf(proxy.localPort.toString()) }
    var proxyType by remember { mutableStateOf(proxy.proxyType) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (proxy.id.isEmpty()) "Add Proxy" else "Edit Proxy") },
                actions = {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 这几个输入框分别对应规则的展示名、远端地址、远端端口和本地监听端口。
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = remoteHost, onValueChange = { remoteHost = it }, label = { Text("Remote Proxy Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = remotePort, onValueChange = { remotePort = it }, label = { Text("Remote Proxy Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = localPort, onValueChange = { localPort = it }, label = { Text("Local Listening Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

            // 代理类型目前主要用于展示和配置区分，后续如果扩展协议解析可以复用这个字段。
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = proxyType == "HTTP", onClick = { proxyType = "HTTP" })
                Text("HTTP")
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(selected = proxyType == "SOCKS5", onClick = { proxyType = "SOCKS5" })
                Text("SOCKS5")
            }

            Button(
                onClick = {
                    // 端口输入非法时给一个保底默认值，避免直接抛异常导致界面崩掉。
                    onSave(proxy.copy(
                        name = name,
                        remoteHost = remoteHost,
                        remotePort = remotePort.toIntOrNull() ?: 1080,
                        localPort = localPort.toIntOrNull() ?: 8080,
                        proxyType = proxyType
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
