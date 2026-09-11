const PAUSED_ROUTES = new Set([
  "events",
  "profile",
  "schedule",
  "attendance",
  "report",
  "lms-diagnostic",
]);

export function pausedCollectionReply(route, method) {
  if (method !== "POST" || !PAUSED_ROUTES.has(route)) return null;
  if (route === "report") {
    return { status: 503, body: { error: "data_collection_paused" } };
  }
  return {
    status: 202,
    body: { saved: false, collection_paused: true },
  };
}
