# Samsung Hotspot UI Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unsupported hidden-API hotspot enable flow with Samsung-specific accessibility automation that can reopen the Wi-Fi hotspot after boot and after Samsung auto-turns it off.

**Architecture:** Keep `ProxyService` as the long-running orchestrator, move hotspot enable behavior into a dedicated `HotspotAutomationCoordinator`, and let a new `AccessibilityService` manipulate the Samsung hotspot settings UI. Persist only the user toggle in `ProxySettings`; treat automation execution state as in-memory runtime state with explicit retries and verification through `HotspotManager`.

**Tech Stack:** Kotlin, Android Service, Android AccessibilityService, Jetpack Compose, SharedPreferences, JUnit4, Gradle

---

## File Structure

### Existing files to modify

- `app/src/main/java/com/example/yyproxy/AutoHotspotPolicy.kt`
  - Replace legacy `WRITE_SETTINGS` support logic with device-specific accessibility support logic.
- `app/src/main/java/com/example/yyproxy/HotspotManager.kt`
  - Keep hotspot state detection only; remove hotspot enable code paths.
- `app/src/main/java/com/example/yyproxy/MainActivity.kt`
  - Update the auto-hotspot UI to require accessibility, surface service status, and deep-link to accessibility settings.
- `app/src/main/java/com/example/yyproxy/ProxyService.kt`
  - Replace direct hotspot enable calls with coordinator-driven automation requests and periodic checks.
- `app/src/main/AndroidManifest.xml`
  - Register the accessibility service.

### New files to create

- `app/src/main/java/com/example/yyproxy/HotspotAutomationState.kt`
  - State enum and data holder for a single automation run.
- `app/src/main/java/com/example/yyproxy/HotspotAutomationCoordinator.kt`
  - Single-instance runtime orchestration, retries, Settings launch, verification, and accessibility event handling.
- `app/src/main/java/com/example/yyproxy/HotspotAutomationAccessibilityService.kt`
  - Accessibility entry point that feeds window updates into the coordinator.
- `app/src/main/java/com/example/yyproxy/AccessibilityServiceState.kt`
  - Helper for determining whether the app accessibility service is enabled.
- `app/src/main/res/xml/hotspot_automation_accessibility_service.xml`
  - Accessibility service metadata.
- `app/src/test/java/com/example/yyproxy/AccessibilityServiceStateTest.kt`
  - Unit tests for parsing the enabled accessibility services setting.
- `app/src/test/java/com/example/yyproxy/HotspotAutomationCoordinatorTest.kt`
  - Unit tests for single-run, retry, and verification behavior.
- `app/src/test/java/com/example/yyproxy/ProxyServiceAutomationDecisionTest.kt`
  - Unit tests for the service-side trigger gate.

### Existing tests to modify

- `app/src/test/java/com/example/yyproxy/AutoHotspotBehaviorTest.kt`
  - Replace legacy `WRITE_SETTINGS` assertions with Samsung/accessibility-specific toggle behavior tests.

---

### Task 1: Replace legacy support logic with Samsung accessibility policy

**Files:**
- Modify: `app/src/main/java/com/example/yyproxy/AutoHotspotPolicy.kt`
- Modify: `app/src/test/java/com/example/yyproxy/AutoHotspotBehaviorTest.kt`

- [ ] **Step 1: Write the failing policy tests**

```kotlin
package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHotspotBehaviorTest {

    @Test
    fun samsung_note9_android10_supports_accessibility_automation() {
        val support = AutoHotspotSupport.forDevice(
            manufacturer = "samsung",
            model = "SM-N9600",
            sdk = 29
        )

        assertTrue(support.isSupported)
        assertTrue(support.requiresAccessibilityService)
    }

    @Test
    fun non_target_devices_are_not_supported() {
        val support = AutoHotspotSupport.forDevice(
            manufacturer = "google",
            model = "Pixel 4",
            sdk = 29
        )

        assertFalse(support.isSupported)
    }

    @Test
    fun enabling_without_accessibility_opens_accessibility_settings_without_persisting() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            support = AutoHotspotSupport(isSupported = true, requiresAccessibilityService = true),
            accessibilityEnabled = false
        )

        assertFalse(result.enabled)
        assertTrue(result.openAccessibilitySettings)
        assertFalse(result.persistChange)
    }

    @Test
    fun enabling_with_accessibility_persists_and_restarts_service() {
        val result = AutoHotspotToggleCoordinator.onToggleRequested(
            requestedEnabled = true,
            support = AutoHotspotSupport(isSupported = true, requiresAccessibilityService = true),
            accessibilityEnabled = true
        )

        assertTrue(result.enabled)
        assertTrue(result.persistChange)
        assertTrue(result.restartService)
    }

    @Test
    fun on_resume_enables_toggle_after_accessibility_is_granted() {
        val result = AutoHotspotToggleCoordinator.onResume(
            currentlyEnabled = false,
            pendingAccessibilityEnable = true,
            accessibilityEnabled = true
        )

        assertTrue(result.enabled)
        assertFalse(result.pendingAccessibilityEnable)
        assertTrue(result.persistChange)
    }
}
```

