import assert from "node:assert/strict";
import test from "node:test";
import { profileMatchStatus, testerDisplayName } from "../app/roster.ts";

test("roster shows the tester-confirmed support name", () => {
  assert.equal(testerDisplayName({ support_name: "Asha Rao" }), "Asha Rao");
  assert.equal(testerDisplayName({ support_name: null }), "Awaiting profile");
});

test("roster distinguishes confirmed, mismatched, and missing LMS cohorts", () => {
  assert.equal(profileMatchStatus({ self_section: "Section B", self_plc: "PLC 4", detected_sections: ["B"], detected_groups: ["4"] }), "confirmed");
  assert.equal(profileMatchStatus({ self_section: "Section A", self_plc: "PLC 4", detected_sections: ["B"], detected_groups: ["4"] }), "mismatch");
  assert.equal(profileMatchStatus({ self_section: "Section B", self_plc: null, detected_sections: [], detected_groups: [] }), "unknown");
});
