# Tester drilldown

Clicking a tester code opens a dialog with that tester's profile, cohort, schedule, auto-attendance state, last seen and sync, plus the per-tester access and auto-attendance forms.

## Sub-features

- `drill-open`: the code chip (`button.code-chip`, title "Open T-01 drilldown") opens a `role="dialog"` headed "Awaiting profile (T-01)" when empty.
- `drill-close`: the dialog's "Close" button removes it.
- `drill-forms`: "Pause access" / "Stop auto attendance" forms need a reason and the typed tester code. Never submit them.

## How to get to it (user POV)

On the roster, click a tester's code chip (T-01 to T-10).

## Driving it with drive-dashboard.mjs

- The driver clicks `button.code-chip` with text `T-01`, checks the dialog has a visible heading containing T-01, screenshots it, then clicks the dialog's `Close` button and checks no dialog remains.
- Proof: `drilldown-phone.png`, `drilldown-desktop.png`, checks `drilldownOpens`, `drilldownNamesT01`, `drilldownCloses` in `drive.json`.

## Gotchas

- On a phone the dialog fills the screen and scrolls; the forms are below the fold.
- Submitting a form locally answers 401, but the same click against the deployed dashboard pauses a real tester. Keep the driver read-only.
