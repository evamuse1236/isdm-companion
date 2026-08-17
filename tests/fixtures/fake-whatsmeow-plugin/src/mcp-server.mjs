import { appendFileSync } from "node:fs";
import { createInterface } from "node:readline";

const chats = JSON.parse(process.env.FAKE_WHATSAPP_CHATS ?? "[]");
const sendLog = process.env.FAKE_SEND_LOG;
const actionStatus = process.env.FAKE_ACTION_STATUS ?? "failed";
let actionNumber = 0;

function toolResult(data) {
  return { content: [{ type: "text", text: JSON.stringify(data) }] };
}

createInterface({ input: process.stdin }).on("line", (line) => {
  const request = JSON.parse(line);
  if (request.id == null) return;
  let result;
  if (request.method === "initialize") {
    result = { protocolVersion: "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "fake-whatsmeow", version: "1" } };
  } else if (request.method === "tools/call") {
    const { name, arguments: args } = request.params;
    if (name === "whatsapp_status") result = toolResult({ collector_alive: true, collector_status: "connected", history_latest_received: true });
    else if (name === "list_chats") result = toolResult(chats);
    else if (name === "search_messages") result = toolResult({ messages: [] });
    else if (name === "send_media") {
      if (sendLog) appendFileSync(sendLog, `${args.chat_jid}\n`);
      actionNumber += 1;
      result = toolResult({ action_id: `action-${actionNumber}`, action: "send_media", status: "pending" });
    } else if (name === "get_action_status") {
      result = toolResult({
        action_id: args.action_id,
        action: "send_media",
        status: actionStatus,
        result: actionStatus === "succeeded" ? { message_id: `message-${args.action_id}` } : null,
        error: actionStatus === "failed" ? "fixture failure" : null
      });
    } else throw new Error(`Unsupported fake tool: ${name}`);
  } else throw new Error(`Unsupported fake method: ${request.method}`);
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id: request.id, result })}\n`);
});
