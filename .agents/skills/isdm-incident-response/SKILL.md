---
name: isdm-incident-response
description: Diagnose ISDM Companion Android, LMS, Supabase telemetry, background-service, attendance, parser, and connected-phone failures. Use for incident reviews, debug APK errors, app-not-working reports, attendance failures, crash or ANR questions, runtime reliability work, and requests to inspect a connected test device.
---

# ISDM Incident Response

Build a timestamped evidence chain across source, tests, device state, and telemetry. Keep diagnosis separate from implementation unless the user asks for a fix.

## Workflow

1. Establish the exact time window, app package, APK version, device, observed symptom, and expected behavior. Record Asia/Kolkata and UTC when correlating systems. Finish when every evidence source uses the same incident window.
2. Preserve the current checkout and phone data. Inspect Git status before builds or edits. Avoid uninstalling, clearing app data, clearing Logcat, changing permissions, force-stopping, or relaunching during the first evidence pass.
3. Resolve bundled script paths from the directory containing this `SKILL.md`, and verify them with `test -f` rather than Git tracking. For a connected device, run `scripts/collect-isdm-diagnostics.sh`. Keep its output private and cite the exact collected files used.
4. Trace the owning source path and the nearest tests. For attendance, trace the Location Gate, LMS markability check, mark request, and post-mark LMS confirmation. For freshness, trace request, parser, memory cache, persistent cache, and refresh scheduler separately.
5. Use the evidence ladder below. Correlate independent sources before naming a root cause.
6. Reproduce the smallest failing contract. Run `scripts/verify-android.sh` for JVM, lint, and debug-build checks. Use connected instrumentation only with its explicit data-risk acknowledgement.
7. If a fix is authorized, write a regression test first when practical, make the smallest change, and rerun the relevant level of the ladder.
8. Report confirmed facts, likely inference, excluded causes, remaining gaps, and the next decisive check. Finish only when each conclusion points to evidence and each gap remains explicit.

## Evidence ladder

Rank evidence from strongest to weakest for the claim being made:

1. LMS confirmation after the action, or a captured authoritative server response.
2. Reproduction on the connected device with private app diagnostics and Android state.
3. Connected instrumentation for the Android integration contract.
4. JVM, fixture, parser, and source tests for implemented behavior.
5. Supabase client-reported telemetry for reported events.
6. MTP exports, screenshots, or coarse activity logs for correlation only.

Source and fixture tests do not prove current live LMS compatibility. Supabase events do not prove an Android crash, ANR, or LMS HTTP failure unless the client recorded that evidence. A successful instrumentation run may reinstall the debug app and must not be called a data-preserving update test.

## Privacy

Keep credentials, cookies, authorization values, invite plaintext, exact coordinates, signed URLs, and LMS payloads out of reports. Use restricted temporary directories for diagnostics. Return only the minimum log lines needed to support the conclusion.

## Scripts

Resolve these paths relative to this skill directory.

- `scripts/collect-isdm-diagnostics.sh --help`: collect read-only device, package, exit, app-ops, private-log, and tagged-Logcat evidence.
- `scripts/verify-android.sh --help`: run the repeatable Android verification matrix with Java 17 and a discovered SDK.
