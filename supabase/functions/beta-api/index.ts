import { createClient } from "npm:@supabase/supabase-js@2.112.3";
import { deriveSyncUpdate } from "./telemetry.ts";
import {
  deleteBetaSupportName,
  updateBetaProfile,
  type BetaProfileDeleteStore,
  type BetaProfileRecord,
  type BetaProfileStore,
} from "./profile.ts";

const supabaseUrl = requiredEnv("SUPABASE_URL");
const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
const db = createClient(supabaseUrl, serviceRoleKey, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const jsonHeaders = { "content-type": "application/json; charset=utf-8" };
const MAX_EVENTS = 100;
const MAX_ATTACHMENTS = 4;
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

Deno.serve(async (request) => {
  try {
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });
    const route = new URL(request.url).pathname.split("/").filter(Boolean).at(-1);

    if (route === "enroll" && request.method === "POST") return enroll(request);

    const installation = await authenticateInstallation(request);
    if (installation instanceof Response) return installation;

    if (route === "config" && request.method === "GET") return configResponse(installation);
    if (route === "auto-preflight" && request.method === "POST") return autoPreflight(installation);
    if (route === "events" && request.method === "POST") return ingestEvents(request, installation);
    if (route === "profile" && request.method === "POST") return updateBetaProfile(request, installation, profileStore);
    if (route === "profile-delete" && request.method === "POST") return deleteBetaSupportName(installation, profileDeleteStore);
    if (route === "schedule" && request.method === "POST") return recordSchedule(request, installation);
    if (route === "attendance" && request.method === "POST") return recordAttendance(request, installation);
    if (route === "report" && request.method === "POST") return recordReport(request, installation);
    if (route === "lms-diagnostic" && request.method === "POST") return recordLmsDiagnostic(request, installation);

    return json({ error: "not_found" }, 404);
  } catch (error) {
    if (error instanceof InputError) return json({ error: error.message }, 400);
    console.error("beta_api_unhandled", safeError(error));
    return json({ error: "internal_error" }, 500);
  }
});

type Installation = {
  tester_code: string;
  installation_id: string;
  app_version_code: number | null;
};

const profileStore: BetaProfileStore = {
  async saveProfile(testerCode: string, profile: BetaProfileRecord): Promise<BetaProfileRecord> {
    const { data, error } = await db.from("beta_installations").update({
      support_name: profile.support_name,
      self_section: profile.self_section,
      self_plc: profile.self_plc,
      detected_sections: profile.detected_sections,
      detected_groups: profile.detected_groups,
      consent_version: profile.consent_version,
      consented_at: profile.consented_at,
      profile_confirmed_at: profile.profile_confirmed_at,
      support_name_deleted_at: null,
      updated_at: new Date().toISOString(),
    }).eq("tester_code", testerCode)
      .select("support_name, self_section, self_plc, detected_sections, detected_groups, consent_version, consented_at, profile_confirmed_at")
      .single();
    if (error) throw error;
    return data as BetaProfileRecord;
  },
};

const profileDeleteStore: BetaProfileDeleteStore = {
  async deleteSupportName(testerCode: string): Promise<void> {
    const { error } = await db.from("beta_installations").update({
      support_name: null,
      support_name_deleted_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    }).eq("tester_code", testerCode);
    if (error) throw error;
  },
};

async function enroll(request: Request): Promise<Response> {
  const body = await readJson(request);
  const inviteCode = requiredString(body.invite_code, 64).toUpperCase();
  const consentVersion = requiredString(body.consent_version, 80);
  const selfSection = requiredString(body.self_section, 40);
  const selfPlc = optionalString(body.self_plc, 120);
  const inviteHash = await sha256(inviteCode);

  const { data: invite, error: lookupError } = await db
    .from("beta_installations")
    .select("tester_code, installation_id")
    .eq("invite_code_hash", inviteHash)
    .maybeSingle();
  if (lookupError) throw lookupError;
  if (!invite) return json({ error: "invalid_invite" }, 404);
  const reclaimed = Boolean(invite.installation_id);

  const installationId = crypto.randomUUID();
  const installToken = randomToken();
  const now = new Date().toISOString();
  const { error } = await db
    .from("beta_installations")
    .update({
      installation_id: installationId,
      install_token_hash: await sha256(installToken),
      consent_version: consentVersion,
      consented_at: now,
      self_section: selfSection,
      self_plc: selfPlc,
      manufacturer: optionalString(body.manufacturer, 120),
      model: optionalString(body.model, 160),
      android_version: optionalString(body.android_version, 80),
      app_version: optionalString(body.app_version, 80),
      app_version_code: optionalInteger(body.app_version_code),
      claimed_at: now,
      first_seen_at: now,
      last_seen_at: now,
      updated_at: now,
    })
    .eq("tester_code", invite.tester_code);
  if (error) throw error;

  return json({
    tester_code: invite.tester_code,
    installation_id: installationId,
    install_token: installToken,
    reclaimed,
  }, 201);
}

