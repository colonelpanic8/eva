#!/usr/bin/env node
import { createInterface } from "node:readline";

const send = (value) => process.stdout.write(`${JSON.stringify(value)}\n`);
const notify = (method, params = {}) => send({ method, params });
let resultCount = 0;
createInterface({ input: process.stdin }).on("line", (line) => {
  const msg = JSON.parse(line);
  const params = msg.params ?? {};
  if (!msg.method) {
    if (msg.id === "tool" || msg.id === "repeat") {
      if (!msg.result?.success) process.exit(3);
      if (++resultCount === 2)
        notify("thread/realtime/transcript/done", { role: "assistant", text: "EVA confirms 7" });
    }
    return;
  }
  if (msg.id === undefined) return;
  let result = {};
  if (msg.method === "initialize") result = { userAgent: "fake-test-only" };
  if (msg.method === "account/read")
    result = { account: { type: process.env.EVA_TEST_AUTH ?? "chatgpt", planType: "test" } };
  if (msg.method === "config/read")
    result = { config: { mcp_servers: { unrelated: { command: "never-run" } } } };
  if (msg.method === "thread/start") {
    if (
      params.config?.mcp_servers?.unrelated?.enabled !== false ||
      params.dynamicTools?.length !== 2 ||
      params.config?.features?.shell_tool !== false ||
      !params.ephemeral
    ) {
      send({ id: msg.id, error: { message: "Missing isolation configuration" } });
      return;
    }
    result = { thread: { id: "test-thread" }, model: params.model };
  }
  if (msg.method === "thread/realtime/start") {
    notify("thread/realtime/sdp", { sdp: "fake-answer" });
    notify("thread/realtime/started");
    const toolParams = {
      threadId: "test-thread",
      turnId: "t1",
      callId: "unique",
      tool: "eva_counter_set",
      arguments: { value: 7 },
    };
    notify("turn/started", { threadId: "test-thread", turn: { id: "t1" } });
    send({ id: "tool", method: "item/tool/call", params: toolParams });
    send({ id: "repeat", method: "item/tool/call", params: toolParams });
  }
  if (msg.method === "thread/realtime/stop")
    notify("thread/realtime/closed", { reason: "requested" });
  send({ id: msg.id, result });
});
