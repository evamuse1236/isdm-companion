# Working on ISDM Companion

ISDM Companion is a native Android app (Kotlin, Jetpack Compose, API 26+) that puts a learner's ISDM LMS schedule, attendance, readings and profile in one place. Beside it live the Supabase beta backend (`supabase/`), the owner's beta dashboard (`beta-dashboard/`, a vinext web app) and guarded release tooling (`scripts/`).

Start every task with `.agents/ISDM_TEAM_BRIEF.md`: it is the system map, the safety boundaries and the routing to the project skills in `.agents/skills/` (`isdm-incident-response`, `isdm-lms-runtime-explainer`, `isdm-beta-release`, `isdm-safe-cleanup`). The owner does not read code: prove changes on the real surface and show the evidence.

## Commands

Run from the repo root in PowerShell, cmd or Git Bash. Needs Node 22.13+, JDK 17+ (21 works) and the Android SDK (API 36).

| Job | Command | What it does |
|---|---|---|
| setup | `npm run setup` | `npm ci` in `beta-dashboard/`, Playwright Chromium, checks the JDK and SDK, writes `android/local.properties`, caches Gradle dependencies. No secrets needed. |
| test | `npm test` | Root Node tests (beta API, release tooling, contracts), dashboard build + tests, Android JVM unit tests. One part: `npm test -- node`, `-- dashboard` or `-- android`. |
| verify | `npm run verify` | The gate before saying done: root tests, dashboard lint + typecheck + tests, Android `testDebugUnitTest lintDebug assembleDebug`, the dashboard drive at 390x844 and 1440x900, then an emulator launch smoke (prints `SKIPPED` when no emulator is open). |
| dev | `npm run dev` | Builds the debug APK, installs it on the emulator, launches it, checks it stays up. Needs the emulator open (below). |
| dashboard | `npm run dev:dashboard -- -p 4418` | Beta dashboard dev server (default port 3000, often taken on this machine by another app; pick a free one). Locally the roster is empty: the API answers 401 without the owner's Sites identity. |
| drive | `verify-isdm-companion` skill (`.claude/skills/verify-isdm-companion/`) | Drives the dashboard and the Android signed-out screen and saves screenshots. |

Measured warm on this machine: `npm test` 28 s, `npm run verify` 2 minutes. A cold Gradle build takes about 5 minutes.

Android emulator: open `Galaxy_S24_FE_API_36` in T3's Device panel (`device_list`, then `device_open`); it appears as `emulator-5554`. Never start an emulator from a shell.

The runner is `scripts/task.mjs` (Node, no dependencies). Gradle directly: `android\gradlew.bat --no-daemon testDebugUnitTest` (PowerShell) or `./gradlew` from `android/` (Git Bash).

## Where things live

- `android/app/src/main/java/org/isdm/companion/`: `data/` LMS gateways and parsers, `domain/` product rules, `engine/` commands and ports, `platform/` Android services, WorkManager, storage, diagnostics, beta telemetry (`BetaApiClient`, `BetaManager`), location evidence; `ui/` Compose screens (`MainActivity.kt`) and view-models. Tests in `android/app/src/test` (JVM, fixtures, MockWebServer) and `androidTest` (needs a device; not in `verify`).
- `supabase/functions/beta-api/` (phone-facing: enroll, nightly-enroll, config, profile, events, report, auto-preflight), `beta-admin/` (owner dashboard data), `_shared/`; `supabase/migrations/` is the stored schema.
- `beta-dashboard/app/` (page, `Dashboard.tsx`, `api/dashboard/route.ts`), `worker/index.ts`, tests in `beta-dashboard/tests/`.
- `scripts/send-beta-release.mjs` + `scripts/lib/release-policy.mjs`: approval-bound WhatsApp release delivery. `scripts/task.mjs`: the jobs above. `scripts/lib/device.mjs`: the one way to pick an Android device.
- `tests/`: root Node tests; they load the real Edge Function handlers in a VM with a fake database.
- `docs/`: current operational notes, incidents and release checks. `CHANGELOG.md`: release history.

## Words

The product glossary is `CONTEXT.md` (Scheduled Session, Attendance Session, Mark action, Attendance Location Gate, Campus Zone, Location Evidence, Trainer, Faculty Reference, Faculty Profile, Issue Report, LMS). Words not defined there:

- **Beta / nightly installation**: a phone registered with `beta-api`. Beta testers have `T-NN` codes from an invite; nightly installations (`N-000001`) register code-free after LMS sign-in (`docs/nightly-enrollment.md`).
- **Collection pause**: the server keeps beta telemetry routes paused (`supabase/functions/beta-api/collection-policy.mjs`); paused routes answer `{saved:false, collection_paused:true}`.
- **Tester access (pause/restore)** and **auto-attendance block**: owner controls in the dashboard; phones check them in `config` and `auto-preflight`.
- **Install mode** (`update` or `signing-reset`) and **signing lineage**: a release either updates in place with the same key or forces testers to reinstall and lose data. Always explicit, never guessed.
- **Done/Undo**: a reading's local completion state; never written to the LMS.

