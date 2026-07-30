// Verifies what happens when the laptop wakes from standby: the app must throw away the
// session and every cached read, log back in, and re-check the day from scratch.
//
// Run with:  node tests/resumetest.js

import assert from 'node:assert/strict';
import http from 'node:http';

let passed = 0;
let failed = 0;
const test = (name, fn) => {
  try { fn(); passed++; console.log(`  ok   ${name}`); }
  catch (err) { failed++; console.error(`  FAIL ${name}\n       ${err.message}`); }
};

const hits = { login: 0, calendar: 0, classroom: 0, manage: 0 };
let cookieSeed = 0;

const stub = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);

  if (req.url.startsWith('/user/login') && req.method === 'GET') {
    cookieSeed++;
    res.writeHead(200, { 'Content-Type': 'text/html', 'Set-Cookie': `SESS=session${cookieSeed}; path=/` });
    return res.end(`<form action="/user/login" method="post" id="user-login">
      <input type="text" name="name"><input type="password" name="pass">
      <input type="hidden" name="form_build_id" value="fb"><input type="hidden" name="form_id" value="user_login">
      </form>`);
  }
  if (req.url.startsWith('/user/login') && req.method === 'POST') {
    hits.login++;
    res.writeHead(302, { Location: '/home' });
    return res.end();
  }
  if (req.url.startsWith('/home')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end('<a href="/user/1042/edit/chgpwd">x</a>');
  }
  if (req.url.startsWith('/calendar/json')) {
    hits.calendar++;
    const today = new Date();
    const p = (n) => String(n).padStart(2, '0');
    const d = `${today.getFullYear()}-${p(today.getMonth() + 1)}-${p(today.getDate())}`;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify([
      { nid: '900', title: 'Excel - Section B - Session 4', url: '/join/webinar?nid=900', start: `${d} 11:30:00`, end: `${d} 13:00:00`, className: 'event-active' },
      { nid: '901', title: 'Attendance - Excel - Section B - Session 4', url: '/classroom/901/view', start: `${d} 11:30:00`, end: `${d} 13:00:00`, className: 'event-active', trainers: 'T', subject: 'S' },
    ]));
  }
  if (req.url.startsWith('/manage/classroom/attendance')) {
    hits.manage++;
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end('<div title="Mark Attendance" class="mark-attend" id="901" uid="1042"></div>');
  }
  if (req.url.startsWith('/classroom/')) {
    hits.classroom++;
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<table>
      <tr><td class="col-1">Location</td><td class="mid">:</td><td class="col-2">Majlis</td></tr>
      <tr><td class="col-1">Attendance Marked (For me)</td><td class="mid">:</td><td class="col-2">No</td></tr>
      <tr><td class="col-1">Status</td><td class="mid">:</td><td class="col-2">Not Marked</td></tr>
    </table>`);
  }
  res.writeHead(404).end('nope');
});

await new Promise((resolve) => stub.listen(0, '127.0.0.1', resolve));
process.env.LMS_BASE = `http://127.0.0.1:${stub.address().port}`;

const { LmsClient } = await import('../src/lms.js');
const { Service } = await import('../src/service.js');

console.log('\nISDM Companion resume-from-standby test\n');

const client = new LmsClient({ email: 'a@b.c', password: 'x' });
const service = new Service(client, { roomFloors: 'Majlis:6' });

// A normal check before the laptop sleeps.
const before = await service.day();
const loginsBefore = hits.login;
const calendarBefore = hits.calendar;

test('the session is live and the class is seen as open', () => {
  assert.equal(before.sessions.length, 1);
  assert.equal(before.sessions[0].state, 'open');
  assert.equal(before.sessions[0].room, 'Majlis');
  assert.equal(before.sessions[0].floor, 6);
  assert.equal(loginsBefore, 1, 'logged in once');
});

// A second check straight away should be served from cache, not refetched.
await service.day();
test('back-to-back checks reuse the cache', () => {
  assert.equal(hits.calendar, calendarBefore, 'calendar should not be refetched immediately');
  assert.equal(hits.login, loginsBefore, 'and should not log in again');
});

// --- now simulate the wake: exactly what watch() does on a long gap ---
client.reset();
service.resetCaches();
const after = await service.day();

test('waking throws away the session and logs in again', () => {
  assert.equal(hits.login, loginsBefore + 1, 'must re-authenticate after a resume');
});

test('waking refetches everything rather than trusting stale reads', () => {
  assert.ok(hits.calendar > calendarBefore, 'calendar must be refetched');
});

test('the class still open on wake is picked up, ready to mark', () => {
  assert.equal(after.sessions.length, 1);
  assert.equal(after.sessions[0].state, 'open', 'must be marked as open so auto-mark fires');
  assert.equal(after.sessions[0].marked, false);
  assert.equal(after.sessions[0].nid, '901');
});

test('a fresh cookie is used, not the one that may have lapsed while asleep', () => {
  assert.ok(cookieSeed >= 2, `expected a new session cookie, got ${cookieSeed}`);
});

await new Promise((resolve) => stub.close(resolve));
console.log(`\n${passed} passed, ${failed} failed\n`);
process.exitCode = failed ? 1 : 0;
