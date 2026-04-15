package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStateTest {

    @Test
    fun parses_enabled_services_for_target_component() {
        val target = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService"
        val enabled = listOf(
            "com.android.talkback/com.google.android.marvin.talkback.TalkBackService",
            target
        ).joinToString(":")

        assertTrue(AccessibilityServiceState.containsService(enabled, target))
    }

    @Test
    fun returns_false_when_target_component_is_missing() {
        val target = "com.example.yyproxy/com.example.yyproxy.HotspotAutomationAccessibilityService"
        val enabled = "com.android.talkback/com.google.android.marvin.talkback.TalkBackService"

        assertFalse(AccessibilityServiceState.containsService(enabled, target))
    }
}
