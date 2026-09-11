import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
// Optional verification runtime, installed outside the application dependency tree.
const modulePath = process.env.PGLITE_TEST_MODULE;
if (!modulePath) throw new Error('Set PGLITE_TEST_MODULE to the installed @electric-sql/pglite dist/index.js');
const { PGlite } = await import(pathToFileURL(modulePath).href);
const db = new PGlite();
try {
  const base = await readFile(new URL('../supabase/migrations/20260815133000_beta_observability.sql', import.meta.url), 'utf8');
  const tables = ['beta_installations', 'beta_control_audit'].map(name => base.match(new RegExp(`create table public\\.${name} \\([\\s\\S]*?\\n\\);`))[0]);
  await db.exec(`create role anon; create role authenticated; create role service_role bypassrls;
    ${tables.join('\n')}
    alter table public.beta_installations enable row level security;
    alter table public.beta_control_audit enable row level security;
    grant usage on schema public to anon, authenticated, service_role;
    grant all on all tables in schema public to service_role;
    grant all on all sequences in schema public to service_role;`);
  await db.exec(await readFile(new URL('../supabase/migrations/20260910192122_tester_access_control.sql', import.meta.url), 'utf8'));
  await db.exec(await readFile(new URL('../supabase/migrations/20260911021740_tester_auto_attendance_control.sql', import.meta.url), 'utf8'));
  await db.exec(`insert into public.beta_installations(tester_code, invite_code_hash) values ('T-98', repeat('a',64)), ('T-99', repeat('b',64));`);
  const update = async (suspended, revision) => (await db.query('select public.set_beta_tester_access($1,$2,$3,$4) as result', ['T-98', suspended, 'Fixture review', revision])).rows[0].result;
  const autoUpdate = async (blocked, revision) => (await db.query('select public.set_beta_tester_auto_attendance($1,$2,$3,$4) as result', ['T-98', blocked, 'Fixture review', revision])).rows[0].result;
  const state = async () => (await db.query("select access_suspended from beta_installations where tester_code = 'T-98'")).rows[0].access_suspended;
  const count = async () => Number((await db.query('select count(*) as count from beta_control_audit')).rows[0].count);
  for (const role of ['anon', 'authenticated']) {
    await db.exec(`set role ${role}`);
    await assert.rejects(() => update(true, null), /permission denied/);
    await assert.rejects(() => autoUpdate(true, null), /permission denied/);
    await db.exec('reset role');
  }
  await db.exec('set role service_role');
  const paused = await update(true, null);
  assert.equal(paused.access_suspended, true);
  assert.equal(await state(), true);
  assert.equal(await count(), 1);
  assert.equal((await db.query("select access_suspended from beta_installations where tester_code = 'T-99'")).rows[0].access_suspended, false);
  assert.equal((await update(false, null)).error, 'access_changed');
  await update(true, paused.access_changed_at);
  assert.equal(await count(), 1);
  const restored = await update(false, paused.access_changed_at);
  assert.equal(await state(), false);
  assert.equal(await count(), 2);
  const autoStopped = await autoUpdate(true, null);
  assert.equal(autoStopped.auto_attendance_blocked, true);
  assert.equal(await state(), false, 'auto stop must leave Companion access allowed');
  assert.equal((await db.query("select auto_attendance_blocked from beta_installations where tester_code='T-99'")).rows[0].auto_attendance_blocked, false);
  assert.equal((await autoUpdate(false, null)).error, 'auto_attendance_changed');
  await autoUpdate(true, autoStopped.auto_attendance_changed_at);
  assert.equal(await count(), 3, 'repeat stop must not add another audit');
  const autoRestored = await autoUpdate(false, autoStopped.auto_attendance_changed_at);
  assert.equal(autoRestored.auto_attendance_blocked, false);
  assert.equal(await count(), 4);
  await db.exec('reset role');
  await db.exec(`create function reject_audit_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture audit unavailable'; end; $$;
    create trigger reject_audit_fixture before insert on beta_control_audit for each row execute function reject_audit_fixture();`);
  await db.exec('set role service_role');
  await assert.rejects(() => update(true, restored.access_changed_at), /fixture audit unavailable/);
  assert.equal(await state(), false);
  assert.equal(await count(), 4);
  await assert.rejects(() => autoUpdate(true, autoRestored.auto_attendance_changed_at), /fixture audit unavailable/);
  assert.equal((await db.query("select auto_attendance_blocked from beta_installations where tester_code='T-98'")).rows[0].auto_attendance_blocked, false);
  assert.equal(await count(), 4);
  console.log('PASS: both migrations, service-only controls, individual auto stop without full suspension, tester isolation, revision conflict, idempotence, restore, and audit rollback.');
} finally { await db.close(); }
