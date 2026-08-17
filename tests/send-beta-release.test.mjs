import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const command = join(repoRoot, "scripts", "send-beta-release.mjs");

async function privateJson(path, value) {
  await writeFile(path, JSON.stringify(value), { mode: 0o600 });
  await chmod(path, 0o600);
}

test("prepare creates an approval-bound batch for claimed external Android testers", async () => {
  const fixtureDir = await mkdtemp(join(tmpdir(), "isdm-release-"));
  try {
    const apk = join(fixtureDir, "ISDM-Companion-Beta-0.5.0.apk");
    const roster = join(fixtureDir, "roster.json");
    const recipients = join(fixtureDir, "recipients.json");
    const changes = join(fixtureDir, "changes.txt");
    const output = join(fixtureDir, "batch");
    await writeFile(apk, "signed-apk-fixture");
    await writeFile(changes, "• Schedule cards stop at their real end time.\n");
    await privateJson(roster, {
      installations: [
        { tester_code: "T-01", installation_id: "owner", support_name: "Vishwa", manufacturer: "samsung", app_version_code: 7 },
        { tester_code: "T-02", installation_id: null, support_name: null, manufacturer: null, app_version_code: null },
        { tester_code: "T-03", installation_id: "android", support_name: "sushant", manufacturer: "vivo", app_version_code: 3 },
        { tester_code: "T-04", installation_id: "iphone", support_name: "Raunak", manufacturer: "Apple", app_version_code: 3 }
      ]
    });
    await privateJson(recipients, {
      "T-03": { recipient_name: "Sushant", chat_jid: "139307448799247@lid" }
    });

    const result = spawnSync(process.execPath, [command, "prepare",
      "--apk", apk,
      "--version", "0.5.0-beta",
      "--roster-json", roster,
      "--recipients", recipients,
      "--changes", changes,
      "--output", output,
      "--offline"
    ], { encoding: "utf8" });

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /Prepared 1 recipient/);
    assert.match(result.stdout, /Approval digest: [a-f0-9]{64}/);
    const batch = JSON.parse(await readFile(join(output, "batch.json"), "utf8"));
    assert.equal(batch.recipients.length, 1);
    assert.equal(batch.recipients[0].tester_code, "T-03");
    assert.equal(batch.recipients[0].chat_jid, "139307448799247@lid");
    assert.match(batch.recipients[0].caption, /don.t uninstall/i);
    assert.deepEqual(batch.excluded.map(({ tester_code, reason }) => ({ tester_code, reason })), [
      { tester_code: "T-01", reason: "owner" },
      { tester_code: "T-02", reason: "unclaimed" },
      { tester_code: "T-04", reason: "incompatible_device" }
    ]);
    assert.match(await readFile(join(output, "preview.md"), "utf8"), /Sushant \(T-03, 139307448799247@lid\)/);
  } finally {
    await rm(fixtureDir, { recursive: true, force: true });
  }
});

test("send refuses an approval digest that does not match the exact batch", async () => {
  const fixtureDir = await mkdtemp(join(tmpdir(), "isdm-release-"));
  try {
    const apk = join(fixtureDir, "ISDM-Companion-Beta-0.5.0.apk");
    const batchDir = join(fixtureDir, "batch");
    await writeFile(apk, "signed-apk-fixture");
    await import("node:fs/promises").then(({ mkdir }) => mkdir(batchDir));
    await writeFile(join(batchDir, ".isdm-beta-release-batch"), "1\n", { mode: 0o600 });
    await privateJson(join(batchDir, "batch.json"), {
      schema_version: 1,
      apk_path: apk,
      apk_filename: "ISDM-Companion-Beta-0.5.0.apk",
      apk_sha256: "ade12cd72b92f20a396dceb889ba24f899806a0afd7904cb69ff815909ef2eb1",
      version: "0.5.0-beta",
      approval_digest: "f".repeat(64),
      recipients: [{ tester_code: "T-03", recipient_name: "Sushant", chat_jid: "139307448799247@lid", caption: "Approved caption", status: "prepared" }]
    });

    const result = spawnSync(process.execPath, [command, "send",
      "--batch", batchDir,
      "--approve", "0".repeat(64)
    ], { encoding: "utf8" });

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /approval digest does not match/i);
  } finally {
    await rm(fixtureDir, { recursive: true, force: true });
  }
});

