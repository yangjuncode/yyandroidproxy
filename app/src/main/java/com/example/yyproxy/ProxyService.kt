package com.example.yyproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    // key 是配置 id，value 是正在运行的代理任务，用于支持热更新和按规则停启。
    private val runningTasks = ConcurrentHashMap<String, ProxyTask>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被启动都重新对齐一次配置，让 UI 改动能即时同步到前台服务。
        updateTasks()
        val notification = createNotification()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun updateTasks() {
        // 只拉起用户启用的规则，禁用项会被视为应当停掉。
        val configs = ProxySettings.loadProxies(this).filter { it.isEnabled }
        val configIds = configs.map { it.id }.toSet()

        // Stop tasks that are no longer in the enabled list
        val iterator = runningTasks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!configIds.contains(entry.key)) {
                entry.value.stop()
                iterator.remove()
            }
        }

        // Start or update tasks
        for (config in configs) {
            val existingTask = runningTasks[config.id]
            if (existingTask == null) {
                // 新规则：直接启动。
                val newTask = ProxyTask(config)
                if (newTask.start()) {
                    runningTasks[config.id] = newTask
                }
            } else if (existingTask.config != config) {
                // Config changed (e.g., port changed), restart it
                existingTask.stop()
                val newTask = ProxyTask(config)
                if (newTask.start()) {
                    runningTasks[config.id] = newTask
                } else {
                    runningTasks.remove(config.id)
                }
            }
        }
    }

    /**
     * 单条配置对应的运行时任务包装。
     *
     * 这里保留一层包装而不是直接把 `NioPortForwarder` 放进 map，
     * 主要是为了把“配置”和“运行实例”绑在一起，方便后续扩展统计或状态。
     */
    private inner class ProxyTask(val config: ProxyConfig) {
        private var forwarder: NioPortForwarder? = null

        fun start(): Boolean {
            try {
                // 每条规则各自持有一个 selector 线程，
                // 相比旧版“每连接两个阻塞线程”的模型，线程数量只跟规则数相关。
                forwarder = NioPortForwarder(
                    localPort = config.localPort,
                    remoteHost = config.remoteHost,
                    remotePort = config.remotePort
                ).also { it.start() }
                Log.d(
                    "ProxyService",
                    "Listening on ${forwarder?.localPort} for ${config.name} -> ${config.remoteHost}:${config.remotePort}"
                )
                return true
            } catch (e: IOException) {
                Log.e("ProxyService", "Error starting task ${config.name}: ${e.message}")
            }
            return false
        }

        fun stop() {
            try {
                // close() 会唤醒 selector、关闭监听端口并回收连接。
                forwarder?.close()
            } catch (_: IOException) {
            }
            forwarder = null
        }
    }

    override fun onDestroy() {
        // Service 销毁时兜底关闭所有正在运行的转发器，防止残留端口监听。
        runningTasks.values.forEach { it.stop() }
        runningTasks.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        val runningCount = runningTasks.size
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Proxy Forwarder")
            .setContentText("$runningCount proxies active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
