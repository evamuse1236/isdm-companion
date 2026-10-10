// Contract between the Android BetaApiClient and the real beta-api handler.
// Incident 2026-09-11 (37509ba): the paused profile route answered 202 while the
// released app accepted only 200/201, so every profile confirmation failed on
// phones. This test reads the statuses each Android call accepts straight from
// the Kotlin source and checks every success status the server returns against
// them, so a server reply the phone would treat as an error fails here first.
import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { webcrypto } from 'node:crypto';
import { registerNightly } from '../supabase/functions/beta-api/nightly.ts';
import { accessConfig, installationAccessResponse } from '../supabase/functions/beta-api/access.ts';
import { pausedCollectionReply } from '../supabase/functions/beta-api/collection-policy.mjs';
import { JsonInputError, readJsonObject, betaBodyLimit } from '../supabase/functions/_shared/http-input.mjs';
import { updateBetaProfile, deleteBetaSupportName } from '../supabase/functions/beta-api/profile.ts';
import { deriveSyncUpdate } from '../supabase/functions/beta-api/telemetry.ts';

const androidSource = (path) => readFile(new URL(`../android/app/src/main/java/org/isdm/companion/platform/${path}`, import.meta.url), 'utf8');

// Successes the phone does not accept today. Each entry must still happen (the test
// fails if it disappears) so this list cannot go stale. Fix the server or the client,
// then delete the entry; never add one to make a new mismatch pass.
const KNOWN_UNACCEPTED_SUCCESSES = new Map([
  ['events', 'Collection pause answers 202; BetaManager.flush throws and keeps the events queued. Owner decision pending.'],
  ['schedule', 'Collection pause answers 202; the queued request stays queued. Owner decision pending.'],
  ['attendance', 'Collection pause answers 202; the queued request stays queued. Owner decision pending.'],
  ['lms-diagnostic', 'Collection pause answers 202; the queued request stays queued. Owner decision pending.'],
]);

function closingParen(text, open) {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')' && --depth === 0) return i;
  }
  throw new Error('unbalanced parentheses in BetaApiClient.kt');
}

function statusSet(text) {
  return new Set(text.split(',').map((s) => Number(s.trim())).filter(Number.isInteger));
}

/** Every route the Android client calls, with its HTTP method and the statuses it accepts. */
export async function androidCalls() {
  const client = await androidSource('BetaApiClient.kt');
  const manager = await androidSource('BetaManager.kt');
  const defaults = client.match(/private fun post\([\s\S]*?acceptedStatuses: Set<Int> = setOf\(([^)]*)\)/);
  assert.ok(defaults, 'BetaApiClient.post lost its default acceptedStatuses; update this contract test');
  const accepted = statusSet(defaults[1]);
  const calls = new Map();
  for (const match of client.matchAll(/\b(post|request)\(\s*"([a-z-]+)"/g)) {
    const args = client.slice(match.index, closingParen(client, match.index + match[1].length));
    const explicit = args.match(/acceptedStatuses\s*=\s*setOf\(([^)]*)\)/);
    const method = match[1] === 'request' && /\(\s*"[a-z-]+",\s*null\b/.test(args) ? 'GET' : 'POST';
    calls.set(match[2], { method, accepted: explicit ? statusSet(explicit[1]) : accepted });
  }
  // BetaManager queues these and BetaApiClient.postQueued sends them with the default statuses.
  assert.match(client, /fun postQueued[\s\S]*?post\(request\.route, JSONObject\(request\.body\), installation\)/);
  for (const match of manager.matchAll(/\bqueue\(\s*"([a-z-]+)"/g)) calls.set(match[1], { method: 'POST', accepted });
  return calls;
}

async function betaApi(db) {
  let serve;
  const code = (await readFile(new URL('../supabase/functions/beta-api/index.ts', import.meta.url), 'utf8'))
    .replace(/^import\s+[\s\S]*?from\s+["'][^"']+["'];/gm, '');
  runInNewContext(stripTypeScriptTypes(code), {
    Deno: { env: { get: () => 'fixture-only' }, serve: (fn) => { serve = fn; } },
    createClient: () => db, accessConfig, installationAccessResponse, pausedCollectionReply, registerNightly,
    JsonInputError, readJsonObject, betaBodyLimit, updateBetaProfile, deleteBetaSupportName, deriveSyncUpdate,
    Request, Response, URL, TextEncoder, crypto: webcrypto, console, Uint8Array, btoa,
  });
  return serve;
}

function fakeDb(row) {
  const chain = () => {
    const q = { error: null, data: row };
    for (const name of ['select', 'eq', 'is', 'update', 'insert', 'upsert', 'order', 'limit', 'in', 'gte', 'lte']) q[name] = () => q;
    q.single = q.maybeSingle = async () => ({ data: row, error: null });
    q.then = (resolve) => resolve({ data: [row], error: null });
    return q;
  };
  return { from: chain, rpc: async () => ({ data: row, error: null }), storage: { from: () => ({ upload: async () => ({ error: null }) }) } };
}

const validBodies = {
  profile: {
    support_name: 'Asha Rao', self_section: 'Section B', self_plc: 'PLC 4',
    detected_sections: ['B'], detected_groups: ['4'],
    consent_version: 'beta-2026-08-16-profile', confirmed_at: '2026-09-11T03:30:00Z',
  },
};

test('every success the beta API returns to an enrolled phone is a status the Android client accepts', async () => {
  const calls = await androidCalls();
  for (const route of ['profile', 'profile-delete', 'config', 'auto-preflight', 'events', 'report', 'schedule', 'attendance', 'lms-diagnostic']) {
    assert.ok(calls.has(route), `BetaApiClient no longer calls "${route}"; update this contract test`);
  }
  const row = {
    tester_code: 'T-03', installation_id: 'fixture-id', app_version_code: 16, access_suspended: false,
    auto_attendance_blocked: false, enrollment_channel: 'beta', minimum_version_code: 1, auto_attendance_enabled: true,
  };
  const serve = await betaApi(fakeDb(row));
  const mismatches = [];
  const seenKnown = new Set();
  for (const [route, { method, accepted }] of calls) {
    if (route === 'enroll' || route === 'nightly-enroll') continue; // need an invite; covered by nightly-enrollment tests
    const response = await serve(new Request(`https://example.test/beta-api/${route}`, {
      method,
      headers: { 'content-type': 'application/json', 'x-installation-id': 'fixture-id', 'x-install-token': 'valid-token', 'x-app-version-code': '16' },
      body: method === 'GET' ? undefined : JSON.stringify(validBodies[route] ?? {}),
    }));
    const success = response.status >= 200 && response.status < 300;
    if (!success || accepted.has(response.status)) continue;
    if (KNOWN_UNACCEPTED_SUCCESSES.has(route)) { seenKnown.add(route); continue; }
    mismatches.push(`${route}: server answered ${response.status}, Android accepts ${[...accepted].join('/')}`);
  }
  assert.deepEqual(mismatches, [], 'The phone treats these server successes as failures (see docs/incident-2026-09-11-beta-profile.md)');
  assert.deepEqual([...seenKnown].sort(), [...KNOWN_UNACCEPTED_SUCCESSES.keys()].sort(),
    'A known mismatch no longer happens: delete its KNOWN_UNACCEPTED_SUCCESSES entry');
});
