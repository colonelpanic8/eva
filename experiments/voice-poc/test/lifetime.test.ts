import assert from "node:assert/strict";
import { test } from "node:test";
import { Lifetime, waitForState } from "../src/lifetime.ts";

test("close is idempotent, releases late microphone acquisition and continues after cleanup error", () => {
  const lifetime = new Lifetime();
  const calls: string[] = [];
  lifetime.add(() => calls.push("peer"));
  lifetime.add(() => {
    throw new Error("already closed");
  });
  lifetime.close();
  lifetime.close();
  lifetime.add(() => calls.push("late microphone"));
  assert.deepEqual(calls, ["peer", "late microphone"]);
  assert.throws(() => lifetime.assertOpen(), /canceled/);
});

test("ICE wait resolves on state, times out, or aborts without blocking reconnection", async () => {
  const target = new EventTarget();
  const old = new Lifetime();
  const pending = waitForState(target, "change", () => false, old, 1000);
  old.close();
  await assert.rejects(pending, /canceled/);
  const next = new Lifetime();
  let ready = false;
  const connected = waitForState(target, "change", () => ready, next, 1000);
  ready = true;
  target.dispatchEvent(new Event("change"));
  await connected;
  await assert.rejects(
    waitForState(target, "change", () => false, next, 5),
    /Timed out/,
  );
  next.close();
});
