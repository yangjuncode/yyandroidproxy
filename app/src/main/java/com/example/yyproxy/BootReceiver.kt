package com.example.yyproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机广播接收器。
 *
 * 设备启动完成后，如果系统允许应用接收开机广播，就会在这里重新拉起前台代理服务，
 * 这样用户配置过的转发规则不需要每次手动打开 App 才能生效。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 这里只处理系统的开机完成广播，避免误响应其它广播事件。
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting ProxyService...")
            val serviceIntent = Intent(context, ProxyService::class.java)

            // Android 8.0 以后后台启动 Service 受限，必须使用前台服务启动方式。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
