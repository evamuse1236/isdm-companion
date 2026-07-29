// Real responses captured from lms.isdm.org.in, trimmed to the interesting cases.
// Structure and ids are as captured; trainer names and the user id are replaced with
// placeholders so this file carries no real personal data.

// A calendar slice covering the three situations that matter:
//  - Mon 27 Jul: classes that exist for both sections (Section A rows must be dropped)
//  - Wed 29 Jul: a normal Section B day
//  - Fri 31 Jul: group-split visits with no attendance rows at all (only Group 3 is mine)
export const CALENDAR = [
  // --- Monday: session events (batch-wide) ---
  { nid: '1285195', title: 'Digital Engagement - Section A - Session 1', url: '/join/webinar?preview=true&nid=1285195&redirect=true', start: '2026-07-27 14:00:00', end: '2026-07-27 15:30:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285210', title: 'Know the Campus - Section B - Session 1', url: '/join/webinar?preview=true&nid=1285210&redirect=true', start: '2026-07-27 14:00:00', end: '2026-07-27 15:30:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285196', title: 'Digital Engagement - Section B - Session 1', url: '/join/webinar?preview=true&nid=1285196&redirect=true', start: '2026-07-27 15:45:00', end: '2026-07-27 17:15:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285208', title: 'Know the Campus - Section A - Session 1', url: '/join/webinar?preview=true&nid=1285208&redirect=true', start: '2026-07-27 15:45:00', end: '2026-07-27 17:15:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285212', title: 'Fun Activity - Section A & B - Session 1', url: '/join/webinar?preview=true&nid=1285212&redirect=true', start: '2026-07-27 17:15:00', end: '2026-07-27 18:15:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  // --- Monday: matching attendance events (personalised to me) ---
  { nid: '1285317', title: 'Attendance - Know the Campus - Section B - Session 1', url: '/classroom/1285317/view', start: '2026-07-27 14:00:00', end: '2026-07-27 15:30:00', className: 'event-past', trainers: 'Trainer Five ', subject: 'B10 - T0 - Fun Activity Know the Campus Context Presentation' },
  { nid: '1285310', title: 'Attendance - Digital Engagement - Section B - Session 1', url: '/classroom/1285310/view', start: '2026-07-27 15:45:00', end: '2026-07-27 17:15:00', className: 'event-past', trainers: 'Trainer Two', subject: 'B10 - T0 - Digital Engagement' },
  { nid: '1285318', title: 'Attendance - Fun Activity - Section A & B - Session 1', url: '/classroom/1285318/view', start: '2026-07-27 17:15:00', end: '2026-07-27 18:15:00', className: 'event-past', trainers: 'Trainer Four', subject: 'B10 - T0 - Fun Activity Know the Campus Context Presentation' },

  // --- Wednesday ---
  { nid: '1285249', title: 'Maths - Section B - Session 2', url: '/join/webinar?preview=true&nid=1285249&redirect=true', start: '2026-07-29 09:30:00', end: '2026-07-29 11:00:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285220', title: 'Excel - Section A - Session 2', url: '/join/webinar?preview=true&nid=1285220&redirect=true', start: '2026-07-29 09:30:00', end: '2026-07-29 11:00:00', className: 'event-past', batch: 'PGP-DM 2026-27' },
  { nid: '1285275', title: 'Bricolage - Section A & B - Session 2', url: '/join/webinar?preview=true&nid=1285275&redirect=true', start: '2026-07-29 15:45:00', end: '2026-07-29 17:15:00', className: 'event-active', batch: 'PGP-DM 2026-27' },
  { nid: '1285336', title: 'Attendance - Maths - Section B - Session 2', url: '/classroom/1285336/view', start: '2026-07-29 09:30:00', end: '2026-07-29 11:00:00', className: 'event-past', trainers: 'Trainer Two', subject: 'B10 - T0 - Maths' },
  { nid: '1285348', title: 'Attendance - Bricolage - Section A & B - Session 2', url: '/classroom/1285348/view', start: '2026-07-29 15:45:00', end: '2026-07-29 17:15:00', className: 'event-active', trainers: 'Trainer One', subject: 'B10 - T0 - Bricolage' },

  // --- Friday: three parallel group visits, no attendance rows for any of them ---
  { nid: '1285267', title: 'SPO Visit - Group 1 - Session 1', url: '/join/webinar?preview=true&nid=1285267&redirect=true', start: '2026-07-31 10:00:00', end: '2026-07-31 12:00:00', className: 'event-future', batch: 'PGP-DM 2026-27' },
  { nid: '1285269', title: 'SPO Visit - Group 2 - Session 1', url: '/join/webinar?preview=true&nid=1285269&redirect=true', start: '2026-07-31 15:00:00', end: '2026-07-31 17:00:00', className: 'event-future', batch: 'PGP-DM 2026-27' },
  { nid: '1285271', title: 'SPO Visit - Group 3 - Session 1', url: '/join/webinar?preview=true&nid=1285271&redirect=true', start: '2026-07-31 15:00:00', end: '2026-07-31 17:00:00', className: 'event-future', batch: 'PGP-DM 2026-27' },
];

// Attendance sessions that only ever appear for my own group, used for cohort detection.
export const CALENDAR_WIDE = CALENDAR.concat([
  { nid: '1285343', title: 'Attendance - PMDL - Group 3 - Session 2', url: '/classroom/1285343/view', start: '2026-08-03 09:30:00', end: '2026-08-03 13:00:00', className: 'event-future', trainers: 'Trainer Three', subject: 'B10 - T0 - PMDL and Plenary' },
]);

// A /classroom/{nid}/view body, cut down to the detail table.
export const CLASSROOM_HTML = `
<div class="classroom-detail"><table><tbody>
<tr> <td class="col-1">Title</td> <td class="mid">:</td> <td class="col-2">Attendance - PMDL - Section B - Session 1</td> </tr>
<tr> <td class="col-1">Start Date &amp; Time</td> <td class="mid">:</td> <td class="col-2">29-Jul-2026 11:30:00</td> </tr>
<tr> <td class="col-1">End Date &amp; Time</td> <td class="mid">:</td> <td class="col-2">29-Jul-2026 13:00:00</td> </tr>
<tr> <td class="col-1">Courses</td> <td class="mid">:</td> <td class="col-2">B10 - T0 - PMDL and Plenary</td> </tr>
<tr> <td class="col-1">Trainers</td> <td class="mid">:</td> <td class="col-2">Trainer Three</td> </tr>
<tr> <td class="col-1">Location</td> <td class="mid">:</td> <td class="col-2">Majlis</td> </tr>
<tr> <td class="col-1">Attendance Marked (For me)</td> <td class="mid">:</td> <td class="col-2">Yes</td> </tr>
<tr> <td class="col-1">Status</td> <td class="mid">:</td> <td class="col-2">Present</td> </tr>
<tr> <td class="col-1">Comment</td> <td class="mid">:</td> <td class="col-2">Marked attendance by self.</td> </tr>
</tbody></table></div>`;

// Two rows from /manage/classroom/attendance: one open for marking, one not.
export const MANAGE_HTML = `
<form id="manage-attendance"><table><tbody>
<tr 1285314="">
  <td>Attendance - Context Presentation - Section A &amp; B - Session 3</td>
  <td>05-08-2026  17:00-18:30</td>
  <td><div id="action-button">
    <div title="Mark Attendance" class="mark-attend-disabled" id="1285314" uid="1042">
      <button type="submit" class="button" id="mark-classroom-attend">Mark</button></div>
    <div title="View" id="classroom-attend-view"><a href="/classroom/1285314/view" class="attend-view button">View</a></div>
  </div></td>
</tr>
<tr 1285348="">
  <td>Attendance - Bricolage - Section A &amp; B - Session 2</td>
  <td>29-07-2026  15:45-17:15</td>
  <td><div id="action-button">
    <div title="Mark Attendance" class="mark-attend" id="1285348" uid="1042">
      <button type="submit" class="button" id="mark-classroom-attend">Mark</button></div>
    <div title="View" id="classroom-attend-view"><a href="/classroom/1285348/view" class="attend-view button">View</a></div>
  </div></td>
</tr>
</tbody></table></form>`;

// The real Drupal login form, reduced to its fields.
export const LOGIN_HTML = `
<form action="/user/login" method="post" id="user-login">
  <input type="text" id="edit-name" name="name" value="" size="60" />
  <input type="password" id="edit-pass" name="pass" size="60" />
  <input type="submit" id="edit-submit" name="op" value="Sign in" />
  <input type="hidden" name="form_build_id" value="form-abc123" />
  <input type="hidden" name="form_id" value="user_login" />
  <input type="hidden" class="form-st" name="st" value="" />
</form>`;
