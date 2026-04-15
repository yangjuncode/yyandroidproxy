package com.example.yyproxy

import android.os.Build

data class AutoHotspotSupport(
    val isSupported: Boolean,
    val requiresAccessibilityService: Boolean
) {
    companion object {
        fun forDevice(manufacturer: String, model: String, sdk: Int): AutoHotspotSupport {
            val isSupported = manufacturer.equals("samsung", ignoreCase = true) &&
                model == "SM-N9600" &&
                sdk == 29

            return AutoHotspotSupport(
                isSupported = isSupported,
                requiresAccessibilityService = isSupported
            )
        }

        fun forSdk(sdk: Int): AutoHotspotSupport =
            forDevice(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdk = sdk
            )
    }
}

data class AutoHotspotToggleResult(
    val enabled: Boolean,
    val pendingAccessibilityEnable: Boolean,
    val persistChange: Boolean,
    val restartService: Boolean,
    val openAccessibilitySettings: Boolean = false,
    val showUnsupportedMessage: Boolean = false
)

object AutoHotspotToggleCoordinator {
    fun onToggleRequested(
        requestedEnabled: Boolean,
        support: AutoHotspotSupport,
        accessibilityEnabled: Boolean
    ): AutoHotspotToggleResult {
        if (!requestedEnabled) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingAccessibilityEnable = false,
                persistChange = true,
                restartService = true
            )
        }

        if (!support.isSupported) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingAccessibilityEnable = false,
                persistChange = false,
                restartService = false,
                showUnsupportedMessage = true
            )
        }

        if (!accessibilityEnabled) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingAccessibilityEnable = true,
                persistChange = false,
                restartService = false,
                openAccessibilitySettings = true
            )
        }

        return AutoHotspotToggleResult(
            enabled = true,
            pendingAccessibilityEnable = false,
            persistChange = true,
            restartService = true
        )
    }

    fun onResume(
        currentlyEnabled: Boolean,
        pendingAccessibilityEnable: Boolean,
        accessibilityEnabled: Boolean
    ): AutoHotspotToggleResult {
        if (!pendingAccessibilityEnable) {
            return AutoHotspotToggleResult(
                enabled = currentlyEnabled,
                pendingAccessibilityEnable = false,
                persistChange = false,
                restartService = false
            )
        }

        if (!accessibilityEnabled) {
            return AutoHotspotToggleResult(
                enabled = currentlyEnabled,
                pendingAccessibilityEnable = true,
                persistChange = false,
                restartService = false
            )
        }

        return AutoHotspotToggleResult(
            enabled = true,
            pendingAccessibilityEnable = false,
            persistChange = true,
            restartService = true
        )
    }
}