async function authenticateInstallation(request: Request): Promise<Installation | Response> {
  const installationId = request.headers.get("x-installation-id");
  const installToken = request.headers.get("x-install-token");
  if (!installationId || !installToken) return json({ error: "installation_auth_required" }, 401);

  const { data, error } = await db
    .from("beta_installations")
    .select("tester_code, installation_id, app_version_code")
    .eq("installation_id", installationId)
    .eq("install_token_hash", await sha256(installToken))
    .maybeSingle();
  if (error) throw error;
  if (!data) return json({ error: "installation_auth_invalid" }, 401);
  return data as Installation;
}

async function configResponse(installation: Installation): Promise<Response> {
  await touch(installation.tester_code);
  const config = await loadConfig();
  return json({
    auto_attendance_blocked: config.auto_attendance_blocked,
    minimum_version_code: config.minimum_version_code,
    update_required: (installation.app_version_code ?? 0) < config.minimum_version_code,
    beta_starts_at: config.beta_starts_at,
    beta_ends_at: config.beta_ends_at,
    checked_at: new Date().toISOString(),
  });
}

async function autoPreflight(installation: Installation): Promise<Response> {
  await touch(installation.tester_code);
  const config = await loadConfig();
  const updateRequired = (installation.app_version_code ?? 0) < config.minimum_version_code;
  return json({
    allowed: !config.auto_attendance_blocked && !updateRequired,
    reason: config.auto_attendance_blocked ? "remote_stop" : updateRequired ? "update_required" : "allowed",
    checked_at: new Date().toISOString(),
  }, config.auto_attendance_blocked || updateRequired ? 423 : 200);
}

async function ingestEvents(request: Request, installation: Installation): Promise<Response> {
  const body = await readJson(request);
  const events = Array.isArray(body.events) ? body.events.slice(0, MAX_EVENTS) : [];
  if (events.length === 0) return json({ error: "events_required" }, 400);
  const rows = events.map((event) => ({
    tester_code: installation.tester_code,
    installation_id: installation.installation_id,
    event_id: requiredUuid(event.event_id),
    event_type: requiredString(event.event_type, 80),
    occurred_at: requiredIsoDate(event.occurred_at),
    payload: redactSecrets(isObject(event.payload) ? event.payload : {}),
  }));
  const { error } = await db.from("beta_events").upsert(rows, { onConflict: "event_id", ignoreDuplicates: true });
  if (error) throw error;

  const syncUpdate = deriveSyncUpdate(rows);

  const { error: installationError } = await db.from("beta_installations").update({
    last_seen_at: new Date().toISOString(),
    manufacturer: optionalString(body.manufacturer, 120),
    model: optionalString(body.model, 160),
    android_version: optionalString(body.android_version, 80),
    app_version: optionalString(body.app_version, 80),
    app_version_code: optionalInteger(body.app_version_code),
    auto_attendance_enabled: optionalBoolean(body.auto_attendance_enabled),
    ...syncUpdate,
    updated_at: new Date().toISOString(),
  }).eq("tester_code", installation.tester_code);
  if (installationError) throw installationError;

  return json({ accepted: rows.length });
}

