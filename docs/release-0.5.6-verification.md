# 0.5.6 beta verification

Verified locally on 2026-09-05, from the isolated `codex/companion-release-0.5.6`
branch based on the 0.5.5 attendance fixes (`05a169d`). The main checkout and
its uncommitted backend/location work were not changed.

## Artifact

- File: `ISDM-Companion-Beta-0.5.6.apk` (13,079,275 bytes).
- Package: `org.isdm.companion`; version: `0.5.6-beta`; version code: `14`.
- Minimum SDK: 26; target SDK: 36.
- SHA-256: `ef6f5f1ab376e4959efaccd84bbb2c570565f8a74d8aa695cd609555ede38f55`.
- Permanent certificate SHA-256: `a9ec6dc0d90624ffb9810f917d146c8b3fcaf3a6b156c737457f0ec5492d912d`.
- Bundled `verify-beta-apk.sh` passed package/version/SDK, certificate,
  16 KB zip alignment, and v2/v3 signature checks. Temporary password copy removed.

## Behavior and build checks

- 49 focused JVM tests passed: `RealLmsAdapterTest`, `ScheduleClockTest`,
  `ScheduleHighlightTest`, `SessionCountdownTest`, `SessionProgressTest`,
  `ReleaseNotesPolicyTest`, `BetaApiClientTest`, and `MonitoringServiceTest`.
- Regression tests first reproduced assessment refresh failure and unnecessary
  schedule ticks. Final tests cover optional 403/503 failures, shared-section
  failure caching, preserved successful detail fields, and propagated HTTP 401.
- Deterministic clock simulation: 61 scheduled updates in an idle future-class
  hour versus the prior 3,600. Active classes retain one-second updates;
  empty/completed days schedule none. This is not a battery-life measurement.
- Background collection uses the existing lifecycle-aware Compose API;
  resume restarts collection with a fresh time. No dependency was added.
- `lintDebug`, `assembleDebug`, `assembleRelease`, and
  `assembleDebugAndroidTest` passed. Lint has 47 warnings, no errors.
- Five instrumented tests passed on disposable Android 16/API 36 emulator
  `isdm_release_056`: `MainActivitySmokeTest` and `CompanionControlsTest`.

## Update and runtime checks

- Bundled `test-beta-update.sh` passed 0.5.4 to 0.5.6 and 0.5.5 to 0.5.6.
  Each used `adb install -r` and retained its pre-update `ceDataInode` (566747).
- Signed release cold launch succeeded; the 0.5.6 release-notes dialog was
  visually checked and dismissed successfully.
- Tests used an emulator without real credentials. Saved-login retention,
  live LMS attendance, location acquisition on a phone, and battery savings
  were not tested in this release pass. No physical phone was modified.
- Attendance gates and backend contracts are unchanged. This release does not
  establish that the paused attendance backend or Sheets migration is ready.
- Local APK only: no push, backend deployment, or tester distribution performed.
