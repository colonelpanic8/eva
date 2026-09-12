import assert from "node:assert/strict";
import { test } from "node:test";
import { Dispatcher } from "../src/dispatcher.ts";

test("executes validated tools, rejects unadvertised actions and malformed arguments", () => {
  const dispatcher = new Dispatcher();
  assert.deepEqual(dispatcher.execute("read", "eva_counter_get", {}), {
    requestId: "read",
    success: true,
    value: 0,
  });
  assert.equal(dispatcher.execute("write", "eva_counter_set", { value: 7 }).value, 7);
  for (const [id, name, args] of [
    ["a", "shell", {}],
    ["b", "eva_counter_set", { value: 1.5 }],
    ["c", "eva_counter_set", { value: "8" }],
    ["d", "eva_counter_set", { value: 1001 }],
    ["e", "eva_counter_set", { value: 1, extra: true }],
    ["f", "eva_counter_get", []],
  ] as const)
    assert.equal(dispatcher.execute(id, name, args).success, false);
  assert.equal(dispatcher.value, 7);
});

test("replays receipts without overwriting newer state, rejects ID conflicts, isolates reconnects", () => {
  const dispatcher = new Dispatcher();
  const original = dispatcher.execute("one", "eva_counter_set", { value: 1 });
  dispatcher.execute("two", "eva_counter_set", { value: 2 });
  assert.deepEqual(dispatcher.execute("one", "eva_counter_set", { value: 1 }), original);
  assert.equal(dispatcher.value, 2);
  assert.equal(dispatcher.execute("one", "eva_counter_set", { value: 3 }).success, false);
  assert.equal(dispatcher.value, 2);
  assert.equal(new Dispatcher().value, 0);
});
