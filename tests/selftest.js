// Checks the parsing and scheduling logic against real captured LMS responses.
// Run with:  node tests/selftest.js
// Needs no credentials and makes no network calls.

import assert from 'node:assert/strict';
import { parseLabelTable, parseForms, parseAttrs } from '../src/html.js';
import { buildSessions, detectCohorts, parseTitle, sessionState, parseLmsTime } from '../src/schedule.js';
import { AutoMarker } from '../src/automark.js';
import { CALENDAR, CALENDAR_WIDE, CLASSROOM_HTML, MANAGE_HTML, LOGIN_HTML } from './fixtures.js';

let passed = 0;
let failed = 0;

function test(name, fn) {
  try { fn(); passed++; console.log(`  ok   ${name}`); }
  catch (err) { failed++; console.error(`  FAIL ${name}\n       ${err.message}`); }
}

console.log('\nISDM Companion self-test\n');

test('title parsing splits course, cohort and session number', () => {
  assert.deepEqual(parseTitle('Attendance - Maths - Section B - Session 2'), { name: 'Maths', cohort: 'Section B', session: 2 });
  assert.deepEqual(parseTitle('Bricolage - Section A & B - Session 2'), { name: 'Bricolage', cohort: 'Section A & B', session: 2 });
  assert.deepEqual(parseTitle('SPO Visit - Group 3 - Session 1'), { name: 'SPO Visit', cohort: 'Group 3', session: 1 });
  // A course name containing its own hyphens must survive.
  assert.equal(parseTitle('Attendance - The Context of Development in India - Section A & B - Session 1').name,
    'The Context of Development in India');
});

test('cohort detection finds my section and group, ignoring shared "A & B" sessions', () => {
  const cohorts = detectCohorts(CALENDAR_WIDE);
  assert.deepEqual([...cohorts.sections], ['B'], 'should detect Section B only');
  assert.deepEqual([...cohorts.groups], ['3'], 'should detect Group 3 only');
});

test('the other section\'s classes are filtered out', () => {
  const rows = buildSessions(CALENDAR, detectCohorts(CALENDAR_WIDE));
  const names = rows.map((r) => `${r.name} (${r.cohort})`);
  assert.ok(!names.some((n) => n.includes('(Section A)')), `Section A leaked through: ${names.join(', ')}`);
  assert.ok(names.includes('Know the Campus (Section B)'));
  assert.ok(names.includes('Fun Activity (Section A & B)'), 'shared sessions must be kept');
});

test('only my own group visit is kept when groups run in parallel', () => {
  const rows = buildSessions(CALENDAR, detectCohorts(CALENDAR_WIDE));
  const visits = rows.filter((r) => r.name === 'SPO Visit');
  assert.equal(visits.length, 1, `expected 1 SPO Visit, got ${visits.length}`);
  assert.equal(visits[0].cohort, 'Group 3');
});

test('the session event and its attendance event merge into one row carrying the nid', () => {
  const rows = buildSessions(CALENDAR, detectCohorts(CALENDAR_WIDE));
  const maths = rows.filter((r) => r.name === 'Maths');
  assert.equal(maths.length, 1, 'Maths must appear once, not twice');
  assert.equal(maths[0].nid, '1285336', 'the attendance nid is what we mark against');
  assert.equal(maths[0].eventNid, '1285249', 'the batch-wide event id is kept too');
  assert.equal(maths[0].trainer, 'Trainer Two');
});

test('a class with no attendance row still appears, with nothing to mark', () => {
  const rows = buildSessions(CALENDAR, detectCohorts(CALENDAR_WIDE));
  const visit = rows.find((r) => r.name === 'SPO Visit');
  assert.equal(visit.nid, null);
  assert.equal(sessionState(visit, Date.parse('2026-07-30T12:00:00')), 'noattendance');
});

test('classroom detail parses room, trainer and marked status', () => {
  const table = parseLabelTable(CLASSROOM_HTML);
  assert.equal(table.Location, 'Majlis');
  assert.equal(table.Trainers, 'Trainer Three');
  assert.equal(table.Status, 'Present');
  assert.equal(table['Attendance Marked (For me)'], 'Yes');
  assert.equal(table['Start Date & Time'], '29-Jul-2026 11:30:00', 'entities must be decoded');
});

