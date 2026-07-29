// Verifies the exact HTTP request the app sends when it marks attendance, against a local
// stub standing in for the LMS. This is the one request that changes your attendance record,
// so its shape is checked rather than fired at the real server.
//
// Run with:  node tests/marktest.js

import assert from 'node:assert/strict';
import http from 'node:http';

let passed = 0;
let failed = 0;
const test = (name, fn) => {
  try { fn(); passed++; console.log(`  ok   ${name}`); }
  catch (err) { failed++; console.error(`  FAIL ${name}\n       ${err.message}`); }
};

const received = [];

const stub = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  received.push({
    method: req.method,
    url: req.url,
    headers: req.headers,
    body: Buffer.concat(chunks).toString('utf8'),
  });

  if (req.url.startsWith('/user/login') && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html', 'Set-Cookie': 'SESSabc=cookievalue; path=/' });
    return res.end(`<form action="/user/login" method="post" id="user-login">
      <input type="text" name="name"><input type="password" name="pass">
      <input type="submit" name="op" value="Sign in">
      <input type="hidden" name="form_build_id" value="form-xyz">
      <input type="hidden" name="form_id" value="user_login">
      <input type="hidden" name="st" value=""></form>`);
  }
  if (req.url.startsWith('/user/login') && req.method === 'POST') {
    res.writeHead(302, { Location: '/home' });
    return res.end();
  }
  if (req.url.startsWith('/home')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end('<a href="/user/1042/edit/chgpwd">Change Password</a>');
  }
  if (req.url.startsWith('/api/mark/classroomsession/attendance')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end('success');
  }
  if (req.url.startsWith('/classroom/')) {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    return res.end(`<table>
      <tr><td class="col-1">Location</td><td class="mid">:</td><td class="col-2">Majlis</td></tr>
      <tr><td class="col-1">Attendance Marked (For me)</td><td class="mid">:</td><td class="col-2">Yes</td></tr>
      <tr><td class="col-1">Status</td><td class="mid">:</td><td class="col-2">Present</td></tr>
    </table>`);
  }
  res.writeHead(404).end('nope');
});

await new Promise((resolve) => stub.listen(0, '127.0.0.1', resolve));
const port = stub.address().port;
process.env.LMS_BASE = `http://127.0.0.1:${port}`;

const { LmsClient } = await import('../src/lms.js');

console.log('\nISDM Companion mark-request test\n');

const client = new LmsClient({ email: 'student@pgp.isdm.org.in', password: 'hunter2' });
const detail = await client.markPresent('1285348');

const login = received.find((r) => r.method === 'POST' && r.url.startsWith('/user/login'));
const mark = received.find((r) => r.url.startsWith('/api/mark/classroomsession/attendance'));

test('logs in before marking, carrying the form tokens', () => {
  assert.ok(login, 'no login POST was sent');
  const body = new URLSearchParams(login.body);
  assert.equal(body.get('name'), 'student@pgp.isdm.org.in');
  assert.equal(body.get('pass'), 'hunter2');
  assert.equal(body.get('form_id'), 'user_login');
  assert.equal(body.get('form_build_id'), 'form-xyz');
  assert.equal(body.get('st'), 'form-xyz', 'st must mirror form_build_id');
});

test('the mark request matches what the LMS front-end sends', () => {
  assert.ok(mark, 'no mark request was sent');
  assert.equal(mark.method, 'POST');
  assert.equal(mark.url, '/api/mark/classroomsession/attendance');
  assert.equal(mark.headers['content-type'], 'application/json');
  assert.equal(mark.headers['x-requested-with'], 'XMLHttpRequest');
  assert.deepEqual(JSON.parse(mark.body), { nid: '1285348', uid: '1042', status: 'present' });
});

test('the session cookie is carried on the mark request', () => {
  assert.ok(/SESSabc=cookievalue/.test(mark.headers.cookie || ''), `cookie missing: ${mark.headers.cookie}`);
});

test('uid comes from the logged-in account, not a hard-coded value', () => {
  assert.equal(JSON.parse(mark.body).uid, '1042', 'uid should be scraped from the profile link');
});

test('success is confirmed by re-reading the session, not by trusting the POST', () => {
  const confirm = received.filter((r) => r.url.startsWith('/classroom/1285348/view'));
  assert.equal(confirm.length, 1, 'should re-fetch the session detail after marking');
  assert.equal(detail.marked, true);
  assert.equal(detail.status, 'Present');
  assert.equal(detail.room, 'Majlis');
});

// Let the stub shut down cleanly; forcing process.exit() here races libuv on Windows.
await new Promise((resolve) => stub.close(resolve));
console.log(`\n${passed} passed, ${failed} failed\n`);
process.exitCode = failed ? 1 : 0;
