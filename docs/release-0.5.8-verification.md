# 0.5.8 nightly verification — 20 September 2026

The owner requested preparation only, with publication later, then explicitly requested installation and checking over ADB. No GitHub release, tester message, database migration, or Edge Function deployment was performed.

- Package: `org.isdm.companion`; version `0.5.8-nightly`; code `16`; minimum SDK 26; target SDK 36.
- Artifact: `.release-private/releases/ISDM-Companion-Nightly-0.5.8.apk`.
- APK SHA-256: `b9e9dd7f1fe0bf1ae2879a8731c13078a4a6eaaa82afc57a1fdcf43a24fc76f6`.
- Permanent replacement certificate SHA-256: `a35f1ba9a17fccd5a03b58c1a69ad339273e730eb5d73cd9bbb2f5ff8cfcf0b3`.
- APK alignment, package/version/SDK and v2/v3 signatures passed the bundled verifier.
- Emulator update from replacement-signed 0.5.7 passed with unchanged `ceDataInode`. Launch through the exported `.LauncherActivity` alias succeeded. Direct launch of the deliberately non-exported implementation activity was correctly denied.
- 209 JVM tests, 64 backend/tooling tests, 15 dashboard tests, dashboard typecheck/lint/build, Android debug/release builds and lint passed.
- Four Android instrumentation tests passed: nightly consent/no-invite submission, encrypted pending-identity persistence across store recreation, normal/large-text schedule rendering and Day/Week switching, automatic-attendance startup restoration.
- Normal and 200% schedule screenshots and the synthetic nightly enrollment screenshot were inspected. Evidence stays in ignored private storage, as repository policy requires.

## Physical phone

The connected Samsung phone had permanent-replacement-signed 0.5.7. Its actual pulled APK certificate matched the new build before installation. `adb install -r` succeeded, version 16 was verified, and all three installed-profile data inodes were unchanged. No uninstall or data clear was performed on the phone.

A cold launch through the launcher alias returned `Status: ok` (346 ms in this one ADB observation, not a comparative performance benchmark). The release notes showed 0.5.8-nightly. Dismissing them opened the existing signed-in schedule with automatic attendance still enabled; no attendance setting or Mark action was changed. Week switching, next-week navigation, and Readings were checked on the phone. Course cards showed no batch/term prefix. Readings displayed an unresolved LMS warning that assessments may be stale because task cards were not recognized; this check does not establish assessment freshness. New-user live enrollment cannot be claimed until the staged backend is deployed; the existing enrollment remains usable.

## Remaining rollout gate

Apply and verify the staged migration and both Edge Functions following [nightly enrollment](nightly-enrollment.md) before publishing this APK for new users. New code-free registrations do not work against the unchanged live backend. Existing older signing-lineage APKs remain incompatible with a normal update; this build preserves the replacement signing lineage only.
