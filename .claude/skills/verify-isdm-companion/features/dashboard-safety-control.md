# Global safety control

"Stop auto attendance for everyone" sets a server block that every phone checks before an automatic mark. The dialog demands the typed word STOP (or ALLOW to lift it).

## Sub-features

- `control-open`: the red "Stop auto attendance for everyone" button opens a dialog titled "Stop auto attendance for everyone?".
- `control-guard`: the submit button stays disabled until the input reads `STOP`.
- `control-cancel`: "Keep auto attendance running" closes the dialog with nothing sent.

## How to get to it (user POV)

Roster page, "GLOBAL SAFETY CONTROL" card, the red button.

## Driving it with Playwright (not scripted yet)

In a Playwright page from `drive-dashboard.mjs`'s setup: click `getByRole("button", { name: "Stop auto attendance for everyone" })`, assert `getByRole("dialog", { name: "Stop auto attendance for everyone?" })` is visible and `getByRole("button", { name: "Stop auto attendance" })` is disabled, then click `getByRole("button", { name: "Keep auto attendance running" })` and assert no dialog remains. Never type STOP.

## Gotchas

- This is the one control that affects every tester at once. Open and cancel only.