test("send stops the batch when one WhatsApp delivery is uncertain", async () => {
  const fixtureDir = await mkdtemp(join(tmpdir(), "isdm-release-"));
  try {
    const apk = join(fixtureDir, "ISDM-Companion-Beta-0.5.0.apk");
    const roster = join(fixtureDir, "roster.json");
    const recipients = join(fixtureDir, "recipients.json");
    const changes = join(fixtureDir, "changes.txt");
    const output = join(fixtureDir, "batch");
    const sendLog = join(fixtureDir, "send.log");
    await writeFile(apk, "signed-apk-fixture");
    await writeFile(changes, "• One verified change.\n");
    await privateJson(roster, { installations: [
      { tester_code: "T-03", installation_id: "a", support_name: "Sushant", manufacturer: "vivo" },
      { tester_code: "T-04", installation_id: "b", support_name: "Varsha", manufacturer: "OnePlus" }
    ] });
    await privateJson(recipients, {
      "T-03": { recipient_name: "Sushant", chat_jid: "111@lid" },
      "T-04": { recipient_name: "Varsha", chat_jid: "222@lid" }
    });
    const prepared = spawnSync(process.execPath, [command, "prepare",
      "--apk", apk, "--version", "0.5.0-beta", "--roster-json", roster,
      "--recipients", recipients, "--changes", changes, "--output", output, "--offline"
    ], { encoding: "utf8" });
    assert.equal(prepared.status, 0, prepared.stderr);
    const batch = JSON.parse(await readFile(join(output, "batch.json"), "utf8"));
    const plugin = join(repoRoot, "tests", "fixtures", "fake-whatsmeow-plugin");
    const sent = spawnSync(process.execPath, [command, "send",
      "--batch", output, "--approve", batch.approval_digest, "--whatsmeow-plugin", plugin
    ], {
      encoding: "utf8",
      env: {
        ...process.env,
        FAKE_SEND_LOG: sendLog,
        FAKE_WHATSAPP_CHATS: JSON.stringify([
          { chat_jid: "111@lid", name: "Sushant", is_group: false },
          { chat_jid: "222@lid", name: "Varsha", is_group: false }
        ])
      }
    });

    assert.notEqual(sent.status, 0);
    assert.match(sent.stderr, /stopped before the next recipient/i);
    assert.equal(await readFile(sendLog, "utf8"), "111@lid\n");
    const saved = JSON.parse(await readFile(join(output, "batch.json"), "utf8"));
    assert.equal(saved.recipients[0].status, "uncertain");
    assert.equal(saved.recipients[1].status, "prepared");
  } finally {
    await rm(fixtureDir, { recursive: true, force: true });
  }
});

test("send delivers the approved batch sequentially and records each success", async () => {
  const fixtureDir = await mkdtemp(join(tmpdir(), "isdm-release-"));
  try {
    const apk = join(fixtureDir, "ISDM-Companion-Beta-0.5.0.apk");
    const roster = join(fixtureDir, "roster.json");
    const recipients = join(fixtureDir, "recipients.json");
    const changes = join(fixtureDir, "changes.txt");
    const output = join(fixtureDir, "batch");
    const sendLog = join(fixtureDir, "send.log");
    await writeFile(apk, "signed-apk-fixture");
    await writeFile(changes, "• One verified change.\n");
    await privateJson(roster, { installations: [
      { tester_code: "T-03", installation_id: "a", support_name: "Sushant", manufacturer: "vivo" },
      { tester_code: "T-04", installation_id: "b", support_name: "Varsha", manufacturer: "OnePlus" }
    ] });
    await privateJson(recipients, {
      "T-03": { recipient_name: "Sushant", chat_jid: "111@lid" },
      "T-04": { recipient_name: "Varsha", chat_jid: "222@lid" }
    });
    const prepared = spawnSync(process.execPath, [command, "prepare",
      "--apk", apk, "--version", "0.5.0-beta", "--roster-json", roster,
      "--recipients", recipients, "--changes", changes, "--output", output, "--offline"
    ], { encoding: "utf8" });
    assert.equal(prepared.status, 0, prepared.stderr);
    const batch = JSON.parse(await readFile(join(output, "batch.json"), "utf8"));
    const plugin = join(repoRoot, "tests", "fixtures", "fake-whatsmeow-plugin");
    const sent = spawnSync(process.execPath, [command, "send",
      "--batch", output, "--approve", batch.approval_digest, "--whatsmeow-plugin", plugin
    ], {
      encoding: "utf8",
      env: {
        ...process.env,
        FAKE_ACTION_STATUS: "succeeded",
        FAKE_SEND_LOG: sendLog,
        FAKE_WHATSAPP_CHATS: JSON.stringify([
          { chat_jid: "111@lid", name: "Sushant", is_group: false },
          { chat_jid: "222@lid", name: "Varsha", is_group: false }
        ])
      }
    });

    assert.equal(sent.status, 0, sent.stderr);
    assert.equal(await readFile(sendLog, "utf8"), "111@lid\n222@lid\n");
    const saved = JSON.parse(await readFile(join(output, "batch.json"), "utf8"));
    assert.deepEqual(saved.recipients.map(({ status }) => status), ["succeeded", "succeeded"]);
    assert.match(saved.completed_at, /^\d{4}-\d{2}-\d{2}T/);
  } finally {
    await rm(fixtureDir, { recursive: true, force: true });
  }
});
