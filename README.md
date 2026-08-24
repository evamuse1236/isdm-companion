# ISDM Companion

ISDM Companion is a native Android app for a learner's ISDM schedule, readings, attendance,
and profile. The production product has one interface with three destinations:

- **Schedule** for personal sessions, rooms, trainers, assessments, and attendance actions.
- **Readings** for course material, assessment resources, and local Done/Undo state.
- **Profile** for confirmed Section/PLC details, LMS attendance progress, and automatic-attendance
  setup.

The Android app is the product direction and UI source of truth. Historical design prototypes and
feature-option documents are intentionally not kept beside production code; release history belongs
in [`CHANGELOG.md`](CHANGELOG.md).

## Repository map

| Path | Purpose |
| --- | --- |
| `android/` | Production Kotlin/Jetpack Compose Android app and tests |
| `supabase/` | Beta enrollment, profile, telemetry, and owner API functions |
| `beta-dashboard/` | Owner-facing beta health dashboard; not an alternate learner UI |
| `scripts/` | Guarded beta release preparation and verification |
| `.agents/skills/` | Project safety workflows for incidents, releases, cleanup, and LMS explanation |
| `src/`, `public/`, `tools/` | Legacy local desktop companion retained for existing users |
| `tests/` | Desktop and beta-platform tests |
| `docs/` | Current operational documentation only |

The legacy desktop client and beta dashboard remain functional, but they do not define new Android
product or visual direction.

## Android development

Requirements:

- JDK 17
- Android SDK with API 36 build tools
- An Android 8.0/API 26 or newer device or emulator

From `android/`, run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/usr/lib/android-sdk \
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

See [`android/README.md`](android/README.md) for runtime behavior, diagnostic logs, background
limits, and update requirements.

## Beta releases

A beta requires a higher Android `versionCode`, updated in-app release notes, a changelog entry,
the permanent beta signing identity, APK identity/signature verification, and an in-place update
test that retains app data.

Release preparation and tester distribution are separate actions. Distribution requires an exact
recipient/artifact preview and explicit approval; see
[`docs/beta-release-script.md`](docs/beta-release-script.md).

## Legacy desktop client

The dependency-free Node.js desktop client remains available for existing users:

```bash
npm run setup
npm start
```

It reads local `.env` configuration and serves `http://localhost:4321`. New learner-facing work
belongs in the Android app unless the desktop client is explicitly placed back in scope.

Run its tests with:

```bash
npm test
```

## Safety boundaries

- Never commit LMS credentials, cookies, signing material, invite plaintext, or `.release-private/`.
- Treat LMS cohort detection and confirmed profile details as account-scoped data.
- Do not claim a release, deployment, tester installation, or message delivery without direct
  evidence for that exact action.
- Keep screenshots, rendered prototypes, and other temporary design evidence outside the repository.

## Licence

MIT — see [`LICENSE`](LICENSE).
