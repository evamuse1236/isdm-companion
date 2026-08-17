type SyncEvent = {
  event_type: string;
  occurred_at: string;
};

type SyncUpdate = {
  last_sync_status?: "success" | "failed" | "retry_exhausted";
  last_sync_at?: string;
};

const SYNC_STATUS: Record<string, SyncUpdate["last_sync_status"]> = {
  background_sync_finished: "success",
  background_sync_failed: "failed",
  background_sync_retry_exhausted: "retry_exhausted",
};

export function deriveSyncUpdate(events: SyncEvent[]): SyncUpdate {
  const latest = events
    .filter((event) => SYNC_STATUS[event.event_type])
    .sort((left, right) => Date.parse(right.occurred_at) - Date.parse(left.occurred_at))[0];
  if (!latest) return {};
  return {
    last_sync_status: SYNC_STATUS[latest.event_type],
    last_sync_at: latest.occurred_at,
  };
}
