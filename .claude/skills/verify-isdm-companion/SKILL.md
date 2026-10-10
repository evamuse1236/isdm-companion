---
name: verify-isdm-companion
description: Drive ISDM Companion for real and capture proof. Two surfaces - the Android app on the T3 Device panel emulator (signed-out screen; everything else needs a real LMS login) and the owner beta dashboard (vinext web UI) through Playwright at phone 390x844 and desktop 1440x900. Use after changing the Android UI or the beta-dashboard, or when asked to show either working.
---

# verify-isdm-companion

Two drivers, each a Node script in this folder. Both start what they need, check it, save evidence, and clean up after themselves. Read `features/README.md` for the feature map before driving.

Hard limits (they come from the owner, not from this skill):

- Emulator only. Never pass the owner's phone serial (`RZCY810547H`) anywhere; `scripts/lib/device.mjs` refuses any serial that is not `emulator-NNNN` reporting `ro.kernel.qemu=1`.
- Never type LMS credentials, tap **Sign in**, or mark attendance. Never submit a dashboard form (pause/restore access, stop/allow auto attendance, triage, suspected marks): the deployed dashboard controls real testers.
- If an install says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and ask the owner. Uninstalling wipes the app's data.
- Nothing opens a visible terminal window: spawns use `windowsHide`, never a raw `detached: true` on Windows.

## Launch

Beta dashboard: `drive-dashboard.mjs` launches its own `vinext dev` on port 4417 (windowless child), waits for `GET /` to answer 200 (first compile takes up to a minute), drives, then stops it. Nothing to start by hand.

Android:

1. Open `Galaxy_S24_FE_API_36` in T3's Device panel (`device_list`, then `device_open`). Never start an emulator from a shell. A sub-agent cannot open the panel; ask the thread that owns it.
2. `npm run dev` from the repo root: builds the debug APK, installs it on the emulator, launches it, and checks it is still running 6 s later with no new crash-log entry.

## Doctor

- Dashboard: `npm run setup` ran (needs `beta-dashboard/node_modules/playwright-core` and Chromium in `%LOCALAPPDATA%\ms-playwright`). Port 4417 must be free; the driver refuses to drive a server it did not start.
- Android: `adb devices` lists `emulator-5554` as `device`; `adb -s emulator-5554 shell getprop ro.kernel.qemu` prints `1`; `adb -s emulator-5554 shell pidof org.isdm.companion` prints a pid after `npm run dev`.

## Drive

```bash
node .claude/skills/verify-isdm-companion/drive-dashboard.mjs --out <evidence-dir>/dashboard   # both widths
node .claude/skills/verify-isdm-companion/drive-android.mjs --out <evidence-dir>/android       # phone, then wm size 1440x900
```

Each prints one `PASS`/`FAIL` line per width with its checks, and exits non-zero on any failure. Without `--out`, evidence goes to `.verify/` (git-ignored). `drive-android.mjs` prints `SKIPPED` and exits 0 when no emulator is running; say so in your report as UNVERIFIED, not as a pass.

`npm run verify` runs the dashboard drive and the Android launch smoke for you; it does not run `drive-android.mjs`.

## Evidence

- Dashboard: `dashboard-phone.png`, `dashboard-desktop.png`, `drilldown-phone.png`, `drilldown-desktop.png`, `drive.json` (checks, console errors, blocked outside requests), `dev-server.log`.
- Android: `signed-out-phone.png`, `signed-out-wide-1440x900.png`, the matching uiautomator `.xml`, `drive.json` (screen size before and after reset).
- Open at least one screenshot per width and confirm it shows the feature, not a blank or error screen.
- Proof standard: drive the real UI path (clicks, launcher intent), capture the state after the action, not only the first screen. Local dashboard data is empty by design (`GET /api/dashboard` answers 401 without the owner's Sites identity; the console error for it is expected), and every non-localhost request is aborted and listed in `drive.json`.

## Cleanup

- The dashboard driver kills the dev server tree it started (`taskkill /T` by pid on Windows, its process group elsewhere). Check: nothing LISTENING on 4417.
- The Android driver always runs `wm size reset` after the wide pass and records the size afterwards in `drive.json`. If a run dies mid-way, run `adb -s emulator-5554 shell wm size reset` yourself.
- Leave the debug app installed (reinstalling over it keeps its data). Evidence folders are never deleted by cleanup.

## Gotchas

- `uiautomator dump` exiting 137 means T3's snapshot helper holds UiAutomation: the driver force-stops `com.callstack.agentdevice.snapshothelper` once and retries.
- After `wm size`, the first frames are black; the driver waits 10 s.
- With the phone and the emulator both attached, bare `adb` fails with "more than one device"; always pass `-s emulator-5554` or set `ANDROID_SERIAL`.
- The debug build talks to the production beta API (`BETA_API_BASE` in `android/app/build.gradle.kts`) once someone signs in and joins nightly. Staying on the signed-out screen sends nothing.

Keep the map honest as the app changes with `/maintain-verification-skill`.
