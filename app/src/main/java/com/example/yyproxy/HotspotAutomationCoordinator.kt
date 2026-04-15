package com.example.yyproxy

interface HotspotSettingsLauncher {
    fun launchHotspotSettings()
}

class HotspotAutomationCoordinator(
    private val settingsLauncher: HotspotSettingsLauncher,
    private val hotspotStateReader: () -> HotspotManager.HotspotState,
    private val accessibilityEnabled: () -> Boolean,
    private val maxAttempts: Int = 3
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    private var snapshot = HotspotAutomationSnapshot()

    fun snapshot(): HotspotAutomationSnapshot = snapshot

    fun requestAutomation(trigger: AutomationTrigger): Boolean {
        if (!accessibilityEnabled()) return false
        val currentState = hotspotStateReader()
        if (currentState == HotspotManager.HotspotState.ENABLED || currentState == HotspotManager.HotspotState.ENABLING) return false

        if (isRunActive()) {
            if (trigger == AutomationTrigger.MANUAL_REFRESH) {
                // If manual refresh, reset the state and start fresh.
                snapshot = HotspotAutomationSnapshot()
            } else {
                return false
            }
        }

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

        val observedState = hotspotStateReader()
        if (snapshot.stage == AutomationStage.VERIFYING) {
            advanceVerification(observedState)
            return
        }

        if (mainSwitchChecked || observedState == HotspotManager.HotspotState.ENABLED || observedState == HotspotManager.HotspotState.ENABLING) {
            advanceVerification(observedState)
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

        advanceVerification(observedState = hotspotStateReader())
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

    private fun advanceVerification(observedState: HotspotManager.HotspotState) {
        if (snapshot.stage != AutomationStage.VERIFYING) {
            snapshot = snapshot.copy(stage = AutomationStage.VERIFYING, failureReason = null)
            return
        }

        snapshot = when (observedState) {
            HotspotManager.HotspotState.ENABLED -> {
                snapshot.copy(stage = AutomationStage.COMPLETED, failureReason = null)
            }
            HotspotManager.HotspotState.ENABLING -> {
                // Keep verifying, don't fail yet
                snapshot.copy(stage = AutomationStage.VERIFYING, failureReason = null)
            }
            else -> {
                // Either DISABLED, DISABLING, FAILED or UNKNOWN
                snapshot.copy(stage = AutomationStage.VERIFYING, failureReason = null)
            }
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
