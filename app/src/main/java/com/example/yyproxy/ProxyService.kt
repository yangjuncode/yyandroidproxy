package com.example.yyproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台代理服务。
 *
 * 它的职责已经被收敛成“配置编排层”：
 * 1. 从持久化配置中读取启用的规则。
 * 2. 为每条规则启动或停止一个 NIO 端口转发器。
 * 3. 通过前台通知让系统尽量长期保活服务。
 *
 * 真正的数据转发细节已经下沉到 [NioPortForwarder]，避免 Service 本身承担复杂网络状态机。
 */
class ProxyService : Service() {

    private val channelId = "ProxyServiceChannel"
    private val platformSupport by lazy { ProxyPlatformSupport.forSdk(Build.VERSION.SDK_INT) }
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
        return START_STICKY
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
        taskCoordinator.stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        val notification = createNotification()
        if (platformSupport.requiresSpecialUseForegroundServiceType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
