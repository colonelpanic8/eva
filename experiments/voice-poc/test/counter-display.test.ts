import assert from "node:assert/strict";
import { test } from "node:test";
import { CounterDisplay } from "../src/counter-display.ts";
import { Dispatcher } from "../src/dispatcher.ts";

test("replayed old receipts do not replace the displayed current counter", () => {
  const dispatcher = new Dispatcher();
  const display = new CounterDisplay();
  const before = dispatcher.execute("read-before", "eva_counter_get", {});
  display.apply(before);
  display.apply(dispatcher.execute("set", "eva_counter_set", { value: 7 }));
  assert.equal(display.apply(dispatcher.execute("read-before", "eva_counter_get", {})), 7);
  assert.equal(display.apply(dispatcher.execute("invalid", "eva_counter_set", { value: 1001 })), 7);
  assert.equal(new CounterDisplay().value, 0);
});
