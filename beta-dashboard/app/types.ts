export type BetaInstallation = {
  tester_code: string;
  installation_id: string | null;
  consent_version: string | null;
  consented_at: string | null;
  self_section: string | null;
  self_plc: string | null;
  manufacturer: string | null;
  model: string | null;
  android_version: string | null;
  app_version: string | null;
  app_version_code: number | null;
  detected_sections: string[];
  detected_groups: string[];
  schedule_status: "not_asked" | "confirmed" | "mismatch";
  auto_attendance_enabled: boolean | null;
  last_sync_status: string | null;
  last_sync_at: string | null;
  first_seen_at: string | null;
  last_seen_at: string | null;
  claimed_at: string | null;
};

export type BetaEvent = {
  id: number;
  tester_code: string;
  event_type: string;
  occurred_at: string;
  payload: Record<string, unknown>;
  received_at: string;
};

export type ScheduleConfirmation = {
  id: number;
  tester_code: string;
  confirmed_at: string;
  status: "confirmed" | "mismatch";
  selected_date: string;
  app_session_count: number;
  note: string | null;
  snapshot: Record<string, unknown>;
};

export type AttendanceDecision = {
  id: string;
  tester_code: string;
  occurred_at: string;
  method: "auto" | "manual";
  session_label: string | null;
  latitude: number | null;
  longitude: number | null;
  accuracy_m: number | null;
  location_age_ms: number | null;
  distance_m: number | null;
  gate_allowed: boolean | null;
  gate_reason: string | null;
  lms_markable: boolean | null;
  outcome: "blocked" | "attempted" | "present" | "failed" | "unknown";
  suspected_incorrect: boolean;
  details: Record<string, unknown>;
};

export type IssueReport = {
  id: string;
  tester_code: string;
  category: "issue" | "suggestion" | "general";
  title: string;
  description: string;
  status: "new" | "seen" | "investigating" | "waiting_on_tester" | "resolved" | "wont_fix";
  owner_note: string | null;
  linked_attendance_id: string | null;
  app_context: Record<string, unknown>;
  created_at: string;
  updated_at: string;
};

export type ReportAttachment = {
  id: string;
  report_id: string;
  storage_path: string;
  content_type: string;
  size_bytes: number;
  created_at: string;
  signed_url: string | null;
};

export type DashboardData = {
  generated_at: string;
  config: {
    auto_attendance_blocked: boolean;
    minimum_version_code: number;
    beta_starts_at: string;
    beta_ends_at: string;
    updated_at: string;
  };
  installations: BetaInstallation[];
  events: BetaEvent[];
  schedule_confirmations: ScheduleConfirmation[];
  attendance_decisions: AttendanceDecision[];
  reports: IssueReport[];
  attachments: ReportAttachment[];
  control_audit: Array<{
    id: number;
    action: "stop_auto_attendance" | "allow_auto_attendance";
    reason: string | null;
    actor_label: string;
    created_at: string;
  }>;
};

export function emptyDashboard(): DashboardData {
  const installations: BetaInstallation[] = Array.from({ length: 10 }, (_, index) => ({
    tester_code: `T-${String(index + 1).padStart(2, "0")}`,
    installation_id: null,
    consent_version: null,
    consented_at: null,
    self_section: null,
    self_plc: null,
    manufacturer: null,
    model: null,
    android_version: null,
    app_version: null,
    app_version_code: null,
    detected_sections: [],
    detected_groups: [],
    schedule_status: "not_asked",
    auto_attendance_enabled: null,
    last_sync_status: null,
    last_sync_at: null,
    first_seen_at: null,
    last_seen_at: null,
    claimed_at: null,
  }));
  return {
    generated_at: new Date().toISOString(),
    config: {
      auto_attendance_blocked: false,
      minimum_version_code: 1,
      beta_starts_at: "2026-08-16T18:30:00.000Z",
      beta_ends_at: "2026-08-23T18:30:00.000Z",
      updated_at: new Date().toISOString(),
    },
    installations,
    events: [],
    schedule_confirmations: [],
    attendance_decisions: [],
    reports: [],
    attachments: [],
    control_audit: [],
  };
}
