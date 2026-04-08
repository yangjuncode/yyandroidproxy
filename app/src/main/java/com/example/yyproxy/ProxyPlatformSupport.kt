package com.example.yyproxy

data class ProxyPlatformSupport(
    val requiresNotificationPermission: Boolean,
    val requiresSpecialUseForegroundServiceType: Boolean
) {
    companion object {
        fun forSdk(sdkInt: Int): ProxyPlatformSupport {
            return ProxyPlatformSupport(
                requiresNotificationPermission = sdkInt >= 33,
                requiresSpecialUseForegroundServiceType = sdkInt >= 34
            )
        }
    }
}
