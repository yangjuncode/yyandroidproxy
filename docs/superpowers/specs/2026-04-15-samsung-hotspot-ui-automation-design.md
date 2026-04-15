# Samsung Android 10 Hotspot UI Automation Design

## Summary

Implement a Samsung-specific automatic hotspot enable flow for `SM-N9600` on Android 10 by driving the system Settings UI through an `AccessibilityService`.

The goal is to support two user-visible behaviors when the app-level auto-hotspot option is enabled:

1. Automatically enable the Wi-Fi hotspot after boot.
2. Automatically re-enable the Wi-Fi hotspot when Samsung turns it off later.

This design is intentionally device-specific. It targets the currently connected device only and does not attempt to support generic Android hotspot automation.

## Device Findings

The design is based on direct `adb` inspection of the target device:

- Manufacturer: `samsung`
- Model: `SM-N9600`
- Android version: `10`
- SDK: `29`
- Locale: `zh-Hans-CN`
- Hotspot settings action: `com.android.settings.WIFI_TETHER_SETTINGS`
- Hotspot settings activity: `com.android.settings/.Settings$WifiTetherSettingsActivity`
- Main hotspot switch resource id: `com.android.settings:id/switch_widget`
- Auto turn-off switch resource id inside advanced page: `android:id/switch_widget`
- The device currently has no secure lockscreen password configured, so lockscreen automation is feasible without bypassing credential protection.

## Goals

- Reuse the existing app toggle for enabling/disabling automatic hotspot behavior.
- Trigger hotspot automation after boot and during runtime if hotspot turns off.
- Use stable Samsung Settings entry points and resource IDs rather than hidden APIs.
- Disable Samsung's "Turn off hotspot automatically" option before enabling hotspot.
- Keep automation single-instance and bounded by explicit timeouts and retries.
- Expose clear UI state so the user can see whether the required accessibility service is enabled.

## Non-Goals

- Support non-Samsung devices.
- Support Android versions other than the current Samsung Android 10 ROM.
- Support devices with secure PIN/pattern/password lockscreen bypass.
- Provide system-level tethering APIs or hidden-API-based hotspot enable paths.
- Guarantee compatibility after future Settings app or ROM changes.

## Architecture

### Existing Components Kept

- `ProxyService` remains the always-on coordinator.
- `BootReceiver` remains the boot trigger.
- `ProxySettings` continues to persist the auto-hotspot option.
- `HotspotManager` remains the hotspot state detection utility.

### New Components

#### `HotspotAutomationAccessibilityService`

Responsible only for driving the Settings UI:

- Observe active accessibility windows.
- Detect the hotspot settings page and the auto turn-off page.
- Click the hotspot switch when needed.
- Click the auto turn-off switch off when needed.
- Navigate back/home when the flow is complete or failed.

This service does not own scheduling or business persistence.

#### `HotspotAutomationCoordinator`

Responsible only for orchestration:

- Accept automation requests from `ProxyService`.
- Reject duplicate requests if a run is already active.
- Launch the Samsung hotspot settings page.
- Track automation progress and retries.
- Ask the accessibility service to continue when matching windows appear.
- Verify final hotspot state using `HotspotManager`.

#### `HotspotAutomationState`

Represents the automation state machine:

- `IDLE`
- `WAKING_DEVICE`
- `OPENING_HOTSPOT_SETTINGS`
- `DISABLING_AUTO_TURNOFF`
- `ENABLING_HOTSPOT`
- `VERIFYING`
- `COMPLETED`
- `FAILED`

## Execution Flow

### Trigger Conditions

`ProxyService` requests automation only when all of the following are true:

- The persisted auto-hotspot option is enabled.
- The accessibility service is enabled.
- Hotspot is currently off.
- No automation run is already active.

The request is triggered in two cases:

1. Service starts after boot.
2. Periodic runtime check finds that hotspot is off.

### Main Flow

1. Wake the device.
2. Launch `Intent("com.android.settings.WIFI_TETHER_SETTINGS")`.
3. Wait for `Settings$WifiTetherSettingsActivity`.
4. If the main hotspot switch is already on, move to verification.
5. Find the `Turn off hotspot automatically` item on the Samsung hotspot page.
6. Enter that page if needed and switch it off when currently enabled.
7. Navigate back to the hotspot main page.
8. Click `com.android.settings:id/switch_widget` if hotspot is still off.
9. Verify hotspot state through `HotspotManager`.
10. Return to home.

### Lockscreen Handling

