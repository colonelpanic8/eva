import assert from "node:assert/strict";
import { randomInt } from "node:crypto";
import { once } from "node:events";
import { createServer } from "node:http";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocket, WebSocketServer } from "ws";
import { attachDeviceConnections } from "../src/device-connection.ts";
import type { RecordValue } from "../src/rpc.ts";

test("device connection authenticates, advertises its catalog and returns phone evidence to the model", async () => {
  const original = process.env.EVA_CODEX_BIN;
  process.env.EVA_CODEX_BIN = fileURLToPath(new URL("./fake-codex.mjs", import.meta.url));
  const token = "test-only-access-code";
  const server = createServer();
  const wss = new WebSocketServer({ server });
  attachDeviceConnections(wss, token);
  const port = randomInt(40000, 60000);
  server.listen(port, "127.0.0.1");
  await once(server, "listening");
  const url = `ws://127.0.0.1:${port}/device`;
  const denied = new WebSocket(url);
  try {
    await once(denied, "open");
    denied.send(JSON.stringify({ version: 1, token: "incorrect" }));
    const [code] = await once(denied, "close");
    assert.equal(code, 1008);
    const client = new WebSocket(url);
    const events: RecordValue[] = [];
    client.on("message", (raw) => events.push(JSON.parse(raw.toString())));
    const waitFor = async (kind: string): Promise<RecordValue> => {
      const deadline = Date.now() + 5000;
      while (!events.some((event) => event.kind === kind)) {
        const failure = events.find((event) => event.kind === "error");
        if (failure) throw new Error(String(failure.message));
        if (Date.now() > deadline) throw new Error(`Missing ${kind}`);
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
      return events.find((event) => event.kind === kind) as RecordValue;
    };
    await once(client, "open");
    client.send(JSON.stringify({ version: 1, token }));
    await waitFor("authorized");
    client.send(
      JSON.stringify({
        type: "start",
        catalogRevision: "catalog-1",
        instructions: "Help with phone actions",
        tools: ["open_map", "open_navigation"].map((name) => ({
          name,
          description: "Open a place",
          inputSchema: {
            type: "object",
            properties: { destination: { type: "string" } },
            required: ["destination"],
            additionalProperties: false,
          },
        })),
      }),
    );
    const { sessionId, catalogRevision } = await waitFor("started");
    assert.equal(catalogRevision, "catalog-1");
    client.send(
      JSON.stringify({
        type: "text",
        sessionId,
        catalogRevision,
        inputId: "input",
        text: "Show the Ferry Building",
      }),
    );
    const call = await waitFor("tool-call");
    assert.equal(call.tool, "open_map");
    assert.equal(call.sessionId, sessionId);
    assert.equal(call.inputId, "input");
    assert.equal(call.generationId, "input");
    assert.equal(call.providerTurnId, "typed");
    assert.equal(call.catalogRevision, catalogRevision);
    assert.equal(
      events.some((event) => event.kind === "backend-output"),
      false,
    );
    client.send(
      JSON.stringify({
        ...call,
        type: "tool-result",
        result: { status: "HANDED_OFF", message: "Map opened." },
      }),
    );
    const output = await waitFor("backend-output");
    assert.match(String(output.text), /Map opened/);
    assert.equal((await waitFor("backend-completed")).inputId, "input");
    client.send(JSON.stringify({ type: "stop" }));
    await once(client, "close");
  } finally {
    denied.terminate();
    for (const client of wss.clients) client.terminate();
    wss.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    if (original === undefined) delete process.env.EVA_CODEX_BIN;
    else process.env.EVA_CODEX_BIN = original;
  }
});

test("voice negotiates an empty catalog and denies even a delegated tool call", async () => {
  const original = process.env.EVA_CODEX_BIN;
  process.env.EVA_CODEX_BIN = fileURLToPath(new URL("./fake-codex.mjs", import.meta.url));
  const server = createServer();
  const wss = new WebSocketServer({ server });
  attachDeviceConnections(wss, "voice-test");
  server.listen(randomInt(40000, 60000), "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const client = new WebSocket(`ws://127.0.0.1:${address.port}/device`);
  const events: RecordValue[] = [];
  client.on("message", (raw) => {
    const event = JSON.parse(raw.toString());
    events.push(event);
    if (event.kind === "authorized")
      client.send(
        JSON.stringify({
          type: "start",
          mode: "voice",
          sdp: "v=0 test-offer",
          catalogRevision: "voice-1",
          tools: [],
          instructions: "Talk to the user",
        }),
      );
  });
  try {
    await once(client, "open");
    client.send(JSON.stringify({ version: 1, token: "voice-test" }));
    const deadline = Date.now() + 5000;
    while (!events.some((event) => event.kind === "transcript")) {
      assert.equal(
        events.find((event) => event.kind === "error"),
        undefined,
      );
      assert.ok(Date.now() < deadline, "Voice reply timed out");
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    assert.equal(events.find((event) => event.kind === "answer")?.sdp, "fake-answer");
    assert.equal(events.find((event) => event.kind === "transcript")?.text, "Voice tool denied");
    assert.equal(
      events.some((event) => event.kind === "tool-call" || event.kind === "backend-turn"),
      false,
    );
    client.send(JSON.stringify({ type: "stop" }));
    await once(client, "close");
  } finally {
    client.terminate();
    for (const peer of wss.clients) peer.terminate();
    wss.close();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    if (original === undefined) delete process.env.EVA_CODEX_BIN;
    else process.env.EVA_CODEX_BIN = original;
  }
});
