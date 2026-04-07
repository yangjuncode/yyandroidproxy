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
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ProxyService : Service() {

    private val channelId = "ProxyServiceChannel"
    private val executorService: ExecutorService = Executors.newCachedThreadPool()
    private val runningTasks = ConcurrentHashMap<String, ProxyTask>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateTasks()
        val notification = createNotification()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun updateTasks() {
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
                val newTask = ProxyTask(config)
                runningTasks[config.id] = newTask
                executorService.execute { newTask.run() }
            } else if (existingTask.config != config) {
                // Config changed (e.g., port changed), restart it
                existingTask.stop()
                val newTask = ProxyTask(config)
                runningTasks[config.id] = newTask
                executorService.execute { newTask.run() }
            }
        }
    }

    private inner class ProxyTask(val config: ProxyConfig) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false

        fun run() {
            isRunning = true
            try {
                serverSocket = ServerSocket(config.localPort)
                Log.d("ProxyService", "Listening on ${config.localPort} for ${config.name}")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    executorService.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e("ProxyService", "Error in task ${config.name}: ${e.message}")
                }
            } finally {
                isRunning = false
            }
        }

        private fun handleClient(clientSocket: Socket) {
            try {
                val remoteSocket = Socket(config.remoteHost, config.remotePort)
                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()
                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                executorService.execute { relayData(clientIn, remoteOut) }
                relayData(remoteIn, clientOut)
            } catch (e: IOException) {
                Log.e("ProxyService", "Relay error in ${config.name}: ${e.message}")
            } finally {
                try { clientSocket.close() } catch (_: IOException) {}
            }
        }

        private fun relayData(inputStream: InputStream, outputStream: OutputStream) {
            val buffer = ByteArray(16384)
            try {
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            } catch (_: IOException) {}
            finally {
                try { inputStream.close() } catch (_: IOException) {}
                try { outputStream.close() } catch (_: IOException) {}
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (_: IOException) {}
        }
    }

    override fun onDestroy() {
        runningTasks.values.forEach { it.stop() }
        executorService.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
