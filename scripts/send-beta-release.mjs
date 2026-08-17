#!/usr/bin/env node

import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { chmod, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, join, resolve } from "node:path";
import { createInterface } from "node:readline";

function fail(message) {
  console.error(`Error: ${message}`);
  process.exit(1);
}

function usage() {
  console.log(`Usage:
  node scripts/send-beta-release.mjs prepare --apk PATH --version VERSION \\
    --recipients PATH --changes PATH --output DIR \\
    [--roster-json PATH | BETA_ADMIN_SECRET=...] [--offline]

  node scripts/send-beta-release.mjs send --batch DIR --approve DIGEST

prepare is read-only. It creates an exact restricted preview and approval digest.
send requires that digest, rechecks the APK and recipients, then sends sequentially.
Use --whatsmeow-plugin PATH only to override plugin auto-discovery.`);
}

function parseArgs(values) {
  const [command, ...rest] = values;
  const options = {};
  for (let index = 0; index < rest.length; index += 1) {
    const token = rest[index];
    if (!token.startsWith("--")) fail(`unexpected argument: ${token}`);
    const key = token.slice(2);
    if (["offline"].includes(key)) options[key] = true;
    else {
      const value = rest[++index];
      if (!value || value.startsWith("--")) fail(`--${key} requires a value`);
      options[key] = value;
    }
  }
  return { command, options };
}

async function sha256File(path) {
  const hash = createHash("sha256");
  hash.update(await readFile(path));
  return hash.digest("hex");
}

function sha256Text(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function requirePrivateFile(path, label) {
  const details = await stat(path);
  if ((details.mode & 0o077) !== 0) fail(`${label} must use mode 600 or stricter: ${path}`);
}

function isIncompatible(installation) {
  const device = `${installation.manufacturer ?? ""} ${installation.model ?? ""}`.toLowerCase();
  return /\b(apple|iphone|ipad|ios)\b/.test(device);
}

function captionFor(name, version, changes) {
  return `Hey ${name}! Here’s ISDM Companion ${version}.\n\n` +
    "Please install it over your current app—don’t uninstall the old version—so your login and beta profile stay saved.\n\n" +
    `What’s fixed:\n${changes.trim()}\n\nWhen Android asks, tap Update.`;
}

function approvalPayload(batch) {
  return JSON.stringify({
    schema_version: batch.schema_version,
    apk_path: batch.apk_path,
    apk_sha256: batch.apk_sha256,
    version: batch.version,
    recipients: batch.recipients.map(({ tester_code, recipient_name, chat_jid, caption }) => ({
      tester_code, recipient_name, chat_jid, caption
    }))
  });
}

async function findWhatsmeowServer(explicitRoot) {
  if (explicitRoot) return join(resolve(explicitRoot), "src", "mcp-server.mjs");
  if (process.env.WHATSMEOW_PLUGIN_ROOT) return join(resolve(process.env.WHATSMEOW_PLUGIN_ROOT), "src", "mcp-server.mjs");
  const cache = join(homedir(), ".codex", "plugins", "cache", "personal", "whatsmeow");
  const versions = (await readdir(cache, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort().reverse();
  if (!versions.length) fail("Whatsmeow plugin was not found; install or configure it first");
  return join(cache, versions[0], "src", "mcp-server.mjs");
}

class WhatsmeowClient {
  constructor(serverPath) {
    this.serverPath = serverPath;
    this.nextId = 1;
    this.pending = new Map();
  }

  async connect() {
    await stat(this.serverPath);
    this.child = spawn(process.execPath, [this.serverPath], { stdio: ["pipe", "pipe", "pipe"] });
    this.stderr = "";
    this.child.stderr.on("data", (chunk) => { this.stderr += chunk.toString(); });
    createInterface({ input: this.child.stdout }).on("line", (line) => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      if (message.id == null) return;
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message ?? JSON.stringify(message.error)));
      else pending.resolve(message.result);
    });
    this.child.on("exit", (code) => {
      for (const pending of this.pending.values()) pending.reject(new Error(`Whatsmeow MCP exited with ${code}: ${this.stderr.trim()}`));
      this.pending.clear();
    });
    await this.request("initialize", {
      protocolVersion: "2025-03-26",
      capabilities: {},
      clientInfo: { name: "isdm-beta-release", version: "1.0.0" }
    });
    this.child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);
  }

  request(method, params) {
    const id = this.nextId++;
    return new Promise((resolveRequest, rejectRequest) => {
      this.pending.set(id, { resolve: resolveRequest, reject: rejectRequest });
      this.child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    });
  }

  async call(name, args) {
    const response = await this.request("tools/call", { name, arguments: args });
    if (response.isError) throw new Error(response.content?.[0]?.text ?? `${name} failed`);
    const text = response.content?.find((item) => item.type === "text")?.text;
    if (!text) throw new Error(`${name} returned no JSON text`);
    return JSON.parse(text);
  }

  close() {
    this.child?.stdin.end();
    this.child?.kill();
  }
}

