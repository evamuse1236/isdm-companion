# 0.5.7 beta candidate verification

Prepared locally on 11 September 2026 from code commit
`b6fee4efa1196acc4a856c7f9d123aa425700f9a` on
`codex/performance-access-polish`. Permanent signing is pending. This is not an
installable beta release and has not been distributed.

## Candidate

- Package: `org.isdm.companion`; version: `0.5.7-beta`; version code: `15`.
- Minimum SDK: 26; target SDK: 36; release variant is not debuggable.
- Local file: `.release-private/candidates/0.5.7/ISDM-Companion-Beta-0.5.7-UNSIGNED.apk`.
- Size: 13,103,588 bytes.
- Unsigned SHA-256: `664e3c431605da4b8a5158dbebe44f0e64a1454068273f3035f6fc6c185a1906`.
- Package/version/SDK and 16 KB alignment checks passed. `apksigner` confirms
  there is no valid signature. Signing will produce a different artifact hash.
- Machine-readable evidence is beside the candidate in `candidate.json`.

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

## Permanent signing and update gate

The previous distributed 0.5.5 APK was reverified locally:

- Version code: 13; SHA-256:
  `afcd3dfe0f5d1e94ba21edbb5e0dcae1b2394f6b2d5f5b38a99920dfa5a53005`.
- Permanent certificate SHA-256:
  `a9ec6dc0d90624ffb9810f917d146c8b3fcaf3a6b156c737457f0ec5492d912d`.
- Package/version/SDK, alignment, and v2/v3 signature checks passed.
- Baseline file: `/home/darax/Downloads/ISDM-Companion-Beta-0.5.5.apk`.

The corresponding permanent private key was not found in the local project,
signing configuration, or available home/mount filename inventory. The debug key
cannot produce a compatible update. The user has been asked for the original
keystore or its backup location; no signing passwords were requested in chat.

Once the original key is available, sign the candidate with that identity, run
`.agents/skills/isdm-beta-release/scripts/verify-beta-apk.sh` for version 0.5.7-beta,
code 15 and the certificate above, then run `test-beta-update.sh` from the verified
previous APK on a disposable emulator. Require `adb install -r` to retain the
existing `ceDataInode`. Verify release launch and the release-notes dialog.

Until those checks pass, do not offer this candidate as an installer. No physical
phone was modified. Tester distribution requires an explicitly approved recipient
batch; none has been prepared or sent for this candidate.