Because the current device has no secure lockscreen credential:

- The coordinator may wake the device and immediately launch the hotspot settings activity.
- No credential entry or lockscreen bypass logic is required.
- If the initial app launch lands behind the keyguard layer, the coordinator retries by sending the device to home and relaunching Settings.

If the device later gains a secure lockscreen, this design is no longer sufficient and the implementation must refuse lockscreen automation rather than pretending to support it.

## UI Matching Strategy

### Main Hotspot Page Detection

Primary match:

- package name equals `com.android.settings`
- window/activity corresponds to `Settings$WifiTetherSettingsActivity`
- node with resource id `com.android.settings:id/switch_widget` exists

Fallback match:

- title text contains `Wi‑Fi hotspot`

### Main Hotspot Switch Detection

Primary match:

- resource id `com.android.settings:id/switch_widget`
- associated with the `switch_bar` section

Click condition:

- only click when `checked = false`

### Auto Turn-Off Page Detection

Primary match:

- find list item whose title text is `Turn off hotspot automatically`
- click into that page
- inside that page, locate `android:id/switch_widget`

Click condition:

- only click when `checked = true`

### Safety Rules

- Never click outside package `com.android.settings`.
- Prefer resource IDs over text matches.
- Use text only as a Samsung-specific fallback.
- Abort if the expected page does not appear within timeout rather than blind clicking.

## Failure Handling

Each step is timeout-bounded, expected in the 3 to 5 second range.

If a step fails:

- record the failure reason
- send the user to home
- restart the flow from the beginning

Per request, the coordinator retries at most 2 to 3 times.

If retries are exhausted:

- mark the run as failed
- show a foreground notification indicating that accessibility automation failed
- keep the service alive so a future periodic check can try again

## UI/UX Changes

The main screen must clearly show that this feature depends on accessibility automation.

Required UI changes:

- keep the auto-hotspot toggle
- show whether the accessibility service is enabled
- provide a one-tap entry into accessibility settings
- explain that this automation is currently Samsung-device-specific

If the user enables auto-hotspot while accessibility is disabled, the app should guide the user to enable accessibility instead of pretending the feature is active.

## Manifest and Resource Changes

### Manifest

Add an `AccessibilityService` declaration with:

- `android.permission.BIND_ACCESSIBILITY_SERVICE`
- service metadata pointing to the service config XML
- `exported="false"`

### New Resource XML

Add `res/xml/hotspot_automation_accessibility_service.xml` to define:

- event types needed for window/content changes
- package names targeting `com.android.settings`
- feedback type
- flags needed for retrieving interactive windows and reporting view IDs

## File-Level Plan

### New Files

- `app/src/main/java/com/example/yyproxy/HotspotAutomationAccessibilityService.kt`
- `app/src/main/java/com/example/yyproxy/HotspotAutomationCoordinator.kt`
- `app/src/main/java/com/example/yyproxy/HotspotAutomationState.kt`
- `app/src/main/java/com/example/yyproxy/AccessibilityServiceState.kt` if a separate helper is useful
- `app/src/main/res/xml/hotspot_automation_accessibility_service.xml`

### Modified Files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/yyproxy/MainActivity.kt`
- `app/src/main/java/com/example/yyproxy/ProxyService.kt`
- `app/src/main/java/com/example/yyproxy/HotspotManager.kt`
- `app/src/main/java/com/example/yyproxy/ProxyConfig.kt` only if needed to store extra state

## Testing Strategy

### Unit Tests

Add unit coverage for:

- coordinator trigger rules
- state machine transitions
- retry limits
- refusal to start when accessibility is disabled

### Manual Device Validation

Required real-device checks on the Samsung target:

1. Enable the app auto-hotspot option and accessibility service.
2. Reboot the device and confirm hotspot turns on automatically.
3. Turn hotspot off manually and confirm runtime automation turns it back on.
4. Confirm the automation disables Samsung's auto turn-off option.
5. Confirm the app recovers cleanly if Settings opens slowly or automation fails.

## Risks

- Samsung may update the Settings page structure and break resource-based matching.
- Accessibility services can be disabled by the system or user, which makes the feature unavailable.
- Bringing Settings to the foreground may be visually intrusive.
- Future secure lockscreen configuration would invalidate lockscreen automation assumptions.

## Recommendation

Proceed with the Samsung-specific accessibility-driven settings automation path, because it is the only fully automatic route available under the user's constraints:

- no root
- no system app privileges
- fixed target device
- full automation required
