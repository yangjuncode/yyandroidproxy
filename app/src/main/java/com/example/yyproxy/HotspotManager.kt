package com.example.yyproxy

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Method

/**
 * 移动热点管理工具类。
 *
 * 在 Android 上自动开启热点涉及权限和隐藏 API，不同版本实现差异巨大。
 * 这里提供一个尽力而为的实现。
 */
object HotspotManager {
    private const val TAG = "HotspotManager"
    private const val WIFI_AP_STATE_ENABLED = 13

    enum class EnableResult {
        ENABLED,
        ALREADY_ENABLED,
        PERMISSION_REQUIRED,
        NOT_SUPPORTED,
        FAILED
    }

    /**
     * 检查移动热点是否已开启。
     */
    fun isHotspotEnabled(context: Context): Boolean {
        if (!AutoHotspotSupport.forSdk(Build.VERSION.SDK_INT).isSupported) {
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get hotspot state", e)
            false
        }
    }

    /**
     * 尝试开启移动热点。
     * 仅对 Android 8.0 以下设备尝试 legacy 反射实现。
     */
    @Suppress("DEPRECATION")
    fun enableHotspot(context: Context): EnableResult {
        val support = AutoHotspotSupport.forSdk(Build.VERSION.SDK_INT)
        if (!support.isSupported) {
            return EnableResult.NOT_SUPPORTED
        }
        if (!Settings.System.canWrite(context)) {
            return EnableResult.PERMISSION_REQUIRED
        }
        if (isHotspotEnabled(context)) {
            return EnableResult.ALREADY_ENABLED
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wasWifiEnabled = wifiManager.isWifiEnabled

        try {
            val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
            val method: Method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                wifiConfigurationClass,
                Boolean::class.javaPrimitiveType
            )

            if (wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = false
            }

            val enabled = method.invoke(wifiManager, null, true) as? Boolean ?: false
            if (enabled) {
                return EnableResult.ENABLED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable hotspot via reflection", e)
        }

        if (wasWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }
        return EnableResult.FAILED
    }
}
