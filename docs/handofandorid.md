# ISDM Companion Android handoff

Date: 2026-08-09

## Objective

Continue the native Android app inside `C:\Repos\isdm\isdm-companion\android` and evolve it from
an attendance-focused Today screen into a simple daily companion for the ISDM LMS.

The next two product features are:

1. A schedule/calendar experience showing upcoming lectures, timings, room, floor, and the next
   lecture clearly.
2. A readings experience aggregating uploaded course readings, with a one-tap local Done action.

## Non-negotiable product principle

**Intuitiveness and simplicity must sit at the core of every UI and workflow.**

The intended feeling is:

> Open the app and immediately know where to go or what to do.

Simplicity here means fewer decisions, not merely fewer visual elements:

- One dominant piece of information per screen.
- One obvious primary action at a time.
- Essential information visible at a glance; secondary details appear on tap.
- Plain language instead of LMS terminology.
- Colour reinforces state but never carries meaning alone.
- Sensible defaults and almost no setup.
- Do not reproduce every LMS menu as an app tab.
- Keep cached information visible during errors, with a quiet last-updated indicator.
- Notifications should be rare and actionable.

## Recommended information architecture

Use two primary destinations, not three:

### Schedule

Schedule is the home screen. Today is its default state, not a separate tab.

- A prominent Next lecture card at the top.
- Course, start time/countdown, room, floor, and trainer.
- Show only the action that is currently useful: Join, Mark attendance, or no action.
- Remaining lectures appear chronologically underneath.
- Past lectures visually recede.
- Day/week navigation is available without dominating the default screen.

### Readings

- Not-done readings appear first.
- Default rows show title and course; extra metadata is progressively disclosed.
- One-tap checkmark marks a reading Done.
- Done items grey out and move below a divider or collapsed Done section.
- Undo remains available.
- Search and course filters stay unobtrusive until needed.
- LMS progress and the user's Done choice are distinct concepts.

Avoid standalone tabs for Attendance, Webinars, Tasks, Gradebooks, Library, Reviews, or Settings.
Attendance and Join belong inside Schedule; settings can live in an overflow/profile destination.

## Repository state

- Repository: `C:\Repos\isdm\isdm-companion`
- Android project: `C:\Repos\isdm\isdm-companion\android`
- Current branch: `agent/add-android-app`
- Current commit: `34eeb81` (`Add native Android companion app`)
- Remote branch: `origin/agent/add-android-app`
- Draft PR: https://github.com/evamuse1236/isdm-companion/pull/1
- Research brief: `docs/lms-feature-exploration.md`
- `docs/` is currently untracked. Do not assume this handoff or the research brief is already in
  commit `34eeb81` or PR #1.

The Android source, tests, Gradle wrapper, README integration, and ignore rules are already in
commit `34eeb81`. Generated build output, local SDK configuration, and credentials remain ignored.

## Current Android behavior

The app is native Kotlin/Jetpack Compose for Android 8.0/API 26 and newer. It currently provides:

- Encrypted on-device LMS credentials.
- Today's personalised schedule.
- Room, floor, trainer, and attendance state.
- Manual attendance marking.
- Foreground class-day monitoring.
- Notify-only mode and separately armed auto-marking.
- Automatic monitoring stop at 18:00/day rollover.

There is no tab navigation yet. The current UI switches between Login and `TodayContent`, and the
engine state contains one Today-shaped session list.

Start with these files:

- `android/app/src/main/java/org/isdm/companion/ui/MainActivity.kt`
- `android/app/src/main/java/org/isdm/companion/ui/CompanionViewModel.kt`
- `android/app/src/main/java/org/isdm/companion/engine/CompanionEngine.kt`
- `android/app/src/main/java/org/isdm/companion/engine/Ports.kt`
- `android/app/src/main/java/org/isdm/companion/data/RealLmsAdapter.kt`
- `android/app/src/main/java/org/isdm/companion/domain/Models.kt`
- `android/app/src/main/java/org/isdm/companion/domain/Schedule.kt`
- `android/app/src/main/java/org/isdm/companion/domain/Rooms.kt`

