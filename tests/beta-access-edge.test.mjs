import assert from "node:assert/strict";
import test from "node:test";
import { updateTesterAccess } from "../supabase/functions/beta-admin/access.ts";
import { installationAccessResponse, accessConfig } from "../supabase/functions/beta-api/access.ts";

const input = { tester_code: "T-03", suspended: true, reason: "Owner review", expected_changed_at: null };
const request = (value) => new Request("https://example.test/access", {
  method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(value),
});

test("owner suspension targets one tester and preserves the expected revision", async () => {
  let saved;
  const response = await updateTesterAccess(request(input), { setAccess: async (update) => {
    saved = update;
    return { tester_code: update.tester_code, access_suspended: update.suspended, access_changed_at: "2026-09-11T00:00:00Z", access_reason: update.reason };
  } });
  assert.equal(response.status, 200);
  assert.deepEqual(saved, input);
  assert.equal((await response.json()).access_suspended, true);
});

test("malformed targets, flags, reasons, and missing revisions never write", async () => {
  for (const change of [{ tester_code: "T-03,T-04" }, { suspended: "false" }, { reason: " " },
    { reason: "x".repeat(1001) }, { expected_changed_at: undefined }, { expected_changed_at: "invalid" }]) {
    const response = await updateTesterAccess(request({ ...input, ...change }), {
      setAccess: async () => assert.fail("must not write"),
    });
    assert.equal(response.status, 400);
  }
});

test("concurrent access changes and unknown testers are explicit errors", async () => {
  for (const [error, status] of [["access_changed", 409], ["tester_not_found", 404]]) {
    const response = await updateTesterAccess(request(input), { setAccess: async () => ({ error }) });
    assert.equal(response.status, status);
  }
});

test("suspended tokens cannot access protected beta routes or automatic attendance", async () => {
  for (const route of ["events", "auto-preflight", "profile", "attendance", "report", "schedule", "lms-diagnostic"]) {
    const response = installationAccessResponse({ access_suspended: true }, route);
    assert.equal(response.status, 423);
    assert.equal((await response.json()).allowed, false);
  }
  for (const route of ["config", "profile-delete"]) assert.equal(installationAccessResponse({ access_suspended: true }, route), null);
  assert.equal(installationAccessResponse({ access_suspended: false }, "attendance"), null);
});

test("configuration provides a six-hour lease only for allowed testers and no private reason", () => {
  const now = new Date("2026-09-11T00:00:00Z");
  assert.deepEqual(accessConfig(false, now), { access_suspended: false, access_valid_until: "2026-09-11T06:00:00.000Z" });
  assert.deepEqual(accessConfig(true, now), { access_suspended: true, access_valid_until: null });
});
