import { readJsonObject } from "../_shared/http-input.mjs";

type Registration = { tester_code?: string; installation_id?: string; error?: string };
export async function registerNightly(
  request: Request,
  register: (input: Record<string, unknown>) => Promise<Registration>,
): Promise<Response> {
  const body = await readJsonObject(request, { maxBytes: 4096 });
  const validText = (value: unknown, max: number): value is string =>
    typeof value === "string" && value.trim().length > 0 && value.length <= max;
  if (!validText(body.installation_id, 36) || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(body.installation_id) ||
      !validText(body.install_token, 64) || !/^[a-f0-9]{64}$/.test(body.install_token) ||
      !validText(body.support_name, 160) || !validText(body.self_section, 40) ||
      (body.self_plc != null && !validText(body.self_plc, 120)) ||
      !validText(body.model, 160) || !validText(body.app_version, 80) ||
      !Number.isSafeInteger(body.app_version_code) || Number(body.app_version_code) < 16 || Number(body.app_version_code) > 2147483647 ||
      body.consent_version !== "nightly-2026-09-20") {
    return Response.json({ error: "invalid_nightly_input" }, { status: 400 });
  }
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(body.install_token));
  const tokenHash = Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, "0")).join("");
  const result = await register({
    p_installation_id: body.installation_id, p_token_hash: tokenHash,
    p_name: body.support_name.trim(), p_section: body.self_section.trim(), p_plc: body.self_plc ?? null,
    p_model: body.model, p_version: body.app_version, p_version_code: body.app_version_code,
  });
  const status = result.error === "access_suspended" ? 423 : result.error === "enrollment_limit" ? 429
    : result.error ? 409 : 201;
  return Response.json(result, { status, headers: { "cache-control": "no-store" } });
}
