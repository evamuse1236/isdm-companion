import { createClient } from "npm:@supabase/supabase-js@2.112.3";

const db = createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
  auth: { persistSession: false, autoRefreshToken: false },
});
const jsonHeaders = { "content-type": "application/json; charset=utf-8" };

Deno.serve(async (request) => {
  try {
    if (!(await isAdmin(request))) return json({ error: "unauthorized" }, 401);
    const route = new URL(request.url).pathname.split("/").filter(Boolean).at(-1);

    if (route === "dashboard" && request.method === "GET") return dashboard();
    if (route === "control" && request.method === "POST") return updateControl(request);
    if (route === "report" && request.method === "PATCH") return updateReport(request);
    if (route === "attendance" && request.method === "PATCH") return updateAttendance(request);
    return json({ error: "not_found" }, 404);
  } catch (error) {
    console.error("beta_admin_unhandled", error instanceof Error ? error.message : "unknown");
    return json({ error: "internal_error" }, 500);
  }
});

async function isAdmin(request: Request): Promise<boolean> {
  const secret = request.headers.get("x-admin-secret");
  if (!secret) return false;
  const { data, error } = await db.from("beta_config").select("admin_secret_hash").eq("singleton", true).single();
  if (error || !data.admin_secret_hash) return false;
  return timingSafeEqual(await sha256(secret), data.admin_secret_hash);
}

async function dashboard(): Promise<Response> {
  const todayIst = startOfTodayIst();
  const [config, installations, events, schedules, attendance, reports, attachments, audit, failures, cancellations, exits] = await Promise.all([
    db.from("beta_config").select("auto_attendance_blocked, minimum_version_code, beta_starts_at, beta_ends_at, updated_at").eq("singleton", true).single(),
    db.from("beta_installations").select("tester_code, installation_id, consent_version, consented_at, support_name, profile_confirmed_at, support_name_deleted_at, self_section, self_plc, manufacturer, model, android_version, app_version, app_version_code, detected_sections, detected_groups, schedule_status, auto_attendance_enabled, last_sync_status, last_sync_at, first_seen_at, last_seen_at, claimed_at").order("tester_code"),
    db.from("beta_events").select("id, tester_code, event_type, occurred_at, payload, received_at").order("occurred_at", { ascending: false }).limit(300),
    db.from("beta_schedule_confirmations").select("id, tester_code, confirmed_at, status, selected_date, app_session_count, note, snapshot").order("confirmed_at", { ascending: false }).limit(100),
    db.from("beta_attendance_decisions").select("id, tester_code, occurred_at, method, session_label, latitude, longitude, accuracy_m, location_age_ms, distance_m, gate_allowed, gate_reason, lms_markable, outcome, suspected_incorrect, details").order("occurred_at", { ascending: false }).limit(200),
    db.from("beta_issue_reports").select("id, tester_code, category, title, description, status, owner_note, linked_attendance_id, app_context, created_at, updated_at").order("created_at", { ascending: false }).limit(200),
    db.from("beta_report_attachments").select("id, report_id, storage_path, content_type, size_bytes, created_at").order("created_at", { ascending: false }).limit(400),
    db.from("beta_control_audit").select("id, action, reason, actor_label, created_at").order("created_at", { ascending: false }).limit(20),
    db.from("beta_events").select("id", { count: "exact", head: true })
      .gte("occurred_at", todayIst)
      .in("event_type", ["command_crashed", "background_sync_failed", "background_reading_sync_failed"]),
    db.from("beta_events").select("id", { count: "exact", head: true })
      .gte("occurred_at", todayIst)
      .eq("event_type", "command_cancelled"),
    db.from("beta_events").select("id", { count: "exact", head: true })
      .gte("occurred_at", todayIst)
      .eq("event_type", "previous_process_exit"),
  ]);
  for (const result of [config, installations, events, schedules, attendance, reports, attachments, audit, failures, cancellations, exits]) {
    if (result.error) throw result.error;
  }

  const signedAttachments = await Promise.all((attachments.data ?? []).map(async (attachment) => {
    const { data } = await db.storage.from("beta-evidence").createSignedUrl(attachment.storage_path, 15 * 60);
    return { ...attachment, signed_url: data?.signedUrl ?? null };
  }));

  return json({
    generated_at: new Date().toISOString(),
    config: config.data,
    installations: installations.data ?? [],
    events: events.data ?? [],
    reliability: {
      failures: failures.count ?? 0,
      cancellations: cancellations.count ?? 0,
      recorded_exits: exits.count ?? 0,
    },
    schedule_confirmations: schedules.data ?? [],
    attendance_decisions: attendance.data ?? [],
    reports: reports.data ?? [],
    attachments: signedAttachments,
    control_audit: audit.data ?? [],
  });
}

function startOfTodayIst(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const part = (type: string) => parts.find((item) => item.type === type)?.value;
  return new Date(`${part("year")}-${part("month")}-${part("day")}T00:00:00+05:30`).toISOString();
}

async function updateControl(request: Request): Promise<Response> {
  const body = await request.json();
  if (typeof body.blocked !== "boolean") return json({ error: "blocked_boolean_required" }, 400);
  const now = new Date().toISOString();
  const { error } = await db.from("beta_config").update({
    auto_attendance_blocked: body.blocked,
    updated_at: now,
  }).eq("singleton", true);
  if (error) throw error;
  const { error: auditError } = await db.from("beta_control_audit").insert({
    action: body.blocked ? "stop_auto_attendance" : "allow_auto_attendance",
    reason: optionalString(body.reason, 1000),
    actor_label: "chatgpt_site_owner",
  });
  if (auditError) throw auditError;
  return json({ auto_attendance_blocked: body.blocked, updated_at: now });
}

async function updateReport(request: Request): Promise<Response> {
  const body = await request.json();
  const reportId = requiredUuid(body.report_id);
  const status = requiredEnum(body.status, ["new", "seen", "investigating", "waiting_on_tester", "resolved", "wont_fix"]);
  const { data, error } = await db.from("beta_issue_reports").update({
    status,
    owner_note: optionalString(body.owner_note, 4000),
    updated_at: new Date().toISOString(),
  }).eq("id", reportId).select("id, status, owner_note, updated_at").single();
  if (error) throw error;
  return json(data);
}

async function updateAttendance(request: Request): Promise<Response> {
  const body = await request.json();
  const attendanceId = requiredUuid(body.attendance_id);
  if (typeof body.suspected_incorrect !== "boolean") return json({ error: "suspected_boolean_required" }, 400);
  const { data, error } = await db.from("beta_attendance_decisions").update({
    suspected_incorrect: body.suspected_incorrect,
  }).eq("id", attendanceId).select("id, suspected_incorrect").single();
  if (error) throw error;
  return json(data);
}

function timingSafeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return difference === 0;
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function requiredUuid(value: unknown): string {
  if (typeof value !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) throw new Error("invalid_uuid");
  return value;
}

function requiredEnum<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) throw new Error("invalid_enum");
  return value as T;
}

function optionalString(value: unknown, max: number): string | null {
  if (value == null || value === "") return null;
  if (typeof value !== "string" || value.trim().length > max) throw new Error("invalid_string");
  return value.trim();
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: jsonHeaders });
}
