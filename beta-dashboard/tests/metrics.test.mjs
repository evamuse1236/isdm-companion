import test from "node:test";
import assert from "node:assert/strict";
import {
  attendanceTrend,
  confirmedPresentCount,
  reliabilityLabel,
  reliabilityNote,
  relativeTime,
  reportAgeLabel,
} from "../app/metrics.ts";

test("marks count only LMS-confirmed new marks", () => {
  assert.equal(confirmedPresentCount([
    { outcome: "present", details: { result: "marked_present" } },
    { outcome: "present", details: { result: "already_marked" } },
    { outcome: "present", gate_allowed: true, lms_markable: true, details: {} },
    { outcome: "present", gate_allowed: null, lms_markable: null, details: {} },
    { outcome: "failed", details: { result: "mark_failed" } },
  ]), 2);
});

test("reliability label reports server-computed failures", () => {
  assert.equal(reliabilityLabel({ failures: 4, cancellations: 12, critical_exits: 1, low_memory_exits: 2, other_exits: 3 }), "4 failures");
});

test("reliability note separates cached low-memory reclamation from critical exits", () => {
  assert.equal(reliabilityNote({
    failures: 2,
    cancellations: 14,
    critical_exits: 0,
    low_memory_exits: 18,
    other_exits: 28,
  }), "14 cancelled · 0 crash/ANR exits · 18 cached low-memory");
});

test("attendance trend buckets decisions into IST days", () => {
  const now = new Date("2026-08-21T06:00:00Z"); // 11:30 IST
  const trend = attendanceTrend([
    { occurred_at: "2026-08-21T04:00:00Z", outcome: "present", suspected_incorrect: false },
    { occurred_at: "2026-08-20T05:00:00Z", outcome: "failed", suspected_incorrect: true },
    { occurred_at: "2026-08-10T05:00:00Z", outcome: "present", suspected_incorrect: false },
  ], 3, now);
  assert.equal(trend.length, 3);
  assert.equal(trend[2].marks, 1);
  assert.equal(trend[2].present, 1);
  assert.equal(trend[1].suspected, 1);
  assert.equal(trend[0].marks, 0);
});

test("report age labels switch units and clamp to zero", () => {
  const now = new Date("2026-08-21T06:00:00Z");
  assert.equal(reportAgeLabel("2026-08-21T05:50:00Z", now), "10m");
  assert.equal(reportAgeLabel("2026-08-21T03:00:00Z", now), "3h");
  assert.equal(reportAgeLabel("2026-08-18T06:00:00Z", now), "3d");
  assert.equal(reportAgeLabel("2026-08-21T07:00:00Z", now), "1m");
});

test("relative time renders coarse human deltas", () => {
  const now = Date.parse("2026-08-21T06:00:30Z");
  assert.equal(relativeTime("2026-08-21T06:00:10Z", now), "20s ago");
  assert.equal(relativeTime("2026-08-21T05:58:30Z", now), "2m ago");
  assert.equal(relativeTime("2026-08-21T03:00:30Z", now), "3h ago");
  assert.equal(relativeTime(null, now), "never");
});
