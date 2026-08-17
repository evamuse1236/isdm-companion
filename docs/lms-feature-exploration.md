# ISDM Companion LMS feature exploration

Date: 2026-08-09
Scope: local source, captured fixtures, and authenticated read-only GET requests to the live LMS.
No attendance, assignment, profile, leave, review, or reading-completion POST was sent. The in-app
browser connection did not surface to the automation session, so visual layout and JavaScript-only
interactions remain unverified. Two individual reading pages were opened before it became clear
that the LMS may treat a page view as progress; subsequent exploration stayed on listing pages.

## Executive read

The proposed calendar tab is a good next feature and is mostly supported by the existing contract:

- `/calendar/json?start=&end=` already accepts a date range.
- The Android gateway already exposes that range-shaped call.
- The existing merge, cohort filtering, classroom-detail lookup, room-to-floor mapping, and
  session-state logic can be reused.

The main Android work is state/UI: the current engine only loads one day, and the current screen
only renders Today. A calendar view should load a week (or a rolling 7–14 days), retain room/floor
details where the LMS exposes them, and compute a single next-lecture highlight in IST.

The readings tab is also implementable now. Live exploration verified the hierarchy
`/show/all/courses` -> `/course/details?cat_id=...` ->
`/course/details?cat_id=...&course_id=...` ->
`/subtopic/view?sid=...&vid=...&cid=...&cat_id=...`. Current Term 1 course pages expose at least 28
items under `Course Readings` or `Workshop Readings`, and their listing HTML exposes unseen,
viewed, in-progress, and completed status classes. The app should still keep the user's explicit
Done/Not done choice locally: no safe first-party write contract for reading completion was
identified, and opening a reading may itself affect LMS progress.

## What the Android app does today (verified)

The Android app is a native Kotlin/Compose project for API 26+, with encrypted on-device LMS
credentials, today's personalised schedule, room/floor/trainer data, manual attendance marking, and
class-day monitoring. This is stated in `android/README.md:3-17`.

The UI has no navigation tabs yet. After login, `MainActivity.kt:139-183` chooses one of Login or
`TodayContent`; `TodayContent` renders the monitor card and today's `state.sessions` list in
`MainActivity.kt:247-298`. Each session card currently shows name, status, start/end, room/floor,
trainer, and the mark button in `MainActivity.kt:352-400`.

The engine state is explicitly today-shaped: `CompanionState` has `today` and one `sessions` list,
with no calendar range, selected date, reading list, or reading-progress field
(`android/app/src/main/java/org/isdm/companion/engine/Ports.kt:122-131`). The only refresh command
is `Command.RefreshToday` (`Ports.kt:166-179`).

The existing Android credential store persists only `email` and `password` in encrypted shared
preferences (`android/app/src/main/java/org/isdm/companion/platform/SecureCredentialStore.kt:7-43`).
It is not a general local-progress store. `CompanionApplication.kt:9-24` creates the credential store,
engine, and notifier; there is no reading repository or progress database.

## Verified LMS contract already used by the app

### Login and session handling

The desktop client documents and implements the authenticated Drupal flow: GET `/user/login`, read
the form fields, POST the form, retain cookies, and retry once after a logged-out response
(`src/lms.js:3-18`, `src/lms.js:114-187`). The Android adapter keeps this behind `LmsGateway` and
implements the same dynamic-form, redirect, cookie, and re-auth behavior
(`android/app/src/main/java/org/isdm/companion/data/RealLmsAdapter.kt:29-64`,
`RealLmsAdapter.kt:176-275`). New read-only LMS calls should go through this seam rather than
creating a second login implementation.

### Calendar events

The proven calendar route is:

```text
GET /calendar/json?start=YYYY-MM-DD&end=YYYY-MM-DD
```

