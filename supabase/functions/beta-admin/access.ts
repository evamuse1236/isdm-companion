export type AccessUpdate = {
  tester_code: string;
  suspended: boolean;
  reason: string;
  expected_changed_at: string | null;
};

export type AccessRecord = {
  tester_code: string;
  access_suspended: boolean;
  access_changed_at: string | null;
  access_reason: string | null;
};

export interface AccessStore {
  setAccess(update: AccessUpdate): Promise<AccessRecord | { error: "tester_not_found" | "access_changed" }>;
}

/** Called only after the owner secret is verified. The store commits state and audit atomically. */
export async function updateTesterAccess(request: Request, store: AccessStore): Promise<Response> {
  const body = await request.json().catch(() => null);
  if (!body || typeof body !== "object" ||
      typeof body.tester_code !== "string" || !/^T-\d{2}$/.test(body.tester_code) ||
      typeof body.suspended !== "boolean" || typeof body.reason !== "string" ||
      body.reason.trim().length < 3 || body.reason.trim().length > 1000 ||
      !(body.expected_changed_at === null ||
        (typeof body.expected_changed_at === "string" && Number.isFinite(Date.parse(body.expected_changed_at))))) {
    return Response.json({ error: "invalid_access_input" }, { status: 400 });
  }
  const result = await store.setAccess({
    tester_code: body.tester_code,
    suspended: body.suspended,
    reason: body.reason.trim(),
    expected_changed_at: body.expected_changed_at,
  });
  return Response.json(result, { status: "error" in result ? result.error === "tester_not_found" ? 404 : 409 : 200 });
}