test('markability reads the LMS\'s own open/closed flag per session', () => {
  const map = new Map();
  const re = /<div\b([^>]*\buid=[^>]*)>/gi;
  let m;
  while ((m = re.exec(MANAGE_HTML))) {
    const attrs = parseAttrs(m[1]);
    if (!attrs.class || !attrs.class.includes('mark-attend') || !/^\d+$/.test(attrs.id || '')) continue;
    map.set(attrs.id, { markable: attrs.class.trim().split(/\s+/).includes('mark-attend'), uid: attrs.uid });
  }
  assert.equal(map.size, 2);
  assert.equal(map.get('1285348').markable, true, 'the live session is open for marking');
  assert.equal(map.get('1285314').markable, false, 'a future session must not be treated as open');
  assert.equal(map.get('1285348').uid, '1042');
});

test('login form fields are discovered rather than hard-coded', () => {
  const form = parseForms(LOGIN_HTML).find((f) => f.fields.some((i) => i.name === 'form_id' && i.value === 'user_login'));
  assert.ok(form, 'login form not found');
  assert.equal(form.fields.find((f) => f.type === 'password').name, 'pass');
  assert.equal(form.fields.find((f) => f.type === 'text').name, 'name');
  assert.equal(form.fields.find((f) => f.type === 'submit').value, 'Sign in');
  assert.ok(form.fields.some((f) => f.name === 'form_build_id'));
});

test('LMS timestamps read as local wall-clock time', () => {
  const d = parseLmsTime('2026-07-29 15:45:00');
  assert.equal(d.getHours(), 15);
  assert.equal(d.getMinutes(), 45);
  assert.equal(d.getDate(), 29);
});

test('session state reflects marked / open / upcoming / missed', () => {
  const base = { nid: '1', startMs: Date.parse('2026-07-29T15:45:00'), endMs: Date.parse('2026-07-29T17:15:00') };
  const before = Date.parse('2026-07-29T15:00:00');
  const during = Date.parse('2026-07-29T15:50:00');
  const after = Date.parse('2026-07-29T18:00:00');
  assert.equal(sessionState({ ...base, marked: true }, during), 'marked');
  assert.equal(sessionState({ ...base, markable: true }, during), 'open');
  assert.equal(sessionState({ ...base }, before), 'upcoming');
  assert.equal(sessionState({ ...base }, after), 'missed');
});

test('auto-mark is off unless explicitly enabled', () => {
  const marker = new AutoMarker();
  assert.equal(marker.isActive(), false);
  assert.equal(marker.status().armed, false);
});

test('auto-mark stops at the end of its arming window', () => {
  let now = 1_000_000;
  const marker = new AutoMarker({ enabled: true, windowMs: 3 * 3_600_000, clock: () => now });
  assert.equal(marker.isActive(), true, 'armed at start');
  now += 2.9 * 3_600_000;
  assert.equal(marker.isActive(), true, 'still armed just inside the window');
  now += 0.2 * 3_600_000;
  assert.equal(marker.isActive(), false, 'must stop once the window passes');
  assert.equal(marker.isExpired(), true, 'and report that it expired, not that it was never on');
});

test('re-arming restarts the window from now', () => {
  let now = 0;
  const marker = new AutoMarker({ enabled: true, windowMs: 3_600_000, clock: () => now });
  now += 2 * 3_600_000;
  assert.equal(marker.isActive(), false);
  marker.arm();
  assert.equal(marker.isActive(), true);
  assert.equal(marker.msRemaining(), 3_600_000);
});

test('disarming takes effect immediately and clears the expired state', () => {
  let now = 0;
  const marker = new AutoMarker({ enabled: true, windowMs: 3_600_000, clock: () => now });
  marker.disarm();
  assert.equal(marker.isActive(), false);
  assert.equal(marker.isExpired(), false);
  assert.equal(marker.status().armed, false);
});

console.log(`\n${passed} passed, ${failed} failed\n`);
process.exit(failed ? 1 : 0);
