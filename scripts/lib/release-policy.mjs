import { open, rename, unlink, chmod } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname } from "node:path";

/** Mode is a deliberate release decision, never inferred from an APK filename. */
export function requireInstallMode(value) {
  if (value !== "update" && value !== "signing-reset") {
    throw new Error("Specify --install-mode update or signing-reset after verifying the signing cohort.");
  }
  return value;
}

export function releaseCaption(name, version, changes, mode, supplementalInstructions = null) {
  requireInstallMode(mode);
  const enrollmentReminder = version.includes("-nightly")
    ? "Keep your LMS login available before proceeding. No beta code is required. "
    : "Keep your LMS login and beta invite available before proceeding. ";
  const instructions = mode === "update"
    ? "Please install it over your current app—don’t uninstall the old version—so your login and beta profile stay saved."
    : "This release uses a different signing identity. It cannot update an app signed with the older key. " +
      "A one-time uninstall is required for those older installs. WARNING: uninstalling deletes your saved login, " +
      "local reading progress, profile, and cache. " + enrollmentReminder +
      "If you are unsure which version you have, ask the owner before uninstalling.";
  const additionalInstructions = supplementalInstructions?.trim()
    ? `\n\nAdditional installation instructions:\n${supplementalInstructions.trim()}`
    : "";
  const action = mode === "update" ? "When Android asks, tap Update." : "After the one-time reset, install this APK and sign in again.";
  return `Hey ${name}! Here’s ISDM Companion ${version}.\n\n${instructions}${additionalInstructions}\n\n` +
    `What’s fixed:\n${changes.trim()}\n\n${action}`;
}

/** An ambiguous external side effect must be reconciled, not blindly retried. */
export function assertSafeToResume(recipients) {
  if (!Array.isArray(recipients) || recipients.length === 0) {
    throw new Error("Release batch has no recipients.");
  }
  for (const recipient of recipients) {
    if (!["prepared", "succeeded"].includes(recipient.status)) {
      throw new Error(`Delivery for ${recipient.recipient_name ?? recipient.tester_code} is ${recipient.status ?? "unknown"}; ` +
        "inspect the recorded action and WhatsApp history before preparing a new batch. Do not reset this status to prepared.");
    }
  }
}

/** Persist intent BEFORE calling the external sender. Persistence errors fail closed. */
export async function dispatchReleaseRecipient(recipient, persist, send) {
  if (recipient.status !== "prepared") throw new Error("Only a prepared recipient can be dispatched.");
  recipient.status = "dispatching";
  await persist();
  const queued = await send();
  if (!queued || typeof queued.action_id !== "string" || queued.action_id.length === 0) {
    throw new Error("Sender returned no action ID; delivery is ambiguous and must be reconciled.");
  }
  recipient.action_id = queued.action_id;
  recipient.status = "pending";
  await persist();
  return queued;
}

/** Same-directory atomic replacement: readers see complete JSON, never a truncated overwrite. */
export async function writePrivateJsonAtomically(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  let handle;
  try {
    handle = await open(temporary, "wx", 0o600);
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, path);
    await chmod(path, 0o600);
    // POSIX release tooling: persist the rename as well as the file contents.
    if (process.platform !== "win32") {
      const directory = await open(dirname(path), "r");
      try { await directory.sync(); } finally { await directory.close(); }
    }
  } finally {
    await handle?.close().catch(() => {});
    await unlink(temporary).catch((error) => { if (error.code !== "ENOENT") throw error; });
  }
}
