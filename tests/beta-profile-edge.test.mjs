import assert from "node:assert/strict";
import test from "node:test";
import { deleteBetaSupportName, updateBetaProfile } from "../supabase/functions/beta-api/profile.ts";

test("authenticated installation updates its confirmed beta profile", async () => {
  let saved;
  const store = {
    saveProfile: async (testerCode, profile) => {
      saved = { testerCode, profile };
      return profile;
    },
  };
  const response = await updateBetaProfile(
    new Request("https://example.test/profile", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        support_name: "Asha Rao",
        self_section: "Section B",
        self_plc: "PLC 4",
        detected_sections: ["B"],
        detected_groups: ["4"],
        consent_version: "beta-2026-08-16-profile",
        confirmed_at: "2026-08-16T10:00:00Z",
      }),
    }),
    { tester_code: "T-03", installation_id: "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f" },
    store,
  );

  assert.equal(response.status, 200);
  assert.equal(saved.testerCode, "T-03");
  assert.equal(saved.profile.support_name, "Asha Rao");
  assert.equal(saved.profile.consent_version, "beta-2026-08-16-profile");
  assert.equal(saved.profile.consented_at, "2026-08-16T10:00:00.000Z");
  assert.deepEqual(saved.profile.detected_sections, ["B"]);
  assert.deepEqual(await response.json(), saved.profile);
});

test("authenticated installation deletes its support name early", async () => {
  let deletedTester;
  const response = await deleteBetaSupportName(
    { tester_code: "T-03", installation_id: "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f" },
    { deleteSupportName: async (testerCode) => { deletedTester = testerCode; } },
  );

  assert.equal(response.status, 200);
  assert.equal(deletedTester, "T-03");
  assert.deepEqual(await response.json(), { deleted: true });
});

test("profile update rejects a blank support name as user input", async () => {
  const response = await updateBetaProfile(
    new Request("https://example.test/profile", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        support_name: "   ",
        detected_sections: ["B"],
        detected_groups: ["4"],
        confirmed_at: "2026-08-16T10:00:00Z",
      }),
    }),
    { tester_code: "T-03", installation_id: "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f" },
    { saveProfile: async () => { throw new Error("must not save"); } },
  );

  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: "invalid_string" });
});

test("profile remains editable after the tester deletes the support name", async () => {
  let saved;
  const response = await updateBetaProfile(
    new Request("https://example.test/profile", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        self_section: "Section C",
        detected_sections: ["C"],
        detected_groups: [],
        consent_version: "beta-2026-08-16-profile",
        confirmed_at: "2026-08-16T10:00:00Z",
      }),
    }),
    { tester_code: "T-03", installation_id: "ba9f5ff7-b439-4efc-b9ba-24df4df6e65f" },
    { saveProfile: async (_testerCode, profile) => { saved = profile; return profile; } },
  );

  assert.equal(response.status, 200);
  assert.equal(saved.support_name, null);
  assert.equal(saved.self_section, "Section C");
});
