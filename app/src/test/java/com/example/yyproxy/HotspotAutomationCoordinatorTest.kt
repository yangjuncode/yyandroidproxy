package com.example.yyproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HotspotAutomationCoordinatorTest {

    @Test
    fun request_automation_starts_single_run_and_launches_settings() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        val started = coordinator.requestAutomation(AutomationTrigger.BOOT)

        assertTrue(started)
        assertEquals(AutomationStage.OPENING_HOTSPOT_SETTINGS, coordinator.snapshot().stage)
        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun stray_callbacks_while_idle_do_nothing() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        val before = coordinator.snapshot()
        coordinator.onHotspotMainPage(mainSwitchChecked = true)
        coordinator.onAutoTurnOffPage(autoTurnOffChecked = false)
        coordinator.onHotspotSwitchClicked()

        assertEquals(before, coordinator.snapshot())
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun timeout_while_idle_does_nothing() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        val before = coordinator.snapshot()
        coordinator.onStepTimeout()

        assertEquals(before, coordinator.snapshot())
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun duplicate_request_is_ignored_while_run_is_active() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        assertTrue(coordinator.requestAutomation(AutomationTrigger.BOOT))
        assertFalse(coordinator.requestAutomation(AutomationTrigger.PERIODIC_CHECK))
    }

    @Test
    fun request_is_ignored_when_accessibility_is_disabled() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { false }
        )

        assertFalse(coordinator.requestAutomation(AutomationTrigger.BOOT))
        assertEquals(AutomationStage.IDLE, coordinator.snapshot().stage)
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun request_is_ignored_when_hotspot_is_already_on() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.ENABLED },
            accessibilityEnabled = { true }
        )

        assertFalse(coordinator.requestAutomation(AutomationTrigger.BOOT))
        assertEquals(AutomationStage.IDLE, coordinator.snapshot().stage)
        assertEquals(0, launcher.launchCount)
    }

    @Test
    fun verification_completes_when_hotspot_is_on() {
        var hotspotState = HotspotManager.HotspotState.DISABLED
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { hotspotState },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)
        coordinator.onAutoTurnOffPage(autoTurnOffChecked = false)
        hotspotState = HotspotManager.HotspotState.ENABLED
        coordinator.onHotspotMainPage(mainSwitchChecked = true)
        coordinator.onHotspotMainPage(mainSwitchChecked = true)

        assertEquals(AutomationStage.COMPLETED, coordinator.snapshot().stage)
    }

    @Test
    fun checked_main_switch_enters_verifying_when_reader_is_stale() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = true)

        assertEquals(AutomationStage.VERIFYING, coordinator.snapshot().stage)
    }

    @Test
    fun reader_observed_once_does_not_flap_when_values_change_between_reads() {
        var reads = 0
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = {
                reads += 1
                when (reads) {
                    1 -> HotspotManager.HotspotState.DISABLED
                    2 -> HotspotManager.HotspotState.ENABLED
                    else -> HotspotManager.HotspotState.ENABLED
                }
            },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)
        coordinator.onHotspotSwitchClicked()
        coordinator.onHotspotMainPage(mainSwitchChecked = true)

        assertEquals(AutomationStage.COMPLETED, coordinator.snapshot().stage)
    }

    @Test
    fun out_of_order_callbacks_are_ignored() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        val before = coordinator.snapshot()

        coordinator.onAutoTurnOffPage(autoTurnOffChecked = false)
        coordinator.onHotspotSwitchClicked()

        assertEquals(before, coordinator.snapshot())
    }

    @Test
    fun auto_turn_off_checked_stays_in_disabling_state() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)
        coordinator.onAutoTurnOffPage(autoTurnOffChecked = true)

        assertEquals(AutomationStage.ENABLING_HOTSPOT, coordinator.snapshot().stage)
    }

    @Test
    fun unchecked_main_switch_moves_directly_to_enabling_state() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)

        assertEquals(AutomationStage.ENABLING_HOTSPOT, coordinator.snapshot().stage)
    }

    @Test
    fun auto_turn_off_callbacks_do_not_change_state_once_main_switch_flow_is_active() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)
        coordinator.onAutoTurnOffPage(autoTurnOffChecked = true)

        assertEquals(AutomationStage.ENABLING_HOTSPOT, coordinator.snapshot().stage)
    }

    @Test
    fun timeout_retry_relaunches_and_clears_failure_reason_until_failed() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true },
            maxAttempts = 3
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onStepTimeout()
        assertEquals(AutomationStage.OPENING_HOTSPOT_SETTINGS, coordinator.snapshot().stage)
        assertEquals(2, coordinator.snapshot().attempt)
        assertNull(coordinator.snapshot().failureReason)
        assertEquals(2, launcher.launchCount)

        coordinator.onStepTimeout()
        assertEquals(AutomationStage.OPENING_HOTSPOT_SETTINGS, coordinator.snapshot().stage)
        assertEquals(3, coordinator.snapshot().attempt)
        assertNull(coordinator.snapshot().failureReason)
        assertEquals(3, launcher.launchCount)

        coordinator.onStepTimeout()
        assertEquals(AutomationStage.FAILED, coordinator.snapshot().stage)
        assertEquals("timeout", coordinator.snapshot().failureReason)
        assertEquals(3, launcher.launchCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun max_attempts_must_be_at_least_one() {
        HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true },
            maxAttempts = 0
        )
    }

    @Test
    fun automation_stage_enum_does_not_include_waking_device() {
        try {
            AutomationStage.valueOf("WAKING_DEVICE")
            fail("WAKING_DEVICE should not exist in AutomationStage")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun failure_after_retry_limit_moves_to_failed() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { HotspotManager.HotspotState.DISABLED },
            accessibilityEnabled = { true },
            maxAttempts = 2
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onStepTimeout()
        coordinator.onStepTimeout()

        assertEquals(AutomationStage.FAILED, coordinator.snapshot().stage)
        assertEquals(2, coordinator.snapshot().attempt)
        assertEquals("timeout", coordinator.snapshot().failureReason)
    }
}

private class RecordingSettingsLauncher : HotspotSettingsLauncher {
    var launchCount = 0

    override fun launchHotspotSettings() {
        launchCount += 1
    }
}
