type ExitEvent = {
  payload?: {
    reason?: unknown;
  } | null;
};

export type ProcessExitSummary = {
  critical_exits: number;
  low_memory_exits: number;
  other_exits: number;
};

const CRITICAL_REASONS = new Set([
  "anr",
  "crash",
  "excessive_resource_usage",
  "initialization_failure",
  "native_crash",
]);

export function summarizeProcessExits(events: ExitEvent[]): ProcessExitSummary {
  const summary: ProcessExitSummary = {
    critical_exits: 0,
    low_memory_exits: 0,
    other_exits: 0,
  };
  for (const event of events) {
    const reason = typeof event.payload?.reason === "string" ? event.payload.reason : "unknown";
    if (reason === "low_memory") summary.low_memory_exits += 1;
    else if (CRITICAL_REASONS.has(reason)) summary.critical_exits += 1;
    else summary.other_exits += 1;
  }
  return summary;
}