The desktop client parses an array response (`src/lms.js:189-199`). Android already exposes both a
typed date-range method and a wire-date overload, and parses `nid`, `title`, `url`, `start`, `end`,
`subject`, and `trainers` (`android/app/src/main/java/org/isdm/companion/data/RealLmsAdapter.kt:69-99`).
The corresponding domain model contains exactly those fields
(`android/app/src/main/java/org/isdm/companion/domain/Models.kt:6-22`).

The captured fixture shows the important wire shape: batch-wide webinar events have URLs such as
`/join/webinar?preview=true&nid=...&redirect=true`, while personalised attendance events have
`/classroom/{nid}/view`; both carry wall-clock IST `start` and `end` values, with optional
`className`, `trainers`, `subject`, and `batch` (`tests/fixtures.js:9-37`). The fixture does not
contain a room, floor, reading, attachment, or due-date field.

Every class may arrive twice. `src/schedule.js:1-7` describes the batch-wide plus personalised
attendance pair. `buildSessions` merges by normalised title/cohort/session/start, keeps the
attendance `nid`, retains subject/trainer, filters batch-wide events using the inferred section and
group, and sorts by start (`src/schedule.js:49-148`). Android mirrors the same behavior in
`android/app/src/main/java/org/isdm/companion/domain/Schedule.kt:92-222`.

The cohort inference is based on personalised attendance events, while combined `Section A & B`
events are not treated as evidence for a single section (`src/schedule.js:56-73`). A calendar range
must run this same filtering; simply displaying raw events would show another section's lectures.

Calendar timestamps are wall-clock IST. The desktop parser constructs local dates on an IST
machine (`src/schedule.js:11-17`); Android makes the zone explicit as `Asia/Kolkata`
(`android/app/src/main/java/org/isdm/companion/domain/Schedule.kt:11-16`). Calendar highlighting must
use this zone, not the phone's arbitrary device zone.

### Classroom detail, room, and floor

For attendance rows, the proven detail route is:

```text
GET /classroom/{nid}/view
```

The detail parser reads `Title`, `Location`/`Venue`, `Trainers`/`Trainer`, `Courses`/`Course`,
`Attendance Marked (For me)`, `Status`, and `Comment` (`src/lms.js:201-223`). Android exposes the
same fields through `ClassroomDetail` (`android/app/src/main/java/org/isdm/companion/engine/Ports.kt:57-66`)
and parses the same table labels (`android/app/src/main/java/org/isdm/companion/data/RealLmsAdapter.kt:101-114`).

The floor is not a field returned by the calendar fixture or the detail parser. It is a local room
lookup: Sahyog defaults to floor 3 and Majlis to floor 6, with `.env` overrides on desktop
(`src/rooms.js:1-36`) and the same defaults injected into the Android engine
(`android/app/src/main/java/org/isdm/companion/engine/CompanionEngine.kt:31-37`). Unknown rooms
correctly produce no floor label. The calendar UI therefore needs an explicit unknown-floor state;
it should not guess.

### Attendance-only routes

The other current LMS route is:

```text
GET  /manage/classroom/attendance
POST /api/mark/classroomsession/attendance
```

The first is parsed for the LMS-owned `mark-attend` versus `mark-attend-disabled` class, and the
second sends `{nid, uid, status:"present"}` (`src/lms.js:225-261`). These are unrelated to reading
completion and should not be repurposed for it.

## Feature 1: calendar tab

### Feasibility: high, with a moderate Android state/UI change

The desktop service already proves the desired range aggregation: `Service.week` fetches a
Monday–Sunday range, assembles sessions, and returns seven day buckets
(`src/service.js:85-163`). The Android gateway already accepts arbitrary `LocalDate` ranges
(`android/app/src/main/java/org/isdm/companion/engine/Ports.kt:31-44`), but the engine currently
uses `today`/`tomorrow` only and caches one day (`CompanionEngine.kt:99-213`,
`CompanionEngine.kt:479-483`).

### Recommended first version

1. Add a calendar state with a requested range (start/end), day buckets, loading/error status, and a
   selected date. Keep Today as the fast/live view used by attendance monitoring.
