import assert from "node:assert/strict";
import test from "node:test";
import { chatGPTUserFromHeaders } from "../app/auth-headers.ts";

test("owner email authenticates when Sites omits the optional user id header", () => {
  const headers = new Headers({
    "oai-authenticated-user-email": "owner@example.test",
    "oai-authenticated-user-full-name": "Owner%20Name",
    "oai-authenticated-user-full-name-encoding": "percent-encoded-utf-8",
  });

  assert.deepEqual(chatGPTUserFromHeaders(headers), {
    userId: "owner@example.test",
    email: "owner@example.test",
    displayName: "Owner Name",
    fullName: "Owner Name",
  });
});

test("missing owner email remains unauthenticated", () => {
  assert.equal(chatGPTUserFromHeaders(new Headers()), null);
});
