package com.example.yyproxy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class ProxyRuntimeState {
    RUNNING,
    STOPPED,
    ERROR
}

data class ProxyRuntimeStatus(
    val state: ProxyRuntimeState,
    val message: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

object ProxyRuntimeStatusStore {
    private const val PREFS_NAME = "proxy_runtime_status"
    private const val KEY_STATUSES = "statuses"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, ProxyRuntimeStatus>>() {}.type

    fun loadStatuses(context: Context): Map<String, ProxyRuntimeStatus> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_STATUSES, null) ?: return emptyMap()
        return gson.fromJson<Map<String, ProxyRuntimeStatus>>(json, mapType) ?: emptyMap()
    }

    fun saveStatus(context: Context, proxyId: String, status: ProxyRuntimeStatus) {
        val updated = loadStatuses(context).toMutableMap().apply {
            this[proxyId] = status
        }
        saveStatuses(context, updated)
    }

    fun removeStatus(context: Context, proxyId: String) {
        val updated = loadStatuses(context).toMutableMap().apply {
            remove(proxyId)
        }
        saveStatuses(context, updated)
    }

    private fun saveStatuses(context: Context, statuses: Map<String, ProxyRuntimeStatus>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATUSES, gson.toJson(statuses)).apply()
    }
}
