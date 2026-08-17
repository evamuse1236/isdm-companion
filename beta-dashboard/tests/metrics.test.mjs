import test from "node:test";
import assert from "node:assert/strict";
import { confirmedPresentCount, reliabilityLabel } from "../app/metrics.ts";

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
  assert.equal(reliabilityLabel({ failures: 4, cancellations: 12, recorded_exits: 2 }), "4 failures");
});