async function validateWhatsApp(client, recipients, apkFilename, { blockDuplicates }) {
  const status = await client.call("whatsapp_status", {});
  if (!status.collector_alive || status.collector_status !== "connected") {
    fail("WhatsApp collector is not connected");
  }
  if (!status.history_latest_received) {
    console.warn("Warning: WhatsApp initial history is incomplete; an empty duplicate search is inconclusive.");
  }
  const chats = await client.call("list_chats", { limit: 200 });
  for (const recipient of recipients) {
    const exact = chats.find((chat) => chat.chat_jid === recipient.chat_jid && chat.is_group === false);
    if (!exact) fail(`direct chat no longer resolves for ${recipient.recipient_name} (${recipient.chat_jid})`);
    if (blockDuplicates && recipient.status !== "succeeded") {
      const result = await client.call("search_messages", {
        query: apkFilename, chat_jid: recipient.chat_jid, limit: 20
      });
      const previousOutgoing = (result.messages ?? []).find((message) => message.is_from_me !== false);
      if (previousOutgoing) fail(`possible prior ${apkFilename} send found for ${recipient.recipient_name}; inspect WhatsApp before retrying`);
    }
  }
  return status;
}

async function loadRoster(options) {
  if (options["roster-json"]) {
    const rosterPath = resolve(options["roster-json"]);
    await requirePrivateFile(rosterPath, "Roster snapshot");
    return { document: JSON.parse(await readFile(rosterPath, "utf8")), source: rosterPath };
  }
  if (options.offline) fail("offline preparation requires --roster-json");
  const secret = process.env.BETA_ADMIN_SECRET;
  if (!secret) fail("set BETA_ADMIN_SECRET or supply a private --roster-json snapshot");
  const url = options["admin-url"] ?? process.env.BETA_ADMIN_URL ??
    "https://tbnfnhepqfqjsowvzncl.supabase.co/functions/v1/beta-admin/dashboard";
  const response = await fetch(url, { headers: { "x-admin-secret": secret } });
  if (!response.ok) fail(`live beta roster request failed with HTTP ${response.status}`);
  return { document: await response.json(), source: "live_beta_admin" };
}

