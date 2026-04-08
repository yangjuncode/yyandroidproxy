package com.example.yyproxy

import java.io.IOException
import java.util.LinkedHashMap

interface ProxyTaskHandle {
    fun stop()
}

interface ProxyTaskFactory {
    @Throws(IOException::class)
    fun start(config: ProxyConfig): ProxyTaskHandle
}

class ProxyTaskCoordinator(
    private val taskFactory: ProxyTaskFactory
) {
    private val runningTasks = LinkedHashMap<String, RunningProxyTask>()

    val runningCount: Int
        get() = runningTasks.size

    fun sync(
        configs: List<ProxyConfig>,
        onStatusChanged: (String, ProxyRuntimeStatus) -> Unit
    ) {
        val configsById = configs.associateBy { it.id }
        val enabledConfigs = configs.filter { it.isEnabled }
        val enabledIds = enabledConfigs.map { it.id }.toSet()
        val duplicatePorts = enabledConfigs
            .groupBy { it.localPort }
            .filterValues { it.size > 1 }
            .keys

        val iterator = runningTasks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in enabledIds || entry.value.config.localPort in duplicatePorts) {
                entry.value.handle.stop()
                iterator.remove()
            }
        }

        configs.forEach { config ->
            if (!config.isEnabled) {
                onStatusChanged(config.id, ProxyRuntimeStatus(ProxyRuntimeState.STOPPED))
                return@forEach
            }

            if (config.localPort in duplicatePorts) {
                onStatusChanged(
                    config.id,
                    ProxyRuntimeStatus(
                        state = ProxyRuntimeState.ERROR,
                        message = "Local port ${config.localPort} conflicts with another enabled rule."
                    )
                )
                return@forEach
            }

            val existingTask = runningTasks[config.id]
            if (existingTask != null && existingTask.config == config) {
                onStatusChanged(config.id, ProxyRuntimeStatus(ProxyRuntimeState.RUNNING))
                return@forEach
            }

            if (existingTask != null) {
                existingTask.handle.stop()
                runningTasks.remove(config.id)
            }

            try {
                val handle = taskFactory.start(config)
                runningTasks[config.id] = RunningProxyTask(config, handle)
                onStatusChanged(config.id, ProxyRuntimeStatus(ProxyRuntimeState.RUNNING))
            } catch (e: IOException) {
                runningTasks.remove(config.id)
                onStatusChanged(
                    config.id,
                    ProxyRuntimeStatus(
                        state = ProxyRuntimeState.ERROR,
                        message = "Failed to listen on local port ${config.localPort}: ${e.message ?: "unknown error"}"
                    )
                )
            }
        }

        runningTasks.keys
            .filter { it !in configsById }
            .toList()
            .forEach { removedId ->
                runningTasks.remove(removedId)?.handle?.stop()
            }
    }

    fun stopAll() {
        runningTasks.values.forEach { it.handle.stop() }
        runningTasks.clear()
    }

    private data class RunningProxyTask(
        val config: ProxyConfig,
        val handle: ProxyTaskHandle
    )
}
