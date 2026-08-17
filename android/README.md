# ISDM Companion for Android

This is a native Kotlin/Jetpack Compose client for Android 8.0 (API 26) and newer. It is a
separate Gradle project so the dependency-free Windows app at the repository root keeps working
unchanged.

## Current features

- Encrypted LMS credentials stored locally with a key protected by Android Keystore.
- A Profile screen with the total attendance percentage, counts, and scope reported directly by
  the LMS attendance page.
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
- Simple in-app release notes shown once after an installed app is updated, with a permanent
  “What’s new” entry in Profile.

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

## Beta updates

Every beta release must have a higher `versionCode`, updated release notes, a changelog entry,
and a Git release commit. Preserve the existing signing identity so Android can update the app in
place without deleting its data.

For tester delivery, prefer Google Play Internal testing (or Closed testing when the group grows).
Each tester joins once through the Play opt-in link; later signed App Bundles are delivered through
Google Play, and devices with Play auto-update enabled update without another APK message. Android
does not let a normal sideloaded app silently replace itself. A custom downloader can only open the
system installer, where the user still confirms the update.

## Private diagnostic log

The app writes a small rotating diagnostic log to its private app storage and mirrors the same
events to Logcat under `ISDMCompanion`. Every line includes a short process-run ID, a monotonic
sequence number, and the emitting thread. It records the app and Android build at startup;
permission decisions; command duration and privacy-safe attendance-state counts; sync decisions;
alarm/service transitions; downloads; and sanitized outer/root failures. Emails, passwords,
cookies, authorization values, OAuth secrets, signed URLs, session identifiers, and LMS payloads
are not recorded in command summaries.

With a debug build installed, retrieve both retained files with:

```powershell
adb shell run-as org.isdm.companion cat files/diagnostics/companion.log
adb shell run-as org.isdm.companion cat files/diagnostics/companion.log.1
```

The current file rotates at 512 KiB and only one previous file is retained.

## Android background limit

The attendance monitor is a `location` foreground service because its continuing task is the
Attendance Location Gate. It deliberately does not declare `dataSync`: Android 15 and newer limit
that foreground-service type to six hours of background runtime in a 24-hour period, which can
prevent a later attendance window from starting. Network requests remain scoped to short windows
around known Attendance Sessions. The service still handles an unexpected platform timeout
defensively and records it in the diagnostic log.
