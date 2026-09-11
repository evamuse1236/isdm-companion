# Automatic attendance permission setup — emulator verification

Verified on 11 September 2026, 03:48–04:20 UTC / 09:18–09:50 IST, using
`isdm-polish-test` (`emulator-5554`), Android 16/API 36, and 0.5.7-beta/code 15.
The emulator used synthetic fixtures without learner credentials. No physical
phone was connected or changed.

## Problem and correction

The old setup policy allowed automatic attendance with notifications denied.
Its notification callback enabled attendance regardless of the result. The
settings button opened generic Android app details, and returning without
background location ended the pending flow instead of keeping the missing step
available. The Profile switch was also disabled while permissions were missing.

Setup now shows four checks in the existing theme: precise location,
notifications, location all the time, and precise alarms. Each missing permission
has an action. Notifications are requested before sending the learner to
background-location settings. Returning without granting access leaves that step
open. A repeated denial can be repaired through app settings. `Not now` leaves
manual attendance and other features available.

The notification result cannot enable attendance. All permissions are rechecked
before the final enable action, on resume, at process startup, when scheduling,
at alarm delivery, and before automatic monitoring ticks. The existing LMS
Section, campus-location, LMS marking-window, and remote-access checks remain.

Android 11 and later require the user to select background location in Settings;
the initial location prompt does not offer “Allow all the time.” The new dialog
explains the Settings path before opening it. See the official
[background-location flow](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
and [notification permission flow](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Evidence

| Check | Observed result |
| --- | --- |
| Regression before the fix | The notifications-denied policy test failed; the settings-entry emulator test left Compose for generic app settings. |
| Foreground location | Android displayed Precise / Approximate and While using the app. Granting precise foreground access advanced to notifications. |
| Notification denial | Android displayed its notification prompt; choosing Don't allow kept the setting off and the notification step visible. |
| Notification grant | Retrying and allowing notifications advanced to the background-location step. |
| Return without background access | The dialog stayed at 3 of 4 ready, with Location all the time still required. |
| Background grant | Permissions → Location → Allow all the time produced 4 of 4 ready. The final enable action then persisted `enabled=true`. |
| Later notification removal | Turning off the real Android notification toggle terminated the process with `PERMISSION CHANGE`, not an app exception. Relaunch succeeded and persisted `enabled=false`. |
| JVM suite | 197 tests passed, no failures/errors/skips. |
| Emulator suite | 15 tests passed, including the guided settings entry and permission dialog. |
| Real system text size | The permission-dialog test passed at Android font scale 1.3; the test asserts the actual configured scale. Both normal and enlarged renders were inspected. |
| Debug and release builds/lint | Passed; zero lint errors, 49 warnings and one hint. |

The temporary interactive probe was removed. One exploratory probe reached its
time limit; a later probe was terminated by Android's permission-change restart.
Those harness exits are not counted as passing instrumentation tests. The
grant/denial observations above use the actual system UI, persisted app setting,
and Android process-exit record. The final retained emulator suite passed.

Evidence is under `.release-private/emulator-0.5.7/`, including the final build
and test logs, `permission-change-exit.txt`, `auto-after-notifications-disabled.xml`,
the notification prompt and setup screenshots, and
`final-visual-review/permissions-100.png` / `permissions-130.png`.

This verifies the debug app and permission flow on Android 16. It does not prove
live LMS attendance or replace the permanent-signature/update-in-place release
gate. The updated unsigned candidate is recorded in
[release-0.5.7-verification.md](release-0.5.7-verification.md).
