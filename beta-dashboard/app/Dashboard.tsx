"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import type {
  AttendanceDecision,
  BetaInstallation,
  DashboardData,
  IssueReport,
} from "./types";
import { profileMatchStatus, testerDisplayName } from "./roster";
import { confirmedPresentCount, reliabilityLabel, reliabilityNote } from "./metrics";

type Props = {
  initialData: DashboardData;
  ownerLabel: string;
};

type ControlMode = "stop" | "allow" | null;

const IST = new Intl.DateTimeFormat("en-IN", {
  timeZone: "Asia/Kolkata",
  day: "2-digit",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const TIME_IST = new Intl.DateTimeFormat("en-IN", {
  timeZone: "Asia/Kolkata",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

export default function Dashboard({ initialData, ownerLabel }: Props) {
  const [data, setData] = useState(initialData);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [controlMode, setControlMode] = useState<ControlMode>(null);
  const [controlToken, setControlToken] = useState("");
  const [controlPending, setControlPending] = useState(false);
  const [selectedReportId, setSelectedReportId] = useState<string | null>(null);
  const [selectedAttendanceId, setSelectedAttendanceId] = useState<string | null>(null);
  const [sectionFilter, setSectionFilter] = useState("all");
  const [plcFilter, setPlcFilter] = useState("all");
  const [seenFilter, setSeenFilter] = useState("all");
  const [search, setSearch] = useState("");
  const [nowMs, setNowMs] = useState(() => Date.parse(initialData.generated_at));

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setRefreshing(true);
    try {
      const response = await fetch("/api/dashboard", { cache: "no-store" });
      if (!response.ok) throw new Error(`Dashboard request failed (${response.status})`);
      const next = await response.json() as DashboardData;
      setData(next);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not reach beta data.");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    const refreshNow = () => {
      setNowMs(Date.now());
      void refresh(true);
    };
    const initialRefresh = window.setTimeout(refreshNow, 0);
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") refreshNow();
    }, 10_000);
    return () => {
      window.clearTimeout(initialRefresh);
      window.clearInterval(timer);
    };
  }, [refresh]);

  const suspected = data.attendance_decisions.filter((item) => item.suspected_incorrect);
  const latestSuspected = suspected[0] ?? null;
  const claimed = data.installations.filter((item) => item.installation_id);
  const seenToday = claimed.filter((item) => isTodayIst(item.last_seen_at));
  const stale = claimed.filter((item) => seenState(item.last_seen_at, nowMs) === "stale");
  const todayAttendance = data.attendance_decisions.filter((item) => isTodayIst(item.occurred_at));
  const presentToday = confirmedPresentCount(todayAttendance);
  const reliability = {
    failures: data.reliability?.failures ?? 0,
    cancellations: data.reliability?.cancellations ?? 0,
    critical_exits: data.reliability?.critical_exits ?? 0,
    low_memory_exits: data.reliability?.low_memory_exits ?? 0,
    other_exits: data.reliability?.other_exits ?? 0,
  };
  const triageReports = data.reports.filter((item) => item.status === "new" || item.status === "seen");
  const selectedReport = data.reports.find((item) => item.id === selectedReportId) ?? null;
  const selectedAttendance = data.attendance_decisions.find((item) => item.id === selectedAttendanceId) ?? null;
  const sections = unique(data.installations.map((item) => item.self_section).filter(Boolean) as string[]);
  const plcs = unique(data.installations.map((item) => item.self_plc).filter(Boolean) as string[]);

  const filteredInstallations = useMemo(() => data.installations.filter((item) => {
    if (sectionFilter !== "all" && item.self_section !== sectionFilter) return false;
    if (plcFilter !== "all" && item.self_plc !== plcFilter) return false;
    if (seenFilter !== "all" && seenState(item.last_seen_at, nowMs) !== seenFilter) return false;
    const haystack = `${item.tester_code} ${item.support_name ?? ""} ${item.manufacturer ?? ""} ${item.model ?? ""}`.toLowerCase();
    return haystack.includes(search.trim().toLowerCase());
  }), [data.installations, sectionFilter, plcFilter, seenFilter, search, nowMs]);

  async function submitControl(event: FormEvent) {
    event.preventDefault();
    if (!controlMode) return;
    const requiredToken = controlMode === "stop" ? "STOP" : "ALLOW";
    if (controlToken !== requiredToken) return;
    setControlPending(true);
    try {
      const response = await fetch("/api/dashboard", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          action: "control",
          blocked: controlMode === "stop",
          reason: controlMode === "stop" ? "Owner stopped auto attendance from beta dashboard" : "Owner allowed auto attendance from beta dashboard",
        }),
      });
      if (!response.ok) throw new Error("The control request was not accepted.");
      setControlMode(null);
      setControlToken("");
      await refresh(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not change auto-attendance control.");
    } finally {
      setControlPending(false);
    }
  }

  const dataAgeSeconds = Math.max(0, Math.round((nowMs - Date.parse(data.generated_at)) / 1000));
  const teachingDay = teachingDayLabel(new Date(nowMs));

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">PRIVATE BETA COMMAND DESK</p>
          <h1>ISDM Companion Beta Control</h1>
        </div>
        <div className="owner-meta">
          <span>{teachingDay}</span>
          <span>{TIME_IST.format(new Date(nowMs))} IST · Data {dataAgeSeconds}s ago</span>
          <span>{ownerLabel}</span>
          <button className="text-button" onClick={() => void refresh()} disabled={refreshing}>
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </header>

      {error && (
        <div className="state-banner warn" role="status">
          <strong>Showing the last available data.</strong> {error}
        </div>
      )}

      <section className={`critical ${latestSuspected ? "active" : "clear"}`} role={latestSuspected ? "alert" : "status"}>
        {latestSuspected ? (
          <>
            <div>
              <p className="eyebrow">CRITICAL · SUSPECTED INCORRECT ATTENDANCE</p>
              <h2>{latestSuspected.tester_code} · {latestSuspected.session_label ?? "Attendance Session"}</h2>
              <p>{formatIst(latestSuspected.occurred_at)} · {capitalize(latestSuspected.method)} · {capitalize(latestSuspected.outcome)}</p>
            </div>
            <div className="critical-actions">
              <button className="secondary-button" onClick={() => setSelectedAttendanceId(latestSuspected.id)}>Review this mark</button>
              <button className="danger-button" onClick={() => openControl("stop")}>Stop auto attendance for everyone</button>
            </div>
          </>
        ) : (
          <>
            <div>
              <p className="eyebrow">ATTENDANCE SAFETY</p>
              <h2>No suspected incorrect marks</h2>
            </div>
            <p className="quiet">Last checked {TIME_IST.format(new Date(data.generated_at))} IST</p>
          </>
        )}
      </section>

      <section className={`control-strip ${data.config.auto_attendance_blocked ? "stopped" : "live"}`} aria-live="polite">
        <div>
          <p className="eyebrow">GLOBAL SAFETY CONTROL</p>
          <h2>{data.config.auto_attendance_blocked ? "Auto attendance is stopped for everyone" : "Auto attendance for all testers"}</h2>
          <p>
            {data.config.auto_attendance_blocked
              ? `Server block active since ${formatIst(data.config.updated_at)}.`
              : "Each phone must pass a fresh remote check immediately before an automatic mark."}
          </p>
        </div>
        <span className={`status-chip ${data.config.auto_attendance_blocked ? "rust" : "teal"}`}>
          {data.config.auto_attendance_blocked ? "STOPPED" : "LIVE"}
        </span>
        <button
          className={data.config.auto_attendance_blocked ? "secondary-button" : "danger-button"}
          onClick={() => openControl(data.config.auto_attendance_blocked ? "allow" : "stop")}
        >
          {data.config.auto_attendance_blocked ? "Allow auto attendance again" : "Stop auto attendance for everyone"}
        </button>
      </section>

      <section className="pulse-grid" aria-label="Today’s beta pulse">
        <Pulse label="Seen today" value={`${seenToday.length} / 10`} note={`${stale.length} stale · ${10 - seenToday.length} unseen`} />
        <Pulse label="Marks today" value={`${presentToday} Present`} note={`${todayAttendance.length} decisions`} />
        <Pulse label="Suspected" value={String(suspected.length)} note={suspected.length ? "Requires review" : "Clear"} critical={suspected.length > 0} />
        <Pulse label="Inbox" value={`${triageReports.length} open`} note={`${data.reports.filter((item) => item.category === "suggestion").length} suggestions total`} />
        <Pulse
          label="Reliability"
          value={reliabilityLabel(reliability)}
          note={reliabilityNote(reliability)}
          critical={reliability.failures > 0}
        />
      </section>

      <section className="panel roster-panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">TEN TESTERS</p>
            <h2>Device and cohort roster</h2>
          </div>
          <div className="filters" aria-label="Roster filters">
            <label>
              <span>Section</span>
              <select value={sectionFilter} onChange={(event) => setSectionFilter(event.target.value)}>
                <option value="all">All</option>
                {sections.map((section) => <option key={section}>{section}</option>)}
              </select>
            </label>
            <label>
              <span>PLC</span>
              <select value={plcFilter} onChange={(event) => setPlcFilter(event.target.value)}>
                <option value="all">All</option>
                {plcs.map((plc) => <option key={plc}>{plc}</option>)}
              </select>
            </label>
            <label>
              <span>Seen</span>
              <select value={seenFilter} onChange={(event) => setSeenFilter(event.target.value)}>
                <option value="all">All</option>
                <option value="live">Live</option>
                <option value="stale">Stale</option>
                <option value="unseen">Unseen</option>
              </select>
            </label>
            <label className="search-field">
              <span>Search</span>
              <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="T-04 or Galaxy" />
            </label>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Code</th><th>Name</th><th>Section</th><th>PLC (self)</th><th>Detected cohort</th><th>Phone</th><th>Android / app</th><th>Last seen</th><th>Sync</th><th>Schedule</th><th>Today’s marks</th><th>Auto</th>
              </tr>
            </thead>
            <tbody>
              {filteredInstallations.map((item) => (
                <TesterRow key={item.tester_code} item={item} attendance={todayAttendance.filter((mark) => mark.tester_code === item.tester_code)} remoteBlocked={data.config.auto_attendance_blocked} nowMs={nowMs} onAttendance={setSelectedAttendanceId} />
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="lower-grid">
        <section className="panel inbox-panel">
          <div className="section-heading compact">
            <div><p className="eyebrow">REPORT INBOX</p><h2>Issues and suggestions</h2></div>
            <span className="count-label">{triageReports.length} need triage</span>
          </div>
          {data.reports.length === 0 ? (
            <Empty title="No reports yet" copy="Issue Reports and Suggestions sent from the app will appear here with screenshots and recent diagnostics." />
          ) : (
            <div className="inbox-list">
              {data.reports.map((report) => (
                <button className="inbox-row" key={report.id} onClick={() => setSelectedReportId(report.id)}>
                  <span className={`type-chip ${report.category}`}>{report.category}</span>
                  <span className="inbox-copy"><strong>{report.title}</strong><small>{report.tester_code} · {formatIst(report.created_at)}</small></span>
                  <span className="status-chip neutral">{statusLabel(report.status)}</span>
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="panel attendance-panel">
          <div className="section-heading compact">
            <div><p className="eyebrow">TODAY</p><h2>Attendance decisions</h2></div>
            <span className="count-label">{todayAttendance.length} total</span>
          </div>
          {todayAttendance.length === 0 ? (
            <Empty title="No attendance decisions today" copy="Manual blocks, automatic attempts, and confirmed LMS outcomes will appear here." />
          ) : (
            <div className="attendance-list">
              {todayAttendance.map((mark) => (
                <button className="attendance-row" key={mark.id} onClick={() => setSelectedAttendanceId(mark.id)}>
                  <span className="mono">{TIME_IST.format(new Date(mark.occurred_at))}</span>
                  <span><strong>{mark.tester_code}</strong><small>{mark.session_label ?? "Attendance Session"}</small></span>
                  <span>{capitalize(mark.method)}</span>
                  <span className={`status-chip ${mark.suspected_incorrect ? "rust" : mark.outcome === "present" ? "teal" : "amber"}`}>{mark.suspected_incorrect ? "SUSPECTED" : mark.outcome}</span>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>

      <footer>
        Beta window 17–23 Aug 2026 · Teaching-day metrics use Asia/Kolkata · Detailed events retained for 30 days
      </footer>

      {controlMode && (
        <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) closeControl(); }}>
          <form className="modal danger-modal" role="dialog" aria-modal="true" aria-labelledby="control-title" onSubmit={submitControl}>
            <p className="eyebrow">GLOBAL SAFETY CONTROL</p>
            <h2 id="control-title">{controlMode === "stop" ? "Stop auto attendance for everyone?" : "Allow auto attendance again?"}</h2>
            <p>{controlMode === "stop"
              ? "This sets a server block for all ten testers. Phones will refuse automatic marks when their immediate preflight check sees the block. Manual marking remains available."
              : "This lifts the server block. A tester whose on-device switch is still on can auto-mark at the next eligible window."}</p>
            <label className="confirm-field">
              <span>Type {controlMode === "stop" ? "STOP" : "ALLOW"} to confirm</span>
              <input value={controlToken} onChange={(event) => setControlToken(event.target.value.toUpperCase())} />
            </label>
            <div className="modal-actions">
              <button type="button" className="secondary-button" onClick={closeControl}>{controlMode === "stop" ? "Keep auto attendance running" : "Keep it stopped"}</button>
              <button type="submit" className="danger-button" disabled={controlPending || controlToken !== (controlMode === "stop" ? "STOP" : "ALLOW")}>
                {controlPending ? "Saving…" : controlMode === "stop" ? "Stop auto attendance" : "Allow auto attendance"}
              </button>
            </div>
          </form>
        </div>
      )}

      {selectedReport && <ReportModal report={selectedReport} data={data} onClose={() => setSelectedReportId(null)} onSaved={() => refresh(true)} />}
      {selectedAttendance && <AttendanceModal attendance={selectedAttendance} onClose={() => setSelectedAttendanceId(null)} onSaved={() => refresh(true)} />}
      {loading && <div className="loading-line" role="status">Loading beta data…</div>}
    </main>
  );

  function openControl(mode: Exclude<ControlMode, null>) {
    setControlToken("");
    setControlMode(mode);
  }
  function closeControl() {
    if (controlPending) return;
    setControlMode(null);
    setControlToken("");
  }
}

function Pulse({ label, value, note, critical = false }: { label: string; value: string; note: string; critical?: boolean }) {
  return <article className={`pulse-card ${critical ? "critical-card" : ""}`}><p>{label}</p><strong>{value}</strong><small>{note}</small></article>;
}

function TesterRow({ item, attendance, remoteBlocked, nowMs, onAttendance }: { item: BetaInstallation; attendance: AttendanceDecision[]; remoteBlocked: boolean; nowMs: number; onAttendance: (id: string) => void }) {
  const state = seenState(item.last_seen_at, nowMs);
  const detected = [
    item.detected_sections.length ? `Section ${item.detected_sections.join(" & ")}` : null,
    item.detected_groups.length ? `Group ${item.detected_groups.join(", ")}` : null,
  ].filter(Boolean).join(" · ") || "—";
  const present = attendance.filter((mark) => mark.outcome === "present").length;
  const profileStatus = profileMatchStatus(item);
  const worst = attendance.find((mark) => mark.suspected_incorrect) ?? attendance.find((mark) => mark.outcome === "failed" || mark.outcome === "blocked") ?? attendance[0];
  return (
    <tr className={!item.installation_id ? "unclaimed" : ""}>
      <td className="mono sticky-code">{item.tester_code}</td>
      <td><strong>{testerDisplayName(item)}</strong><small className="cell-note">{profileStatus === "confirmed" ? "LMS confirmed" : profileStatus === "mismatch" ? "Profile mismatch" : "LMS cohort missing"}</small></td>
      <td>{item.self_section ?? "—"}</td>
      <td>{item.self_plc ?? "—"}</td>
      <td>{detected}</td>
      <td>{[item.manufacturer, item.model].filter(Boolean).join(" ") || "Not installed"}</td>
      <td>{item.android_version ? `${item.android_version} / ${item.app_version ?? "—"}` : "—"}</td>
      <td><span className={`status-chip ${state === "live" ? "teal" : state === "stale" ? "amber" : "neutral"}`}>{state}</span><small className="cell-note">{item.last_seen_at ? formatIst(item.last_seen_at) : "Never"}</small></td>
      <td>{item.last_sync_status ?? "—"}</td>
      <td><span className={`status-chip ${item.schedule_status === "confirmed" ? "teal" : item.schedule_status === "mismatch" ? "amber" : "neutral"}`}>{item.schedule_status.replace("_", " ")}</span></td>
      <td>{attendance.length ? <button className="cell-button" onClick={() => worst && onAttendance(worst.id)}>{present}/{attendance.length} present{worst?.suspected_incorrect ? " · suspected" : ""}</button> : "—"}</td>
      <td>{remoteBlocked ? <span className="status-chip rust">BLOCKED</span> : item.auto_attendance_enabled == null ? "—" : item.auto_attendance_enabled ? <span className="status-chip teal">ON</span> : <span className="status-chip neutral">OFF</span>}</td>
    </tr>
  );
}

function ReportModal({ report, data, onClose, onSaved }: { report: IssueReport; data: DashboardData; onClose: () => void; onSaved: () => void }) {
  const [status, setStatus] = useState(report.status);
  const [note, setNote] = useState(report.owner_note ?? "");
  const [saving, setSaving] = useState(false);
  const attachments = data.attachments.filter((item) => item.report_id === report.id);

  async function save() {
    setSaving(true);
    const response = await fetch("/api/dashboard", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ action: "report", report_id: report.id, status, owner_note: note }),
    });
    setSaving(false);
    if (response.ok) { onSaved(); onClose(); }
  }

  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="modal detail-modal" role="dialog" aria-modal="true" aria-labelledby="report-title">
      <div className="detail-header">
        <div><span className={`type-chip ${report.category}`}>{report.category}</span><h2 id="report-title">{report.title}</h2><p>{report.tester_code} · Received {formatIst(report.created_at)}</p></div>
        <button className="close-button" onClick={onClose} aria-label="Close report">Close</button>
      </div>
      <div className="report-copy">{report.description}</div>
      {attachments.length > 0 && <div className="evidence-grid">{attachments.map((item, index) => item.signed_url ? <a key={item.id} href={item.signed_url} target="_blank" rel="noreferrer"><img src={item.signed_url} alt={`Evidence ${index + 1} of ${attachments.length}`} /></a> : null)}</div>}
      <div className="edit-grid">
        <label><span>Status</span><select value={status} onChange={(event) => setStatus(event.target.value as IssueReport["status"])}>{["new", "seen", "investigating", "waiting_on_tester", "resolved", "wont_fix"].map((item) => <option key={item} value={item}>{statusLabel(item as IssueReport["status"])}</option>)}</select></label>
        <label><span>Private owner note</span><textarea value={note} onChange={(event) => setNote(event.target.value)} rows={4} /></label>
      </div>
      <div className="modal-actions"><button className="secondary-button" onClick={onClose}>Cancel</button><button className="primary-button" onClick={() => void save()} disabled={saving}>{saving ? "Saving…" : "Save triage"}</button></div>
    </section>
  </div>;
}

function AttendanceModal({ attendance, onClose, onSaved }: { attendance: AttendanceDecision; onClose: () => void; onSaved: () => void }) {
  const [saving, setSaving] = useState(false);
  async function setSuspected(value: boolean) {
    setSaving(true);
    const response = await fetch("/api/dashboard", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ action: "attendance", attendance_id: attendance.id, suspected_incorrect: value }),
    });
    setSaving(false);
    if (response.ok) { onSaved(); onClose(); }
  }
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="modal detail-modal" role="dialog" aria-modal="true" aria-labelledby="attendance-title">
      <div className="detail-header"><div><span className={`status-chip ${attendance.suspected_incorrect ? "rust" : "teal"}`}>{attendance.suspected_incorrect ? "SUSPECTED" : attendance.outcome}</span><h2 id="attendance-title">{attendance.session_label ?? "Attendance decision"}</h2><p>{attendance.tester_code} · {formatIst(attendance.occurred_at)}</p></div><button className="close-button" onClick={onClose}>Close</button></div>
      <dl className="decision-grid">
        <Row term="Method" value={capitalize(attendance.method)} /><Row term="LMS outcome" value={capitalize(attendance.outcome)} /><Row term="Location gate" value={attendance.gate_allowed == null ? "Not recorded" : attendance.gate_allowed ? "Allowed" : "Blocked"} /><Row term="Gate reason" value={attendance.gate_reason ?? "—"} />
        <Row term="Coordinates" value={attendance.latitude != null && attendance.longitude != null ? `${attendance.latitude.toFixed(5)}, ${attendance.longitude.toFixed(5)}` : "Not recorded"} /><Row term="Accuracy" value={attendance.accuracy_m != null ? `±${Math.round(attendance.accuracy_m)} m` : "—"} /><Row term="Distance" value={attendance.distance_m != null ? `${Math.round(attendance.distance_m)} m from campus centre` : "—"} /><Row term="Location age" value={attendance.location_age_ms != null ? `${Math.round(attendance.location_age_ms / 1000)} s` : "—"} />
      </dl>
      <p className="privacy-note">Coordinates are shown only for this attendance decision. No location trail is collected.</p>
      <div className="modal-actions"><button className="secondary-button" onClick={onClose}>Close</button><button className={attendance.suspected_incorrect ? "primary-button" : "danger-button"} onClick={() => void setSuspected(!attendance.suspected_incorrect)} disabled={saving}>{saving ? "Saving…" : attendance.suspected_incorrect ? "Resolve suspicion" : "Mark as suspected"}</button></div>
    </section>
  </div>;
}

function Row({ term, value }: { term: string; value: string }) { return <div><dt>{term}</dt><dd>{value}</dd></div>; }
function Empty({ title, copy }: { title: string; copy: string }) { return <div className="empty"><strong>{title}</strong><p>{copy}</p></div>; }
function unique(values: string[]) { return [...new Set(values)].sort(); }
function capitalize(value: string) { return value.charAt(0).toUpperCase() + value.slice(1).replaceAll("_", " "); }
function statusLabel(value: IssueReport["status"]) { return value.split("_").map(capitalize).join(" "); }
function formatIst(value: string | null) { return value ? `${IST.format(new Date(value))} IST` : "Never"; }
function isTodayIst(value: string | null) {
  if (!value) return false;
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" });
  return parts.format(new Date(value)) === parts.format(new Date());
}
function seenState(value: string | null, now: number): "live" | "stale" | "unseen" {
  if (!value || !isTodayIst(value)) return "unseen";
  return now - Date.parse(value) <= 15 * 60 * 1000 ? "live" : "stale";
}
function teachingDayLabel(date: Date) {
  const day = Math.floor((date.getTime() - Date.parse("2026-08-16T18:30:00Z")) / 86_400_000) + 1;
  if (day < 1) return "Beta opens Mon 17 Aug";
  if (day <= 5) return `Teaching day ${day} of 5`;
  if (day <= 7) return `Watch day ${day - 5} of 2`;
  return "Beta window ended";
}
