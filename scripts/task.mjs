#!/usr/bin/env node
// One command per job across the three toolchains (root Node tests, beta-dashboard,
// android). Same behaviour from PowerShell, cmd and Git Bash. Run through npm:
//
//   npm run setup     install dashboard deps + Playwright chromium, check JDK 17+ and the
//                     Android SDK, write android/local.properties, warm Gradle
//   npm test          fast suite: root Node tests, dashboard tests, Android JVM unit tests
//                     (`npm test -- node|dashboard|android` runs one part)
//   npm run verify    the gate before saying done: tests, dashboard lint + typecheck + build,
//                     Android lintDebug + assembleDebug, the dashboard drive at phone and
//                     desktop widths, then an emulator launch smoke (SKIPPED when no emulator)
//   npm run dev       build the debug APK, install it on the emulator, launch, check it runs
//   npm run dev:dashboard -- -p 4418   the beta dashboard dev server on a free port
//
// Never releases, signs, deploys or messages anyone. Device steps use an emulator only.
import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { LAUNCH_COMPONENT, PACKAGE, adb, findEmulator, sdkDir } from "./lib/device.mjs";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const DASHBOARD = join(ROOT, "beta-dashboard");
const ANDROID = join(ROOT, "android");
const APK = join(ANDROID, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const WIN = process.platform === "win32";

// Tests that fail on Windows hosts only, skipped there by exact name and listed in
// AGENTS.md "Known failures". They still run on Linux and macOS.
const WINDOWS_KNOWN_FAILURES = [
  // scripts/send-beta-release.mjs requires recipient files to be mode 600; Windows file
  // modes cannot express that, so the guard refuses every fixture.
  "prepare creates an approval-bound batch for claimed external Android testers",
  "send refuses an approval digest that does not match the exact batch",
  "send stops the batch when one WhatsApp delivery is uncertain",
  "send delivers the approved batch sequentially and records each success",
  "reinstall preparation uses live current-plugin checks and holds unresolved testers",
];
// The Android one (RollingDiagnosticStoreTest) is excluded in android/app/build.gradle.kts.

function run(cmd, args, { cwd = ROOT, env } = {}) {
  console.log(`\n$ ${[cmd, ...args].join(" ")}${cwd === ROOT ? "" : `   (in ${cwd})`}`);
  const options = { cwd, stdio: "inherit", windowsHide: true, env: env ?? process.env };
  // Node refuses to spawn .cmd/.bat without a shell; the arguments here are fixed strings.
  const result = /\.(cmd|bat)$/i.test(cmd)
    ? spawnSync(`"${cmd}" ${args.join(" ")}`, { ...options, shell: true })
    : spawnSync(cmd, args, options);
  if (result.error) console.error(result.error.message);
  return result.status ?? 1;
}

function npm(args, cwd) {
  // npm sets npm_execpath when it runs this file; call its CLI with this Node directly.
  const cli = process.env.npm_execpath;
  return cli && cli.endsWith(".js") ? run(process.execPath, [cli, ...args], { cwd }) : run(WIN ? "npm.cmd" : "npm", args, { cwd });
}

function gradle(...tasks) {
  const wrapper = WIN ? join(ANDROID, "gradlew.bat") : join(ANDROID, "gradlew");
  // --no-daemon: no Gradle process outlives the command (and none can open a window later).
  return run(WIN ? wrapper : "sh", [...(WIN ? [] : [wrapper]), "--no-daemon", "--console=plain", ...tasks], { cwd: ANDROID });
}

function javaMajor() {
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", WIN ? "java.exe" : "java") : "java";
  const out = spawnSync(java, ["-version"], { encoding: "utf8", windowsHide: true });
  const match = /version "(\d+)/.exec(`${out.stderr}${out.stdout}`);
  return match ? Number(match[1]) : null;
}

function checkNode() {
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major < 22 || (major === 22 && minor < 13)) {
    console.error(`Node.js 22.13 or newer is required (found ${process.versions.node}).`);
    return 1;
  }
  return 0;
}

function checkAndroidToolchain() {
  const major = javaMajor();
  if (!major || major < 17) {
    console.error(`JDK 17 or newer is required (found ${major ?? "none"}). Install one and set JAVA_HOME.`);
    return 1;
  }
  const sdk = sdkDir();
  const props = join(ANDROID, "local.properties");
  if (!sdk && !existsSync(props)) {
    console.error("No Android SDK found. Install it (API 36 build tools) and set ANDROID_HOME.");
    return 1;
  }
  if (sdk && !existsSync(props)) {
    writeFileSync(props, `sdk.dir=${sdk.replace(/\\/g, "\\\\").replace(/:/g, "\\:")}\n`);
    console.log(`wrote android/local.properties (sdk.dir=${sdk}; git-ignored)`);
  }
  console.log(`JDK ${major}; Android SDK ${sdk ?? "from android/local.properties"}`);
  return 0;
}

function setup() {
  return checkNode()
    || npm(["ci"], DASHBOARD)
    || run(process.execPath, [join(DASHBOARD, "node_modules", "playwright-core", "cli.js"), "install", "chromium"])
    || checkAndroidToolchain()
    || gradle(":app:dependencies", "--configuration", "debugUnitTestRuntimeClasspath", "-q");
}

