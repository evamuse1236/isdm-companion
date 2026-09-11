import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { webcrypto } from 'node:crypto';
import { accessConfig, installationAccessResponse } from '../supabase/functions/beta-api/access.ts';
import { pausedCollectionReply } from '../supabase/functions/beta-api/collection-policy.mjs';
import { updateTesterAutoAttendance } from '../supabase/functions/beta-admin/auto-attendance.ts';
import { updateTesterAccess } from '../supabase/functions/beta-admin/access.ts';

async function handler(name, db) {
  let serve;
  const code = (await readFile(new URL(`../supabase/functions/${name}/index.ts`, import.meta.url), 'utf8'))
    .replace(/^import\s+[\s\S]*?from\s+["'][^"']+["'];/gm, '');
  runInNewContext(stripTypeScriptTypes(code), {
    Deno: { env: { get: () => 'fixture-only' }, serve: fn => { serve = fn; } },
    createClient: () => db, accessConfig, installationAccessResponse, updateTesterAccess, updateTesterAutoAttendance, pausedCollectionReply,
    Request, Response, URL, TextEncoder, crypto: webcrypto, console, Uint8Array, btoa,
  });
  return serve;
}
function query(data) {
  const chain = { error: null, data };
  for (const name of ['select','eq','update','is']) chain[name] = () => chain;
  chain.single = chain.maybeSingle = async () => ({ data, error: null });
  return chain;
}

test('real beta handler authenticates an existing token then denies every protected route', async () => {
  const installation = { tester_code: 'T-03', installation_id: 'fixture-id', access_suspended: true, app_version_code: 11 };
  const serve = await handler('beta-api', { from: table => query(table === 'beta_installations' ? installation : { minimum_version_code: 1 }) });
  for (const route of ['events', 'auto-preflight', 'attendance', 'profile', 'report', 'schedule', 'lms-diagnostic']) {
    const response = await serve(new Request(`https://example.test/beta-api/${route}`, { method: 'POST', headers: {
      'x-installation-id': 'fixture-id', 'x-install-token': 'old-valid-token',
    } }));
    assert.equal(response.status, 423, route);
    assert.equal((await response.json()).error, 'access_suspended');
  }
  const config = await serve(new Request('https://example.test/beta-api/config', { headers: {
    'x-installation-id': 'fixture-id', 'x-install-token': 'old-valid-token',
  } }));
  assert.equal(config.status, 200);
  assert.equal((await config.json()).access_suspended, true);
  const denied = await serve(new Request('https://example.test/beta-api/config'));
  assert.equal(denied.status, 401);
});

test('real enrollment handler rejects a suspended invite before token rotation', async () => {
  let writes = 0;
  const serve = await handler('beta-api', { from: () => {
    const q = query({ tester_code: 'T-03', access_suspended: true });
    q.update = () => { writes++; return q; };
    return q;
  } });
  const response = await serve(new Request('https://example.test/beta-api/enroll', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ invite_code: 'FIXTURE', consent_version: 'test', self_section: 'A' }),
  }));
  assert.equal(response.status, 423);
  assert.equal(writes, 0);
});

test('real admin handler requires the owner secret before invoking access RPC', async () => {
  let writes = 0;
  const hash = Buffer.from(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode('fixture-secret'))).toString('hex');
  const serve = await handler('beta-admin', {
    from: () => query({ admin_secret_hash: hash }),
    rpc: async (name, args) => {
      writes++;
      assert.equal(name, 'set_beta_tester_access');
      assert.equal(args.p_tester_code, 'T-03');
      return { data: { access_suspended: true }, error: null };
    },
  });
  const request = secret => new Request('https://example.test/beta-admin/access', {
    method: 'POST', headers: { 'content-type': 'application/json', ...(secret ? { 'x-admin-secret': secret } : {}) },
    body: JSON.stringify({ tester_code: 'T-03', suspended: true, reason: 'Owner review', expected_changed_at: null }),
  });
  for (const secret of [undefined, 'wrong']) assert.equal((await serve(request(secret))).status, 401);
  assert.equal(writes, 0);
  assert.equal((await serve(request('fixture-secret'))).status, 200);
  assert.equal(writes, 1);
});

test('upgraded app checks its current version even when stored telemetry is older', async () => {
  const serve = await handler('beta-api', { from: table => query(table === 'beta_installations'
    ? { tester_code: 'T-03', installation_id: 'fixture-id', access_suspended: false, app_version_code: 1 }
    : { minimum_version_code: 11 }) });
  const config = await serve(new Request('https://example.test/beta-api/config', { headers: {
    'x-installation-id': 'fixture-id', 'x-install-token': 'valid-token', 'x-app-version-code': '11',
  } }));
  assert.equal(config.status, 200);
  assert.equal((await config.json()).update_required, false);
});

