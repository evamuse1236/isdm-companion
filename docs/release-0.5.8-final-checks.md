# 0.5.8 nightly final checks — 26 September 2026

The owner authorized nightly activation and distribution to all identified beta testers, including later additions. Nightly must not request beta codes. T-12 is held at the owner's explicit request because its recipient remains unidentified.

## Artifact and local checks

- Application source: `f414975`; package `org.isdm.companion`, version `0.5.8-nightly`, code 16, min SDK 26, target SDK 36.
- APK: `.release-private/releases/ISDM-Companion-Nightly-0.5.8.apk`.
- SHA-256: `b9e9dd7f1fe0bf1ae2879a8731c13078a4a6eaaa82afc57a1fdcf43a24fc76f6`.
- Permanent replacement certificate SHA-256: `a35f1ba9a17fccd5a03b58c1a69ad339273e730eb5d73cd9bbb2f5ff8cfcf0b3`.
- APK identity, SDK, alignment and v2/v3 signatures passed. The artifact contains the nightly enrollment and weekly schedule implementation.
- 209 Android JVM tests, debug build and lint passed. 65 backend/tooling tests passed, including a regression test removing obsolete beta-invite instructions from nightly release captions.
- Dashboard typecheck, build, and 15 tests passed; lint had no errors and one existing image optimization warning.
- The migration passed isolated PGlite checks for service-role permissions, retry identity, credential conflict, suspension/restoration, revision checks, individual automatic-attendance controls, retention and quota.
- A fresh emulator test installed replacement-signed 0.5.7, updated with `adb install -r`, and retained `ceDataInode=376983`. Version 16 and a successful launcher cold start were verified. No physical phone was connected for this check.

## Live backend verification

Applied `nightly_enrollment`; deployed `beta-admin` v5 and `beta-api` v9 with their shared imports and existing custom authentication. Prior deployed sources were saved privately before deployment.

A disposable nightly registration, created without an invite code, returned 201. An identical retry reused its identity; a different credential returned 409. Individual automatic-attendance stop returned 423 / `tester_remote_stop`, while configuration still permitted Companion access. Restoration returned 200 / allowed. Full suspension denied automatic attendance and re-enrollment; restoration allowed the same registration again. Profile-name deletion succeeded. The test registration and its test audit entries were then removed. An unauthenticated request to the live owner control returned 401.

No real tester's access or automatic-attendance state was changed. Security advisors reported only the expected informational notices for service-only tables with RLS and no client policies.

## Review conclusions and limits

- Standards/security review: owner authentication, private credentials, atomic audited state changes and revision checks remain intact. No release-blocking finding remains in the reviewed enrollment/control path.
- Requested behavior: code-free nightly registration is live; owner controls accept both legacy `T-` and nightly `N-` registrations. Android monitoring checks remote automatic-attendance permission before an automatic tick and fails closed when the check fails.
- Controls identify an installation, not a verified person. A fresh reinstall creates a new nightly registration and can evade a block on an old registration. This release does not claim account-wide blocking.
- Live control verification exercised the production RPC and app-facing endpoint. The owner dashboard's authentication and proxy were tested locally; its signed-in browser UI was not re-tested because no owner browser session was available.
- Older signing-key installations require a one-time reinstall with loss of local login/progress/settings/cache; replacement-signed 0.5.7 can update in place. Existing server records remain, and fresh reinstalls receive new nightly records without beta codes.
- The previously recorded assessment-freshness warning was not re-tested against the LMS in this release review.

## Distribution

The reconciled roster has 14 identified external recipients, owner T-01, and held T-12. Private previews contain exact recipients, captions, signing-cohort instructions and APK digest. WhatsApp contact resolution was checked live; cached history is partial, so duplicate searches are not exhaustive. Delivery and installation outcomes are recorded separately in the private release batches.

All 14 APK sends were accepted by WhatsApp, each with one saved request ID and a distinct message ID. The first receipt reconciliation confirmed device delivery for 12 recipients (including one read receipt); two remained awaiting device acknowledgement. No failed or uncertain submission remained. The refreshed Supabase roster contained the same 16 beta records and did not yet confirm a recipient reporting version code 16 or a new nightly registration. Delivery does not establish installation; legacy beta metadata can remain stale while telemetry collection is paused.

Evidence directory: `.release-private/verification/nightly-final-20260926/`.
