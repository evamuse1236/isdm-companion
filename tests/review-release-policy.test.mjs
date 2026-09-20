import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, readFile, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { requireInstallMode, releaseCaption, assertSafeToResume, dispatchReleaseRecipient,
  writePrivateJsonAtomically } from "../scripts/lib/release-policy.mjs";

test("release mode is mandatory and cannot be guessed", () => {
  for (const mode of [undefined, null, "", "automatic", "UPDATE"]) assert.throws(() => requireInstallMode(mode));
  assert.equal(requireInstallMode("update"), "update");
  assert.equal(requireInstallMode("signing-reset"), "signing-reset");
});
test("update caption retains existing installation data guidance", () => {
  const text = releaseCaption("Tester", "0.6.0-beta", "One fix.", "update");
  assert.match(text, /don.t uninstall/); assert.match(text, /tap Update/);
  assert.doesNotMatch(text, /one-time uninstall/);
});
test("signing reset caption warns of data loss and never promises an in-place update", () => {
  const text = releaseCaption("Tester", "0.5.7-beta", "One fix.", "signing-reset");
  assert.match(text, /cannot update/); assert.match(text, /WARNING/); assert.match(text, /deletes/);
  assert.doesNotMatch(text, /don.t uninstall|tap Update/);
});
test("only prepared and completed recipients can resume", () => {
  assert.doesNotThrow(() => assertSafeToResume([{ status: "prepared" }, { status: "succeeded" }]));
  for (const status of ["pending", "dispatching", "uncertain", "failed", undefined]) {
    assert.throws(() => assertSafeToResume([{ status, recipient_name: "Tester" }]), /inspect/);
  }
  assert.throws(() => assertSafeToResume([]));
});
test("dispatch intent is durably staged before the simulated side effect", async () => {
  const calls = [], recipient = { status: "prepared" };
  await dispatchReleaseRecipient(recipient, async () => calls.push(`save:${recipient.status}`), async () => {
    calls.push("fake-send"); return { action_id: "test-action" };
  });
  assert.deepEqual(calls, ["save:dispatching", "fake-send", "save:pending"]);
  assert.equal(recipient.action_id, "test-action");
});
test("no simulated send happens when persisting intent fails", async () => {
  let sent = false; const recipient = { status: "prepared" };
  await assert.rejects(dispatchReleaseRecipient(recipient, async () => { throw new Error("disk-full"); }, async () => {
    sent = true;
  }), /disk-full/);
  assert.equal(sent, false);
});
test("lost response leaves an ambiguous status and blocks a second dispatch", async () => {
  let sends = 0; const recipient = { status: "prepared" };
  await assert.rejects(dispatchReleaseRecipient(recipient, async () => {}, async () => {
    sends++; throw new Error("response-lost");
  }));
  assert.equal(recipient.status, "dispatching");
  assert.throws(() => assertSafeToResume([recipient]));
  await assert.rejects(dispatchReleaseRecipient(recipient, async () => {}, async () => { sends++; }));
  assert.equal(sends, 1);
});
test("missing action ID is not treated as successfully dispatched", async () => {
  const recipient = { status: "prepared" };
  await assert.rejects(dispatchReleaseRecipient(recipient, async () => {}, async () => ({})), /no action ID/);
  assert.equal(recipient.status, "dispatching");
});
test("failure after dispatch does not make the recipient retryable", async () => {
  const recipient = { status: "prepared" }; let saves = 0;
  await assert.rejects(dispatchReleaseRecipient(recipient, async () => {
    if (++saves === 2) throw new Error("disk-full");
  }, async () => ({ action_id: "test-action" })), /disk-full/);
  assert.equal(recipient.status, "pending"); assert.throws(() => assertSafeToResume([recipient]));
});
test("atomic journal updates leave private, complete JSON and no temp files", async () => {
  const dir = await mkdtemp(join(tmpdir(), "isdm-review-test-"));
  try {
    const path = join(dir, "batch.json");
    await writePrivateJsonAtomically(path, { status: "dispatching" });
    await writePrivateJsonAtomically(path, { status: "pending", action_id: "test-action" });
    assert.deepEqual(JSON.parse(await readFile(path, "utf8")), { status: "pending", action_id: "test-action" });
    assert.deepEqual(await readdir(dir), ["batch.json"]);
    if (process.platform !== "win32") assert.equal((await stat(path)).mode & 0o777, 0o600);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
