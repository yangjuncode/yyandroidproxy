package com.example.yyproxy

import android.os.Build

data class AutoHotspotSupport(
    val canProgrammaticallyEnable: Boolean,
    val requiresWriteSettingsPermission: Boolean
) {
    companion object {
        fun forSdk(sdk: Int): AutoHotspotSupport {
            val canProgrammaticallyEnable = sdk < Build.VERSION_CODES.O
            return AutoHotspotSupport(
                canProgrammaticallyEnable = canProgrammaticallyEnable,
                requiresWriteSettingsPermission = canProgrammaticallyEnable
            )
        }
    }
}

data class AutoHotspotToggleResult(
    val enabled: Boolean,
    val pendingPermissionRequest: Boolean,
    val persistChange: Boolean,
    val restartService: Boolean,
    val openWriteSettings: Boolean = false,
    val showUnsupportedMessage: Boolean = false
)

object AutoHotspotToggleCoordinator {
    fun onToggleRequested(
        requestedEnabled: Boolean,
        canProgrammaticallyEnable: Boolean,
        canWriteSettings: Boolean
    ): AutoHotspotToggleResult {
        if (!requestedEnabled) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingPermissionRequest = false,
                persistChange = true,
                restartService = true
            )
        }

        if (!canProgrammaticallyEnable) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingPermissionRequest = false,
                persistChange = false,
                restartService = false,
                showUnsupportedMessage = true
            )
        }

        if (!canWriteSettings) {
            return AutoHotspotToggleResult(
                enabled = false,
                pendingPermissionRequest = true,
                persistChange = false,
                restartService = false,
                openWriteSettings = true
            )
        }

        return AutoHotspotToggleResult(
            enabled = true,
            pendingPermissionRequest = false,
            persistChange = true,
            restartService = true
        )
    }

    fun onResume(
        currentlyEnabled: Boolean,
        pendingPermissionRequest: Boolean,
        canWriteSettings: Boolean
    ): AutoHotspotToggleResult {
        if (!pendingPermissionRequest) {
            return AutoHotspotToggleResult(
                enabled = currentlyEnabled,
                pendingPermissionRequest = false,
                persistChange = false,
                restartService = false
            )
        }

        if (!canWriteSettings) {
            return AutoHotspotToggleResult(
                enabled = currentlyEnabled,
                pendingPermissionRequest = true,
                persistChange = false,
                restartService = false
            )
        }

        return AutoHotspotToggleResult(
            enabled = true,
            pendingPermissionRequest = false,
            persistChange = true,
            restartService = true
        )
    }
}
