# Performance, access control, and theme polish

11 September 2026. Implemented on `codex/performance-access-polish`.
The changes include `codex/companion-release-0.5.6` (`cb7b48e`), preserving its
Google theme, attendance safeguards, partial assessment recovery, and lifecycle clock.
Android remains the learner product; the existing private beta dashboard is the
owner control surface. These changes are prepared locally, not deployed or distributed.

## Login and responsiveness

- HTTP response bodies were consumed on the caller after a coroutine resumed.
  A delayed response could therefore block the UI. OkHttp now consumes and closes
  the response on its callback thread, and cancellation cancels the call.
- Authentication uses a typed failure. A timeout mentioning `/user/login` is a
  network failure, rather than a request to enter the email again. Transient
  refresh failures retry after one and three seconds using the secure saved login.
- The account cache appears before a live refresh finishes. Actual rejected
  credentials still show the login form with the saved email filled in. Offline
  failures retain that login and show stale-data feedback.
- Concurrent expired responses share one session recovery. A revision check stops
  a late response from clearing a newly authenticated cookie.
- The course/readings/faculty scan no longer owns the command mutex throughout its
  network work. Done/Undo and attendance can proceed during that scan. Account
  replacement cancels and joins the scan; result commits check the account
  generation and apply local Done changes at commit time.
- `LmsContentLoader` runs three courses concurrently. Readings, faculty, and
  assessment resources share successful pages and recoverable failures within a refresh, with four content
  requests at most. Assessment details use three concurrent tasks and can appear
  before the course scan completes. Attendance and markability are never served
  from this content cache.

The engine retains serialized attendance decisions and live schedule work. This
is a focused separation of long content work from commands, not a rewrite of the
attendance state machine. A large or slow live schedule/attendance request can
still delay another engine command; this remains a measured follow-up candidate.

## Hermes contracts adopted

The live task **Recreate Hermes LMS extractor** and
`EvaMS/automation/hermes/lms_evidence.py` established these source contracts:

1. Fetch active (`status=0`) and upcoming (`status=3`) task pages and deduplicate
   by LMS task identity.
2. Preserve disabled upcoming cards even when the LMS exposes no activity URL.
   Such cards get a deterministic namespaced identity, never an invented link.
3. Resolve the outer subtopic page through its `/load/video` AJAX fragment when
   necessary, then accept only a same-origin `/activity/user/attempt` frame.
4. Separate the assignment's **Due Date** from its **End Date**. Unverified task
   card dates are labelled “LMS lists”; they do not trigger an “Overdue” label.
   Per-task detail failures leave the task visible with a clear notice.

The other task also recovered WhatsApp corrections. They are not hard-coded into
this APK: the shared correction feed is not published, and messages need source,
time, cohort scope, and supersession evidence before replacing a learner's LMS
facts. This implementation adopts its verified extractor improvements.

## Owner access control

Open a tester in the existing dashboard, enter an audit reason and that tester's
code, then choose **Pause access** or **Restore access**. State, reason, timestamp,
and audit entry are committed in one database transaction. A stale dashboard
revision returns a conflict rather than overwriting a more recent decision.

- The suspension belongs to the tester, so reclaiming an invite cannot erase it.
  Enrollment compares the previous installation and the current suspension flag
  in the same update predicate.
- Beta API authentication reads the tester flag for every authenticated request.
  Suspended installations may check for restoration and delete their support name;
  other beta routes, including automatic-attendance preflight, reject access.
- The dashboard accepts only the verified Sites owner. The server retains the
  admin secret, requires JSON for mutations, and rejects cross-origin requests.
  Authenticated headers are trusted only behind the Sites ingress that supplies
  them; do not expose the worker directly on an untrusted origin.
- Android checks while foregrounded and while its reader is open, about once a
  minute plus network time. A remembered suspension survives restarts and network
  failure. Restoration requires a successful server check.
