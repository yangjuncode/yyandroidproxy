package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServiceAutomationDecisionTest {

    @Test
    fun automation_request_requires_toggle_accessibility_and_hotspot_off() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = true,
            hotspotState = HotspotManager.HotspotState.DISABLED
        )

        assertTrue(shouldRequest)
    }

    @Test
    fun automation_request_is_skipped_when_accessibility_is_disabled() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = false,
            hotspotState = HotspotManager.HotspotState.DISABLED
        )

        assertFalse(shouldRequest)
    }

    @Test
    fun automation_request_is_skipped_when_auto_hotspot_toggle_is_off() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = false,
            accessibilityEnabled = true,
            hotspotState = HotspotManager.HotspotState.DISABLED
        )

        assertFalse(shouldRequest)
    }

    @Test
    fun automation_request_is_skipped_when_hotspot_is_already_enabled() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = true,
            hotspotState = HotspotManager.HotspotState.ENABLED
        )

        assertFalse(shouldRequest)
    }

    @Test
    fun automation_request_is_skipped_when_hotspot_state_is_unknown() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = true,
            hotspotState = HotspotManager.HotspotState.UNKNOWN
        )

        assertFalse(shouldRequest)
    }
}
