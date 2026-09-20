import assert from "node:assert/strict";
import test from "node:test";
import { JsonInputError, readJsonObject, betaBodyLimit } from "../supabase/functions/_shared/http-input.mjs";
const req = (body, headers = {}) => new Request("https://example.test/profile", { method: "POST", body, headers });
const fails = (code, status = 400) => (error) => error instanceof JsonInputError && error.message === code && error.status === status;

test("valid JSON object and UTF-8 are preserved", async () => {
  assert.deepEqual(await readJsonObject(req('{"name":"पुणे","done":true}')), { name: "पुणे", done: true });
});
test("JSON primitives, arrays and null are rejected", async () => {
  for (const body of ["null", "[]", "1", '"hello"', "true"]) {
    await assert.rejects(readJsonObject(req(body)), fails("json_object_required"));
  }
});
test("malformed and missing JSON get a stable error", async () => {
  for (const body of ["{", "", "undefined", '{"a":}']) await assert.rejects(readJsonObject(req(body)), fails("invalid_json"));
  await assert.rejects(readJsonObject(new Request("https://example.test/profile")), fails("invalid_json"));
});
test("declared oversized body is rejected before reading", async () => {
  await assert.rejects(readJsonObject(req("{}", { "content-length": "1000" }), { maxBytes: 30 }), fails("body_too_large", 413));
});
test("lying small Content-Length cannot bypass actual byte limit", async () => {
  await assert.rejects(readJsonObject(req(JSON.stringify({ a: "x".repeat(100) }), { "content-length": "2" }), { maxBytes: 30 }), fails("body_too_large", 413));
});
test("chunked input without Content-Length is bounded and cancelled", async () => {
  let cancelled = false;
  const body = new ReadableStream({
    pull(controller) { controller.enqueue(new TextEncoder().encode("xxxxxxxx")); },
    cancel() { cancelled = true; },
  });
  const request = new Request("https://example.test/enroll", { method: "POST", body, duplex: "half" });
  await assert.rejects(readJsonObject(request, { maxBytes: 20 }), fails("body_too_large", 413));
  assert.equal(cancelled, true);
});
test("byte limit is UTF-8 bytes rather than JavaScript character count", async () => {
  const body = '{"a":"पुणे"}';
  await assert.rejects(readJsonObject(req(body), { maxBytes: body.length }), fails("body_too_large", 413));
});
test("exact byte boundary is accepted", async () => {
  assert.deepEqual(await readJsonObject(req('{"a":1}'), { maxBytes: 7 }), { a: 1 });
});
test("malformed UTF-8 is rejected", async () => {
  await assert.rejects(readJsonObject(req(new Uint8Array([123, 34, 120, 34, 58, 34, 255, 34, 125]))), fails("invalid_json"));
});
test("deep objects cannot reach recursive downstream handlers", async () => {
  const body = '{"x":'.repeat(100) + "1" + "}".repeat(100);
  await assert.rejects(readJsonObject(req(body)), fails("json_structure_too_complex", 413));
});
test("wide objects are subject to the node budget", async () => {
  await assert.rejects(readJsonObject(req('{"a":[1,2,3,4,5]}'), { maxNodes: 5 }), fails("json_structure_too_complex", 413));
});
test("normal nested event payload is accepted", async () => {
  const value = { events: [{ payload: { count: 3, flags: [true, false] } }] };
  assert.deepEqual(await readJsonObject(req(JSON.stringify(value))), value);
});
test("invalid Content-Length and invalid limits fail closed", async () => {
  await assert.rejects(readJsonObject(req("{}", { "content-length": "bad" })), fails("invalid_content_length"));
  await assert.rejects(readJsonObject(req("{}"), { maxDepth: 0 }), RangeError);
});
test("report allowance preserves four normal 5 MiB base64 attachments", () => {
  const limit = betaBodyLimit(new Request("https://example.test/beta-api/report"));
  assert.ok(limit > 4 * 4 * Math.ceil(5 * 1024 * 1024 / 3) + 10000);
  assert.equal(betaBodyLimit(new Request("https://example.test/beta-api/enroll")), 65536);
  assert.equal(betaBodyLimit(new Request("https://example.test/beta-api/events")), 1048576);
});
