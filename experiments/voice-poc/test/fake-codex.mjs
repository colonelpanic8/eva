#!/usr/bin/env node
import { createInterface } from "node:readline";

const send = (value) => process.stdout.write(`${JSON.stringify(value)}\n`);
const notify = (method, params = {}) => send({ method, params });
let resultCount = 0;
let catalog = [];
createInterface({ input: process.stdin }).on("line", (line) => {
  const msg = JSON.parse(line);
  const params = msg.params ?? {};
  if (!msg.method) {
    if (msg.id === "voice-forbidden") {
      notify("thread/realtime/transcript/done", {
        role: "assistant",
        text: msg.error ? "Voice tool denied" : "UNSAFE tool accepted",
      });
    }
    if (msg.id === "device-tool") {
      notify("item/completed", {
        item: { type: "agentMessage", text: JSON.stringify(msg.result) },
      });
      notify("turn/completed", {
        threadId: "test-thread",
        turn: { id: "typed", status: "completed" },
      });
    }
    if (msg.id === "voice-tool") {
      if (!msg.result?.success) process.exit(4);
      notify("item/completed", {
        item: { type: "agentMessage", text: JSON.stringify(msg.result) },
      });
      notify("turn/completed", {
        threadId: "test-thread",
        turn: { id: "voice-turn-1", status: "completed" },
      });
      notify("thread/realtime/transcript/done", { role: "assistant", text: "Timer set" });
    }
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
    catalog = params.dynamicTools ?? [];
    if (
      params.config?.mcp_servers?.unrelated?.enabled !== false ||
      ![0, 1, 2].includes(params.dynamicTools?.length) ||
      params.config?.features?.shell_tool !== false ||
      !params.ephemeral
    ) {
      send({ id: msg.id, error: { message: "Missing isolation configuration" } });
      return;
    }
    result = { thread: { id: "test-thread" }, model: params.model };
  }
  if (msg.method === "turn/start") {
    result = { turn: { id: "typed" } };
    notify("turn/started", { threadId: "test-thread", turn: { id: "typed" } });
    send({
      id: "device-tool",
      method: "item/tool/call",
      params: {
        threadId: "test-thread",
        turnId: "typed",
        callId: "device-call",
        tool: catalog[0].name,
        arguments: { destination: "Ferry Building" },
      },
    });
  }
  if (msg.method === "thread/realtime/start") {
    notify("thread/realtime/sdp", { sdp: "fake-answer" });
    notify("thread/realtime/started");
    if (catalog.length === 0) {
      notify("turn/started", { threadId: "delegated-voice-thread", turn: { id: "voice-turn" } });
      send({
        id: "voice-forbidden",
        method: "item/tool/call",
        params: {
          threadId: "delegated-voice-thread",
          turnId: "voice-turn",
          callId: "forbidden",
          tool: "open_map",
          arguments: {},
        },
      });
      send({ id: msg.id, result });
      return;
    }
    if (catalog.length === 1) {
      notify("turn/started", { threadId: "test-thread", turn: { id: "voice-turn-1" } });
      notify("thread/realtime/transcript/done", {
        role: "user",
        text: "Set a timer for three minutes",
      });
      send({
        id: "voice-tool",
        method: "item/tool/call",
        params: {
          threadId: "test-thread",
          turnId: "voice-turn-1",
          callId: "voice-call-1",
          tool: catalog[0].name,
          arguments: { seconds: 180 },
        },
      });
      send({ id: msg.id, result });
      return;
    }
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
