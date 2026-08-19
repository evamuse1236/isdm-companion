---
name: isdm-lms-runtime-explainer
description: Explain how ISDM Companion attendance, schedules, courses, readings, faculty data, refresh, caches, parsers, and background work really operate. Use for questions about source of truth, real-time behavior, stale data, Mark requests, what the app stores, or why displayed LMS data may differ.
---

# ISDM LMS Runtime Explainer

Explain the system boundary in Simplified Technical English, grounded in the current checkout.

## Workflow

1. Read `CONTEXT.md` and the source that owns the requested behavior. Read the nearest tests to learn the intended contract.
2. Start with the boundary: “Companion is an LMS client. The LMS is the source of truth for LMS data.”
3. Classify each relevant value:
   - **Live request**: fetched from the LMS during the current action.
   - **Refresh**: a later pull that replaces or extends stored data.
   - **Memory cache**: process-local data that disappears with the process.
   - **Persistent cache**: device data reused across launches.
   - **Parser output**: labels and fields extracted from LMS HTML or JSON.
   - **Local-only state**: preferences such as a reading Done/Undo choice.
   - **Beta telemetry**: client-reported evidence, not the LMS record.
4. Describe trigger, request, transformation, storage, refresh, and failure behavior in that order.
5. For attendance, separate the local Attendance Location Gate from the LMS markability check and the Mark action. Treat the LMS re-fetch or authoritative result as confirmation.
6. State what the inspected tests prove and what they do not prove. Fixture success proves the implemented parser contract, not today’s live markup.
7. Use short sentences, one term for one concept, and concrete timing. Finish when the user can tell which values are live, cached, parsed, local, or unverified.

If the question is actually about a failure, invoke `$isdm-incident-response` and keep the explanation as the opening system map.