The desktop implementation is useful reference code:

- `src/lms.js`
- `src/service.js`
- `src/schedule.js`
- `src/rooms.js`

## Verified LMS data contracts

### Existing attendance/schedule contract

```text
GET  /user/login
GET  /calendar/json?start=YYYY-MM-DD&end=YYYY-MM-DD
GET  /classroom/{nid}/view
GET  /manage/classroom/attendance
POST /api/mark/classroomsession/attendance
```

Calendar events expose `nid`, `title`, `url`, `start`, `end`, `className`, `batch`, `trainers`, and
`subject`. A live 45-day query on 2026-08-09 returned 186 raw events which the existing merge and
cohort-filter pipeline reduced to 91 personal schedule rows.

Important rules:

- A class can appear twice: a batch event and a personalised attendance event. Reuse the existing
  merge logic; do not show raw calendar events directly.
- Section/group filtering must remain intact.
- Times are LMS wall-clock IST and Android should interpret them in `Asia/Kolkata`.
- Room comes from classroom detail, not the raw calendar event.
- Floor is a local mapping, currently including Sahyog -> Floor 3 and Majlis -> Floor 6.
- Unknown rooms must not receive a guessed floor.
- Group/schedule-only events may have no attendance record, room, or Mark action.

### Verified course/readings hierarchy

Authenticated read-only exploration confirmed:

```text
GET /show/all/courses
GET /course/details?cat_id={courseId}
GET /course/details?cat_id={courseId}&course_id={sectionId}
GET /subtopic/view?sid={sectionId}&vid={itemId}&cid={collectionId}&cat_id={courseId}
```

Section labels vary: `Course Readings`, `Workshop Readings`, `Mandatory Reading`, and generic
`Resources` all occur. The item URL provides stable numeric `cat_id`, `sid`, `vid`, and `cid`
identifiers. Prefer `vid` as the item key while retaining the complete tuple.

Current Term 1 course listing pages exposed at least 28 reading items:

- State, Market and Society: 16
- Perspectives on Society and Development: 1
- Data Analysis for Development: 6
- Participatory Research workshop: 2
- CBCL: 2
- WID: 1

Listing pages also expose LMS progress through HTML classes resembling:

- `document_status_icon`
- `document_viewed_status_icon`
- `flat_list_video_status_inprogress`
- `flat_list_video_status_completed`

The global `/reviewer/library` page returned no items despite populated course reading sections.
Readings must therefore be aggregated from active course pages.

## Calendar implementation target

### Minimal state

Add range-oriented calendar state containing:

- Loaded start/end dates.
- Day buckets.
- Selected day.
- Loading/error/last-sync state.
- One computed next future lecture.

Keep the existing Today/live attendance refresh path separate so calendar caching cannot delay
attendance markability.

### First-version behavior

- Default to today with the Next lecture card.
- Support a week or rolling 7-day view; avoid a dense month grid initially.
- Reuse `LmsGateway.calendar`, cohort detection, session merging, classroom detail pooling, and the
  floor lookup.
- Highlight exactly one next lecture: the first session whose start is after now.
- Treat a currently running lecture separately from the next future lecture.
- Preserve `/join/webinar?...` when merging raw events so eligible rows can expose Join.
- Show a calm unknown-location state when room/floor is unavailable.
- Do not duplicate attendance controls elsewhere in the app.

### Calendar acceptance criteria

- Multi-day range uses the requested inclusive/exclusive dates.
- Duplicate batch/attendance events merge into one lecture.
- Section/group filtering remains correct.
- Next lecture works before, during, and after sessions and across day boundaries.
- Known rooms show floors; unknown rooms do not guess.
- Schedule-only events never show a Mark action.
- Today refresh still revalidates live marking independently of the calendar range.

## Readings implementation target

### Read-only LMS model

Add a small materials seam to `LmsGateway` and `RealLmsAdapter`; do not create a second login or
cookie implementation.

