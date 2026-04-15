package com.example.yyproxy

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityServiceState {
    fun isAutomationServiceEnabled(context: Context): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!accessibilityEnabled) {
            return false
        }

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val component = ComponentName(
            context.packageName,
            HotspotAutomationAccessibilityService::class.java.name
        )
        return isServiceEnabled(
            enabledServicesSetting = enabledServices,
            expectedComponentFull = component.flattenToString(),
            expectedComponentShort = component.flattenToShortString()
        )
    }

    fun isHotspotAutomationServiceEnabled(context: Context): Boolean {
        return isAutomationServiceEnabled(context)
    }

    fun isServiceEnabled(
        enabledServicesSetting: String,
        expectedComponentFull: String,
        expectedComponentShort: String
    ): Boolean {
        if (enabledServicesSetting.isBlank()) {
            return false
        }
        return enabledServicesSetting
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any {
                it.equals(expectedComponentFull, ignoreCase = true) ||
                    it.equals(expectedComponentShort, ignoreCase = true)
            }
    }
}
