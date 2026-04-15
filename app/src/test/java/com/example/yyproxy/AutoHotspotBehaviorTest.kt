package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHotspotBehaviorTest {

    @Test
    fun sdk25_supports_legacy_auto_hotspot_automation() {
        val support = AutoHotspotSupport.forSdk(25)

        assertTrue(support.canProgrammaticallyEnable)
        assertTrue(support.requiresWriteSettingsPermission)
    }

    @Test
    fun sdk26_and_above_do_not_support_programmatic_auto_hotspot() {
        val support = AutoHotspotSupport.forSdk(26)

        assertFalse(support.canProgrammaticallyEnable)
        assertFalse(support.requiresWriteSettingsPermission)
    }

    @Test
    fun enabling_without_write_settings_requests_permission_and_keeps_setting_disabled() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            canProgrammaticallyEnable = true,
            canWriteSettings = false
        )

        assertFalse(result.enabled)
        assertTrue(result.pendingPermissionRequest)
        assertTrue(result.openWriteSettings)
        assertFalse(result.persistChange)
    }

    @Test
    fun resuming_after_permission_grant_completes_pending_enable() {
        val result = AutoHotspotToggleCoordinator.onResume(
            currentlyEnabled = false,
            pendingPermissionRequest = true,
            canWriteSettings = true
        )

        assertTrue(result.enabled)
        assertFalse(result.pendingPermissionRequest)
        assertTrue(result.persistChange)
    }

    @Test
    fun enabling_on_unsupported_platform_shows_unsupported_without_persisting() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            canProgrammaticallyEnable = false,
            canWriteSettings = false
        )

        assertFalse(result.enabled)
        assertFalse(result.pendingPermissionRequest)
        assertFalse(result.openWriteSettings)
        assertTrue(result.showUnsupportedMessage)
        assertFalse(result.persistChange)
    }
}