- [ ] **Step 2: Run the policy tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.AutoHotspotBehaviorTest`

Expected: FAIL with unresolved references or mismatched constructor/function signatures in `AutoHotspotSupport` and `AutoHotspotToggleCoordinator`.

- [ ] **Step 3: Replace the policy implementation with accessibility-based support**

```kotlin
package com.example.yyproxy

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
```

- [ ] **Step 4: Run the policy tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.AutoHotspotBehaviorTest`

Expected: PASS with 5 tests passing.

- [ ] **Step 5: Commit the policy change**

```bash
git add app/src/main/java/com/example/yyproxy/AutoHotspotPolicy.kt app/src/test/java/com/example/yyproxy/AutoHotspotBehaviorTest.kt
git commit -m "feat: switch auto hotspot policy to accessibility automation"
```

---

### Task 2: Add the automation state machine and coordinator

**Files:**
- Create: `app/src/main/java/com/example/yyproxy/HotspotAutomationState.kt`
- Create: `app/src/main/java/com/example/yyproxy/HotspotAutomationCoordinator.kt`
- Create: `app/src/test/java/com/example/yyproxy/HotspotAutomationCoordinatorTest.kt`

- [ ] **Step 1: Write the failing coordinator tests**

```kotlin
package com.example.yyproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAutomationCoordinatorTest {

    @Test
    fun request_automation_starts_single_run_and_launches_settings() {
        val launcher = RecordingSettingsLauncher()
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = launcher,
            hotspotStateReader = { false },
            accessibilityEnabled = { true }
        )

        val started = coordinator.requestAutomation(AutomationTrigger.BOOT)

        assertTrue(started)
        assertEquals(AutomationStage.OPENING_HOTSPOT_SETTINGS, coordinator.snapshot().stage)
        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun duplicate_request_is_ignored_while_run_is_active() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { false },
            accessibilityEnabled = { true }
        )

        assertTrue(coordinator.requestAutomation(AutomationTrigger.BOOT))
        assertFalse(coordinator.requestAutomation(AutomationTrigger.PERIODIC_CHECK))
    }

    @Test
    fun verification_completes_when_hotspot_is_on() {
        var hotspotEnabled = false
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { hotspotEnabled },
            accessibilityEnabled = { true }
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onHotspotMainPage(mainSwitchChecked = false)
        coordinator.onAutoTurnOffPage(autoTurnOffChecked = false)
        hotspotEnabled = true
        coordinator.onHotspotMainPage(mainSwitchChecked = true)

        assertEquals(AutomationStage.COMPLETED, coordinator.snapshot().stage)
    }

    @Test
    fun failure_after_retry_limit_moves_to_failed() {
        val coordinator = HotspotAutomationCoordinator(
            settingsLauncher = RecordingSettingsLauncher(),
            hotspotStateReader = { false },
            accessibilityEnabled = { true },
            maxAttempts = 2
        )

        coordinator.requestAutomation(AutomationTrigger.BOOT)
        coordinator.onStepTimeout()
        coordinator.onStepTimeout()

        assertEquals(AutomationStage.FAILED, coordinator.snapshot().stage)
        assertEquals(2, coordinator.snapshot().attempt)
    }
}

private class RecordingSettingsLauncher : HotspotSettingsLauncher {
    var launchCount = 0

    override fun launchHotspotSettings() {
        launchCount += 1
    }
}
```

