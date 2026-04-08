package com.example.yyproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProxyTaskCoordinatorTest {

    @Test
    fun duplicate_enabled_ports_are_reported_as_errors_without_starting_tasks() {
        val factory = RecordingProxyTaskFactory()
        val coordinator = ProxyTaskCoordinator(factory)
        val statuses = linkedMapOf<String, ProxyRuntimeStatus>()

        val first = ProxyConfig(id = "one", name = "one", localPort = 8080, isEnabled = true)
        val second = ProxyConfig(id = "two", name = "two", localPort = 8080, isEnabled = true)

        coordinator.sync(listOf(first, second)) { id, status ->
            statuses[id] = status
        }

        assertTrue(factory.startedConfigs.isEmpty())
        assertEquals(ProxyRuntimeState.ERROR, statuses.getValue("one").state)
        assertEquals(ProxyRuntimeState.ERROR, statuses.getValue("two").state)
        assertTrue(statuses.getValue("one").message.orEmpty().contains("8080"))
    }

    @Test
    fun factory_failure_is_reported_back_to_the_rule_status() {
        val factory = RecordingProxyTaskFactory(failingPorts = setOf(9090))
        val coordinator = ProxyTaskCoordinator(factory)
        val statuses = linkedMapOf<String, ProxyRuntimeStatus>()

        val config = ProxyConfig(id = "three", name = "broken", localPort = 9090, isEnabled = true)

        coordinator.sync(listOf(config)) { id, status ->
            statuses[id] = status
        }

        assertEquals(ProxyRuntimeState.ERROR, statuses.getValue("three").state)
        assertTrue(statuses.getValue("three").message.orEmpty().contains("9090"))
    }

    @Test
    fun disabled_rules_are_marked_stopped_and_running_count_tracks_started_tasks() {
        val factory = RecordingProxyTaskFactory()
        val coordinator = ProxyTaskCoordinator(factory)
        val statuses = linkedMapOf<String, ProxyRuntimeStatus>()
        val enabled = ProxyConfig(id = "enabled", name = "enabled", localPort = 7000, isEnabled = true)
        val disabled = ProxyConfig(id = "disabled", name = "disabled", localPort = 7001, isEnabled = false)

        coordinator.sync(listOf(enabled, disabled)) { id, status ->
            statuses[id] = status
        }

        assertEquals(1, coordinator.runningCount)
        assertEquals(ProxyRuntimeState.RUNNING, statuses.getValue("enabled").state)
        assertEquals(ProxyRuntimeState.STOPPED, statuses.getValue("disabled").state)
    }

    private class RecordingProxyTaskFactory(
        private val failingPorts: Set<Int> = emptySet()
    ) : ProxyTaskFactory {
        val startedConfigs = mutableListOf<ProxyConfig>()

        override fun start(config: ProxyConfig): ProxyTaskHandle {
            if (config.localPort in failingPorts) {
                throw IOException("port ${config.localPort} unavailable")
            }

            startedConfigs += config
            return object : ProxyTaskHandle {
                override fun stop() = Unit
            }
        }
    }
}
