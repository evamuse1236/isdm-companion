// Gradle deploys to every attached device, including the owner's phone. build.gradle.kts must
// make install*, uninstall* and connected* tasks fail unless ANDROID_SERIAL is an emulator.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const gradle = readFileSync(new URL('../android/app/build.gradle.kts', import.meta.url), 'utf8');

test('Gradle refuses device-deploying tasks unless ANDROID_SERIAL is an emulator', () => {
  const guard = /tasks\.configureEach\s*\{([\s\S]*?)\n\}/.exec(gradle)?.[1] ?? '';
  for (const prefix of ['install', 'uninstall', 'connected']) {
    assert.ok(guard.includes(`name.startsWith("${prefix}")`), `phone guard in android/app/build.gradle.kts must cover ${prefix}* tasks`);
  }
  assert.match(guard, /doFirst/, 'the guard must run in doFirst so configuration and normal builds are unaffected');
  assert.ok(guard.includes('System.getenv("ANDROID_SERIAL")'), 'the guard must read ANDROID_SERIAL');
  assert.ok(guard.includes(String.raw`^emulator-\d+$`), 'the guard must accept only emulator-NNNN serials');
  assert.match(guard, /throw GradleException/);
  assert.match(guard, /set ANDROID_SERIAL=emulator-5554/, 'the message must name the fix');
});