- [ ] **Step 2: Run the coordinator tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.HotspotAutomationCoordinatorTest`

Expected: FAIL because `HotspotAutomationCoordinator`, `AutomationStage`, `AutomationTrigger`, and `HotspotSettingsLauncher` do not exist yet.

- [ ] **Step 3: Create the automation state model**

```kotlin
package com.example.yyproxy

enum class AutomationStage {
    IDLE,
    WAKING_DEVICE,
    OPENING_HOTSPOT_SETTINGS,
    DISABLING_AUTO_TURNOFF,
    ENABLING_HOTSPOT,
    VERIFYING,
    COMPLETED,
    FAILED
}

enum class AutomationTrigger {
    BOOT,
    PERIODIC_CHECK
}

data class HotspotAutomationSnapshot(
    val stage: AutomationStage = AutomationStage.IDLE,
    val attempt: Int = 0,
    val trigger: AutomationTrigger? = null,
    val failureReason: String? = null
)
```

- [ ] **Step 4: Create the minimal coordinator implementation**

```kotlin
package com.example.yyproxy

interface HotspotSettingsLauncher {
    fun launchHotspotSettings()
}

class HotspotAutomationCoordinator(
    private val settingsLauncher: HotspotSettingsLauncher,
    private val hotspotStateReader: () -> Boolean,
    private val accessibilityEnabled: () -> Boolean,
    private val maxAttempts: Int = 3
) {
    private var snapshot = HotspotAutomationSnapshot()

    fun snapshot(): HotspotAutomationSnapshot = snapshot

    fun requestAutomation(trigger: AutomationTrigger): Boolean {
        if (snapshot.stage != AutomationStage.IDLE &&
            snapshot.stage != AutomationStage.COMPLETED &&
            snapshot.stage != AutomationStage.FAILED
        ) {
            return false
        }
        if (!accessibilityEnabled()) return false
        if (hotspotStateReader()) return false

        snapshot = HotspotAutomationSnapshot(
            stage = AutomationStage.OPENING_HOTSPOT_SETTINGS,
            attempt = 1,
            trigger = trigger
        )
        settingsLauncher.launchHotspotSettings()
        return true
    }

    fun onHotspotMainPage(mainSwitchChecked: Boolean) {
        if (mainSwitchChecked || hotspotStateReader()) {
            snapshot = snapshot.copy(stage = AutomationStage.COMPLETED, failureReason = null)
            return
        }
        snapshot = snapshot.copy(stage = AutomationStage.DISABLING_AUTO_TURNOFF)
    }

    fun onAutoTurnOffPage(autoTurnOffChecked: Boolean) {
        snapshot = snapshot.copy(
            stage = if (autoTurnOffChecked) AutomationStage.ENABLING_HOTSPOT else AutomationStage.ENABLING_HOTSPOT
        )
    }

    fun onStepTimeout() {
        if (snapshot.attempt >= maxAttempts) {
            snapshot = snapshot.copy(stage = AutomationStage.FAILED, failureReason = "timeout")
            return
        }

        snapshot = snapshot.copy(
            stage = AutomationStage.OPENING_HOTSPOT_SETTINGS,
            attempt = snapshot.attempt + 1,
            failureReason = "timeout"
        )
        settingsLauncher.launchHotspotSettings()
    }
}
```

- [ ] **Step 5: Expand the coordinator to the intended behavior**

```kotlin
class HotspotAutomationCoordinator(
    private val settingsLauncher: HotspotSettingsLauncher,
    private val hotspotStateReader: () -> Boolean,
    private val accessibilityEnabled: () -> Boolean,
    private val maxAttempts: Int = 3
) {
    private var snapshot = HotspotAutomationSnapshot()

    fun snapshot(): HotspotAutomationSnapshot = snapshot

    fun requestAutomation(trigger: AutomationTrigger): Boolean {
        if (isRunActive() || !accessibilityEnabled() || hotspotStateReader()) return false
        snapshot = HotspotAutomationSnapshot(
            stage = AutomationStage.WAKING_DEVICE,
            attempt = 1,
            trigger = trigger
        )
        launchSettings()
        return true
    }

    fun onHotspotMainPage(mainSwitchChecked: Boolean) {
        if (hotspotStateReader() || mainSwitchChecked) {
            snapshot = snapshot.copy(stage = AutomationStage.VERIFYING)
            completeIfHotspotEnabled()
            return
        }
        snapshot = snapshot.copy(stage = AutomationStage.DISABLING_AUTO_TURNOFF)
    }

    fun onAutoTurnOffPage(autoTurnOffChecked: Boolean) {
        snapshot = snapshot.copy(
            stage = if (autoTurnOffChecked) AutomationStage.DISABLING_AUTO_TURNOFF else AutomationStage.ENABLING_HOTSPOT
        )
    }

    fun onHotspotSwitchClicked() {
        snapshot = snapshot.copy(stage = AutomationStage.VERIFYING)
        completeIfHotspotEnabled()
    }

    fun onStepTimeout() {
        retryOrFail("timeout")
    }

    private fun launchSettings() {
        snapshot = snapshot.copy(stage = AutomationStage.OPENING_HOTSPOT_SETTINGS)
        settingsLauncher.launchHotspotSettings()
    }

    private fun completeIfHotspotEnabled() {
        snapshot = if (hotspotStateReader()) {
            snapshot.copy(stage = AutomationStage.COMPLETED, failureReason = null)
        } else {
            snapshot.copy(stage = AutomationStage.ENABLING_HOTSPOT)
        }
    }

    private fun retryOrFail(reason: String) {
        if (snapshot.attempt >= maxAttempts) {
            snapshot = snapshot.copy(stage = AutomationStage.FAILED, failureReason = reason)
            return
        }
        snapshot = snapshot.copy(attempt = snapshot.attempt + 1, failureReason = reason)
        launchSettings()
    }

    private fun isRunActive(): Boolean {
        return snapshot.stage != AutomationStage.IDLE &&
            snapshot.stage != AutomationStage.COMPLETED &&
            snapshot.stage != AutomationStage.FAILED
    }
}
```

- [ ] **Step 6: Run the coordinator tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.HotspotAutomationCoordinatorTest`

