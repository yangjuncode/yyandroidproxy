package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHotspotBehaviorTest {

    @Test
    fun samsung_note9_android10_supports_accessibility_automation() {
        val support = AutoHotspotSupport.forDevice(
            manufacturer = "samsung",
            model = "SM-N9600",
            sdk = 29
        )

        assertTrue(support.isSupported)
        assertTrue(support.requiresAccessibilityService)
    }

    @Test
    fun non_target_devices_are_not_supported() {
        val support = AutoHotspotSupport.forDevice(
            manufacturer = "google",
            model = "Pixel 4",
            sdk = 29
        )

        assertFalse(support.isSupported)
    }

    @Test
    fun enabling_without_accessibility_opens_accessibility_settings_without_persisting() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            support = AutoHotspotSupport(isSupported = true, requiresAccessibilityService = true),
            accessibilityEnabled = false
        )

        assertFalse(result.enabled)
        assertTrue(result.pendingAccessibilityEnable)
        assertTrue(result.openAccessibilitySettings)
        assertFalse(result.persistChange)
    }

    @Test
    fun enabling_with_accessibility_persists_and_restarts_service() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            support = AutoHotspotSupport(isSupported = true, requiresAccessibilityService = true),
            accessibilityEnabled = true
        )

        assertTrue(result.enabled)
        assertTrue(result.persistChange)
        assertTrue(result.restartService)
    }

    @Test
    fun disabling_persists_disabled_state_and_restarts_service() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = false,
            support = AutoHotspotSupport(isSupported = true, requiresAccessibilityService = true),
            accessibilityEnabled = true
        )

        assertFalse(result.enabled)
        assertFalse(result.pendingAccessibilityEnable)
        assertTrue(result.persistChange)
        assertTrue(result.restartService)
        assertFalse(result.openAccessibilitySettings)
    }

    @Test
    fun enabling_on_unsupported_device_shows_unsupported_message_without_persisting() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            support = AutoHotspotSupport(isSupported = false, requiresAccessibilityService = false),
            accessibilityEnabled = true
        )

        assertFalse(result.enabled)
        assertFalse(result.pendingAccessibilityEnable)
        assertFalse(result.persistChange)
        assertFalse(result.restartService)
        assertTrue(result.showUnsupportedMessage)
        assertFalse(result.openAccessibilitySettings)
    }

    @Test
    fun on_resume_enables_toggle_after_accessibility_is_granted() {
        val result = AutoHotspotToggleCoordinator.onResume(
            currentlyEnabled = false,
            pendingAccessibilityEnable = true,
            accessibilityEnabled = true
        )

        assertTrue(result.enabled)
        assertFalse(result.pendingAccessibilityEnable)
        assertTrue(result.persistChange)
    }
}
