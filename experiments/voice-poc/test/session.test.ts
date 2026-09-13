import assert from "node:assert/strict";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { tools } from "../src/dispatcher.ts";
import { type Event, Session } from "../src/session.ts";

async function waitFor(probe: () => boolean): Promise<void> {
  const deadline = Date.now() + 2000;
  while (!probe()) {
    if (Date.now() > deadline) throw new Error("Fake provider timed out");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

test("session uses isolated tools, handles SDP before start response, returns receipts, and reconnects", async () => {
  const original = process.env.EVA_CODEX_BIN;
  process.env.EVA_CODEX_BIN = fileURLToPath(new URL("./fake-codex.mjs", import.meta.url));
  try {
    for (let reconnect = 0; reconnect < 2; reconnect++) {
      const events: Event[] = [];
      const session = new Session((event) => events.push(event));
      try {
        assert.equal(session.dispatcher.value, 0);
        await session.start("v=0", "Test only");
        await waitFor(() => events.some((event) => event.kind === "transcript"));
        assert.equal(events.find((event) => event.kind === "answer")?.sdp, "fake-answer");
        assert.equal(session.dispatcher.value, 7);
        const calls = events.filter((event) => event.kind === "dispatch");
        assert.equal(calls.length, 2);
        assert.equal(calls[0].requestId, calls[1].requestId);
        assert.equal(calls[0].backendTurns, 1);
        await session.close();
        await session.close();
        assert.equal(events.filter((event) => event.kind === "closed").length, 1);
        await assert.rejects(session.text("read it"), /not connected/);
      } finally {
        await session.close();
      }
    }
  } finally {
    if (original === undefined) delete process.env.EVA_CODEX_BIN;
    else process.env.EVA_CODEX_BIN = original;
  }
});

test("API authentication fails closed before a realtime session or tool can start", async () => {
  const original = process.env.EVA_CODEX_BIN;
  process.env.EVA_CODEX_BIN = fileURLToPath(new URL("./fake-codex.mjs", import.meta.url));
  process.env.EVA_TEST_AUTH = "apiKey";
  const events: Event[] = [];
  const session = new Session((event) => events.push(event));
  try {
    await session.start("v=0", "Test only");
    await waitFor(() => events.some((event) => event.kind === "closed"));
    assert.match(
      String(events.find((event) => event.kind === "error")?.message),
      /subscription login/,
    );
    assert.equal(
      events.some((event) => event.kind === "started" || event.kind === "dispatch"),
      false,
    );
  } finally {
    await session.close();
    delete process.env.EVA_TEST_AUTH;
    if (original === undefined) delete process.env.EVA_CODEX_BIN;
    else process.env.EVA_CODEX_BIN = original;
  }
});

test("text-only session advertises supplied tools and returns asynchronous phone evidence to the model", async () => {
  const original = process.env.EVA_CODEX_BIN;
  process.env.EVA_CODEX_BIN = fileURLToPath(new URL("./fake-codex.mjs", import.meta.url));
  const events: Event[] = [];
  let release!: () => void;
  const waiting = new Promise<void>((resolve) => {
    release = resolve;
  });
  const session = new Session((event) => events.push(event), {
    tools,
    instructions: "Use phone tools",
    execute: async (callId, name, args) => {
      assert.equal(callId, "device-call");
      assert.equal(name, tools[0].name);
      assert.deepEqual(args, { destination: "Ferry Building" });
      await waiting;
      return { success: true, status: "HANDED_OFF", message: "Phone opened map" };
    },
  });
  try {
    await session.start(undefined, "");
    assert.equal(
      events.some((event) => event.kind === "answer"),
      false,
    );
    assert.equal(events.find((event) => event.kind === "started")?.mode, "text");
    await session.text("Show the Ferry Building on a map");
    assert.equal(
      events.some((event) => event.kind === "backend-output"),
      false,
    );
    release();
    await waitFor(() => events.some((event) => event.kind === "backend-output"));
    assert.match(
      String(events.find((event) => event.kind === "backend-output")?.text),
      /Phone opened map/,
    );
  } finally {
    release();
    await session.close();
    if (original === undefined) delete process.env.EVA_CODEX_BIN;
    else process.env.EVA_CODEX_BIN = original;
  }
});