Expected: PASS with 4 tests passing.

- [ ] **Step 7: Commit the coordinator**

```bash
git add app/src/main/java/com/example/yyproxy/HotspotAutomationState.kt app/src/main/java/com/example/yyproxy/HotspotAutomationCoordinator.kt app/src/test/java/com/example/yyproxy/HotspotAutomationCoordinatorTest.kt
git commit -m "feat: add hotspot automation coordinator"
```

---

### Task 3: Add the accessibility service and manifest wiring

**Files:**
- Create: `app/src/main/java/com/example/yyproxy/AccessibilityServiceState.kt`
- Create: `app/src/main/java/com/example/yyproxy/HotspotAutomationAccessibilityService.kt`
- Create: `app/src/main/res/xml/hotspot_automation_accessibility_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Write the failing helper test for accessibility state parsing**

```kotlin
package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStateTest {

    @Test
    fun enabled_services_string_matches_service_component() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "com.example.yyproxy/.HotspotAutomationAccessibilityService:other/service",
            expectedComponent = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertTrue(enabled)
    }

    @Test
    fun missing_service_component_returns_false() {
        val enabled = AccessibilityServiceState.isServiceEnabled(
            enabledServicesSetting = "other/service",
            expectedComponent = "com.example.yyproxy/.HotspotAutomationAccessibilityService"
        )

        assertFalse(enabled)
    }
}
```

- [ ] **Step 2: Run the helper test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.AccessibilityServiceStateTest`

Expected: FAIL because `AccessibilityServiceState` does not exist yet.

- [ ] **Step 3: Create the accessibility enabled-state helper**

```kotlin
package com.example.yyproxy

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityServiceState {
    fun isAutomationServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val component = ComponentName(
            context,
            HotspotAutomationAccessibilityService::class.java
        ).flattenToShortString()
        return isServiceEnabled(enabled, component)
    }

    fun isServiceEnabled(
        enabledServicesSetting: String,
        expectedComponent: String
    ): Boolean {
        return enabledServicesSetting
            .split(':')
            .any { it.equals(expectedComponent, ignoreCase = true) }
    }
}
```

- [ ] **Step 4: Create the accessibility service metadata**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:description="@string/app_name"
    android:notificationTimeout="100"
    android:packageNames="com.android.settings" />
```

- [ ] **Step 5: Create the accessibility service**

```kotlin
package com.example.yyproxy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HotspotAutomationAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName != "com.android.settings") return

        val className = event?.className?.toString().orEmpty()
        HotspotAutomationRuntime.handleWindowUpdate(
            service = this,
            root = root,
            packageName = packageName,
            className = className
        )
    }

    override fun onInterrupt() = Unit
}

