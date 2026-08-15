import { getChatGPTUser } from "../../chatgpt-auth";

export const dynamic = "force-dynamic";

export async function GET() {
  const denied = await requireOwner();
  if (denied) return denied;
  return proxy("dashboard", { method: "GET" });
}

export async function POST(request: Request) {
  const denied = await requireOwner();
  if (denied) return denied;
  const body = await request.json().catch(() => null) as { action?: string; [key: string]: unknown } | null;
  if (!body?.action) return Response.json({ error: "action_required" }, { status: 400 });

  if (body.action === "control") {
    return proxy("control", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ blocked: body.blocked, reason: body.reason }),
    });
  }
  if (body.action === "report") {
    return proxy("report", {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ report_id: body.report_id, status: body.status, owner_note: body.owner_note }),
    });
  }
  if (body.action === "attendance") {
    return proxy("attendance", {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ attendance_id: body.attendance_id, suspected_incorrect: body.suspected_incorrect }),
    });
  }
  return Response.json({ error: "unknown_action" }, { status: 400 });
}

async function requireOwner(): Promise<Response | null> {
  const user = await getChatGPTUser();
  if (user || process.env.NODE_ENV !== "production") return null;
  return Response.json({ error: "unauthorized" }, { status: 401 });
}

async function proxy(path: string, init: RequestInit): Promise<Response> {
  const baseUrl = process.env.BETA_ADMIN_API_BASE;
  const secret = process.env.BETA_ADMIN_SECRET;
  if (!baseUrl || !secret) {
    return Response.json({ error: "dashboard_backend_not_configured" }, { status: 503 });
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}/${path}`, {
      ...init,
      cache: "no-store",
      signal: controller.signal,
      headers: {
        ...init.headers,
        "x-admin-secret": secret,
      },
    });
    return new Response(response.body, {
      status: response.status,
      headers: { "content-type": response.headers.get("content-type") ?? "application/json" },
    });
  } catch {
    return Response.json({ error: "dashboard_backend_unreachable" }, { status: 502 });
  } finally {
    clearTimeout(timeout);
  }
}
