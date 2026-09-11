# 0.5.7 beta candidate verification

Prepared locally on 11 September 2026 from code commit
`02cf954e4c82336bb96106094c1538f11cb26616` on
`codex/performance-access-polish`. Permanent signing is pending. This is not an
installable beta release and has not been distributed.

## Candidate

- Package: `org.isdm.companion`; version: `0.5.7-beta`; version code: `15`.
- Minimum SDK: 26; target SDK: 36; release variant is not debuggable.
- Local file: `.release-private/candidates/0.5.7/ISDM-Companion-Beta-0.5.7-UNSIGNED.apk`.
- Size: 13,087,204 bytes.
- Unsigned SHA-256: `f9f978c4a1494598ad76b4978d471eb4758f1978367239d42a8c0ba3fd960cc1`.
- Package/version/SDK and 16 KB alignment checks passed. `apksigner` confirms
  there is no valid signature. Signing will produce a different artifact hash.
- Machine-readable evidence is beside the candidate in `candidate.json`.

## Verified behavior and builds

- The Android JVM suite passed all 194 tests with no failures, errors, or skips.
- Release build and `lintRelease` passed: no errors, 48 warnings and one hint.
- The final package was rebuilt after shortening the release-note text to five
  items; that final edit only changes displayed text.
- Earlier verification of the implementation passed 13 Android 16 instrumentation
  tests, 25 root tests, and 15 dashboard tests. Theme renders were checked at normal
  and 130% text size. These emulator checks used the debug build before the version
  bump, rather than this unsigned release candidate.
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
