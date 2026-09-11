# Local debugging logs — 11 September 2026

Implemented in Android source commit `b6fee4efa1196acc4a856c7f9d123aa425700f9a`. Included in the
prepared 0.5.7 candidate; permanent signing and distribution are still pending.

## Behavior

- Private log files retain the last seven days, bounded to 4 MiB. The oldest
  complete segments are recycled first when the size cap is reached.
- Writes run through a bounded background queue. Overflow is recorded as a
  dropped-event count; the app does not block waiting for space in the queue.
  The existing beta event upload queue also writes off the caller thread.
- Records cover app/version information, permission decisions, command durations,
  attendance and location-gate outcomes, safe LMS endpoint/status labels, sync,
  alarms, services, downloads, and process exits. An uncaught exception adds its
  type and bounded source frames before delegating to Android's existing handler.
- Local logging permits known operational fields only. It omits exception
  messages, credentials, emails, exact coordinates, session IDs, and LMS bodies.
  URLs and common secret patterns are redacted from permitted string values.
- **Save debug logs** is available on Profile, sign-in, and the issue-report sheet.
  A system file picker saves a text copy to a destination the learner chooses.
  There is no new automatic upload or automatic issue attachment.

## Retention and limits

Cleanup runs at app start, approximately hourly during logging, on export, and
through an offline daily WorkManager job. Android can delay periodic work, and
force-stopping prevents background cleanup until the app runs again. Export
always filters records to the exact seven-day window. The size cap can shorten
history under heavy logging. A saved external copy is outside app cleanup; the UI
explains that the owner must delete it.

The old size-only files are discarded on upgrade: their format may contain raw
exception messages and does not satisfy this retention policy. Abrupt process
termination can lose buffered events. Native crashes and ANRs are represented by
Android's historical exit reason when available; a detailed stack is not guaranteed.

The existing beta telemetry payloads and collection pause are unchanged. This
retention policy applies to the new local diagnostic files, not to server data or
the separate pre-existing pending telemetry queue.

## Verification

| Check | Result |
| --- | --- |
| JVM suite | 204 tests passed; zero failures/errors/skips |
| Retention regression | Exact seven-day cutoff, including lines on the boundary day |
| Storage regression | UTF-8 byte cap, newest whole records, concurrent writes, restart after idle, clock correction, legacy cleanup |
| Privacy regression | Sensitive fields/messages excluded; safe error type and source frame retained |
| Android 16 / API 36 | 18 instrumentation tests passed |
| Main-thread logging | StrictMode recorded zero disk-read/write violations through `app.diagnostics.log` |
| Crash handler | Sanitized frame persisted and previous Android handler delegated to |
| Export contract | Save-document intent, cancellation, and chosen-URI callback verified |
| Real system picker | Saved a 923-byte text file from the actual sign-in screen; startup, app version, and export event verified |
| Debug and release | Build/lint passed; zero lint errors, 49 existing warnings, one hint |
| Read-only collector | New segmented-log tar archive captured and read successfully |

Testing used the dedicated `isdm-polish-test` emulator and no real learner login.
The real saved file and UI capture are in
`.release-private/weekly-logs-2026-09-11/`, alongside final test output and the
collector output. No physical phone or live tester state was changed.

The final unsigned APK is recorded in [candidate verification](release-0.5.7-verification.md).
It cannot be installed as a compatible update until the original signing key is available.

## Android platform references

[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
supports a user-selected save location without a storage permission.
[WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
is scheduled by Android and is not an exact cleanup timer.
