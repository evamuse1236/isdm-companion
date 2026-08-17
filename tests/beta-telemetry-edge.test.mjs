import test from "node:test";
import assert from "node:assert/strict";
import { deriveSyncUpdate } from "../supabase/functions/beta-api/telemetry.ts";

test("event ingestion derives the latest completed sync without clearing it on unrelated batches", () => {
  assert.deepEqual(deriveSyncUpdate([
    { event_type: "background_sync_finished", occurred_at: "2026-08-17T08:00:00.000Z" },
    { event_type: "application_started", occurred_at: "2026-08-17T09:00:00.000Z" },
  ]), {
    last_sync_status: "success",
    last_sync_at: "2026-08-17T08:00:00.000Z",
  });
  assert.deepEqual(deriveSyncUpdate([
    { event_type: "application_started", occurred_at: "2026-08-17T09:00:00.000Z" },
  ]), {});
});

test("retry exhaustion is visible as a sync status", () => {
  assert.deepEqual(deriveSyncUpdate([
    { event_type: "background_sync_retry_exhausted", occurred_at: "2026-08-17T09:00:00.000Z" },
  ]), {
    last_sync_status: "retry_exhausted",
    last_sync_at: "2026-08-17T09:00:00.000Z",
  });
});
