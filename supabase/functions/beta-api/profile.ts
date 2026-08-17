export type BetaInstallationIdentity = {
  tester_code: string;
  installation_id: string;
};

export type BetaProfileRecord = {
  support_name: string | null;
  self_section: string | null;
  self_plc: string | null;
  detected_sections: string[];
  detected_groups: string[];
  consent_version: string;
  consented_at: string;
  profile_confirmed_at: string;
};

export type BetaProfileStore = {
  saveProfile(testerCode: string, profile: BetaProfileRecord): Promise<BetaProfileRecord>;
};

export type BetaProfileDeleteStore = {
  deleteSupportName(testerCode: string): Promise<void>;
};

export async function updateBetaProfile(
  request: Request,
  installation: BetaInstallationIdentity,
  store: BetaProfileStore,
): Promise<Response> {
  let profile: BetaProfileRecord;
  try {
    const body = await request.json() as Record<string, unknown>;
    const confirmedAt = requiredIsoDate(body.confirmed_at);
    profile = {
      support_name: optionalString(body.support_name, 160),
      self_section: optionalString(body.self_section, 40),
      self_plc: optionalString(body.self_plc, 120),
      detected_sections: stringArray(body.detected_sections, 12, 20),
      detected_groups: stringArray(body.detected_groups, 12, 40),
      consent_version: requiredString(body.consent_version, 80),
      consented_at: confirmedAt,
      profile_confirmed_at: confirmedAt,
    };
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "invalid_profile" }, 400);
  }
  return json(await store.saveProfile(installation.tester_code, profile));
}

export async function deleteBetaSupportName(
  installation: BetaInstallationIdentity,
  store: BetaProfileDeleteStore,
): Promise<Response> {
  await store.deleteSupportName(installation.tester_code);
  return json({ deleted: true });
}

function requiredString(value: unknown, max: number): string {
  if (typeof value !== "string") throw new Error("invalid_string");
  const clean = value.trim();
  if (!clean || clean.length > max) throw new Error("invalid_string");
  return clean;
}

function optionalString(value: unknown, max: number): string | null {
  if (value == null || value === "") return null;
  if (typeof value !== "string") throw new Error("invalid_string");
  const clean = value.trim();
  if (!clean || clean.length > max) throw new Error("invalid_string");
  return clean;
}

function stringArray(value: unknown, maxItems: number, maxLength: number): string[] {
  if (!Array.isArray(value) || value.length > maxItems) throw new Error("invalid_string_array");
  return value.map((item) => requiredString(item, maxLength));
}

function requiredIsoDate(value: unknown): string {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) throw new Error("invalid_date");
  return new Date(value).toISOString();
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
