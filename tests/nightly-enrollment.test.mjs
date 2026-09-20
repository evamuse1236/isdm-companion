import test from 'node:test';
import assert from 'node:assert/strict';
import { registerNightly } from '../supabase/functions/beta-api/nightly.ts';
import { updateTesterAccess } from '../supabase/functions/beta-admin/access.ts';
import { updateTesterAutoAttendance } from '../supabase/functions/beta-admin/auto-attendance.ts';
const fixture = { installation_id: '12345678-1234-4234-8234-123456789012', install_token: 'a'.repeat(64), support_name: 'Learner', self_section: 'Section A', model: 'Fixture', app_version: '0.5.8-nightly', app_version_code: 16, consent_version: 'nightly-2026-09-20' };
const request = body => new Request('https://example.test/nightly-enroll', { method: 'POST', body: JSON.stringify(body) });
test('nightly needs no invite, hashes credentials and only sends roster fields to storage', async () => {
  const response = await registerNightly(request(fixture), async data => {
    assert.equal(data.p_name, 'Learner');
    assert.equal(data.p_installation_id, fixture.installation_id);
    assert.match(data.p_token_hash, /^[a-f0-9]{64}$/);
    assert.notEqual(data.p_token_hash, fixture.install_token);
    assert.equal('invite_code' in data, false);
    assert.equal('install_token' in data, false);
    return { tester_code: 'N-000001', installation_id: data.p_installation_id };
  });
  assert.equal(response.status, 201);
  assert.equal((await response.json()).tester_code, 'N-000001');
});
test('nightly rejects missing name, consent, weak credentials and invalid versions before storage', async () => {
  for (const patch of [{ support_name: '' }, { consent_version: '' }, { install_token: 'weak' }, { installation_id: 'bad' }, { app_version_code: 1 }, { app_version_code: 2147483648 }]) {
    const response = await registerNightly(request({ ...fixture, ...patch }), async () => assert.fail('must not write'));
    assert.equal(response.status, 400);
  }
});
test('suspension, collision and daily quota remain explicit failures', async () => {
  for (const [error, status] of [['access_suspended',423], ['registration_conflict',409], ['enrollment_limit',429]]) {
    assert.equal((await registerNightly(request(fixture), async () => ({error}))).status, status);
  }
});
test('nightly enrollment rejects oversized streamed input', async () => {
  await assert.rejects(() => registerNightly(request({...fixture, extra:'x'.repeat(5000)}), async () => assert.fail()), {status:413});
});
test('owner controls accept nightly codes and preserve reason and revision requirements', async () => {
  const base = { tester_code:'N-000001', reason:'Owner review', expected_changed_at:null };
  assert.equal((await updateTesterAccess(request({...base,suspended:true}), {setAccess:async x=>x})).status,200);
  assert.equal((await updateTesterAutoAttendance(request({...base,blocked:true}), {setAutoAttendance:async x=>x})).status,200);
  assert.equal((await updateTesterAccess(request({...base,reason:'',suspended:true}), {setAccess:async()=>assert.fail()})).status,400);
});