- Cached viewing has a server-issued lease bounded to six hours. Each manual or
  automatic attendance decision requires a fresh successful online access check
  before location and LMS validation. A connection failure cannot authorize a mark.
- Suspension stops automatic attendance and alarms. Restoration does not silently
  re-enable that preference. An upgraded app reports its current version during
  access checks so old device telemetry cannot strand it on “update required”.

This controls Companion and its beta API, not the learner's independent LMS
account. Existing APKs can be denied beta requests and automatic preflight, but
must be updated to enforce the new manual-attendance and screen restrictions.
An action already authorized and in flight cannot be recalled.

## Visual contract and review

The existing Google-inspired palette, typography, card geometry, bottom navigation,
accent picker, and layout from the 0.5.6 release branch are preserved. Imagegen was used against the actual rendered schedule
as a conservative polish reference. Its incidental gradients were rejected;
no generated bitmap, texture, or palette change is shipped.

- Destination transitions settle in 180 ms.
- Schedule filtering and reading/faculty grouping are remembered.
- Only a highlighted schedule row observes the ticking clock; static rows and
  the surrounding list no longer rebuild every second. The release branch's
  lifecycle-aware clock is retained: active classes tick each second, future
  classes near minute changes, and empty or completed days stop ticking.
- Old cached timestamps say “Saved” with the date. Network failures surface a
  stale-data message without discarding the saved LMS login.

The actual Compose screen was rendered with synthetic test data at normal and
130% text size on an isolated Android 16 emulator. The new tester panel was also
checked in a local browser: the wrong tester code kept the action disabled, and
an exact match enabled it. No tester's access was changed during these checks.

## Validation and rollout

Validation completed successfully:

| Check | Result |
| --- | --- |
| Android JVM suite | 194 tests passed |
| Android 16 instrumentation | 13 tests passed |
| Root Edge Function and tooling suite | 21 tests passed |
| Dashboard suite and production build | 15 tests passed; build passed |
| Dashboard TypeScript / ESLint | Passed; one image-optimization warning remains |
| Android lint / debug APK | Passed; 48 advisory warnings remain |
| Local database transaction verification | All assertions passed |

In the delayed-HTTP-body fixture, a heartbeat scheduled after 100 ms ran at
119 ms while the response body was delayed for 700 ms. This verifies that waiting
for that body does not block the caller; it is not a physical-phone speed benchmark.
The shared course-page fixture verifies one request for readings plus faculty,
then a new request after invalidation.

The local database check can be repeated without connecting to production:

```bash
npm install --prefix /tmp/isdm-db-check --no-audit --no-fund @electric-sql/pglite
PGLITE_TEST_MODULE=/tmp/isdm-db-check/node_modules/@electric-sql/pglite/dist/index.js \
  node scripts/check-beta-access-migration.mjs
```

It verifies migration execution, service-role-only access, per-tester isolation,
revision conflicts, idempotence, restoration, and rollback when the audit insert
fails. PGlite uses a single connection; live multi-session lock contention has
not been measured. Root tooling requires Node.js 22.13 or newer.

Release order:

1. Apply `20260910192122_tester_access_control.sql` to the existing Supabase project.
2. Deploy `beta-api` and `beta-admin` with their current custom authentication.
3. Publish the updated private dashboard without broadening its audience.
4. Prepare a higher-version beta with release notes, permanent signing identity,
   signature verification and the repository's data-preserving update test.
5. Distribute only after the exact recipient/artifact preview is approved.

The new app fails closed when the backend does not provide an access lease, so
steps 1–2 must precede installing it on a real tester's device. The debug build is
for local verification and is not a signed beta update. No physical-device speed,
live learner login, production suspension, or tester delivery is claimed.

Implementation references: [Compose performance](https://developer.android.com/develop/ui/compose/performance/phases),
[Supabase Edge Functions](https://supabase.com/docs/guides/functions), and
[PGlite verification runtime](https://pglite.dev/docs/).