function nodeTests() {
  const skips = WIN ? WINDOWS_KNOWN_FAILURES.map((name) => `--test-skip-pattern=^${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`) : [];
  if (WIN) console.log(`\nKNOWN FAILURES SKIPPED on Windows (${skips.length}): send-beta-release fixture tests; see AGENTS.md Known failures.`);
  const files = readdirSync(join(ROOT, "tests")).filter((f) => f.endsWith(".test.mjs")).map((f) => join("tests", f));
  return run(process.execPath, ["--experimental-strip-types", "--test", ...skips, ...files]);
}

const dashboardTests = () => npm(["test"], DASHBOARD);
const androidTests = () => checkAndroidToolchain() || gradle("testDebugUnitTest");

function test(part) {
  const parts = { node: nodeTests, dashboard: dashboardTests, android: androidTests };
  if (part && !parts[part]) {
    console.error(`Unknown part "${part}". Use one of: ${Object.keys(parts).join(", ")}.`);
    return 2;
  }
  for (const [name, fn] of Object.entries(parts)) {
    if (part && name !== part) continue;
    const code = fn();
    if (code) return code;
  }
  return 0;
}

function crashCount(serial) {
  return (adb(serial, ["logcat", "-d", "-b", "crash"]).stdout ?? "").split(`Process: ${PACKAGE}`).length - 1;
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** Install the debug APK on the emulator, launch it, and prove it is running without a crash. */
function launchOnEmulator(serial) {
  if (!existsSync(APK)) {
    console.error(`No debug APK at ${APK}; run gradle assembleDebug first.`);
    return 1;
  }
  console.log(`\n$ adb -s ${serial} install -r app-debug.apk`);
  const install = adb(serial, ["install", "-r", APK]);
  const said = `${install.stdout}${install.stderr}`.trim();
  console.log(said);
  if (said.includes("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
    console.error("The installed ISDM Companion has a different signature. Uninstalling wipes its data: ask the owner first; never uninstall on your own.");
    return 1;
  }
  if (install.status !== 0 || !said.includes("Success")) return install.status || 1;
  const before = crashCount(serial);
  const start = adb(serial, ["shell", "am", "start", "-W", "-n", LAUNCH_COMPONENT]);
  console.log((start.stdout ?? "").trim());
  let pid = "";
  for (let waited = 0; waited < 30_000 && !pid; waited += 2000) {
    pid = (adb(serial, ["shell", "pidof", PACKAGE]).stdout ?? "").trim();
    if (!pid) sleep(2000);
  }
  sleep(6000); // long enough for startup work, where a crash would show
  pid = (adb(serial, ["shell", "pidof", PACKAGE]).stdout ?? "").trim();
  if (!pid || crashCount(serial) > before) {
    console.error((adb(serial, ["logcat", "-d", "-b", "crash"]).stdout ?? "").slice(-3000));
    console.error(`ISDM Companion is not running on ${serial} after launch (crash log above).`);
    return 1;
  }
  console.log(`ISDM Companion is running on ${serial} (pid ${pid}). Drive it with the verify-isdm-companion skill.`);
  return 0;
}

function verify() {
  // Refuse a wrong ANDROID_SERIAL before minutes of building, not after.
  const device = process.env.ANDROID_SERIAL ? findEmulator() : null;
  if (device?.error && !device.missing) {
    console.error(device.error);
    return 1;
  }
  const steps = [
    nodeTests,
    () => npm(["run", "lint"], DASHBOARD),
    () => npm(["run", "typecheck"], DASHBOARD),
    dashboardTests,
    () => checkAndroidToolchain() || gradle("testDebugUnitTest", "lintDebug", "assembleDebug"),
    () => run(process.execPath, [join(ROOT, ".claude", "skills", "verify-isdm-companion", "drive-dashboard.mjs")]),
  ];
  for (const step of steps) {
    const code = step();
    if (code) return code;
  }
  const emulator = device ?? findEmulator();
  if (emulator.serial) return launchOnEmulator(emulator.serial);
  if (emulator.missing) {
    console.log(`\nSKIPPED: emulator launch smoke. ${emulator.error}`);
    return 0;
  }
  console.error(emulator.error);
  return 1;
}

function dev() {
  const emulator = findEmulator();
  if (!emulator.serial) {
    console.error(emulator.error);
    return 1;
  }
  return checkAndroidToolchain() || gradle("assembleDebug") || launchOnEmulator(emulator.serial);
}

const jobs = { setup, test: () => test(process.argv[3]), verify, dev };
const job = process.argv[2];
if (!jobs[job]) {
  console.error(readFileSync(fileURLToPath(import.meta.url), "utf8").split("\n").slice(1, 15).join("\n"));
  process.exit(2);
}
const started = Date.now();
const code = jobs[job]();
console.log(`\n${job}: ${code === 0 ? "ok" : `FAILED (exit ${code})`} in ${Math.round((Date.now() - started) / 1000)} s`);
process.exit(code);
