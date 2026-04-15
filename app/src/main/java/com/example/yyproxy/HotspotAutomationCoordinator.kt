package com.example.yyproxy

interface HotspotSettingsLauncher {
    fun launchHotspotSettings()
}

class HotspotAutomationCoordinator(
    private val settingsLauncher: HotspotSettingsLauncher,
    private val hotspotStateReader: () -> Boolean,
    private val accessibilityEnabled: () -> Boolean,
    private val maxAttempts: Int = 3
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    private var snapshot = HotspotAutomationSnapshot()

    fun snapshot(): HotspotAutomationSnapshot = snapshot

    fun requestAutomation(trigger: AutomationTrigger): Boolean {
        if (isRunActive() || !accessibilityEnabled() || hotspotStateReader()) return false

        snapshot = HotspotAutomationSnapshot(
            stage = AutomationStage.OPENING_HOTSPOT_SETTINGS,
            attempt = 1,
            trigger = trigger
        )
        launchSettings()
        return true
    }

    fun onHotspotMainPage(mainSwitchChecked: Boolean) {
        if (!isHotspotMainPageStage(snapshot.stage)) return

        val observedHotspotEnabled = hotspotStateReader()
        if (snapshot.stage == AutomationStage.VERIFYING) {
            advanceVerification(observedHotspotEnabled)
            return
        }

        if (mainSwitchChecked || observedHotspotEnabled) {
            advanceVerification(observedHotspotEnabled)
            return
        }

        snapshot = snapshot.copy(
            stage = AutomationStage.ENABLING_HOTSPOT,
            failureReason = null
        )
    }

    fun onAutoTurnOffPage(autoTurnOffChecked: Boolean) {
        if (snapshot.stage != AutomationStage.DISABLING_AUTO_TURNOFF) return

        snapshot = snapshot.copy(
            stage = if (autoTurnOffChecked) {
                AutomationStage.DISABLING_AUTO_TURNOFF
            } else {
                AutomationStage.ENABLING_HOTSPOT
            },
            failureReason = null
        )
    }

    fun onHotspotSwitchClicked() {
        if (snapshot.stage != AutomationStage.ENABLING_HOTSPOT &&
            snapshot.stage != AutomationStage.VERIFYING
        ) return

        advanceVerification(observedHotspotEnabled = hotspotStateReader())
    }

    fun onStepTimeout() {
        if (!isRunActive()) return

        retryOrFail("timeout")
    }

    private fun launchSettings() {
        snapshot = snapshot.copy(
            stage = AutomationStage.OPENING_HOTSPOT_SETTINGS,
            failureReason = null
        )
        settingsLauncher.launchHotspotSettings()
    }

    private fun advanceVerification(observedHotspotEnabled: Boolean) {
        if (snapshot.stage != AutomationStage.VERIFYING) {
            snapshot = snapshot.copy(stage = AutomationStage.VERIFYING, failureReason = null)
            return
        }

        snapshot = if (observedHotspotEnabled) {
            snapshot.copy(stage = AutomationStage.COMPLETED, failureReason = null)
        } else {
            snapshot.copy(stage = AutomationStage.VERIFYING, failureReason = null)
        }
    }

    private fun retryOrFail(reason: String) {
        if (snapshot.attempt >= maxAttempts) {
            snapshot = snapshot.copy(stage = AutomationStage.FAILED, failureReason = reason)
            return
        }

        snapshot = snapshot.copy(attempt = snapshot.attempt + 1, failureReason = null)
        launchSettings()
    }

    private fun isRunActive(): Boolean {
        return snapshot.stage != AutomationStage.IDLE &&
            snapshot.stage != AutomationStage.COMPLETED &&
            snapshot.stage != AutomationStage.FAILED
    }

    private fun isHotspotMainPageStage(stage: AutomationStage): Boolean {
        return stage == AutomationStage.OPENING_HOTSPOT_SETTINGS ||
            stage == AutomationStage.ENABLING_HOTSPOT ||
            stage == AutomationStage.VERIFYING
    }
}
