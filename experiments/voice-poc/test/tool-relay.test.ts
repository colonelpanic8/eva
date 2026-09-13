import assert from "node:assert/strict";
import { test } from "node:test";
import type { RecordValue } from "../src/rpc.ts";
import { deviceTools, ToolRelay } from "../src/tool-relay.ts";

const tools = deviceTools([
  { name: "open_place", description: "Open a place", inputSchema: { type: "object" } },
]);

test("relay waits for phone evidence and deduplicates pending and completed calls", async () => {
  const events: RecordValue[] = [];
  const relay = new ToolRelay(tools, (event) => events.push(event));
  try {
    relay.beginInput("input");
    relay.startGeneration("provider-turn");
    const first = relay.execute("call", "open_place", { destination: "Park" });
    assert.equal(relay.execute("call", "open_place", { destination: "Park" }), first);
    assert.equal(events.length, 1);
    assert.equal(
      (await relay.execute("second", "open_place", { destination: "Other" })).status,
      "NOT_EXECUTED",
    );
    assert.equal(events.length, 1);
    assert.throws(() => relay.completeInput(), /unresolved/);
    relay.accept({ ...events[0], result: { status: "HANDED_OFF", message: "Opened" } });
    assert.deepEqual(await first, { success: true, status: "HANDED_OFF", message: "Opened" });
    assert.equal(relay.execute("call", "open_place", { destination: "Park" }), first);
    await assert.rejects(
      relay.execute("call", "open_place", { destination: "Other" }),
      /Conflicting/,
    );
    assert.equal(relay.completeInput(), "input");
    assert.throws(() => relay.beginInput("input"), /duplicated/);
  } finally {
    relay.close();
  }
});

test("relay rejects unknown tools and stale results; disconnect resolves uncertainty without replay", async () => {
  const events: RecordValue[] = [];
  const relay = new ToolRelay(tools, (event) => events.push(event));
  relay.beginInput("input");
  relay.startGeneration("provider-turn");
  await assert.rejects(relay.execute("unknown", "not_advertised", {}), /Unknown/);
  const result = relay.execute("call", "open_place", {});
  assert.throws(() => relay.accept({ ...events[0], sessionId: "other", result: {} }), /Stale/);
  assert.throws(() => relay.accept({ ...events[0], inputId: "other", result: {} }), /Unexpected/);
  assert.throws(
    () => relay.accept({ ...events[0], generationId: "other", result: {} }),
    /Unexpected/,
  );
  assert.throws(
    () => relay.accept({ ...events[0], providerTurnId: "other", result: {} }),
    /Unexpected/,
  );
  assert.throws(
    () => relay.accept({ ...events[0], catalogRevision: "other", result: {} }),
    /Stale/,
  );
  assert.throws(
    () => relay.accept({ ...events[0], result: { status: "SENT", message: "invented" } }),
    /Invalid/,
  );
  relay.close();
  assert.equal((await result).status, "UNKNOWN");
  assert.equal(events.length, 1);
  await assert.rejects(relay.execute("new", "open_place", {}), /No active/);
});

test("timeout ends the relay and never sends the action again", async () => {
  const events: RecordValue[] = [];
  const relay = new ToolRelay(tools, (event) => events.push(event), 5);
  relay.beginInput("input");
  relay.startGeneration("provider-turn");
  const result = await relay.execute("call", "open_place", {});
  assert.equal(result.status, "UNKNOWN");
  assert.equal(events.filter((event) => event.kind === "tool-call").length, 1);
  assert.equal(events.at(-1)?.kind, "error");
});

test("catalog rejects duplicate names and malformed schemas", () => {
  assert.throws(() => deviceTools([...tools, ...tools]), /duplicate/);
  assert.throws(
    () => deviceTools([{ name: "tool", description: "test", inputSchema: {} }]),
    /Invalid/,
  );
});

test("outcome envelopes preserve structured results without depending on the action kind", async () => {
  for (const status of [
    "COMPLETED",
    "ACCEPTED",
    "HANDED_OFF",
    "NOT_EXECUTED",
    "FAILED",
    "CANCELED",
    "UNKNOWN",
    "REQUIRES_RESOLUTION",
  ]) {
    const events: RecordValue[] = [];
    const relay = new ToolRelay(tools, (event) => events.push(event));
    try {
      relay.beginInput("input");
      await assert.rejects(relay.execute("call", "open_place", {}), /generation/);
      relay.startGeneration("generation");
      const result = relay.execute("call", "open_place", {});
      relay.accept({
        ...events[0],
        result: {
          status,
          message: "Actual result",
          data: { value: 7 },
          evidence: { source: "backend" },
        },
      });
      assert.deepEqual((await result).data, { value: 7 });
      assert.deepEqual((await result).evidence, { source: "backend" });
      assert.equal(
        (await result).success,
        ["COMPLETED", "ACCEPTED", "HANDED_OFF"].includes(status),
      );
    } finally {
      relay.close();
    }
  }
});
