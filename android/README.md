# ISDM Companion for Android

This is a native Kotlin/Jetpack Compose client for Android 8.0 (API 26) and newer. It is a
separate Gradle project so the dependency-free Windows app at the repository root keeps working
unchanged.

## Current features

- Encrypted LMS credentials stored locally with a key protected by Android Keystore.
- Today's personalised schedule, room, floor, trainer, and attendance state.
- Manual attendance marking, revalidated immediately before the request and confirmed by
  reading the classroom detail again.
- User-started class-day monitoring every 30 seconds through a visible foreground service.
- Notify-only mode by default, with an actionable `Mark present` notification.
- Separately armed auto-mark mode, limited to three attempts per session.
- Automatic stop at 18:00, at IST day rollover, or when Android ends the foreground-service
  quota. Monitoring never starts at boot and is not restored after process death.

Automated tests use fixtures and MockWebServer. They never contact the real LMS.

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

## Android background limit

Android 15 and newer limits `dataSync` foreground services to six hours of background runtime
in a 24-hour period. The app handles the timeout, stops monitoring, and asks you to reopen it.
Opening the app and starting monitoring is always an explicit daily action.
