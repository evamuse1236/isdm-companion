"use client";

import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useState } from "react";
import type {
  AttendanceDecision,
  BetaInstallation,
  DashboardData,
  IssueReport,
} from "./types";
import { profileMatchStatus, testerDisplayName, testerVersionLabel } from "./roster";
import { attendanceTrend, confirmedPresentCount, reliabilityLabel, reliabilityNote, relativeTime, reportAgeLabel } from "./metrics";
import type { TrendPoint } from "./metrics";

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
  const [selectedTesterCode, setSelectedTesterCode] = useState<string | null>(null);
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

  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

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
  const selectedTester = data.installations.find((item) => item.tester_code === selectedTesterCode) ?? null;
  const sections = unique(data.installations.map((item) => item.self_section).filter(Boolean) as string[]);
  const plcs = unique(data.installations.map((item) => item.self_plc).filter(Boolean) as string[]);
  const lastAudit = data.control_audit[0] ?? null;

  const filteredInstallations = useMemo(() => data.installations.filter((item) => {
    if (sectionFilter !== "all" && item.self_section !== sectionFilter) return false;
    if (plcFilter !== "all" && item.self_plc !== plcFilter) return false;
    if (seenFilter !== "all" && seenState(item.last_seen_at, nowMs) !== seenFilter) return false;
    const haystack = `${item.tester_code} ${item.support_name ?? ""} ${item.manufacturer ?? ""} ${item.model ?? ""}`.toLowerCase();
    return haystack.includes(search.trim().toLowerCase());
  }), [data.installations, sectionFilter, plcFilter, seenFilter, search, nowMs]);

  const minuteBucket = Math.floor(nowMs / 60_000);
  const trend = useMemo(
    () => attendanceTrend(data.attendance_decisions, 7, new Date(minuteBucket * 60_000)),
    [data.attendance_decisions, minuteBucket],
  );
  const oldestOpenReport = [...triageReports].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at))[0] ?? null;
  const triageSorted = [...triageReports].sort((a, b) =>
    (a.status === "new" ? 0 : 1) - (b.status === "new" ? 0 : 1) ||
    Date.parse(a.created_at) - Date.parse(b.created_at),
  );

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
    <main className="desk">
      <div className="ambient" aria-hidden="true"><i className="w1" /><i className="w2" /><i className="w3" /></div>

      <header className="desk-head">
        <div className="rise">
          <p className="eyebrow">PRIVATE BETA COMMAND DESK</p>
          <h1>ISDM Companion <em>Beta Control</em></h1>
          <p className="subtitle">{teachingDay} · Ten testers · Asia/Kolkata</p>
        </div>
        <div className="head-side rise" style={{ animationDelay: ".06s" }}>
          <span className="clockpill mono">{TIME_IST.format(new Date(nowMs))} IST</span>
          <span className={`freshpill ${refreshing ? "syncing" : dataAgeSeconds <= 15 ? "fresh" : "aging"}`}>
            <i className={`lamp ${dataAgeSeconds <= 15 || refreshing ? "" : "slow"}`} />
            {refreshing ? "Syncing…" : `Data ${dataAgeSeconds}s ago`}
          </span>
          <span className="ownerchip">{ownerLabel}</span>
          <button className="text-button" onClick={() => void refresh()} disabled={refreshing}>
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </header>

      {error && (
        <div className="state-banner warn rise" role="status">
          <strong>Showing the last available data.</strong> {error}
        </div>
      )}

      <section className={`critical ${latestSuspected ? "active" : "clear"} rise`} style={{ animationDelay: ".1s" }} role={latestSuspected ? "alert" : "status"}>
        {latestSuspected ? (
          <>
            <span className="crit-glyph" aria-hidden="true">!</span>
            <div className="crit-copy">
              <p className="eyebrow alert-eyebrow">CRITICAL · SUSPECTED INCORRECT ATTENDANCE</p>
              <h2>{latestSuspected.tester_code} · {latestSuspected.session_label ?? "Attendance Session"}</h2>
              <p>{formatIst(latestSuspected.occurred_at)} · {capitalize(latestSuspected.method)} mark · {capitalize(latestSuspected.outcome)}</p>
            </div>
            <div className="critical-actions">
              <button className="secondary-button" onClick={() => setSelectedAttendanceId(latestSuspected.id)}>Review this mark</button>
              <button className="danger-button" onClick={() => openControl("stop")}>Stop auto attendance for everyone</button>
            </div>
          </>
        ) : (
          <>
            <div className="crit-copy">
              <p className="eyebrow">ATTENDANCE SAFETY</p>
              <h2>No suspected incorrect marks</h2>
              <p>
                {lastAudit
                  ? <>Last control action {lastAudit.action === "stop_auto_attendance" ? "stopped" : "allowed"} {relativeTime(lastAudit.created_at, nowMs)} · {data.control_audit.length} logged</>
                  : "No control actions logged yet."}
              </p>
            </div>
            <span className="allclear"><i className="lamp breathe" />All clear</span>
          </>
        )}
      </section>

      <section className={`control-strip ${data.config.auto_attendance_blocked ? "stopped" : "live"} rise`} aria-live="polite" style={{ animationDelay: ".14s" }}>
        <div className="strip-main">
          <span className={`master-lamp ${data.config.auto_attendance_blocked ? "rust" : "green"}`} aria-hidden="true">
            <i />
          </span>
          <div>
            <p className="eyebrow">GLOBAL SAFETY CONTROL</p>
            <h2>{data.config.auto_attendance_blocked ? "Auto attendance is stopped for everyone" : "Auto attendance for all testers"}</h2>
            <p className="quiet">
              {data.config.auto_attendance_blocked
                ? `Server block active since ${formatIst(data.config.updated_at)}.`
                : "Each phone must pass a fresh remote check immediately before an automatic mark."}
              {lastAudit && (
                <span className="auditline">
                  {" "}Last action {lastAudit.action === "stop_auto_attendance" ? "stopped" : "allowed"} {relativeTime(lastAudit.created_at, nowMs)} · actor {lastAudit.actor_label}.
                </span>
              )}
            </p>
          </div>
        </div>
        <div className="strip-side">
          <span className={`status-chip ${data.config.auto_attendance_blocked ? "rust" : "teal"}`}>
            {data.config.auto_attendance_blocked ? "STOPPED" : "LIVE"}
          </span>
          <button
            className={data.config.auto_attendance_blocked ? "secondary-button" : "danger-button"}
            onClick={() => openControl(data.config.auto_attendance_blocked ? "allow" : "stop")}
          >
            {data.config.auto_attendance_blocked ? "Allow auto attendance again" : "Stop auto attendance for everyone"}
          </button>
        </div>
      </section>

      <section className="pulse-grid" aria-label="Today’s beta pulse">
        <Tile label="Seen today" delay=".16s" value={`${seenToday.length} / 10`} note={`${stale.length} stale · ${10 - seenToday.length} unseen`} tone="mint" />
        <Tile
          label="Marks today"
          delay=".19s"
          value={`${presentToday} Present`}
          note={`${todayAttendance.length} decisions today`}
          tone="teal"
          spark={<Sparkbars points={trend} />}
          sparkNote="7-day marks"
        />
        <Tile label="Suspected" delay=".22s" value={String(suspected.length)} note={suspected.length ? "Requires review" : "Clear"} critical={suspected.length > 0} tone="blush" />
        <Tile
          label="Inbox"
          delay=".25s"
          value={`${triageReports.length} open`}
          note={oldestOpenReport ? `Oldest waiting ${reportAgeLabel(oldestOpenReport.created_at, new Date(nowMs))}` : "Nothing waiting"}
          tone="purple"
        />
        <Tile label="Reliability" delay=".28s" value={reliabilityLabel(reliability)} note={reliabilityNote(reliability)} critical={reliability.failures > 0} tone="butter" />
      </section>

      <section className="panel roster-panel rise" style={{ animationDelay: ".3s" }}>
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
                <th>Code</th><th>Name</th><th>Cohort</th><th>Device</th><th>Last seen</th><th>Sync</th><th>Schedule</th><th>Today’s marks</th><th>Auto</th>
              </tr>
            </thead>
            <tbody>
              {filteredInstallations.map((item) => (
                <TesterRow
                  key={item.tester_code}
                  item={item}
                  attendance={todayAttendance.filter((mark) => mark.tester_code === item.tester_code)}
                  remoteBlocked={data.config.auto_attendance_blocked}
                  nowMs={nowMs}
                  onAttendance={setSelectedAttendanceId}
                  onTester={setSelectedTesterCode}
                />
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="lower-grid">
        <section className="panel inbox-panel rise" style={{ animationDelay: ".34s" }}>
          <div className="section-heading compact">
            <div><p className="eyebrow">REPORT INBOX</p><h2>Issues and suggestions</h2></div>
            <span className="count-label">{triageReports.length} need triage</span>
          </div>
          {data.reports.length === 0 ? (
            <Empty title="No reports yet" copy="Issue Reports and Suggestions sent from the app will appear here with screenshots and recent diagnostics." />
          ) : (
            <div className="inbox-list">
              {triageSorted.map((report) => (
                <button className="inbox-row" key={report.id} onClick={() => setSelectedReportId(report.id)}>
                  <span className={`type-chip ${report.category}`}>{report.category}</span>
                  <span className="inbox-copy"><strong>{report.title}</strong><small>{report.tester_code} · {formatIst(report.created_at)}</small></span>
                  <span className={`agechip ${(nowMs - Date.parse(report.created_at)) > 24 * 3_600_000 ? "hot" : ""}`}>{reportAgeLabel(report.created_at, new Date(nowMs))}</span>
                  <span className="status-chip neutral">{statusLabel(report.status)}</span>
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="panel attendance-panel rise" style={{ animationDelay: ".38s" }}>
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
                  <span className="mono feed-time">{TIME_IST.format(new Date(mark.occurred_at))}</span>
                  <span className={`method-chip`} data-method={mark.method}>{mark.method.toUpperCase()}</span>
                  <span><strong>{mark.tester_code}</strong><small>{mark.session_label ?? "Attendance Session"}</small></span>
                  <span className={`status-chip ${mark.suspected_incorrect ? "rust" : mark.outcome === "present" ? "teal" : "amber"}`}>{mark.suspected_incorrect ? "SUSPECTED" : mark.outcome}</span>
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="panel activity-panel rise" style={{ animationDelay: ".42s" }}>
          <div className="section-heading compact">
            <div><p className="eyebrow">LIVE ACTIVITY</p><h2>Phone events</h2></div>
            <span className="count-label">{data.events.length} recent</span>
          </div>
          {data.events.length === 0 ? (
            <Empty title="No phone events yet" copy="Syncs, marks, cancellations, and process exits stream in here as phones report." />
          ) : (
            <ol className="feed">
              {data.events.slice(0, 10).map((event) => (
                <li className="feed-row" key={event.id}>
                  <span className="feed-time mono">{relativeTime(event.occurred_at, nowMs)}</span>
                  <span className="feed-body"><strong>{humanizeEvent(event.event_type)}</strong><small>{event.tester_code}{payloadHint(event.payload)}</small></span>
                </li>
              ))}
            </ol>
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
      {selectedTester && <TesterModal code={selectedTester.tester_code} data={data} nowMs={nowMs} onClose={() => setSelectedTesterCode(null)} onReviewAttendance={setSelectedAttendanceId} />}
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

function Tile({ label, value, note, delay, critical = false, tone = "neutral", spark, sparkNote }: {
  label: string;
  value: string;
  note: string;
  delay?: string;
  critical?: boolean;
  tone?: "mint" | "teal" | "blush" | "purple" | "butter" | "neutral";
  spark?: ReactNode;
  sparkNote?: string;
}) {
  return (
    <article
      className={`tile tone-${tone} ${critical ? "critical-tile" : ""}`}
      style={delay ? { animationDelay: delay } : undefined}
    >
      <div className="pulse-head"><p>{label}</p>{sparkNote && <small className="spark-note">{sparkNote}</small>}</div>
      <strong>{value}</strong>
      {spark}
      <small className="tile-note">{note}</small>
    </article>
  );
}

function Sparkbars({ points }: { points: TrendPoint[] }) {
  const max = Math.max(1, ...points.map((point) => point.marks));
  return (
    <svg className="sparkbars" viewBox={`0 0 ${points.length * 15} 32`} role="img" aria-label="Marks per day over the last seven days">
      {points.map((point, index) => {
        const barHeight = point.marks === 0 ? 2 : Math.max(4, Math.round((point.marks / max) * 26));
        const isLast = index === points.length - 1;
        return (
          <g key={point.key}>
            <title>{`${point.label}: ${point.marks} marks, ${point.present} present${point.suspected ? `, ${point.suspected} suspected` : ""}`}</title>
            <rect x={index * 15 + 3} y={30 - barHeight} width={9} height={barHeight} rx={3} className={isLast ? "spark-bar now" : "spark-bar"} />
            {point.suspected > 0 && <circle cx={index * 15 + 7.5} cy={3.5} r={2.6} className="spark-flag" />}
          </g>
        );
      })}
    </svg>
  );
}

function TesterRow({ item, attendance, remoteBlocked, nowMs, onAttendance, onTester }: {
  item: BetaInstallation;
  attendance: AttendanceDecision[];
  remoteBlocked: boolean;
  nowMs: number;
  onAttendance: (id: string) => void;
  onTester: (code: string) => void;
}) {
  const state = seenState(item.last_seen_at, nowMs);
  const detected = [
    item.detected_sections.length ? `Section ${item.detected_sections.join(" & ")}` : null,
    item.detected_groups.length ? `Group ${item.detected_groups.join(", ")}` : null,
  ].filter(Boolean).join(" · ") || "—";
  const present = attendance.filter((mark) => mark.outcome === "present").length;
  const profileStatus = profileMatchStatus(item);
  const worst = attendance.find((mark) => mark.suspected_incorrect) ?? attendance.find((mark) => mark.outcome === "failed" || mark.outcome === "blocked") ?? attendance[0];
  const selfCohort = [item.self_section, item.self_plc].filter(Boolean).join(" · ") || "—";
  const seed = (Number(item.tester_code.slice(2)) || 1) % 4;
  return (
    <tr className={!item.installation_id ? "unclaimed" : ""}>
      <td className="sticky-code">
        <button className="code-chip" data-seed={seed} onClick={() => onTester(item.tester_code)} title={`Open ${item.tester_code} drilldown`}>{item.tester_code}</button>
      </td>
      <td>
        <strong className="cell-strong">{testerDisplayName(item)}</strong>
        <small className="cell-note">{profileStatus === "confirmed" ? "✓ LMS confirmed" : profileStatus === "mismatch" ? "! Profile mismatch" : "· LMS cohort missing"}</small>
      </td>
      <td>
        <strong className="cell-strong">{selfCohort}</strong>
        <small className="cell-note">{detected}</small>
      </td>
      <td>
        <strong className="cell-strong">{[item.manufacturer, item.model].filter(Boolean).join(" ") || "Not installed"}</strong>
        <small className="cell-note">{testerVersionLabel(item)}</small>
      </td>
      <td>
        <span className={`status-chip ${state === "live" ? "teal" : state === "stale" ? "amber" : "neutral"}`}>{state}</span>
        <small className="cell-note">{relativeTime(item.last_seen_at, nowMs)}</small>
      </td>
      <td>{item.last_sync_status ?? "—"}</td>
      <td><span className={`status-chip ${item.schedule_status === "confirmed" ? "teal" : item.schedule_status === "mismatch" ? "amber" : "neutral"}`}>{item.schedule_status.replace("_", " ")}</span></td>
      <td>{attendance.length ? <button className="cell-button" onClick={() => worst && onAttendance(worst.id)}>{present}/{attendance.length} present{worst?.suspected_incorrect ? " · suspected" : ""}</button> : "—"}</td>
      <td>{remoteBlocked ? <span className="status-chip rust">BLOCKED</span> : item.auto_attendance_enabled == null ? "—" : item.auto_attendance_enabled ? <span className="status-chip teal">ON</span> : <span className="status-chip neutral">OFF</span>}</td>
    </tr>
  );
}

function TesterModal({ code, data, nowMs, onClose, onReviewAttendance }: {
  code: string;
  data: DashboardData;
  nowMs: number;
  onClose: () => void;
  onReviewAttendance: (id: string) => void;
}) {
  const tester = data.installations.find((item) => item.tester_code === code);
  if (!tester) return null;
  const decisions = data.attendance_decisions.filter((item) => item.tester_code === code).slice(0, 6);
  const confirmations = data.schedule_confirmations.filter((item) => item.tester_code === code).slice(0, 3);
  const events = data.events.filter((item) => item.tester_code === code).slice(0, 6);
  const detected = [
    tester.detected_sections.length ? `Section ${tester.detected_sections.join(" & ")}` : null,
    tester.detected_groups.length ? `Group ${tester.detected_groups.join(", ")}` : null,
  ].filter(Boolean).join(" · ") || "—";
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="modal detail-modal tester-modal" role="dialog" aria-modal="true" aria-labelledby="tester-title">
        <div className="detail-header">
          <div>
            <span className={`status-chip ${tester.installation_id ? "teal" : "neutral"}`}>{tester.installation_id ? "CLAIMED" : "UNCLAIMED"}</span>
            <h2 id="tester-title">{testerDisplayName(tester)} <span className="mono muted-mono">({code})</span></h2>
            <p>{[tester.manufacturer, tester.model].filter(Boolean).join(" ") || "Device not installed"} · {testerVersionLabel(tester)}</p>
          </div>
          <button className="close-button" onClick={onClose}>Close</button>
        </div>
        <dl className="decision-grid">
          <Row term="Self cohort" value={[tester.self_section, tester.self_plc].filter(Boolean).join(" · ") || "—"} />
          <Row term="Detected" value={detected} />
          <Row term="Schedule" value={tester.schedule_status.replace("_", " ")} />
          <Row term="Auto attendance" value={tester.auto_attendance_enabled == null ? "Not reported" : tester.auto_attendance_enabled ? "On" : "Off"} />
          <Row term="Last seen" value={`${formatIst(tester.last_seen_at)} · ${relativeTime(tester.last_seen_at, nowMs)}`} />
          <Row term="Last sync" value={`${tester.last_sync_status ?? "—"} · ${relativeTime(tester.last_sync_at, nowMs)}`} />
        </dl>
        {decisions.length > 0 && (
          <>
            <p className="eyebrow spaced">RECENT ATTENDANCE DECISIONS</p>
            <ul className="mini-list">
              {decisions.map((decision) => (
                <li key={decision.id}>
                  <button className="mini-row" onClick={() => onReviewAttendance(decision.id)}>
                    <span className="mono feed-time">{formatIst(decision.occurred_at)}</span>
                    <span><strong>{decision.session_label ?? "Attendance Session"}</strong><small>{capitalize(decision.method)} · gate {decision.gate_allowed == null ? "?" : decision.gate_allowed ? "allowed" : "blocked"}</small></span>
                    <span className={`status-chip ${decision.suspected_incorrect ? "rust" : decision.outcome === "present" ? "teal" : "amber"}`}>{decision.suspected_incorrect ? "SUSPECTED" : decision.outcome}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
        {confirmations.length > 0 && (
          <>
            <p className="eyebrow spaced">SCHEDULE CONFIRMATIONS</p>
            <ul className="mini-list">
              {confirmations.map((confirmation) => (
                <li key={confirmation.id}>
                  <div className="mini-row static">
                    <span className="mono feed-time">{formatIst(confirmation.confirmed_at)}</span>
                    <span><strong>{confirmation.selected_date}</strong><small>{confirmation.app_session_count} sessions{confirmation.note ? ` · “${confirmation.note}”` : ""}</small></span>
                    <span className={`status-chip ${confirmation.status === "confirmed" ? "teal" : "amber"}`}>{confirmation.status}</span>
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
        {events.length > 0 && (
          <>
            <p className="eyebrow spaced">RECENT PHONE EVENTS</p>
            <ul className="mini-list">
              {events.map((event) => (
                <li key={event.id}>
                  <div className="mini-row static">
                    <span className="mono feed-time">{relativeTime(event.occurred_at, nowMs)}</span>
                    <span><strong>{humanizeEvent(event.event_type)}</strong><small>{payloadHint(event.payload)}</small></span>
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </div>
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
function Empty({ title, copy }: { title: string; copy: string }) { return <div className="empty"><i className="empty-dots" aria-hidden="true"><i /><i /><i /></i><strong>{title}</strong><p>{copy}</p></div>; }
function unique(values: string[]) { return [...new Set(values)].sort(); }
function capitalize(value: string) { return value.charAt(0).toUpperCase() + value.slice(1).replaceAll("_", " "); }
function statusLabel(value: IssueReport["status"]) { return value.split("_").map(capitalize).join(" "); }
function formatIst(value: string | null) { return value ? `${IST.format(new Date(value))} IST` : "Never"; }
function humanizeEvent(value: string) { return capitalize(value.replaceAll("_", " ")); }
function payloadHint(payload: Record<string, unknown>): string {
  const candidates = ["session_label", "reason", "result", "outcome", "exit_reason", "detail"];
  for (const key of candidates) {
    const value = payload[key];
    if (typeof value === "string" && value.trim()) return ` · ${value.trim().slice(0, 48)}`;
  }
  return "";
}
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
