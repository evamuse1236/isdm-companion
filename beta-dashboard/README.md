# ISDM Companion Beta Control

Private operations dashboard for the ten-person ISDM Companion Android beta. It shows enrollment and device health, schedule confirmations, attendance decisions, issue reports, and the global automatic-attendance safety control.

## Local verification

Requires Node.js 22.13 or newer.

```bash
npm install
npm run lint
npm test
```

The production worker reads beta data through the private Supabase `beta-admin` Edge Function. Deployment-specific bindings and secrets belong in the hosting platform, never in this repository.
