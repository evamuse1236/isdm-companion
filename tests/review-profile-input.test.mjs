import assert from "node:assert/strict";
import test from "node:test";
import { updateBetaProfile } from "../supabase/functions/beta-api/profile.ts";
const installation = { tester_code: "T-99", installation_id: "test-installation" };
const request = (body) => new Request("https://example.test/profile", { method: "POST", body });
const valid = { support_name: "Example Tester", self_section: "Section A", self_plc: null,
  detected_sections: ["A"], detected_groups: [], consent_version: "test-v1", confirmed_at: "2026-09-18T06:00:00Z" };

test("patched profile handler preserves valid field normalization", async () => {
  let writes = 0;
  const result = await updateBetaProfile(request(JSON.stringify(valid)), installation, {
    async saveProfile(code, profile) { writes++; assert.equal(code, "T-99"); return profile; },
  });
  assert.equal(result.status, 200); assert.equal(writes, 1);
  assert.equal(result.headers.get("cache-control"), "no-store");
  assert.equal((await result.json()).consented_at, "2026-09-18T06:00:00.000Z");
});
test("null profile returns a stable 400 and never reaches storage", async () => {
  const result = await updateBetaProfile(request("null"), installation, {
    async saveProfile() { assert.fail("must not save"); },
  });
  assert.equal(result.status, 400);
  assert.deepEqual(await result.json(), { error: "json_object_required" });
});
test("oversized profile returns 413 and never reaches storage", async () => {
  const result = await updateBetaProfile(request(JSON.stringify({ x: "a".repeat(70000) })), installation, {
    async saveProfile() { assert.fail("must not save"); },
  });
  assert.equal(result.status, 413);
});
test("paused collection profile echo contract remains compatible", async () => {
  const result = await updateBetaProfile(request(JSON.stringify(valid)), installation, {
    async saveProfile(_code, profile) { return { ...profile, saved: false, collection_paused: true }; },
  });
  assert.equal(result.status, 200);
  const body = await result.json();
  assert.equal(body.saved, false); assert.equal(body.collection_paused, true);
  assert.equal(body.support_name, "Example Tester");
});
test("existing blank-name validation still applies", async () => {
  const result = await updateBetaProfile(request(JSON.stringify({ ...valid, support_name: "  " })), installation, {
    async saveProfile() { assert.fail("must not save"); },
  });
  assert.equal(result.status, 400); assert.deepEqual(await result.json(), { error: "invalid_string" });
});
