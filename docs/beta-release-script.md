# Send an Android beta safely

`scripts/send-beta-release.mjs` repeats the tester-distribution workflow without
putting WhatsApp identifiers or admin credentials in Git. It requires Node
22.13 or newer and the linked Whatsmeow plugin.

The command has two phases. `prepare` is read-only. It reconciles claimed
Android testers, excludes the owner, unclaimed slots, iPhones, and testers
already reporting the target version, verifies every direct-chat JID against
WhatsApp, searches for a possible duplicate APK filename, and creates an exact
preview. `send` accepts only that preview's SHA-256 approval digest. It rechecks
the APK and chats, sends one document at a time, waits for a terminal WhatsApp
result, and stops before the next tester if delivery is failed or uncertain.

## One-time private recipient map

Copy `scripts/beta-recipients.example.json` to
`.release-private/recipients.json`, add each claimed Android tester's exact
direct-chat JID returned by WhatsApp, and restrict it:

```bash
mkdir -p -m 700 .release-private
cp scripts/beta-recipients.example.json .release-private/recipients.json
chmod 600 .release-private/recipients.json
```

`.release-private/` and `.release-batches/` are ignored by Git. Never infer a
JID from a phone number, and never put a group JID in this file.

## Prepare the exact release

First verify the permanent-signed APK and its update-in-place lineage using the
ISDM beta-release checks. Put the short, user-friendly release notes in a text
file with one bullet per change.

For a live roster, keep the beta admin secret outside the command line:

```bash
export BETA_ADMIN_SECRET='...'
npm run beta:release -- prepare \
  --apk /absolute/path/ISDM-Companion-Beta-0.5.0.apk \
  --version 0.5.0-beta \
  --recipients .release-private/recipients.json \
  --changes /absolute/path/changes.txt \
  --output .release-batches/0.5.0
```

If Codex has already exported a current Supabase dashboard response to a
mode-600 JSON file, use `--roster-json /absolute/path/roster.json` instead of
`BETA_ADMIN_SECRET`. `--offline` is only for tests: it skips live WhatsApp
checks and must never be used for a real send.

Read `.release-batches/0.5.0/preview.md`. Show its exact recipients, APK path,
SHA-256, and full captions in the current Codex conversation. Sending is not
authorized until the user approves that exact batch. The preview expires after
30 minutes so the roster and WhatsApp reconciliation remain current.

## Send the approved batch

After approval, copy the digest printed by `prepare`:

```bash
npm run beta:release -- send \
  --batch .release-batches/0.5.0 \
  --approve THE_64_CHARACTER_APPROVAL_DIGEST
```

Progress and WhatsApp message IDs are saved in the private `batch.json`, so a
successful recipient is not sent twice when the command is resumed. If the
command reports an uncertain delivery, inspect that direct chat and obtain new
approval before retrying. A successful WhatsApp upload means delivered to
WhatsApp; it does not mean the tester installed the update. Refresh the live
beta roster afterward and report installation separately.

## Replacement signing and the current WhatsApp plugin

For the explicitly authorized one-time signing reset, pass
`--installation-instructions PATH` with the reinstall instructions. These replace
both the normal keep-data paragraph and the final Android Update instruction;
the complete resulting caption remains covered by the approval digest.
Use `--hold-testers T-12` to record an explicit hold for an unresolved recipient.
Suspended testers are excluded automatically.

`prepare --whatsapp-plugin PATH` uses the current WhatsApp plugin's `fast.mjs`
for connected status, exact recipient resolution, and cached duplicate checks.
Partial history is recorded as incomplete, not evidence that no prior send exists.
This is a live preparation, not `--offline`.

The legacy `send` command refuses current-plugin batches. After exact preview
approval, use the current WhatsApp skill's persistent request-ID file-send
workflow: recheck the fresh roster, APK hash, recipient and caption; persist one
request ID per recipient before sending; poll that same ID after uncertainty.
Do not treat submission as confirmed device delivery or installation. Refresh
previews older than 30 minutes. No message is authorized merely by preparation.
