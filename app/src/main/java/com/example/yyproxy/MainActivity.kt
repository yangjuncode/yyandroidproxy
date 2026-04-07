package com.example.yyproxy

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.example.yyproxy.ui.theme.YyproxyTheme
import java.net.Inet4Address
import java.net.NetworkInterface

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

@Composable
fun ProxyApp() {
    val context = LocalContext.current
    var proxies by remember { mutableStateOf(ProxySettings.loadProxies(context)) }
    var editingProxy by remember { mutableStateOf<ProxyConfig?>(null) }
    var isAdding by remember { mutableStateOf(false) }

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
            onAdd = { isAdding = true },
            onEdit = { editingProxy = it },
            onDelete = { proxy ->
                val newList = proxies.filter { it.id != proxy.id }
                proxies = newList
                ProxySettings.saveProxies(context, newList)
                startService(context)
            },
            onToggle = { proxy, enabled ->
                val newList = proxies.map { if (it.id == proxy.id) it.copy(isEnabled = enabled) else it }
                proxies = newList
                ProxySettings.saveProxies(context, newList)
                startService(context)
            }
        )
    }
}

fun startService(context: Context) {
    val intent = Intent(context, ProxyService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

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
        e.printStackTrace()
    }
    return if (ips.isEmpty()) listOf("No IP found") else ips
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyListScreen(
    proxies: List<ProxyConfig>,
    onAdd: () -> Unit,
    onEdit: (ProxyConfig) -> Unit,
    onDelete: (ProxyConfig) -> Unit,
    onToggle: (ProxyConfig, Boolean) -> Unit
) {
    val context = LocalContext.current
    var ipAddresses by remember { mutableStateOf(getLocalIpAddresses()) }

    // Listen for network changes
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
            // IP Addresses Display Area
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
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(proxies) { proxy ->
                    ProxyItem(proxy, onEdit, onDelete, onToggle)
                }
            }
        }
    }
}

@Composable
fun ProxyItem(
    proxy: ProxyConfig,
    onEdit: (ProxyConfig) -> Unit,
    onDelete: (ProxyConfig) -> Unit,
    onToggle: (ProxyConfig, Boolean) -> Unit
) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(text = proxy.name.ifEmpty { "Unnamed Proxy" }, style = MaterialTheme.typography.titleMedium)
                Text(text = "${proxy.proxyType}: ${proxy.localPort} -> ${proxy.remoteHost}:${proxy.remotePort}", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = proxy.isEnabled, onCheckedChange = { onToggle(proxy, it) })
            IconButton(onClick = { onDelete(proxy) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditScreen(
    proxy: ProxyConfig,
    onSave: (ProxyConfig) -> Unit,
    onCancel: () -> Unit
) {
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
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = remoteHost, onValueChange = { remoteHost = it }, label = { Text("Remote Proxy Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = remotePort, onValueChange = { remotePort = it }, label = { Text("Remote Proxy Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = localPort, onValueChange = { localPort = it }, label = { Text("Local Listening Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = proxyType == "HTTP", onClick = { proxyType = "HTTP" })
                Text("HTTP")
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(selected = proxyType == "SOCKS5", onClick = { proxyType = "SOCKS5" })
                Text("SOCKS5")
            }

            Button(
                onClick = {
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
