import assert from "node:assert/strict";
import test from "node:test";
import { profileMatchStatus, testerDisplayName, testerVersionLabel } from "../app/roster.ts";

test("roster shows the tester-confirmed support name", () => {
  assert.equal(testerDisplayName({ support_name: "Asha Rao" }), "Asha Rao");
  assert.equal(testerDisplayName({ support_name: null }), "Awaiting profile");
});

test("roster keeps an available app version visible and includes its code", () => {
  assert.equal(
    testerVersionLabel({ android_version: null, app_version: "0.4.3-beta", app_version_code: 7 }),
    "0.4.3-beta (code 7)",
  );
});

test("roster distinguishes confirmed, mismatched, and missing LMS cohorts", () => {
  assert.equal(profileMatchStatus({ self_section: "Section B", self_plc: "PLC 4", detected_sections: ["B"], detected_groups: ["4"] }), "confirmed");
  assert.equal(profileMatchStatus({ self_section: "Section A", self_plc: "PLC 4", detected_sections: ["B"], detected_groups: ["4"] }), "mismatch");
  assert.equal(profileMatchStatus({ self_section: "Section B", self_plc: null, detected_sections: [], detected_groups: [] }), "unknown");
});
