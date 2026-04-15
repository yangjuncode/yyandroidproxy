package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStateTest {

    @Test
    fun either_component_match_accepts_short_form() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "com.example.yyproxy/.HotspotAutomationAccessibilityService:other/service",
            expectedComponentFull = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService",
            expectedComponentShort = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertTrue(enabled)
    }

    @Test
    fun either_component_match_accepts_full_form() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService:other/service",
            expectedComponentFull = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService",
            expectedComponentShort = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertTrue(enabled)
    }

    @Test
    fun either_component_match_ignores_token_whitespace() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "  com.example.yyproxy/.HotspotAutomationAccessibilityService  : other/service",
            expectedComponentFull = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService",
            expectedComponentShort = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertTrue(enabled)
    }

    @Test
    fun either_component_match_returns_false_when_missing_both_forms() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "other/service",
            expectedComponentFull = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService",
            expectedComponentShort = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertFalse(enabled)
    }
}