async function prepare(options) {
  for (const required of ["apk", "version", "recipients", "changes", "output"]) {
    if (!options[required]) fail(`prepare requires --${required}`);
  }
  const apkPath = resolve(options.apk);
  const recipientsPath = resolve(options.recipients);
  const changesPath = resolve(options.changes);
  const outputPath = resolve(options.output);
  await stat(apkPath);
  await requirePrivateFile(recipientsPath, "Recipient map");

  const { document: rosterDocument, source: rosterSource } = await loadRoster(options);
  const installations = Array.isArray(rosterDocument) ? rosterDocument : rosterDocument.installations;
  if (!Array.isArray(installations)) fail("roster JSON requires an installations array");
  const recipientMap = JSON.parse(await readFile(recipientsPath, "utf8"));
  const changes = await readFile(changesPath, "utf8");
  if (!changes.trim()) fail("changes file is empty");

  const recipients = [];
  const excluded = [];
  const seenJids = new Set();
  for (const installation of installations) {
    const testerCode = String(installation.tester_code ?? "").trim();
    let reason = null;
    if (!installation.installation_id) reason = "unclaimed";
    else if (testerCode === "T-01") reason = "owner";
    else if (isIncompatible(installation)) reason = "incompatible_device";
    else if (installation.app_version === options.version) reason = "already_updated";
    if (reason) {
      excluded.push({ tester_code: testerCode, reason });
      continue;
    }

    const mapped = recipientMap[testerCode];
    if (!mapped) fail(`claimed Android tester ${testerCode} has no recipient mapping`);
    const name = String(mapped.recipient_name ?? installation.support_name ?? "").trim();
    const chatJid = String(mapped.chat_jid ?? "").trim();
    if (!name) fail(`${testerCode} has no recipient name`);
    if (!/^[^@\s]+@(s\.whatsapp\.net|lid)$/.test(chatJid)) fail(`${testerCode} has an invalid direct-chat JID`);
    if (seenJids.has(chatJid)) fail(`duplicate direct-chat JID: ${chatJid}`);
    seenJids.add(chatJid);
    recipients.push({
      tester_code: testerCode,
      recipient_name: name,
      chat_jid: chatJid,
      caption: captionFor(name, options.version, changes),
      status: "prepared"
    });
  }
  if (recipients.length === 0) fail("no eligible recipients remain");

  const batch = {
    schema_version: 1,
    created_at: new Date().toISOString(),
    apk_path: apkPath,
    apk_filename: basename(apkPath),
    apk_sha256: await sha256File(apkPath),
    version: options.version,
    roster_source: rosterSource,
    recipients,
    excluded
  };
  batch.approval_digest = sha256Text(approvalPayload(batch));

  if (!options.offline) {
    const client = new WhatsmeowClient(await findWhatsmeowServer(options["whatsmeow-plugin"]));
    try {
      await client.connect();
      const status = await validateWhatsApp(client, recipients, batch.apk_filename, { blockDuplicates: true });
      batch.whatsapp_checked_at = new Date().toISOString();
      batch.whatsapp_history_complete = Boolean(status.history_latest_received);
    } finally {
      client.close();
    }
  }

  try {
    await stat(outputPath);
    fail(`output already exists: ${outputPath}`);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  await mkdir(outputPath, { mode: 0o700, recursive: true });
  await chmod(outputPath, 0o700);
  const preview = [
    `# ISDM Companion ${batch.version} release preview`, "",
    `APK: ${batch.apk_path}`,
    `SHA-256: ${batch.apk_sha256}`,
    `Approval digest: ${batch.approval_digest}`, "",
    ...recipients.flatMap((recipient, index) => [
      `## ${index + 1}. ${recipient.recipient_name} (${recipient.tester_code}, ${recipient.chat_jid})`, "",
      recipient.caption, ""
    ]),
    "## Excluded", "",
    ...excluded.map((entry) => `- ${entry.tester_code}: ${entry.reason}`), ""
  ].join("\n");
  await writeFile(`${outputPath}/.isdm-beta-release-batch`, "1\n", { mode: 0o600 });
  await writeFile(`${outputPath}/batch.json`, `${JSON.stringify(batch, null, 2)}\n`, { mode: 0o600 });
  await writeFile(`${outputPath}/preview.md`, preview, { mode: 0o600 });
  for (const name of [".isdm-beta-release-batch", "batch.json", "preview.md"]) {
    await chmod(`${outputPath}/${name}`, 0o600);
  }

  console.log(`Prepared ${recipients.length} recipient(s).`);
  console.log(`Preview: ${outputPath}/preview.md`);
  console.log(`Approval digest: ${batch.approval_digest}`);
  if (options.offline) console.log("Offline preparation: live WhatsApp checks were skipped.");
}

async function saveBatch(batchPath, batch) {
  await writeFile(batchPath, `${JSON.stringify(batch, null, 2)}\n`, { mode: 0o600 });
  await chmod(batchPath, 0o600);
}

async function send(options) {
  for (const required of ["batch", "approve"]) {
    if (!options[required]) fail(`send requires --${required}`);
  }
  const batchDir = resolve(options.batch);
  await stat(`${batchDir}/.isdm-beta-release-batch`);
  const batchPath = `${batchDir}/batch.json`;
  await requirePrivateFile(batchPath, "Release batch");
  const batch = JSON.parse(await readFile(batchPath, "utf8"));
  if (options.approve !== batch.approval_digest) fail("approval digest does not match the exact previewed batch");
  if (sha256Text(approvalPayload(batch)) !== batch.approval_digest) fail("batch integrity check failed after approval");
  const batchAgeMs = Date.now() - Date.parse(batch.created_at);
  if (!Number.isFinite(batchAgeMs) || batchAgeMs < 0 || batchAgeMs > 30 * 60 * 1000) {
    fail("release preview is older than 30 minutes; prepare and approve a fresh live batch");
  }
  if (await sha256File(batch.apk_path) !== batch.apk_sha256) fail("APK changed after the release preview");
  if (!Array.isArray(batch.recipients) || !batch.recipients.length) fail("release batch has no recipients");
  const uncertain = batch.recipients.find((recipient) => recipient.status === "uncertain");
  if (uncertain) fail(`delivery for ${uncertain.recipient_name} is uncertain; inspect WhatsApp and prepare a fresh batch before retrying`);

  const client = new WhatsmeowClient(await findWhatsmeowServer(options["whatsmeow-plugin"]));
  try {
    await client.connect();
    await validateWhatsApp(client, batch.recipients, batch.apk_filename, { blockDuplicates: true });
    for (const recipient of batch.recipients) {
      if (recipient.status === "succeeded") {
        console.log(`Skip ${recipient.recipient_name}: already succeeded in this batch.`);
        continue;
      }
      console.log(`Sending to ${recipient.recipient_name} (${recipient.tester_code})...`);
      const queued = await client.call("send_media", {
        chat_jid: recipient.chat_jid,
        file_path: batch.apk_path,
        caption: recipient.caption,
        media_type: "document",
        confirm: true
      });
      recipient.action_id = queued.action_id;
      recipient.status = "pending";
      await saveBatch(batchPath, batch);

      const deadline = Date.now() + 30_000;
      let result = null;
      while (Date.now() < deadline) {
        result = await client.call("get_action_status", { action_id: queued.action_id });
        if (["succeeded", "failed"].includes(result.status)) break;
        await new Promise((resolveDelay) => setTimeout(resolveDelay, 500));
      }
      if (result?.status === "succeeded") {
        recipient.status = "succeeded";
        recipient.message_id = result.result?.message_id ?? null;
        recipient.sent_at = new Date().toISOString();
        await saveBatch(batchPath, batch);
        console.log(`Succeeded: ${recipient.recipient_name}`);
        continue;
      }
      recipient.status = "uncertain";
      recipient.error = result?.error ?? `action did not finish within 30 seconds (last status: ${result?.status ?? "unknown"})`;
      await saveBatch(batchPath, batch);
      fail(`delivery is uncertain for ${recipient.recipient_name}; stopped before the next recipient`);
    }
    batch.completed_at = new Date().toISOString();
    await saveBatch(batchPath, batch);
    console.log(`Completed ${batch.recipients.length} verified WhatsApp send(s).`);
    console.log("Delivery is verified separately from installation; refresh the live roster after testers open the app.");
  } finally {
    client.close();
  }
}

const { command, options } = parseArgs(process.argv.slice(2));
if (!command || ["help", "--help", "-h"].includes(command)) {
  usage();
  process.exit(command ? 0 : 2);
}
if (command === "prepare") await prepare(options);
else if (command === "send") await send(options);
else fail(`unknown command: ${command}`);