2. Fetch the next 7 days (or the current Monday–Sunday week) through the existing
   `LmsGateway.calendar` method. Apply `detectCohorts` and `buildSessions`; do not duplicate the
   filtering logic.
3. For sessions with an attendance `nid`, reuse the existing classroom-detail pool and floor lookup
   so the row can show `Majlis · Floor 6`, etc. The current detail loader already limits parallel
   requests to five and caches marked/static details (`CompanionEngine.kt:225-257`).
4. Sort within each day by start, show date/day headers, and retain the current time format. For
   batch-only events without an attendance row/detail, show the time and `Floor unavailable` (or
   omit the floor) rather than inventing a location.
5. Add a single prominent `Next lecture` treatment. The simplest definition is the first session
   whose start is after `now` in the loaded range. If a lecture is currently in progress, keep the
   existing Today hero semantics (`open`, then current, then upcoming) and label the next future
   row separately. If there are no future sessions in the range, say `No more lectures in this
   range` and offer the next-range navigation.
6. Preserve a join action where the raw event URL is a `/join/webinar...` URL. The Android model
   currently parses `CalendarEvent.url`, but `buildSessions` does not carry it into `Session`; add
   an optional join URL only after verifying the LMS redirect behavior in a read-only test.

### Important calendar edge cases

- A raw batch event may have no room. Room comes from `/classroom/{nid}/view`, so a future row
  without a personalised attendance entry cannot reliably show a floor.
- Shared `Section A & B` lectures should remain visible; another section's single-section lecture
  should not.
- Group-split events can have no attendance rows at all. The fixture deliberately includes three
  `SPO Visit` group rows, only one of which belongs to Group 3
  (`tests/fixtures.js:28-31`). They need to remain schedule-only rows with no mark action.
- A live Today request needs short cache TTLs for marking; a future calendar range can use a longer
  TTL. The desktop service already distinguishes live versus non-live calls
  (`src/service.js:85-124`).

### Calendar tests to add before implementation is considered done

- A range query is made with the requested inclusive/exclusive dates.
- Duplicate batch/attendance events produce one session.
- Section/group filtering still works over a multi-day range.
- A next-lecture selector handles before, during, and after a lecture, including a day boundary.
- Known rooms produce floor labels; unknown rooms do not produce a guessed floor.
- A schedule-only group event has no attendance action.
- Refreshing Today still revalidates marking independently of the calendar tab.

## Feature 2: readings tab

### Verified live LMS hierarchy

The existing Companion clients do not yet implement materials, but authenticated LMS listing pages
verified a stable, parseable hierarchy on 2026-08-09:

```text
GET /show/all/courses
GET /course/details?cat_id={courseId}
GET /course/details?cat_id={courseId}&course_id={sectionId}
GET /subtopic/view?sid={sectionId}&vid={itemId}&cid={collectionId}&cat_id={courseId}
```

The section labels vary by course: `Course Readings`, `Workshop Readings`, `Mandatory Reading`, and
generic `Resources` all occur. Reading rows provide a human title and the numeric `cat_id`, `sid`,
`vid`, and `cid` values in the item URL. `vid` is the strongest observed item identity, with the full
ID tuple available as a defensive composite. These claims come from the authenticated course and
section listing routes above; they are not inferred from the existing attendance client.

Current Term 1 listing pages contained:

| Course area | Reading items |
| --- | ---: |
| State, Market and Society | 16 |
| Perspectives on Society and Development | 1 |
| Data Analysis for Development | 6 |
| Participatory Research workshop | 2 |
| CBCL | 2 |
| WID | 1 |
| **Observed total** | **28** |

The same listing HTML exposes progress through classes including `document_status_icon`,
`document_viewed_status_icon`, `flat_list_video_status_inprogress`, and
`flat_list_video_status_completed`. This gives the app a read-only LMS progress signal without
opening every document. It should be represented separately from the user's explicit local Done
choice because the exact LMS semantics differ by material type.