object HotspotAutomationRuntime {
    @Volatile
    var coordinator: UiAutomationCoordinatorBridge? = null

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
    fun handleWindowUpdate(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        packageName: String,
        className: String
    )
}
```

- [ ] **Step 6: Register the accessibility service in the manifest**

```xml
<service
    android:name=".HotspotAutomationAccessibilityService"
    android:enabled="true"
    android:exported="false"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>

    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/hotspot_automation_accessibility_service" />
</service>
```

- [ ] **Step 7: Run the helper test and a compile check**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.AccessibilityServiceStateTest :app:compileDebugKotlin`

Expected: PASS with the helper test green and Kotlin compilation successful.

- [ ] **Step 8: Commit the accessibility service wiring**

```bash
git add app/src/main/java/com/example/yyproxy/AccessibilityServiceState.kt app/src/main/java/com/example/yyproxy/HotspotAutomationAccessibilityService.kt app/src/main/res/xml/hotspot_automation_accessibility_service.xml app/src/main/AndroidManifest.xml
git commit -m "feat: add Samsung hotspot accessibility service"
```

---

### Task 4: Integrate the coordinator into service, UI, and hotspot detection

**Files:**
- Modify: `app/src/main/java/com/example/yyproxy/HotspotManager.kt`
- Modify: `app/src/main/java/com/example/yyproxy/ProxyService.kt`
- Modify: `app/src/main/java/com/example/yyproxy/MainActivity.kt`

- [ ] **Step 1: Write an integration-oriented unit test for service trigger decisions**

```kotlin
package com.example.yyproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServiceAutomationDecisionTest {

    @Test
    fun automation_request_requires_toggle_accessibility_and_hotspot_off() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = true,
            hotspotEnabled = false
        )

        assertTrue(shouldRequest)
    }

    @Test
    fun automation_request_is_skipped_when_accessibility_is_disabled() {
        val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
            autoHotspotEnabled = true,
            accessibilityEnabled = false,
            hotspotEnabled = false
        )

        assertFalse(shouldRequest)
    }
}
```

- [ ] **Step 2: Run the integration-oriented test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.ProxyServiceAutomationDecisionTest`

Expected: FAIL because `ProxyServiceAutomationDecision` does not exist yet.

- [ ] **Step 3: Simplify `HotspotManager` to state detection only**

```kotlin
package com.example.yyproxy

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.lang.reflect.Method

object HotspotManager {
    private const val TAG = "HotspotManager"
    private const val WIFI_AP_STATE_ENABLED = 13

    fun isHotspotEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get hotspot state", e)
            false
        }
    }
}
```

- [ ] **Step 4: Add a small service decision helper and use it from `ProxyService`**

```kotlin
package com.example.yyproxy

