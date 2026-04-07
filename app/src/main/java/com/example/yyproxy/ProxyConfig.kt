package com.example.yyproxy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class ProxyConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var remoteHost: String = "",
    var remotePort: Int = 1080,
    var localPort: Int = 8080,
    var proxyType: String = "HTTP",
    var isEnabled: Boolean = true
)

object ProxySettings {
    private const val PREFS_NAME = "proxy_prefs_multi"
    private const val KEY_PROXIES = "proxies"
    private val gson = Gson()

    fun saveProxies(context: Context, proxies: List<ProxyConfig>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(proxies)
        prefs.edit().putString(KEY_PROXIES, json).apply()
    }

    fun loadProxies(context: Context): List<ProxyConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROXIES, null) ?: return emptyList()
        val type = object : TypeToken<List<ProxyConfig>>() {}.type
        return gson.fromJson(json, type)
    }
}
