// Agents may drive an emulator only, never the owner's phone (it holds a real LMS
// login and tester data). scripts/lib/device.mjs is the one place that picks a device.
import assert from 'node:assert/strict';
import test from 'node:test';
import { chooseEmulator, isEmulator, parseDevices } from '../scripts/lib/device.mjs';

const PHONE = 'RZCY810547H';
const emulatorProps = () => ['1', ''];

test('a phone serial is refused without being asked anything', () => {
  let asked = false;
  const result = chooseEmulator({ requested: PHONE, devices: [PHONE, 'emulator-5554'], qemuProps: () => { asked = true; return ['1']; } });
  assert.match(result.error, /Refusing RZCY810547H/);
  assert.equal(result.serial, undefined);
  assert.equal(asked, false);
});

test('an emulator-looking serial must also report qemu', () => {
  assert.equal(isEmulator('emulator-5554', ['', '']), false);
  assert.equal(isEmulator('emulator-5554', ['1', '']), true);
  assert.match(chooseEmulator({ requested: 'emulator-5554', devices: ['emulator-5554'], qemuProps: () => ['0', ''] }).error, /Refusing/);
});

test('without ANDROID_SERIAL the single emulator is chosen even when the phone is attached', () => {
  const devices = parseDevices('List of devices attached\nRZCY810547H\tdevice\nemulator-5554\tdevice\n\n');
  assert.deepEqual(devices, [PHONE, 'emulator-5554']);
  assert.deepEqual(chooseEmulator({ requested: '', devices, qemuProps: emulatorProps }), { serial: 'emulator-5554' });
});

test('only the phone attached means no device, with how to open the emulator', () => {
  const result = chooseEmulator({ requested: '', devices: [PHONE], qemuProps: emulatorProps });
  assert.equal(result.missing, true);
  assert.match(result.error, /Device panel/);
});

test('offline and unauthorized devices are not candidates', () => {
  assert.deepEqual(parseDevices('List of devices attached\nemulator-5554\toffline\nRZCY810547H\tunauthorized\n'), []);
});