object ProxyServiceAutomationDecision {
    fun shouldRequestAutomation(
        autoHotspotEnabled: Boolean,
        accessibilityEnabled: Boolean,
        hotspotEnabled: Boolean
    ): Boolean {
        return autoHotspotEnabled && accessibilityEnabled && !hotspotEnabled
    }
}
```

```kotlin
private fun checkAndEnableHotspot() {
    val shouldRequest = ProxyServiceAutomationDecision.shouldRequestAutomation(
        autoHotspotEnabled = ProxySettings.isAutoHotspotEnabled(this),
        accessibilityEnabled = AccessibilityServiceState.isAutomationServiceEnabled(this),
        hotspotEnabled = HotspotManager.isHotspotEnabled(this)
    )
    if (!shouldRequest) return

    hotspotAutomationCoordinator.requestAutomation(AutomationTrigger.PERIODIC_CHECK)
}
```

- [ ] **Step 5: Instantiate and wire the coordinator bridge inside `ProxyService`**

```kotlin
fun handleWindowUpdate(
    service: AccessibilityService,
    root: AccessibilityNodeInfo,
    className: String
) {
    val mainSwitch = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_widget")
        .firstOrNull()

    if (className.contains("WifiTetherSettingsActivity") && mainSwitch != null) {
        onHotspotMainPage(mainSwitchChecked = mainSwitch.isChecked)
        if (!mainSwitch.isChecked) {
            mainSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            onHotspotSwitchClicked()
        }
        return
    }

    val autoTurnOffSwitch = root.findAccessibilityNodeInfosByViewId("android:id/switch_widget")
        .firstOrNull()
    if (autoTurnOffSwitch != null && snapshot().stage == AutomationStage.DISABLING_AUTO_TURNOFF) {
        onAutoTurnOffPage(autoTurnOffChecked = autoTurnOffSwitch.isChecked)
        if (autoTurnOffSwitch.isChecked) {
            autoTurnOffSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
```

```kotlin
private val hotspotAutomationCoordinator = HotspotAutomationCoordinator(
    settingsLauncher = object : HotspotSettingsLauncher {
        override fun launchHotspotSettings() {
            val intent = Intent("com.android.settings.WIFI_TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    },
    hotspotStateReader = { HotspotManager.isHotspotEnabled(this) },
    accessibilityEnabled = { AccessibilityServiceState.isAutomationServiceEnabled(this) }
)

override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    HotspotAutomationRuntime.coordinator = object : UiAutomationCoordinatorBridge {
        override fun handleWindowUpdate(
            service: AccessibilityService,
            root: AccessibilityNodeInfo,
            packageName: String,
            className: String
        ) {
            hotspotAutomationCoordinator.handleWindowUpdate(
                service = service,
                root = root,
                className = className
            )
        }
    }
}
```

- [ ] **Step 6: Update the Compose UI to depend on accessibility instead of `WRITE_SETTINGS`**

```kotlin
val autoHotspotSupport = remember {
    AutoHotspotSupport.forDevice(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdk = Build.VERSION.SDK_INT
    )
}
var accessibilityEnabled by remember {
    mutableStateOf(AccessibilityServiceState.isAutomationServiceEnabled(context))
}
```

```kotlin
val result = AutoHotspotToggleCoordinator.onToggleRequested(
    requestedEnabled = enabled,
    support = autoHotspotSupport,
    accessibilityEnabled = accessibilityEnabled
)
```

```kotlin
if (result.openAccessibilitySettings) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
```

```kotlin
Text(
    text = if (accessibilityEnabled) {
        "Accessibility automation ready"
    } else {
        "Accessibility service required"
    },
    style = MaterialTheme.typography.bodySmall
)
```

- [ ] **Step 7: Run targeted tests and a compile check**

Run: `./gradlew :app:testDebugUnitTest --tests com.example.yyproxy.ProxyServiceAutomationDecisionTest --tests com.example.yyproxy.AutoHotspotBehaviorTest --tests com.example.yyproxy.HotspotAutomationCoordinatorTest :app:compileDebugKotlin`

Expected: PASS for all targeted unit tests and successful Kotlin compilation.

- [ ] **Step 8: Commit the service and UI integration**

```bash
git add app/src/main/java/com/example/yyproxy/HotspotManager.kt app/src/main/java/com/example/yyproxy/ProxyService.kt app/src/main/java/com/example/yyproxy/MainActivity.kt
git commit -m "feat: integrate Samsung hotspot automation"
```

---

### Task 5: Run full verification and document device validation steps

**Files:**
- Modify: `docs/superpowers/plans/2026-04-15-samsung-hotspot-ui-automation.md`

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS with all unit tests green.

- [ ] **Step 2: Run a final compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the Samsung device validation checklist**

```text
1. Install the debug build on SM-N9600.
2. Enable the app's accessibility service in system settings.
3. Turn on the app's auto-hotspot switch.
4. Reboot the phone and confirm hotspot turns on automatically.
5. Manually turn hotspot off and confirm the service reopens it.
6. Enter Samsung hotspot advanced settings and confirm "Turn off hotspot automatically" is off after automation runs.
7. Disable the accessibility service and confirm the app surfaces the missing prerequisite instead of silently claiming success.
```

- [ ] **Step 4: Record any device-only deviations back into the plan if observed**

```text
If the Samsung settings page differs from the captured `adb` XML, update the exact resource IDs, titles, and click order in this plan before continuing to broader rollout.
```

- [ ] **Step 5: Commit the final verification update if the plan changed**

```bash
git add docs/superpowers/plans/2026-04-15-samsung-hotspot-ui-automation.md
git commit -m "docs: capture Samsung hotspot automation verification notes"
```
