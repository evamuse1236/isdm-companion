# 0.5.7 beta replacement-signing verification

Originally prepared locally on 11 September 2026 from code commit
`b6fee4efa1196acc4a856c7f9d123aa425700f9a` on
`codex/performance-access-polish`. The original signing identity was lost during
an operating-system reinstall. The replacement release starts a new permanent
signing identity and therefore requires a one-time uninstall of earlier builds.

The installable replacement was packaged on 18 September 2026 from current main
commit `37509ba` plus the release-signing configuration recorded with this release.

## Replacement release

- Package: `org.isdm.companion`; version: `0.5.7-beta`; version code: `15`.
- Minimum SDK: 26; target SDK: 36; release variant is not debuggable.
- Local file: `.release-private/releases/ISDM-Companion-Beta-0.5.7-new-signing.apk`.
- Size: 13,115,876 bytes.
- SHA-256: `8fb6f4f91ec452e11996564902187c05580a3fa9bf9ef14f5e53a3f65d4c14b9`.
- Replacement certificate SHA-256:
  `a35f1ba9a17fccd5a03b58c1a69ad339273e730eb5d73cd9bbb2f5ff8cfcf0b3`.
- Package/version/SDK, 16 KB alignment, RSA-4096 certificate identity, and v2/v3
  signature verification passed with the bundled release verifier.
- On a disposable Android 16 emulator, installing this APK over signed 0.5.5 was
  rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, as expected. Uninstalling
  0.5.5 and clean-installing 0.5.7 succeeded; version code 15 launched and remained
  running.

## Verified behavior and builds

- The Android JVM suite passed all 204 tests with no failures, errors, or skips.
- Debug/release builds and lint passed: no errors, 49 warnings and one hint.
- All 18 Android 16 instrumentation tests passed on the updated 0.5.7 debug build.
  The permission dialog also passed a separate test at 130% Android system text
  size. Normal and enlarged renders were inspected.
- Real Android notification and background-location grants, denial, return without
  granting, final enable, and stopping after notification removal were checked on
  the emulator. See [permission setup verification](emulator-2026-09-11-permission-setup.md).
- The release APK contains the new permission helper and setup dialog. These
  emulator checks use the debug variant, rather than the unsigned release file.
- Rolling seven-day logs, redaction, background logging and a real Android file export
  were verified. See [local logging verification](local-debug-logs-2026-09-11.md).
- Earlier deployment verification passed 25 root tests and 15 dashboard tests.
- Detailed performance, login recovery, parser, access-control, and production
  deployment evidence is in [performance-access-review.md](performance-access-review.md).

## Signing reset and installation gate

The previous distributed 0.5.5 APK was reverified locally:

- Version code: 13; SHA-256:
  `afcd3dfe0f5d1e94ba21edbb5e0dcae1b2394f6b2d5f5b38a99920dfa5a53005`.
- Permanent certificate SHA-256:
  `a9ec6dc0d90624ffb9810f917d146c8b3fcaf3a6b156c737457f0ec5492d912d`.
- Package/version/SDK, alignment, and v2/v3 signature checks passed.
- Baseline file: `/home/darax/Downloads/ISDM-Companion-Beta-0.5.5.apk`.

The corresponding permanent private key was not found because this Linux filesystem
was created after the last signed release. The replacement key cannot update the
old signing lineage. The expected rejection and clean-install transition were
verified on a disposable emulator. Future releases must preserve and verify the
replacement certificate. No physical phone was modified. Tester distribution still
requires an explicitly approved recipient batch.
