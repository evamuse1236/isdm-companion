#!/usr/bin/env node
// Drive the installed debug app on the emulator at its own phone size and at 1440x900
// (`wm size`, reset afterwards). A fresh install first shows the 3-page welcome tour
// (a local preference, nothing is sent): the driver captures it, taps through it, then
// checks the signed-out screen, the only screen reachable without a real LMS login.
// Never types credentials, never taps Sign in.
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

const TOUR_BUTTONS = ["Next", "Continue"];

/** Tap through the welcome tour if it is showing; returns the tour pages seen. */
async function passTour(name) {
  const pages = [];
  for (let step = 0; step < 5; step++) {
    const { xml, texts } = await uiTexts();
    if (!texts.includes("Skip tour")) break;
    pages.push(texts.find((t) => / of \d+$/.test(t)) ?? "?");
    if (step === 0) screenshot(join(out, `tour-${name}.png`));
    const button = [...xml.matchAll(/text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g)]
      .find((m) => TOUR_BUTTONS.includes(m[1]));
    if (!button) break;
    const [x1, y1, x2, y2] = button.slice(2).map(Number);
    shell("input", "tap", String(Math.round((x1 + x2) / 2)), String(Math.round((y1 + y2) / 2)));
    await sleep(1500);
  }
  return pages;
}

async function capture(name) {
  const run = { width: name, size: (shell("wm", "size").stdout ?? "").trim(), checks: {} };
  run.tourPages = await passTour(name);
  if (run.tourPages.length) run.checks["tour ends on its last page"] = /^(\d+) of \1$/.test(run.tourPages.at(-1));
  const { xml, texts } = await uiTexts();
  writeFileSync(join(out, `signed-out-${name}.xml`), xml);
  run.texts = texts;
  run.checks.appInForeground = xml.includes(`package="${PACKAGE}"`);
  run.screenshot = join(out, `signed-out-${name}.png`);
  run.checks.screenshotSaved = screenshot(run.screenshot) > 10_000;
  // On a short screen (1440x900) the form runs below the fold: scroll once and look again.
  let seen = texts;
  run.belowFold = SIGNED_OUT.filter((text) => !texts.some((t) => t.includes(text)));
  if (run.belowFold.length) {
    const [width, height] = (run.size.match(/(\d+)x(\d+)\s*$/) ?? [0, 1080, 2340]).slice(1).map(Number);
    shell("input", "swipe", String(width / 2), String(Math.round(height * 0.8)), String(width / 2), String(Math.round(height * 0.2)), "400");
    await sleep(1500);
    const scrolled = await uiTexts();
    writeFileSync(join(out, `signed-out-${name}-scrolled.xml`), scrolled.xml);
    screenshot(join(out, `signed-out-${name}-scrolled.png`));
    seen = [...texts, ...scrolled.texts];
  }
  for (const text of SIGNED_OUT) run.checks[`shows "${text}"`] = seen.some((t) => t.includes(text));
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
