package com.example.yyproxy

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityServiceState {
    private const val HOTSPOT_AUTOMATION_SERVICE_CLASS =
        "com.example.yyproxy.HotspotAutomationAccessibilityService"

    fun isHotspotAutomationServiceEnabled(context: Context): Boolean {
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
        )
        val full = ComponentName(
            context.packageName,
            HOTSPOT_AUTOMATION_SERVICE_CLASS
        ).flattenToString()
        val short = ComponentName(
            context.packageName,
            HOTSPOT_AUTOMATION_SERVICE_CLASS
        ).flattenToShortString()
        return containsService(enabledServices, full) || containsService(enabledServices, short)
    }

    fun containsService(enabledServices: String?, expectedServiceId: String): Boolean {
        if (enabledServices.isNullOrBlank()) {
            return false
        }
        return enabledServices
            .split(':')
            .any { it.equals(expectedServiceId, ignoreCase = true) }
    }
}
