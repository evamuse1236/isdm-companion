import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
const { PGlite } = await import(pathToFileURL(process.env.PGLITE_TEST_MODULE).href);
const db = new PGlite();
const dir = new URL('../supabase/migrations/', import.meta.url);
try {
  const base = await readFile(new URL('20260815133000_beta_observability.sql',dir),'utf8');
  const tables = ['beta_installations','beta_control_audit','beta_config'].map(name=>base.match(new RegExp(`create table public\\.${name} \\([\\s\\S]*?\\n\\);`))[0]);
  await db.exec(`create role anon; create role authenticated; create role service_role bypassrls;
    ${tables.join('\n')}
    alter table beta_installations enable row level security;
    alter table beta_control_audit enable row level security;
    grant usage on schema public to anon,authenticated,service_role;
    grant all on all tables in schema public to service_role;
    grant all on all sequences in schema public to service_role;
    create schema cron; create table cron.commands(name text, command text);
    create function cron.schedule(text,text,text) returns bigint language sql as $$ insert into cron.commands values ($1,$3); select 1::bigint $$;`);
  const profile=await readFile(new URL('20260816155455_beta_profile_and_retention.sql',dir),'utf8');
  await db.exec(profile.slice(0,profile.indexOf('create extension')));
  for (const file of ['20260910192122_tester_access_control.sql','20260911021740_tester_auto_attendance_control.sql']) await db.exec(await readFile(new URL(file,dir),'utf8'));
  const file=(await readdir(dir)).find(x=>x.endsWith('_nightly_enrollment.sql'));
  await db.exec(await readFile(new URL(file,dir),'utf8'));
  const id='12345678-1234-4234-8234-123456789012';
  const register=async(token='a'.repeat(64))=>(await db.query('select register_nightly_installation($1,$2,$3,$4,$5,$6,$7,$8) as result',[id,token,'Fixture learner','A',null,'Fixture phone','0.5.8-nightly',16])).rows[0].result;
  for(const role of ['anon','authenticated']) {
    await db.exec(`set role ${role}`);await assert.rejects(()=>register(),/permission denied/);await db.exec('reset role');
  }
  await db.exec('set role service_role');
  const first=await register();assert.equal(first.tester_code,'N-000001');
  assert.deepEqual(await register(),first);
  assert.equal((await register('b'.repeat(64))).error,'registration_conflict');
  assert.equal(Number((await db.query('select count(*) as n from beta_installations')).rows[0].n),1);
  const paused=(await db.query('select set_beta_tester_access($1,true,$2,null) as result',[first.tester_code,'Fixture suspension'])).rows[0].result;
  assert.equal(paused.access_suspended,true);assert.equal((await register()).error,'access_suspended');
  assert.equal((await db.query('select set_beta_tester_access($1,false,$2,null) as result',[first.tester_code,'Stale revision'])).rows[0].result.error,'access_changed');
  await db.query('select set_beta_tester_access($1,false,$2,$3)',[first.tester_code,'Fixture restore',paused.access_changed_at]);
  assert.deepEqual(await register(),first);
  assert.equal((await db.query('select set_beta_tester_auto_attendance($1,true,$2,null) as result',[first.tester_code,'Fixture auto stop'])).rows[0].result.auto_attendance_blocked,true);
  await db.exec('reset role');
  await db.exec(`insert into beta_installations(tester_code,invite_code_hash,support_name) values ('T-98',repeat('c',64),'Legacy learner');
    insert into beta_config(singleton,beta_ends_at) values (true,now()-interval '31 days');`);
  const commands=(await db.query('select * from cron.commands')).rows;
  for(const row of commands) await db.exec(row.command);
  assert.equal((await db.query("select support_name from beta_installations where tester_code='T-98'")).rows[0].support_name,null);
  assert.equal((await db.query("select support_name from beta_installations where tester_code='N-000001'")).rows[0].support_name,'Fixture learner');
  await db.exec("update beta_installations set last_seen_at=now()-interval '181 days' where tester_code='N-000001'");
  await db.exec(commands.find(x=>x.name.startsWith('nightly-')).command);
  assert.equal((await db.query("select support_name from beta_installations where tester_code='N-000001'")).rows[0].support_name,null);
  await db.exec(`insert into beta_installations(tester_code,enrollment_channel) select 'N-'||(100000+n)::text,'nightly' from generate_series(1,999) n;`);
  await db.exec('set role service_role');
  const quota=(await db.query("select register_nightly_installation('12345678-1234-4234-8234-123456789013',repeat('a',64),'Fixture','A',null,'Fixture','0.5.8-nightly',16) as result")).rows[0].result;
  assert.equal(quota.error,'enrollment_limit');
  assert.deepEqual(await register(),first,'retry must work even at quota');
  console.log('PASS: migration, service-only enrollment, retry identity, token conflict, suspension/restore, revision guard, automatic-attendance control, beta/nightly retention separation, inactivity cleanup and daily quota.');
} finally {await db.close();}
