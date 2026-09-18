# ISDM Companion for Android

This is the production ISDM Companion client for Android 8.0 (API 26) and newer. It is a native
Kotlin/Jetpack Compose app with one shipped interface: Schedule, Readings, and Profile.

Production UI changes belong in `app/src/main/java/org/isdm/companion/ui/`. Temporary screenshots,
HTML prototypes, and competing visual directions should stay outside the repository or on a
short-lived branch and be removed once a direction is accepted.

## Current features

- Encrypted LMS credentials stored locally with a key protected by Android Keystore.
- A Profile screen with the completed-session attendance percentage and counts from the LMS
  historical report for the last year. Upcoming sessions are excluded.
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

Release signing is loaded locally from
`.release-private/signing/signing.properties`. The keystore and credentials stay outside Git.
Back up that directory outside this computer before distributing a release; losing it requires every
tester to uninstall the app and lose local app data before the next signing identity can be installed.

For tester delivery, prefer Google Play Internal testing (or Closed testing when the group grows).
Each tester joins once through the Play opt-in link; later signed App Bundles are delivered through
Google Play, and devices with Play auto-update enabled update without another APK message. Android
does not let a normal sideloaded app silently replace itself. A custom downloader can only open the
system installer, where the user still confirms the update.

## Private diagnostic log

The app keeps a rolling seven-day diagnostic history in private app storage, capped at
4 MiB. Oldest segments are recycled sooner if the cap is reached. Routine writes use a
bounded background queue; overload is recorded as a dropped-event count instead of
blocking the UI or attendance. Logs are mirrored to the `ISDMCompanion` Logcat tag.

The log contains UTC timestamps, process-run IDs and sequence numbers, app/Android versions,
permission decisions, command timings and counts, safe LMS endpoint/status labels,
attendance-gate outcomes, sync decisions, and alarm/service transitions. Failures retain
exception types and bounded source frames, without exception messages, raw LMS responses,
credentials, email addresses, exact coordinates, or session identifiers. Android's previous
process-exit reason is recorded when available. The uncaught-exception handler records a
sanitized fatal event and then delegates to Android's existing crash handler.

Choose **Save debug logs** in Profile, on the sign-in screen, or in the issue-report sheet.
Android asks where to save a text file; this needs no storage permission and works in release
builds. The file is only saved when a destination is chosen. It is not uploaded or attached
to an issue automatically. Exported copies are outside the app's automatic retention and
must be deleted by their owner.

Cleanup runs on app startup, roughly hourly while writing, before every export, and in an
offline daily WorkManager job. Android can defer background work or prevent it after a
force-stop; the next app start cleans up, and exports always filter to the seven-day window.
The 4 MiB cap can shorten history under high volume. Buffered events can be lost if Android
kills the process abruptly; an OS/native crash or ANR is represented by the process-exit
reason when Android exposes it, not by a guaranteed captured stack trace.

On upgrade, the old `companion.log` and `companion.log.1` files are discarded because their
format has no enforced retention and may include exception messages. New files are named
`YYYY-MM-DD-NNNNNN.log` under `files/diagnostics/`. The read-only diagnostics collector also
captures these files as a private tar archive on debuggable builds.

The local log is independent of beta telemetry. This change does not alter the server's
existing data-collection pause or add an automatic upload route.

## Android background limit

The attendance monitor is a `location` foreground service because its continuing task is the
Attendance Location Gate. It deliberately does not declare `dataSync`: Android 15 and newer limit
that foreground-service type to six hours of background runtime in a 24-hour period, which can
prevent a later attendance window from starting. Network requests remain scoped to short windows
around known Attendance Sessions. The service still handles an unexpected platform timeout
defensively and records it in the diagnostic log.
