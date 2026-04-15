package com.example.yyproxy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HotspotAutomationAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName != SETTINGS_PACKAGE_NAME) return

        val root = rootInActiveWindow ?: return
        try {
            HotspotAutomationRuntime.handleWindowUpdate(
                service = this,
                root = root,
                packageName = packageName,
                className = event?.className?.toString().orEmpty()
            )
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        private const val SETTINGS_PACKAGE_NAME = "com.android.settings"
    }
}

object HotspotAutomationRuntime {
    @Volatile
    var coordinator: UiAutomationCoordinatorBridge? = null

    /**
     * Ownership contract: [root] is borrowed for the duration of this call only.
     * Implementations must not retain [root] after returning.
     */
    fun handleWindowUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String
    ) {
        coordinator?.handleWindowUpdate(service, root, packageName, className)
    }
}

interface UiAutomationCoordinatorBridge {
    /**
     * Ownership contract: [root] must be treated as ephemeral and must not be retained.
     * The accessibility service recycles it after this method returns.
     */
    fun handleWindowUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String
    )
}