async function recordSchedule(request: Request, installation: Installation): Promise<Response> {
  const body = await readJson(request);
  const status = requiredEnum(body.status, ["confirmed", "mismatch"]);
  const confirmedAt = requiredIsoDate(body.confirmed_at);
  const selectedDate = requiredDate(body.selected_date);
  const detectedSections = stringArray(body.detected_sections, 12, 20);
  const detectedGroups = stringArray(body.detected_groups, 12, 40);
  const snapshot = redactSecrets(isObject(body.snapshot) ? body.snapshot : {});

  const { error } = await db.from("beta_schedule_confirmations").insert({
    tester_code: installation.tester_code,
    installation_id: installation.installation_id,
    confirmed_at: confirmedAt,
    status,
    selected_date: selectedDate,
    app_session_count: requiredNonNegativeInteger(body.app_session_count),
    note: optionalString(body.note, 1000),
    snapshot,
  });
  if (error) throw error;
  await db.from("beta_installations").update({
    schedule_status: status,
    detected_sections: detectedSections,
    detected_groups: detectedGroups,
    last_seen_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }).eq("tester_code", installation.tester_code);
  return json({ saved: true }, 201);
}

async function recordAttendance(request: Request, installation: Installation): Promise<Response> {
  const body = await readJson(request);
  const row = {
    event_id: requiredUuid(body.event_id),
    tester_code: installation.tester_code,
    installation_id: installation.installation_id,
    occurred_at: requiredIsoDate(body.occurred_at),
    method: requiredEnum(body.method, ["auto", "manual"]),
    session_fingerprint: requiredString(body.session_fingerprint, 160),
    session_label: optionalString(body.session_label, 240),
    latitude: optionalNumber(body.latitude),
    longitude: optionalNumber(body.longitude),
    accuracy_m: optionalNumber(body.accuracy_m),
    location_age_ms: optionalInteger(body.location_age_ms),
    distance_m: optionalNumber(body.distance_m),
    gate_allowed: optionalBoolean(body.gate_allowed),
    gate_reason: optionalString(body.gate_reason, 120),
    lms_markable: optionalBoolean(body.lms_markable),
    outcome: requiredEnum(body.outcome, ["blocked", "attempted", "present", "failed", "unknown"]),
    suspected_incorrect: body.suspected_incorrect === true,
    details: redactSecrets(isObject(body.details) ? body.details : {}),
  };
  const { data, error } = await db
    .from("beta_attendance_decisions")
    .upsert(row, { onConflict: "event_id", ignoreDuplicates: true })
    .select("id")
    .maybeSingle();
  if (error) throw error;
  await touch(installation.tester_code);
  return json({ saved: true, attendance_id: data?.id ?? null }, 201);
}

async function recordReport(request: Request, installation: Installation): Promise<Response> {
  const body = await readJson(request);
  const category = requiredEnum(body.category, ["issue", "suggestion", "general"]);
  const attachments = Array.isArray(body.attachments) ? body.attachments.slice(0, MAX_ATTACHMENTS) : [];
  const { data: report, error } = await db.from("beta_issue_reports").insert({
    tester_code: installation.tester_code,
    installation_id: installation.installation_id,
    category,
    title: requiredString(body.title, 160),
    description: requiredString(body.description, 8000),
    linked_attendance_id: optionalUuid(body.linked_attendance_id),
    app_context: redactSecrets(isObject(body.app_context) ? body.app_context : {}),
  }).select("id").single();
  if (error) throw error;

  let uploaded = 0;
  try {
    for (const attachment of attachments) {
      const contentType = requiredEnum(attachment.content_type, ["image/jpeg", "image/png"]);
      const bytes = decodeBase64(requiredString(attachment.base64, 8_000_000));
      if (bytes.byteLength === 0 || bytes.byteLength > MAX_ATTACHMENT_BYTES) throw new InputError("attachment_too_large");
      const extension = contentType === "image/png" ? "png" : "jpg";
      const path = `${installation.tester_code}/${report.id}/${crypto.randomUUID()}.${extension}`;
      const { error: uploadError } = await db.storage.from("beta-evidence").upload(path, bytes, {
        contentType,
        upsert: false,
      });
      if (uploadError) throw uploadError;
      const { error: metadataError } = await db.from("beta_report_attachments").insert({
        report_id: report.id,
        storage_path: path,
        content_type: contentType,
        size_bytes: bytes.byteLength,
      });
      if (metadataError) throw metadataError;
      uploaded += 1;
    }
  } catch (attachmentError) {
    console.error("report_attachment_failed", report.id, safeError(attachmentError));
  }
  await touch(installation.tester_code);
  return json({ report_id: report.id, attachments_uploaded: uploaded }, 201);
}