test('invite rotation compares the current suspension and previous installation in one write', async () => {
  const predicates = [];
  let lookups = 0;
  const serve = await handler('beta-api', { from: () => {
    const q = query(++lookups === 1 ? { tester_code: 'T-03', installation_id: 'old-device', access_suspended: false } : null);
    q.eq = (key, value) => { if (lookups > 1) predicates.push([key, value]); return q; };
    return q;
  } });
  const response = await serve(new Request('https://example.test/beta-api/enroll', { method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ invite_code: 'FIXTURE', consent_version: 'test', self_section: 'A' }),
  }));
  assert.equal(response.status, 409);
  assert.deepEqual(predicates, [['tester_code', 'T-03'], ['access_suspended', false], ['installation_id', 'old-device']]);
});


test('access checks preserve the live collection pause for unsuspended testers', async () => {
  let writes = 0;
  const serve = await handler('beta-api', { from: () => {
    const q = query({ tester_code: 'T-03', installation_id: 'fixture-id', access_suspended: false });
    q.update = q.insert = () => { writes++; return q; };
    return q;
  } });
  for (const route of ['events', 'profile', 'schedule', 'attendance', 'lms-diagnostic', 'report']) {
    const response = await serve(new Request(`https://example.test/beta-api/${route}`, { method: 'POST',
      headers: { 'x-installation-id': 'fixture-id', 'x-install-token': 'valid-token' },
    }));
    assert.equal(response.status, route === 'report' ? 503 : 202, route);
    assert.equal((await response.json())[route === 'report' ? 'error' : 'collection_paused'],
      route === 'report' ? 'data_collection_paused' : true);
  }
  assert.equal(writes, 0);
});


test('individual auto stop blocks automatic checks but preserves Companion access and other testers', async () => {
  for (const blocked of [true, false]) {
    const serve = await handler('beta-api', { from: table => query(table === 'beta_installations'
      ? { tester_code: blocked ? 'T-03' : 'T-04', installation_id: 'fixture-id', access_suspended: false, auto_attendance_blocked: blocked, app_version_code: 14 }
      : { auto_attendance_blocked: false, minimum_version_code: 1 }) });
    const headers = { 'x-installation-id': 'fixture-id', 'x-install-token': 'valid-token' };
    const preflight = await serve(new Request('https://example.test/beta-api/auto-preflight', { method: 'POST', headers }));
    assert.equal(preflight.status, blocked ? 423 : 200);
    assert.deepEqual(await preflight.json().then(({allowed,reason}) => ({allowed,reason})), {
      allowed: !blocked, reason: blocked ? 'tester_remote_stop' : 'allowed',
    });
    const config = await serve(new Request('https://example.test/beta-api/config', { headers }));
    const data = await config.json();
    assert.equal(config.status, 200);
    assert.equal(data.access_suspended, false);
    assert.equal(data.auto_attendance_blocked, blocked);
    assert.ok(data.access_valid_until);
  }
});

test('global stop still applies after an individual is allowed', async () => {
  const serve = await handler('beta-api', { from: table => query(table === 'beta_installations'
    ? { tester_code: 'T-03', access_suspended: false, auto_attendance_blocked: false, app_version_code: 14 }
    : { auto_attendance_blocked: true, minimum_version_code: 1 }) });
  const response = await serve(new Request('https://example.test/beta-api/auto-preflight', { method: 'POST',
    headers: { 'x-installation-id': 'fixture-id', 'x-install-token': 'valid-token' },
  }));
  assert.equal(response.status, 423);
  assert.equal((await response.json()).reason, 'remote_stop');
});

test('individual auto control verifies the owner secret and uses a separate atomic RPC', async () => {
  let writes = 0;
  const hash = Buffer.from(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode('fixture-secret'))).toString('hex');
  const serve = await handler('beta-admin', {
    from: () => query({ admin_secret_hash: hash }),
    rpc: async (name, args) => {
      writes++;
      assert.equal(name, 'set_beta_tester_auto_attendance');
      assert.equal(args.p_tester_code, 'T-03');
      assert.equal(args.p_blocked, true);
      return { data: { auto_attendance_blocked: true }, error: null };
    },
  });
  const request = secret => new Request('https://example.test/beta-admin/auto-attendance', { method: 'POST',
    headers: { 'content-type': 'application/json', ...(secret ? { 'x-admin-secret': secret } : {}) },
    body: JSON.stringify({ tester_code: 'T-03', blocked: true, reason: 'Owner review', expected_changed_at: null }),
  });
  assert.equal((await serve(request('wrong'))).status, 401);
  assert.equal(writes, 0);
  assert.equal((await serve(request('fixture-secret'))).status, 200);
  assert.equal(writes, 1);
});
