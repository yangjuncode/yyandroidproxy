package com.example.yyproxy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 单条代理规则的配置模型。
 *
 * 这份配置既用于 Compose 界面展示和编辑，也用于 Service 启动时构造真正的端口转发器。
 */
data class ProxyConfig(
    // 每条配置都用稳定 id 标识，便于编辑、启停和热更新时做差量对比。
    val id: String = UUID.randomUUID().toString(),
    // 纯展示名称，方便用户区分不同规则。
    var name: String = "",
    // 远端目标主机，当前实现本质上是 TCP 端口转发到这个地址。
    var remoteHost: String = "",
    // 远端目标端口。
    var remotePort: Int = 1080,
    // 本机监听端口，局域网设备可以连到这个端口进入转发流程。
    var localPort: Int = 8080,
    // 预留的代理类型字段，目前 UI 会显示它，但 NIO 转发核心还没有按协议做分流。
    var proxyType: String = "HTTP",
    // 是否启用这条规则，Service 会只启动启用状态的配置。
    var isEnabled: Boolean = true
)

/**
 * 代理配置的本地持久化工具。
 *
 * 当前项目使用 SharedPreferences + JSON 的轻量方案，
 * 目的是让 App 无数据库依赖也能保存多条转发规则。
 */
object ProxySettings {
    private const val PREFS_NAME = "proxy_prefs_multi"
    private const val KEY_PROXIES = "proxies"
    private val gson = Gson()

    fun saveProxies(context: Context, proxies: List<ProxyConfig>) {
        // 统一把整份配置列表序列化后写入，逻辑简单，适合当前小型配置规模。
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(proxies)
        prefs.edit().putString(KEY_PROXIES, json).apply()
    }

    fun loadProxies(context: Context): List<ProxyConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 首次启动或从未保存过配置时，直接返回空列表，调用方按“无规则”处理。
        val json = prefs.getString(KEY_PROXIES, null) ?: return emptyList()
        val type = object : TypeToken<List<ProxyConfig>>() {}.type
        return gson.fromJson(json, type)
    }
}