The global `/reviewer/library` page returned no items even though course pages contained readings.
The implementation therefore needs to aggregate each active course's reading/resource sections;
the Library menu is not a usable source of truth. Individual items open through `/subtopic/view` and
may expose a generic `/Download` action, but the final file URL and authentication behavior remain
unverified.

### Recommended first version

- Extend `LmsGateway` with read-only course, material-section, and material-item listing calls, using
  the existing authenticated request seam in `RealLmsAdapter` rather than a second login stack.
- Add `ReadingItem` with `vid`, `sid`, `cid`, `catId`, title, course, section label, source URL, LMS
  progress, and sync timestamp. Treat file type, size, due date, author, and direct download URL as
  optional because listing pages did not prove them consistently.
- Keep the user's Done/Not done state in a dedicated local store keyed primarily by `vid`; do not
  piggyback on the encrypted credential store (`SecureCredentialStore.kt:7-43`).
- Render not-done items first, grouped or filterable by course. Put a divider before Done items,
  grey those cards out, retain an Undo action, and preserve their relative ordering.
- Show the LMS signal as secondary metadata such as `Unopened`, `Viewed`, `In progress`, or
  `Completed in LMS`; do not silently equate `Viewed` with the user's `Done`.
- Open the authenticated `/subtopic/view?...` URL first. Add direct downloads/offline caching only
  after the file redirect and expiry behavior is verified.
- Sync course/readings metadata independently of Today's attendance refresh, cache the last good
  result, and show `Last synced` plus explicit empty/error states.

### Reading risks/open questions

- Opening an individual item may update LMS progress even though it is a GET. Exploration stopped
  opening new items after the progress classes became apparent. Automated sync must stay on course
  and section listings.
- Some one-item reading sections did not expose the same document status class as bulk document
  sections, so the parser needs fixture coverage for document, embedded, and external material
  variants.
- No safe endpoint for writing reading completion was identified. Local Done state will not sync
  across devices unless a separate backend or verified LMS completion API is deliberately added.
- Direct files may be session-bound or routed through a viewer. Do not assume that a static-looking
  link can be downloaded without the authenticated session.

## Other live LMS surfaces checked

The live calendar contract is already rich enough for the proposed tab. A 45-day query on
2026-08-09 returned 186 raw events which the existing merge/filter pipeline reduced to 91 personal
schedule rows. Raw events carried `nid`, `title`, `url`, `start`, `end`, `className`, `batch`,
`trainers`, and `subject`; room and floor still required classroom detail plus the local floor map.
This was verified against authenticated `/calendar/json?start=...&end=...` responses.

`/webinar/list` presented the upcoming lecture schedule with course and start time, while the raw
calendar events already carry `/join/webinar?...` URLs. A Join action belongs naturally on the
calendar/session card; the separate webinar page does not justify its own app tab.

Several LMS menu areas are poor near-term app targets in the current account state:

- `/my-activities` exposed course/status filters but returned `No Activity found`.
- `/user/all/gradebooks` returned `No Gradebooks to display`.
- `/reviewer/library` returned no items despite populated per-course reading sections.
- `/user/activity/reviews` returned `No records found`.
- `/user/interaction/view` returned `No data to display`.

These routes can remain discovery leads, but building dedicated tabs now would produce empty UI.
`/manage/classroomleave` did contain a searchable session table and course/group/trainer/date
filters. It is a plausible later attendance/absence companion, but observed anomalous future dates
on some rows mean it should not replace `/calendar/json` as the schedule source.

## Additional high-value surfaces found in the existing code

These are suggestions grounded in existing fields/routes, not claims that they should all be built
now.

### 1. Join/open lecture action (high value, low-to-medium effort)

The calendar fixture includes `/join/webinar?preview=true&nid=...&redirect=true` URLs
(`tests/fixtures.js:10-15`, `tests/fixtures.js:21-31`), and Android already parses `CalendarEvent.url`
(`Models.kt:13-22`). The merged `Session` currently drops that URL, so a `Join` action needs one
small model change and an authenticated-browser/deep-link decision. This would make the calendar
more useful than a passive timetable.

