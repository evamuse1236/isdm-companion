export type AutoAttendanceUpdate = {
  tester_code: string;
  blocked: boolean;
  reason: string;
  expected_changed_at: string | null;
};

export type AutoAttendanceRecord = {
  tester_code: string;
  auto_attendance_blocked: boolean;
  auto_attendance_changed_at: string | null;
  auto_attendance_reason: string | null;
};

export interface AutoAttendanceStore {
  setAutoAttendance(update: AutoAttendanceUpdate): Promise<AutoAttendanceRecord | { error: "tester_not_found" | "auto_attendance_changed" }>;
}

/** Called only after the owner secret is verified. The store commits state and audit atomically. */
export async function updateTesterAutoAttendance(request: Request, store: AutoAttendanceStore): Promise<Response> {
  const body = await request.json().catch(() => null);
  if (!body || typeof body !== "object" ||
      typeof body.tester_code !== "string" || !/^T-\d{2}$/.test(body.tester_code) ||
      typeof body.blocked !== "boolean" || typeof body.reason !== "string" ||
      body.reason.trim().length < 3 || body.reason.trim().length > 1000 ||
      !(body.expected_changed_at === null ||
        (typeof body.expected_changed_at === "string" && Number.isFinite(Date.parse(body.expected_changed_at))))) {
    return Response.json({ error: "invalid_auto_attendance_input" }, { status: 400 });
  }
  const result = await store.setAutoAttendance({
    tester_code: body.tester_code,
    blocked: body.blocked,
    reason: body.reason.trim(),
    expected_changed_at: body.expected_changed_at,
  });
  return Response.json(result, { status: "error" in result ? result.error === "tester_not_found" ? 404 : 409 : 200 });
}
