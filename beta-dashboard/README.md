# ISDM Companion Beta Control

Private operations dashboard for the ten-person ISDM Companion Android beta. It shows enrollment and device health, schedule confirmations, attendance decisions, issue reports, the global automatic-attendance safety control, and per-tester pause/restore access controls.

## Local verification

Requires Node.js 22.13 or newer.

```bash
npm install
npm run lint
npm test
```

The production worker reads beta data through the private Supabase `beta-admin` Edge Function. Deployment-specific bindings and secrets belong in the hosting platform, never in this repository.

## Tester access

Open the tester row, enter a reason and the matching tester code, then pause or
restore Companion access. Reasons remain owner-visible and changes are audited.
The server rejects conflicting revisions. Existing apps need an update for full
screen and manual-attendance enforcement. See
[the architecture and rollout review](../docs/performance-access-review.md).

The API accepts only this private Sites project's verified owner. Local previews
show an empty roster until an authenticated request is available; development
mode does not bypass API authorization. Do not expose the worker directly outside
Sites' authenticated ingress.
