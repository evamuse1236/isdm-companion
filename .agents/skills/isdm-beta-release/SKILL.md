---
name: isdm-beta-release
description: Release and distribute ISDM Companion Android betas. Use when the user asks to build, sign, verify, install, update, ship, send, or resend a beta APK; manage beta testers or invites; reconcile the Supabase roster; or confirm WhatsApp delivery and installation.
---

# ISDM Beta Release

Run the release from the repository root. Preserve active work, tester app data,
the permanent signing lineage, private identifiers, and outward-message approval.

## Choose the branch

- For a code release, complete **Build and verify**, then **Distribute**.
- For an already verified APK, start at **Distribute**.
- For a new tester, use **Invite** before distribution.

## Build and verify

1. Inspect Git status, release notes, Android version name/code, Edge Function
   contracts, migrations, and dashboard changes. Preserve unrelated work.
2. Run the root, dashboard, and Android checks relevant to the change. Resolve
   Java 17 and the Android SDK with
   `../isdm-incident-response/scripts/verify-android.sh`.
3. Build with the permanent beta signing identity. Keep keystore secrets out of
   repository files and command output.
4. Run `scripts/verify-beta-apk.sh --help`, then verify package, version,
   versionCode, SDK range, alignment, v2/v3 signatures, permanent certificate,
   and APK SHA-256.
5. Run `scripts/test-beta-update.sh --help`, then prove `adb install -r` from the
   previous permanent-signed APK on an emulator. Require an unchanged
   `ceDataInode`. Use update install on physical tester phones.
6. Commit the intentional release changes. A commit does not authorize a push.

Completion criterion: the final artifact has verified identity and signing,
passes update-in-place with retained data, and matches committed release notes.

## Distribute

1. Read `scripts/send-beta-release.mjs --help` and
   `docs/beta-release-script.md`. Use the repository script as the distribution
   source of truth.
2. Query the live Supabase roster immediately before preparation. Use
   `BETA_ADMIN_SECRET` privately, or create a mode-600 roster snapshot from the
   Supabase connector. Keep `.release-private/recipients.json` private.
3. Write short user-facing change bullets, then run
   `npm run beta:release -- prepare ...`. Use live WhatsApp checks for real
   distribution.
4. Read the generated `preview.md`. Show the user every recipient, exact direct
   JID, APK path, SHA-256, and full personalized caption. State exclusions and
   incomplete WhatsApp history. Wait for approval of that exact batch.
5. After approval, run `npm run beta:release -- send --batch ... --approve ...`
   with the preview digest. Let the script send sequentially and poll each
   action. If it stops on uncertainty, inspect that direct chat and prepare a
   fresh batch before any retry.
6. Re-query Supabase after delivery. Report WhatsApp success, failure or
   uncertainty separately from the app version each tester reports.

Completion criterion: every eligible claimed Android tester has one terminal
delivery result, exclusions are explicit, and installation state is reported
from the refreshed roster rather than inferred from delivery.

## Invite

1. Query the live roster and allocate only an unclaimed compatible slot.
2. Persist only the invite hash. Keep plaintext invite material in a temporary
   mode-700 directory with mode-600 files.
3. Resolve the exact direct chat with WhatsApp. Show the recipient, invite, and
   complete message; obtain approval; send once; poll to a terminal result.
4. Re-query the roster to distinguish delivery from claim. Remove temporary
   plaintext after confirmed delivery.

## Hard boundaries

- Use direct JIDs returned by WhatsApp; accept neither inferred JIDs nor groups.
- Treat an empty WhatsApp search as inconclusive when history is incomplete.
- Preserve the permanent signing identity and tester data across updates.
- Keep service-role credentials server-side and invitation plaintext temporary.
- Treat live LMS, device, delivery, and installation checks as separate evidence.

## Bundled verification scripts

- `scripts/verify-beta-apk.sh`: verify APK identity and signing.
- `scripts/test-beta-update.sh`: prove a data-preserving emulator update.
