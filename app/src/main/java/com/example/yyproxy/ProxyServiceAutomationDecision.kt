package com.example.yyproxy

object ProxyServiceAutomationDecision {
    fun shouldRequestAutomation(
        autoHotspotEnabled: Boolean,
        accessibilityEnabled: Boolean,
        hotspotState: HotspotManager.HotspotState
    ): Boolean {
        return autoHotspotEnabled &&
            accessibilityEnabled &&
            hotspotState == HotspotManager.HotspotState.DISABLED
    }
}
