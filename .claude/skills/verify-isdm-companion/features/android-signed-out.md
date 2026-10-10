# Signed-out screen (Android)

The first screen of a fresh install: "Your ISDM companion", the line "Schedule, attendance and course readings—calmly in one place.", the "LMS email" and "LMS password" fields, the "Sign in" button and the encrypted-login note. Source: `LoginContent` in `android/app/src/main/java/org/isdm/companion/ui/MainActivity.kt`.

## Sub-features

- `signed-out-render`: the screen draws at phone size and at 1440x900 without a crash.
- `signed-out-debug-logs`: "Save debug logs" is offered here too (Android's file picker; not driven).

## How to get to it (user POV)

Install the app and open it from the launcher (component `org.isdm.companion/.LauncherActivity`).

## Driving it with drive-android.mjs

- Preconditions: emulator open in the Device panel, `npm run dev` passed.
- Run `node .claude/skills/verify-isdm-companion/drive-android.mjs --out <dir>`. It launches the app, dumps the UI with uiautomator, checks the app is in front and the four texts are present, screenshots, sets `wm size 1440x900`, waits 10 s, repeats, and resets the size.
- Proof: `signed-out-phone.png`, `signed-out-wide-1440x900.png`, the `.xml` dumps, `drive.json`.

## Gotchas

- If a system permission prompt covers the app, the text checks fail; record the screenshot and handle the prompt by hand on the emulator (notifications and location prompts belong to setup after sign-in, so expect none here).
- An emulator that already has a signed-in app shows Schedule instead; that run cannot prove this screen. Do not sign out of someone's session to get here.