### 2. Attendance recap and missed-class list (high value, no new LMS route)

The domain already computes `MARKED`, `OPEN`, `UPCOMING`, `MISSED`, `DONE`, and `NO_ATTENDANCE`
(`android/app/src/main/java/org/isdm/companion/domain/Schedule.kt:224-233`), while the desktop week
service already returns a seven-day structure (`src/service.js:153-163`). A calendar/week summary
could show marked, missed, and schedule-only counts and link to the relevant session detail.

The authenticated `/manage/classroom/attendance` page is a current/upcoming attendance list, not
a reliable historical denominator. Overall attendance comes from the LMS My Progress report:
`/api/executereport?report=student-dashboard-user-classroom-session-summary&start_time=...&end_time=...`.
It returns Total, Not Marked, Present, and Absent for the selected period. Companion requests the
last year, validates that the counts add up, and calculates the displayed percentage as Present ÷
Total. Upcoming sessions are excluded.

### 3. Session detail sheet (medium value, low effort)

The adapter already obtains course, trainer, room, mark status, status, and comment
(`android/app/src/main/java/org/isdm/companion/engine/Ports.kt:57-66`), but the Android card only
surfaces name, time, room/floor, trainer, and marking (`MainActivity.kt:371-398`). A tap-through sheet
could show the complete verified detail and provide the join link when available.

### 4. Recordings/transcripts connection (high value, separate integration)

The adjacent local project `C:\Repos\isdm\isdm-transcriber` proves that the same LMS has additional
course/video surfaces, but these are not part of the Companion contract. It discovers course
catalogue pages via `/show/all/courses` and `/course/details?cat_id=...`, then video/subtopic links
and playback through `/load/video?...` (`C:\Repos\isdm\isdm-transcriber\src\worker.js:176-201`,
`worker.js:254-301`). It parses video item metadata including title, course/topic, type, and
download path (`C:\Repos\isdm\isdm-transcriber\src\logic.js:93-133`) and matches lecture metadata
back to calendar events by title and subject (`logic.js:141-155`).

That suggests a future `Recordings`/`Notes` surface, especially because local transcripts already
exist in the separate project. It needs an explicit integration boundary (shared local API, exported
files, or a server-side service); the Android app cannot assume that Windows-only project is
available on the phone. The transcriber routes are evidence of a discovery lead, not proof that they
contain readings or that they are safe to call from the app.

### 5. Upcoming-lecture reminders (medium value, opt-in)

The current Android notifier only handles an attendance window opening, mark results, and monitoring
stop (`android/app/src/main/java/org/isdm/companion/platform/AndroidNotifier.kt:14-33`). Once the
calendar range and next-lecture selector exist, an opt-in reminder 15–30 minutes before the next
lecture could reuse the same notification channel. It should be deduplicated per session/date and
must not be coupled to auto-marking.

## Suggested priority order

1. Calendar tab with next-lecture highlight, floor/timing display, and optional join action.
2. Add captured fixtures for course/reading listings and implement the read-only readings gateway.
3. Readings tab with local done state, pending-first sorting, greyed completed items, LMS progress,
   and undo.
4. Join action, session detail sheet, and attendance recap/missed-class summary.
5. Recording/transcript integration after deciding how the separate transcriber project will be
   reachable from Android.
6. Optional upcoming-lecture notifications.

## Bottom line

Calendar is an extension of an already proven data path, and live LMS evidence now makes Readings a
realistic second tab rather than a speculative feature. The best product shape is a focused daily
companion: Calendar/Next up, Readings, attendance status, session details, and Join. The many empty
LMS menu areas should not be copied into the app merely because they exist. Before implementation,
capture sanitized course/section HTML fixtures so the new parsers can be built and tested without
repeatedly touching the live LMS.
