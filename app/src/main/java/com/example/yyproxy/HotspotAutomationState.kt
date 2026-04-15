package com.example.yyproxy

enum class AutomationStage {
    IDLE,
    OPENING_HOTSPOT_SETTINGS,
    DISABLING_AUTO_TURNOFF,
    ENABLING_HOTSPOT,
    VERIFYING,
    COMPLETED,
    FAILED
}

enum class AutomationTrigger {
    BOOT,
    PERIODIC_CHECK,
    MANUAL_REFRESH
}

data class HotspotAutomationSnapshot(
    val stage: AutomationStage = AutomationStage.IDLE,
    val attempt: Int = 0,
    val trigger: AutomationTrigger? = null,
    val failureReason: String? = null
)
