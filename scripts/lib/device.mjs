// The one way scripts pick an Android device. Agents may touch an emulator only:
// the owner's phone holds a real LMS login and tester data, and a wrong install or
// uninstall there cannot be undone.
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

export const PACKAGE = "org.isdm.companion";
export const LAUNCH_COMPONENT = `${PACKAGE}/.LauncherActivity`;

export function sdkDir(env = process.env) {
  for (const candidate of [env.ANDROID_HOME, env.ANDROID_SDK_ROOT,
    env.LOCALAPPDATA && join(env.LOCALAPPDATA, "Android", "Sdk"),
    env.HOME && join(env.HOME, "Android", "Sdk"), env.HOME && join(env.HOME, "Library", "Android", "sdk")]) {
    if (candidate && existsSync(join(candidate, "platforms"))) return candidate;
  }
  return null;
}

export function adbPath(env = process.env) {
  const sdk = sdkDir(env);
  const exe = process.platform === "win32" ? "adb.exe" : "adb";
  if (sdk && existsSync(join(sdk, "platform-tools", exe))) return join(sdk, "platform-tools", exe);
  return "adb";
}

export function adb(serial, args, options = {}) {
  const prefix = serial ? ["-s", serial] : [];
  return spawnSync(adbPath(), [...prefix, ...args], { encoding: "utf8", windowsHide: true, ...options });
}

/** Serials from `adb devices` that are online. */
export function parseDevices(output) {
  return output.split(/\r?\n/).slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter(([serial, state]) => serial && state === "device")
    .map(([serial]) => serial);
}

/** An emulator has an `emulator-NNNN` serial and reports ro.kernel.qemu or ro.boot.qemu = 1. */
export function isEmulator(serial, qemuProps) {
  return /^emulator-\d+$/.test(serial) && qemuProps.some((value) => String(value).trim() === "1");
}

/**
 * Pick the emulator to use, or explain why not.
 * Returns { serial } or { error, missing } where missing means no emulator is running.
 */
export function chooseEmulator({ requested = process.env.ANDROID_SERIAL, devices, qemuProps }) {
  if (requested) {
    // Check the serial before asking the device anything: never run adb against a phone.
    if (!/^emulator-\d+$/.test(requested) || !isEmulator(requested, qemuProps(requested))) {
      return { error: `Refusing ${requested}: agents may use an emulator only (emulator-NNNN reporting ro.kernel.qemu=1).` };
    }
    if (!devices.includes(requested)) return { error: `${requested} is not online (adb devices).`, missing: true };
    return { serial: requested };
  }
  const emulators = devices.filter((serial) => /^emulator-\d+$/.test(serial) && isEmulator(serial, qemuProps(serial)));
  if (emulators.length === 1) return { serial: emulators[0] };
  if (emulators.length === 0) {
    return { missing: true, error: "No emulator is running. Open Galaxy_S24_FE_API_36 in T3's Device panel (device_list, then device_open); never launch one from the shell." };
  }
  return { error: `Several emulators are running (${emulators.join(", ")}); set ANDROID_SERIAL to one of them.` };
}

export function findEmulator() {
  const listed = adb(null, ["devices"]);
  if (listed.error || listed.status !== 0) {
    return { missing: true, error: `adb is unavailable (${listed.error?.message ?? listed.stderr.trim()}). Run npm run setup.` };
  }
  return chooseEmulator({
    devices: parseDevices(listed.stdout),
    qemuProps: (serial) => ["ro.kernel.qemu", "ro.boot.qemu"].map((prop) => adb(serial, ["shell", "getprop", prop]).stdout ?? ""),
  });
}
