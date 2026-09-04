# ISDM Companion changelog

## 0.5.6 beta

- Assessments retain their task-list title and deadline when optional LMS detail pages fail;
  other assessments continue refreshing, and successful detail fields remain available.
- Schedule clocks pause in the background and on empty or completed days. Upcoming countdowns
  update near their displayed minute changes; active classes retain second-by-second progress.
- Past dates no longer highlight a completed class as Up next.
- Includes the 0.5.5 attendance-window and slow-location fixes. Attendance safety gates are unchanged.

## 0.5.5 beta

- Mark attendance no longer appears before the LMS attendance window opens.
- Slow location acquisition now uses the time when the fix arrives, so fresh campus evidence is
  not rejected as stale.

## 0.5.4 beta

- Profile now offers Lavender, Ocean, Forest, Amber, and Slate accent colors. The selected accent
  applies across the app and stays on the phone.
- Automatic-attendance settings now open the relevant Android app settings, and bottom navigation
  uses clearer, larger tab targets.

## 0.5.3 beta

- Restores the familiar compact Schedule, Readings, and Profile interface while retaining
  assessment Done/Undo actions and real attendance progress.
- Uses the student's confirmed section when LMS cohort detection is missing or ambiguous, so a
  Section A student does not also receive Section B sessions.

## 0.5.2 beta

- Schedule assessment actions now open the matching item in Readings. Students can mark work
  done, undo it, and keep completed work off the Schedule while preserving the LMS status.
- The full two-week date range stays spatially stable and schedule content moves in the direction
  of the selected date.
- Clearer controls, larger touch targets, and real attendance progress replace ambiguous or
  premature confirmation states.

## 0.5.1 beta

- Schedule, Readings, and Profile received a compact visual refresh with an Auto toggle, week
  strip, expansion controls, and redesigned attendance action.
- Assessment cards retain their due-work details and matching PDF downloads while submission
  actions are temporarily hidden.

## 0.5.0 beta

- Profile attendance now excludes orientation sessions from 27 July through 7 August, accepts
  more LMS report formats, and shows the available Present, Absent, Not marked, Total, and
  percentage values consistently across supported phones.
- Assessment Submit opens the embedded LMS form, and assessment PDFs are matched to the correct
  assessment instead of an unrelated downloadable resource.
- Course cards now expose the LMS course outline.
- The beta feedback sheet remains usable above the keyboard and on short screens.
- Attendance conflicts are verified against the authoritative classroom page so an LMS-confirmed
  mark is recorded as successful.
- The beta dashboard now shows each tester's installed app version and distinguishes cached
  low-memory process exits from critical reliability failures.

## 0.4.3 beta

- Long-running assessment windows no longer appear as Scheduled Sessions or stay highlighted as
  happening now.
- Assessments now use the due date shown in My Activities when the submission page does not
  provide a date.

## 0.4.2 beta

- Assessments now appear at the top of Readings with the due date from the assessment itself,
  its related PDF, and its submission link.
- Course reading cards are sorted by the nearest upcoming session.
- Assessment submission windows no longer appear as Scheduled Sessions.

## 0.4.1 beta

- Attendance now uses completed LMS sessions from the last year. Upcoming sessions no longer
  lower the percentage.
- Profile now has a visual attendance ring, clear present/absent/total counts, and a cleaner
  personal-details editor.

## 0.4.0 beta

- Profile now shows the attendance percentage reported by the LMS.
- Location checks now wait longer for a reliable GPS fix.
- Background attendance and readings recover better from temporary failures.
- The beta dashboard now separates confirmed app results from diagnostic signals.
