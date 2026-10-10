# Roster and pulse

The dashboard's main page: the paused-collection banner, attendance safety, the global control card, today's pulse tiles, and the ten-slot tester roster with filters.

## Sub-features

- `roster-header`: "PRIVATE BETA COMMAND DESK", "ISDM Companion Beta Control", clock, owner chip, Refresh.
- `roster-banners`: "Data collection remains paused" and, locally, "Dashboard request failed (401)".
- `roster-table`: rows T-01 to T-10 with "Awaiting profile" when empty; Section, PLC, Seen filters and a search box (placeholder "T-04 or Galaxy").

## How to get to it (user POV)

Open the dashboard URL. Locally: `npm run dev:dashboard -- -p 4418`, then http://localhost:4418 (the driver starts its own server on 4417).

## Driving it with drive-dashboard.mjs

- Run `node .claude/skills/verify-isdm-companion/drive-dashboard.mjs --out <dir>`.
- Checks per width: `GET /` is 200, the heading is visible, `button.code-chip` exists for T-01 and T-10, no `vite-error-overlay`, no sideways scroll (`scrollWidth <= innerWidth`).
- Proof: `dashboard-phone.png`, `dashboard-desktop.png`, `drive.json`.

## Gotchas

- Real roster data needs the owner's Sites identity and the private `beta-admin` function; it never appears locally. Do not try to supply secrets.
- First load compiles for up to a minute; the driver waits up to 120 s for 200.