A practical `ReadingItem` needs:

- `vid`, `sid`, `cid`, and `catId`
- Title
- Course name
- Section label
- Authenticated source URL
- Optional LMS progress
- Last-sync timestamp

Treat direct file URL, file type, size, author, upload date, and due date as optional until each is
proven across real material variants.

### Local Done state

Introduce a dedicated small local progress store keyed primarily by `vid`.

- Done is a user-owned local state.
- LMS Viewed/In progress/Completed is secondary metadata.
- Never interpret LMS Viewed as automatically Done.
- Done survives app restart and material refresh.
- Undo restores the item to the not-done group.
- A reading sync failure must never block Schedule or attendance refresh.

Do not store reading progress inside `SecureCredentialStore`; it currently has a deliberately narrow
credential responsibility.

### Critical safety boundary

No verified safe LMS write endpoint for reading completion was found. Do not invent or probe one
during implementation.

Opening `/subtopic/view` may itself update LMS progress even though it is a GET. Background sync must
stay on course and section listing pages and must not open every reading item. Capture sanitized
fixtures from listing pages and develop parsers against those fixtures.

### Readings acceptance criteria

- Active course reading sections aggregate into one list without duplicates.
- Stable IDs survive refresh and ordering changes.
- Not-done items sort before Done items.
- Mark Done, move to bottom, grey state, persistence, and Undo all work offline.
- LMS progress displays without overwriting local Done state.
- Empty, cached, syncing, stale, and failure states are visually simple and explicit.
- Sync never opens individual reading pages or calls an LMS write endpoint.

## Additional features worth retaining

After Calendar and Readings:

1. Join lecture from the schedule card.
2. Opt-in upcoming-lecture reminder.
3. Session detail sheet.
4. Weekly attendance recap and missed-session list.
5. Cross-course material search.
6. Later integration with `C:\Repos\isdm\isdm-transcriber` for recordings, transcripts, and notes.

Do not prioritize dedicated LMS tabs for the following yet; the current account returned empty
states:

- My Tasks
- Gradebooks
- Global Library
- Reviews
- Interaction Log

Leave Management contained data but also showed anomalous future dates on some rows. Keep
`/calendar/json` as the schedule source of truth.

## Recommended execution order

1. Add sanitized calendar-range and course/reading-listing fixtures.
2. Write parser/domain tests before touching UI.
3. Add range state and the two-destination navigation shell.
4. Implement Schedule and Next lecture while preserving live attendance behavior.
5. Add read-only course/readings gateway methods.
6. Add the dedicated local Done store.
7. Implement Readings with pending-first sorting, Done collapse, and Undo.
8. Run unit tests, lint, assemble the debug APK, and perform a real-device visual pass.

## Validation commands

From `C:\Repos\isdm\isdm-companion\android`:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

The connected test requires an authorised USB-debugging device. The debug APK is produced at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

The last full local validation completed with exit code 0. Gradle emitted a transient Kotlin daemon
incremental-cache error and fell back successfully, plus deprecation warnings around AndroidX
encrypted preferences.

## Known exploration limitation

The user signed into the in-app browser, but that browser tab never surfaced to the automation
session. Live exploration was completed through the app's authenticated client using GET requests.
No attendance, assignment, profile, leave, review, or reading-completion POST was sent.

Two individual reading pages were opened before it became clear that a page view may affect LMS
progress. They may have been recorded as viewed or in progress. Do not repeat broad item-level
exploration; use listing pages and sanitized fixtures.

## Definition of done for the next implementation phase

The next phase is complete when a first-time user can:

1. Open the app and immediately understand their next lecture.
2. See time, room, floor, and the one relevant action without opening details.
3. Move naturally through the week without seeing duplicate or wrong-cohort lectures.
4. Open Readings and immediately distinguish what remains from what they marked Done.
5. Mark or unmark a reading with one tap and retain that choice after restart.
6. Use both screens during weak connectivity without losing the last useful information.

Every added control should earn its place against the core question: does it make the next action
more obvious and less effortful?
