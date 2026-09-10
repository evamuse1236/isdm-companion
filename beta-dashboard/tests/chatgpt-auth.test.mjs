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

import { isDashboardOwner } from "../app/owner-access.ts";

test("dashboard accepts only the verified owner and denies other signed in users", () => {
  const user = (email, id) => chatGPTUserFromHeaders(new Headers({
    "oai-authenticated-user-email": email, ...(id ? { "oai-authenticated-user-id": id } : {}),
  }));
  assert.equal(isDashboardOwner(null), false);
  assert.equal(isDashboardOwner(user("outsider@example.test")), false);
  assert.equal(isDashboardOwner(user("vishwajit1236@gmail.com")), true);
  assert.equal(isDashboardOwner(user("vishwajit1236@gmail.com", "not-the-owner")), false);
  assert.equal(isDashboardOwner(user("vishwajit1236@gmail.com", "7bc1ddcb-d58a-4eda-8c01-7f57215fb01f")), true);
});