async function recordLmsDiagnostic(request: Request, installation: Installation): Promise<Response> {
  const body = await readJson(request);
  const { error } = await db.from("beta_lms_diagnostics").insert({
    tester_code: installation.tester_code,
    installation_id: installation.installation_id,
    endpoint_label: requiredString(body.endpoint_label, 120),
    http_status: optionalInteger(body.http_status),
    diagnostic: redactSecrets(isObject(body.diagnostic) ? body.diagnostic : {}),
  });
  if (error) throw error;
  await touch(installation.tester_code);
  return json({ saved: true }, 201);
}

async function touch(testerCode: string): Promise<void> {
  const now = new Date().toISOString();
  const { error } = await db.from("beta_installations").update({ last_seen_at: now, updated_at: now }).eq("tester_code", testerCode);
  if (error) throw error;
}

async function loadConfig() {
  const { data, error } = await db
    .from("beta_config")
    .select("auto_attendance_blocked, minimum_version_code, beta_starts_at, beta_ends_at")
    .eq("singleton", true)
    .single();
  if (error) throw error;
  return data;
}

function redactSecrets(value: unknown, key = ""): unknown {
  if (/(password|passwd|cookie|authorization|token|secret|signature|csrf|session)/i.test(key)) return "[redacted]";
  if (typeof value === "string") {
    return value
      .replace(/\bBearer\s+[^\s,;]+/gi, "Bearer [redacted]")
      .replace(/\b(password|cookie|authorization|token|secret|csrf|session)\b\s*[:=]\s*[^\s,;]+/gi, "$1=[redacted]")
      .slice(0, 20_000);
  }
  if (Array.isArray(value)) return value.slice(0, 200).map((item) => redactSecrets(item));
  if (isObject(value)) return Object.fromEntries(Object.entries(value).slice(0, 200).map(([childKey, child]) => [childKey, redactSecrets(child, childKey)]));
  return value;
}

class InputError extends Error {}

async function readJson(request: Request): Promise<Record<string, any>> {
  try {
    const body = await request.json();
    if (!isObject(body)) throw new InputError("json_object_required");
    return body;
  } catch (error) {
    if (error instanceof InputError) throw error;
    throw new InputError("invalid_json");
  }
}

function requiredString(value: unknown, max: number): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.trim().length > max) throw new InputError("invalid_string");
  return value.trim();
}

function optionalString(value: unknown, max: number): string | null {
  if (value == null || value === "") return null;
  return requiredString(value, max);
}

function requiredEnum<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) throw new InputError("invalid_enum");
  return value as T;
}

function optionalBoolean(value: unknown): boolean | null {
  return typeof value === "boolean" ? value : null;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalInteger(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) ? value : null;
}

function requiredNonNegativeInteger(value: unknown): number {
  const number = optionalInteger(value);
  if (number == null || number < 0) throw new InputError("invalid_integer");
  return number;
}

function requiredUuid(value: unknown): string {
  const string = requiredString(value, 36);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(string)) throw new InputError("invalid_uuid");
  return string;
}

function optionalUuid(value: unknown): string | null {
  return value == null || value === "" ? null : requiredUuid(value);
}

function requiredIsoDate(value: unknown): string {
  const string = requiredString(value, 40);
  if (!Number.isFinite(Date.parse(string))) throw new InputError("invalid_timestamp");
  return new Date(string).toISOString();
}

function optionalIsoDate(value: unknown): string | null {
  return value == null || value === "" ? null : requiredIsoDate(value);
}

function requiredDate(value: unknown): string {
  const string = requiredString(value, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(string)) throw new InputError("invalid_date");
  return string;
}

function stringArray(value: unknown, maxItems: number, maxLength: number): string[] {
  if (!Array.isArray(value)) return [];
  return value.slice(0, maxItems).map((item) => requiredString(item, maxLength));
}

function isObject(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value.replace(/^data:[^;]+;base64,/, ""));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

function safeError(error: unknown): string {
  return error instanceof Error ? `${error.name}: ${error.message}`.slice(0, 500) : "unknown";
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: jsonHeaders });
}