## Rules

| Rule | Enforced by |
|---|---|
| Never release, sign, deploy (dashboard, Supabase), publish, install on testers' phones, or message anyone (`npm run beta:release`, WhatsApp) without the owner's explicit go for that exact action. A fix request authorizes code and local checks only. | Written only (`.agents/ISDM_TEAM_BRIEF.md`). `npm run setup/test/verify/dev` never call release, signing or messaging code. |
| Agents touch the emulator only, never the owner's phone (`RZCY810547H`). | `scripts/lib/device.mjs` refuses any serial that is not `emulator-NNNN` reporting `ro.kernel.qemu=1`, before running anything on it; `tests/device-guard.test.mjs`. |
| If an install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and ask. Uninstalling wipes the app's saved login and data. | `scripts/task.mjs` stops with that message; otherwise written only. |
| Every success status the beta API returns to the phone is one `BetaApiClient` accepts. (Incident 2026-09-11: paused profile POSTs answered 202, the app accepted 200/201, profile confirmation failed on every phone.) | `tests/beta-api-client-contract.test.mjs` reads the accepted statuses from the Kotlin source and drives the real `beta-api` handler; plus the paused-profile tests in `tests/beta-access-handlers.test.mjs`. |
| `.cmd` and `.bat` files stay CRLF; cmd.exe mis-parses LF-only scripts (2f7fc12). | `.gitattributes`; `tests/windows-launchers.test.mjs`. |
| Automatic attendance is ready only when precise location, notifications, background location and exact alarms are all granted; location alone never completes setup (a92fa1a). | `BetaSetupPolicyTest` (`android/app/src/test/.../ui/`). |
| A release's install mode is explicit, never inferred from a filename; an ambiguous send is reconciled, never retried blindly; batch journals are written atomically. | `tests/review-release-policy.test.mjs`. |
| Never repeat an LMS POST (attendance mark) blindly; attendance safety gates are product behaviour, and a local `present` is not a server-confirmed mark. | Written only (`.agents/ISDM_TEAM_BRIEF.md`, `RealLmsAdapter.kt`). |
| Never commit LMS credentials, cookies, signing material, invite codes, recipient maps or `.release-private/`. | `.gitignore` (`.env`, `.release-private/`, `.release-batches/`, `android/local.properties`); otherwise written only. |
| Nothing an agent starts opens a visible window on Windows: spawn with `windowsHide`, never a raw `detached: true` on Windows; stop only processes you started, by pid. | `scripts/task.mjs` and the verify drivers follow it; written only. |

## Verify a change

1. `npm run verify`.
2. Android UI: open the emulator in the Device panel, `npm run dev`, then drive with the `verify-isdm-companion` skill. Everything past the signed-out screen needs a real LMS login: never sign in or mark attendance; report those screens as unverified.
3. Dashboard UI: the same skill, `drive-dashboard.mjs`, at both widths.

## Known failures

Both pass on Linux (the owner's documented runs) and fail on Windows hosts only. They are skipped on Windows by exact name and print a `KNOWN FAILURE(S) SKIPPED` line.

- 5 tests in `tests/send-beta-release.test.mjs` ("prepare creates an approval-bound batch...", "send refuses an approval digest...", "send stops the batch...", "send delivers the approved batch...", "reinstall preparation uses live current-plugin checks..."): the release script requires recipient files to be mode 600, which Windows cannot express. List: `WINDOWS_KNOWN_FAILURES` in `scripts/task.mjs`. `npm run test:node` runs them unfiltered.
- `RollingDiagnosticStoreTest` "keeps exact seven day boundary and recycles earlier lines within same day": `File.renameTo` cannot replace an existing file on Windows, so `RollingDiagnosticStore.prune` fails its `check`. Android replaces atomically. Excluded in `android/app/build.gradle.kts`.
- Open question for the owner, not a test failure: during the collection pause, `events`, `schedule`, `attendance` and `lms-diagnostic` answer 202, which `BetaApiClient` treats as a failure, so beta phones keep those items queued and retry. Listed in `KNOWN_UNACCEPTED_SUCCESSES` in the contract test.

## Gotchas

- `core.autocrlf=true` on this machine checks most files out with CRLF; Git normalizes them back on commit.
- With the phone and the emulator both attached, bare `adb` fails with "more than one device"; pass `-s emulator-5554`.
- `uiautomator dump` exiting 137: run `adb -s emulator-5554 shell am force-stop com.callstack.agentdevice.snapshothelper` and retry. After `adb shell wm size`, wait about 10 s before a screenshot; reset with `wm size reset`.
- The debug build uses the production `BETA_API_BASE`. Signing in and joining nightly on the emulator would register a real installation.
- `npm` 12 blocks install scripts for esbuild and workerd; the dashboard builds and tests anyway.
- The dashboard's `start` script has no Wrangler log-path prefix any more (it broke cmd.exe); `vite.config.ts` sets `WRANGLER_WRITE_LOGS=false` and a project-local log path for `dev` and `build`.
