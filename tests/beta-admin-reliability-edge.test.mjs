import test from "node:test";
import assert from "node:assert/strict";
import { summarizeProcessExits } from "../supabase/functions/beta-admin/reliability.ts";

test("cached low-memory exits stay separate from crash and ANR exits", () => {
  assert.deepEqual(summarizeProcessExits([
    { payload: { reason: "low_memory" } },
    { payload: { reason: "low_memory" } },
    { payload: { reason: "crash" } },
    { payload: { reason: "anr" } },
    { payload: { reason: "user_requested" } },
    { payload: { reason: "other" } },
  ]), {
    critical_exits: 2,
    low_memory_exits: 2,
    other_exits: 2,
  });
});
