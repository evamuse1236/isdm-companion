import test from "node:test";
import assert from "node:assert/strict";
import { confirmedPresentCount, reliabilityLabel, reliabilityNote } from "../app/metrics.ts";

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
