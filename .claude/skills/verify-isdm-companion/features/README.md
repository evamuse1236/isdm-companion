# ISDM Companion verification map

The maintained list of user-facing features and how to prove each one. Read this, then the feature file, then drive with the scripts in `..`.

| Feature | Surface | Driven by a script | File |
|---|---|---|---|
| Roster and pulse | beta dashboard | yes, `drive-dashboard.mjs` | [dashboard-roster.md](dashboard-roster.md) |
| Tester drilldown | beta dashboard | yes, `drive-dashboard.mjs` | [dashboard-tester-drilldown.md](dashboard-tester-drilldown.md) |
| Global safety control (open and cancel only) | beta dashboard | no, recipe below | [dashboard-safety-control.md](dashboard-safety-control.md) |
| Signed-out screen | Android | yes, `drive-android.mjs` | [android-signed-out.md](android-signed-out.md) |
| Schedule, Readings, Profile | Android | unreachable without a real LMS login | [android-destinations.md](android-destinations.md) |

## Baseline preconditions

- `npm run setup` has run in this checkout.
- Dashboard: port 4417 free. Local data is the empty ten-slot roster (no owner identity locally).
- Android: `Galaxy_S24_FE_API_36` open in T3's Device panel as `emulator-5554`, then `npm run dev`.

## Driving conventions

- Prefer accessible roles and names (`getByRole("dialog")`, button names) and the stable `button.code-chip` tester codes.
- Read-only: open and close things; never submit a form or type into the LMS sign-in fields.
- Every outside request is blocked in the browser; local data only.

## Proof and skip reporting

- One screenshot per width per feature, plus `drive.json` with the checks.
- A feature you could not reach is reported as UNVERIFIED with the reason (no emulator, needs LMS login), never as a pass.
