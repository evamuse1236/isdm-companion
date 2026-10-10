#!/usr/bin/env node
// Drive the installed debug app on the emulator at its own phone size and at 1440x900
// (`wm size`, reset afterwards). Checks the signed-out screen, the only screen reachable
// without a real LMS login. Never types credentials, never taps Sign in.
//
//   npm run dev                       (build, install, launch on the emulator first)
//   node .claude/skills/verify-isdm-companion/drive-android.mjs [--out DIR]
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { LAUNCH_COMPONENT, PACKAGE, adb, findEmulator } from "../../../scripts/lib/device.mjs";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const i = process.argv.indexOf("--out");
const out = resolve(i > 0 ? process.argv[i + 1] : join(root, ".verify", "android"));
mkdirSync(out, { recursive: true });

const SIGNED_OUT = ["Your ISDM companion", "LMS email", "LMS password", "Sign in"];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const device = findEmulator();
if (!device.serial) {
  console.log(`${device.missing ? "SKIPPED" : "REFUSED"}: ${device.error}`);
  process.exit(device.missing ? 0 : 1);
}
const serial = device.serial;
const shell = (...args) => adb(serial, ["shell", ...args]);

async function uiTexts() {
  for (let attempt = 0; attempt < 2; attempt++) {
    const dump = shell("uiautomator", "dump", "/sdcard/isdm-verify-ui.xml");
    if (dump.status === 137 || /Killed/.test(`${dump.stdout}${dump.stderr}`)) {
      // T3's snapshot helper holds UiAutomation; T3 restarts it on next use.
      shell("am", "force-stop", "com.callstack.agentdevice.snapshothelper");
      continue;
    }
    const xml = shell("cat", "/sdcard/isdm-verify-ui.xml").stdout ?? "";
    shell("rm", "-f", "/sdcard/isdm-verify-ui.xml");
    return { xml, texts: [...xml.matchAll(/(?:text|content-desc)="([^"]+)"/g)].map((m) => m[1]) };
  }
  return { xml: "", texts: [] };
}

function screenshot(file) {
  const png = adb(serial, ["exec-out", "screencap", "-p"], { encoding: "buffer", maxBuffer: 64 * 1024 * 1024 });
  writeFileSync(file, png.stdout);
  return png.stdout.length;
}

async function capture(name) {
  const run = { width: name, size: (shell("wm", "size").stdout ?? "").trim(), checks: {} };
  const { xml, texts } = await uiTexts();
  writeFileSync(join(out, `signed-out-${name}.xml`), xml);
  run.texts = texts;
  run.checks.appInForeground = xml.includes(`package="${PACKAGE}"`);
  for (const text of SIGNED_OUT) run.checks[`shows "${text}"`] = texts.some((t) => t.includes(text));
  run.screenshot = join(out, `signed-out-${name}.png`);
  run.checks.screenshotSaved = screenshot(run.screenshot) > 10_000;
  run.pass = Object.values(run.checks).every(Boolean);
  console.log(`${run.pass ? "PASS" : "FAIL"} android ${name} (${run.size}) ${JSON.stringify(run.checks)}`);
  return run;
}

const report = { serial, out, runs: [] };
let resized = false;
try {
  shell("am", "start", "-W", "-n", LAUNCH_COMPONENT);
  await sleep(4000);
  report.runs.push(await capture("phone"));
  shell("wm", "size", "1440x900");
  resized = true;
  await sleep(10_000); // the first frames after a resize are black
  shell("am", "start", "-W", "-n", LAUNCH_COMPONENT);
  await sleep(3000);
  report.runs.push(await capture("wide-1440x900"));
} finally {
  if (resized) {
    shell("wm", "size", "reset");
    await sleep(3000);
  }
  report.sizeAfter = (shell("wm", "size").stdout ?? "").trim();
  writeFileSync(join(out, "drive.json"), `${JSON.stringify(report, null, 2)}\n`);
  console.log(`evidence: ${out}; display after reset: ${report.sizeAfter.replace(/\s+/g, " ")}`);
}
process.exit(report.runs.every((r) => r.pass) ? 0 : 1);
