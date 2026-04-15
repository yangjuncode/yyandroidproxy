package com.example.yyproxy

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.lang.reflect.Method

object HotspotManager {
    private const val TAG = "HotspotManager"
    private const val WIFI_AP_STATE_ENABLED = 13
    @Volatile
    private var hasLoggedStateReflectionFailure = false

    enum class HotspotState {
        ENABLED,
        DISABLED,
        UNKNOWN
    }

    fun getHotspotState(context: Context): HotspotState {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            hasLoggedStateReflectionFailure = false
            if (state == WIFI_AP_STATE_ENABLED) {
                HotspotState.ENABLED
            } else {
                HotspotState.DISABLED
            }
        } catch (e: Exception) {
            if (!hasLoggedStateReflectionFailure) {
                hasLoggedStateReflectionFailure = true
                Log.w(
                    TAG,
                    "Hotspot state reflection unavailable (${e.javaClass.simpleName}); state treated as unknown"
                )
            }
            HotspotState.UNKNOWN
        }
    }

    fun isHotspotEnabled(context: Context): Boolean {
        return getHotspotState(context) == HotspotState.ENABLED
    }
}
