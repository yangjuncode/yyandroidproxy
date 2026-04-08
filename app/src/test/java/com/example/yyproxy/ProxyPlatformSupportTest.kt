package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPlatformSupportTest {

    @Test
    fun sdk32_does_not_require_notification_permission_or_special_use_fgs() {
        val support = ProxyPlatformSupport.forSdk(32)

        assertFalse(support.requiresNotificationPermission)
        assertFalse(support.requiresSpecialUseForegroundServiceType)
    }

    @Test
    fun sdk33_requires_notification_permission_only() {
        val support = ProxyPlatformSupport.forSdk(33)

        assertTrue(support.requiresNotificationPermission)
        assertFalse(support.requiresSpecialUseForegroundServiceType)
    }

    @Test
    fun sdk34_requires_notification_permission_and_special_use_fgs() {
        val support = ProxyPlatformSupport.forSdk(34)

        assertTrue(support.requiresNotificationPermission)
        assertTrue(support.requiresSpecialUseForegroundServiceType)
    }
}
