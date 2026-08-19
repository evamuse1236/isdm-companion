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
