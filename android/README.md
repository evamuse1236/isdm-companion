# ISDM Companion for Android

This is a native Kotlin/Jetpack Compose client for Android 8.0 (API 26) and newer. It is a
separate Gradle project so the dependency-free Windows app at the repository root keeps working
unchanged.

## Current features

- Encrypted LMS credentials stored locally with a key protected by Android Keystore.
- An account-scoped cached rolling 14-day personalised schedule with room, floor, trainer, and
  attendance state.
- Read-only aggregation of course reading/resource listing pages, including stable LMS item IDs,
  mandatory-section classification, explicit session-number mapping, and LMS
  viewed/in-progress/completed signals. Ambiguous readings remain in General readings.
- Readings are grouped by the nearest relevant scheduled course session, then later numbered
  sessions, then general material. A reading opens in an authenticated in-app LMS browser only
  after an explicit tap.
- A separate local Done/Undo state for readings that survives restarts and never writes completion
  back to the LMS.
- Safe six-hour periodic schedule/readings refresh while connected. Automated reading sync never
  opens individual material pages because the LMS may count a page view as progress.
- Manual attendance marking, revalidated immediately before the request and confirmed by
  reading the classroom detail again.
- Auto attendance is enabled by default and stored on the device. It schedules a precise,
  user-visible monitoring window for each upcoming attendance session, including the next class
  day when it is switched on after 18:00.
- Each class window starts ten minutes before the session, checks every 30 seconds, is limited to
  three mark attempts, and stops 15 minutes after the session ends or at 18:00 IST.
- Exact alarms restore a scheduled class window after process death without keeping an all-day
  service alive. Turning the switch off cancels every pending window.
- A Material-style adaptive launcher icon with round-mask and themed-icon support.

Automated tests use sanitized HTML strings, fixtures, and MockWebServer. They never contact the
real LMS.

## Build and test on Windows

Set `JAVA_HOME` to JDK 17 or newer and `ANDROID_HOME` to the Android SDK, then run:

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on an authorised USB-debugging device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Private diagnostic log

The app writes a small rotating diagnostic log to its private app storage and mirrors the same
events to Logcat under `ISDMCompanion`. It records lifecycle, command outcomes, sync decisions,
alarm/service transitions, downloads, and sanitized failures. Emails, passwords, cookies,
authorization values, signed URLs, and LMS payloads are not recorded.

With a debug build installed, retrieve both retained files with:

```powershell
adb shell run-as org.isdm.companion cat files/diagnostics/companion.log
adb shell run-as org.isdm.companion cat files/diagnostics/companion.log.1
```

The current file rotates at 512 KiB and only one previous file is retained.

## Android background limit

Android 15 and newer limits `dataSync` foreground services to six hours of background runtime
in a 24-hour period. The app avoids an all-day service by running short windows around each known
attendance session. It still handles the platform timeout defensively and records it in the
diagnostic log.
