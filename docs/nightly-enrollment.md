# Companion Nightly enrollment

Released backend for `0.5.8-nightly` (version code 16) on 26 September 2026 at the owner's request. Code-free registration is live: the nightly migration and both Edge Functions were deployed and verified with a disposable registration. See [final release checks](release-0.5.8-final-checks.md).

## Behavior

After LMS sign-in, a new installation provides its support name, section, optional PLC, and explicit roster consent. No invitation code is requested. The small “night owls” welcome adds character without changing attendance behavior.

The Android client saves a random installation UUID and 256-bit credential in encrypted preferences **before** its first registration request. A timeout/retry uses the same credentials. The backend hashes the credential, atomically reuses an existing matching registration, or allocates an `N-000001`-style code. Reusing the UUID with a different credential fails; suspended registrations cannot enroll again through retry.

Suspension identifies an installation, not a verified person. Reinstalling and creating a new registration can evade it. Names are self-reported. Existing beta installations retain their IDs, credentials, audit history, and suspension state; this release does not automatically rotate or migrate them.

The owner dashboard's existing roster and audited access/automatic-attendance controls support nightly IDs. Enrollment keeps name, section/PLC, device model, version, and registration/access times. The config check refreshes last access and version code. Names can be deleted in Profile and expire after 180 days without access. The original beta-name retention policy continues to apply only to beta rows. Other backend telemetry remains paused; nightly clients do not enqueue or upload diagnostic/attendance telemetry. Phone-local diagnostics and location gates continue to work.

## Deployment order (completed 26 September 2026)

1. Apply `supabase/migrations/20260920093643_nightly_enrollment.sql`.
2. Deploy `beta-admin` and `beta-api`, including their local shared imports. Preserve current `verify_jwt=false`: these endpoints use their existing owner-secret / installation-token authentication. Only `nightly-enroll` is public.
3. Smoke-test a clearly labelled disposable registration, retry, rejection with wrong credentials, owner suspension/restoration and profile deletion. Remove the test row afterwards. Do not test against a real tester's access.
4. Publish/distribute the signed APK only after backend verification.

The database registration RPC is SECURITY INVOKER and executable only by service_role. Existing table RLS stays enabled. Registration bodies are limited to 4 KiB. A transaction-level lock enforces a global quota of 1,000 new registrations per UTC database day; retries remain available at the quota. This is a storage-growth bound, not person verification or complete bot protection.

## Local verification

- `npm test`: 64 passing tests, including endpoint validation, hashed credentials, owner controls, input bounds and existing access/collection-pause behavior.
- `scripts/check-nightly-migration.mjs` with isolated PGlite: actual migration SQL, service-role permissions, retry identity, token conflict, suspension/restore, revision checks, automatic-attendance controls, retention separation, inactivity deletion and quota passed. The local cron stub captures and executes the scheduled SQL; it does not test the hosted cron scheduler.
- Dashboard typecheck, lint, build and all 15 tests passed.
- Android JVM: 209 tests passed. Debug/release assembly and lint passed.
- Signing, emulator update and rendered checks are recorded in `release-0.5.8-verification.md`.
