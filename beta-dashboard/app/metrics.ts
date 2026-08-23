import type { AttendanceDecision, ReliabilitySummary } from "./types";

export function confirmedPresentCount(decisions: AttendanceDecision[]): number {
  return decisions.filter((decision) => {
    if (decision.outcome !== "present") return false;
    if (decision.details.result === "already_marked") return false;
    if (decision.details.result === "marked_present") return true;
    return decision.gate_allowed === true && decision.lms_markable === true;
  }).length;
}

export function reliabilityLabel(summary: ReliabilitySummary): string {
  return `${summary.failures} failures`;
}

export function reliabilityNote(summary: ReliabilitySummary): string {
  return `${summary.cancellations} cancelled · ${summary.critical_exits} crash/ANR exits · ${summary.low_memory_exits} cached low-memory`;
}

export function istDayKey(date: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

export type TrendPoint = {
  key: string;
  label: string;
  marks: number;
  present: number;
  suspected: number;
};

export function attendanceTrend(decisions: AttendanceDecision[], days: number, now = new Date()): TrendPoint[] {
  const points: TrendPoint[] = [];
  const labelFormat = new Intl.DateTimeFormat("en-IN", { timeZone: "Asia/Kolkata", weekday: "short" });
  for (let index = days - 1; index >= 0; index -= 1) {
    const day = new Date(now.getTime() - index * 86_400_000);
    points.push({ key: istDayKey(day), label: labelFormat.format(day), marks: 0, present: 0, suspected: 0 });
  }
  const byKey = new Map(points.map((point) => [point.key, point]));
  for (const decision of decisions) {
    const point = byKey.get(istDayKey(new Date(decision.occurred_at)));
    if (!point) continue;
    point.marks += 1;
    if (decision.outcome === "present") point.present += 1;
    if (decision.suspected_incorrect) point.suspected += 1;
  }
  return points;
}

export function reportAgeHours(created_at: string, now = new Date()): number {
  return Math.max(0, (now.getTime() - Date.parse(created_at)) / 3_600_000);
}

export function reportAgeLabel(created_at: string, now = new Date()): string {
  const hours = reportAgeHours(created_at, now);
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))}m`;
  if (hours < 48) return `${Math.floor(hours)}h`;
  return `${Math.floor(hours / 24)}d`;
}

export function relativeTime(value: string | null, nowMs: number): string {
  if (!value) return "never";
  const seconds = Math.max(0, Math.round((nowMs - Date.parse(value)) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}
