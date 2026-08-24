# ISDM Companion Team Brief

Use this brief to orient at the start of an ISDM task. It is a map, not runtime proof. Current repository source, tests, Git state, an attached device, the LMS, and Supabase outrank this document.

## Start every task

1. Run `git status --short` and preserve all existing work. Attribute no dirty file to yourself unless the current task changed it. Never discard, reset, push, deploy, sign, install, or communicate externally without the user's authority.
2. Name the system boundary: Android app, LMS, Supabase beta backend, beta dashboard, release/distribution, or connected device.
3. Load the matching project skill, inspect the owning source and tests, and define an observable completion check before changing anything.
4. Treat memory and historical incidents as leads. Recheck paths, versions, live counts, credentials, connected devices, deployments, and external state.
5. Report confirmed evidence, inference, missing evidence, verification performed, and any decision that needs the user.

## Product and system map

- `CONTEXT.md` is the product-language glossary. Preserve distinctions such as Scheduled Session, Attendance Session, Attendance Location Gate, Campus Zone, Location Evidence, Faculty Reference, and Faculty Profile.
- Root Node tooling is limited to guarded beta-release preparation and beta API/release tests. It is not a learner-facing application.
- `android/` is the native Kotlin/Jetpack Compose application for API 26+. The application ID is `org.isdm.companion`; read `android/app/build.gradle.kts` for the current version.
- The LMS at `lms.isdm.org.in` is authoritative for personalized schedules, attendance identity and markability, attendance history, courses, readings, and available faculty data. The app pulls data; it does not receive a live push feed. Cached or displayed state can therefore be stale.
- Android boundaries: `data/` contains LMS gateways/parsers, `domain/` contains product rules, `engine/` coordinates commands and ports, `platform/` contains Android services, WorkManager, storage, diagnostics, beta telemetry, and location evidence, and `ui/` contains Compose/view-model behavior.
- `supabase/functions/beta-api/` ingests beta telemetry and serves enrolled clients. `supabase/functions/beta-admin/` produces owner/admin dashboard data. Migrations define the authoritative stored schema.
- `beta-dashboard/` is the beta operations UI. A plausible UI number is not authoritative until reconciled with admin-function behavior and direct Supabase state.
- `scripts/send-beta-release.mjs` and `.agents/skills/isdm-beta-release/` own approval-bound beta preparation and distribution. Delivery, installation, and telemetry adoption are separate states.

## Verification recipes

- Root beta-platform and release-tooling checks: `npm test`.
- Android JVM/build checks from `android/`: use Java 17 and `bash gradlew testDebugUnitTest lintDebug assembleDebug`. Source and fixture tests do not prove live LMS or device behavior.
- Connected Android checks: first verify `adb devices`; use `bash gradlew connectedDebugAndroidTest` only with an authorized device. Preserve installed app data and signing identity for update tests.
- Dashboard checks from `beta-dashboard/`: `npm test`; inspect its `package.json` for current build/lint commands.
- Live incidents: correlate Android/source evidence, device logs when available, Supabase rows, Edge Function behavior, and dashboard rendering. State which layers were unavailable.

## Project skills

- `isdm-incident-response`: Android, LMS, parser, telemetry, background-service, attendance, crash/ANR, or connected-device diagnosis.
- `isdm-lms-runtime-explainer`: attendance, schedule, course, reading, faculty, refresh, cache, parser, or source-of-truth explanations.
- `isdm-beta-release`: build, sign, verify, update, distribute, roster, invite, delivery, or installation work.
- `isdm-safe-cleanup`: recoverable generated-artifact cleanup while preserving active work.

## Safety and evidence boundaries

- Diagnose before implementing when the request asks for an explanation. A fix request authorizes scoped implementation and proportionate verification, not release or outward communication.
- Device claims require current ADB/device evidence. MTP visibility is not ADB connectivity.
- Live LMS, Supabase, dashboard, roster, release, and tester facts are time-sensitive. Query them when they determine the answer.
- Attendance safety gates are intentional product behavior. Distinguish a prevented Mark action from an app failure, and a local `present` outcome from a server-confirmed mark.
- Releases must preserve package identity, signing lineage, version monotonicity, and tester data. Preview exact recipients, artifact digest, and message before requesting approval.
- Never store or quote passwords, cookies, access tokens, signing secrets, invite codes, private recipient mappings, or raw sensitive LMS payloads in memory.

## Dated diagnostic leads — reverify before use

- 2026-08-18: one attendance-history failure was traced to valid HTTP/JSON with inconsistent LMS totals (`Total != Present + Absent + Not Marked`). The exact parser evidence was collapsed into generic `lms_failure` telemetry before a diagnostic build exposed `protocol_totals_mismatch`.
- 2026-08-18: the beta dashboard's roster sync fields and aggregate attendance presentation did not fully represent authoritative event/attendance state. Reconcile presentation with direct backend semantics rather than repeating historical counts.
- 2026-08-18/19: `BetaManager.flush()` taking one queue snapshot while unique WorkManager upload uses `KEEP` was identified as a credible lost-wakeup/delay path for events enqueued during a successful upload. Inspect the current working tree and tests; do not assume the risk remains unfixed or confirmed in production.
- Connected-phone incidents should begin with ADB and on-device reproduction. Remote telemetry may omit the parser, queue, or platform detail needed to establish cause.

## Team routing

- Ino is the only user-facing bridge. Ino clarifies intent and returns decisions in natural language.
- Head of Engineering owns technical plans, chooses one cheapest-capable specialist by default, integrates results, and verifies them.
- Engineer 1/2 handle bounded implementation; Executor 1/2 handle small mechanical packets; Debugger handles hard diagnosis; Researcher handles fresh external evidence; Designer handles experience/design work.
- Reviewer is an exceptional gate for high-consequence release, security/privacy/auth, permission/migration, data-loss, large cross-module, materially conflicting-evidence, or consequential failed-fix risk. Normal work ends with Head of Engineering verification.
