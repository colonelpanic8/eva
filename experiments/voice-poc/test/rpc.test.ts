import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { test } from "node:test";
import { Rpc, safeError } from "../src/rpc.ts";

test("correlates responses, handles server requests, ignores late replies and rejects pending on shutdown", async () => {
  const child = spawn(process.execPath, ["-e", "setInterval(()=>{}, 1000)"], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  const rpc = new Rpc(child);
  try {
    const one = rpc.request("one", {}, 1000);
    const two = rpc.request("two", {}, 1000);
    rpc.receive({ id: 2, result: "second" });
    rpc.receive({ id: 1, result: "first" });
    assert.equal(await one, "first");
    assert.equal(await two, "second");
    await assert.rejects(rpc.request("timeout", {}, 5), /timed out/);
    rpc.receive({ id: 3, result: "too late" });
    let handled = false;
    rpc.onRequest = () => {
      handled = true;
      return { success: true };
    };
    rpc.receive({ id: "server-call", method: "item/tool/call", params: {} });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(handled, true);
    const pending = rpc.request("pending", {}, 1000);
    rpc.dispose();
    await assert.rejects(pending, /closed/);
    await assert.rejects(rpc.request("after", {}), /closed/);
  } finally {
    rpc.dispose();
  }
});

test("diagnostics redact bearer tokens, JWTs, URLs and email addresses", () => {
  const sanitized = safeError(
    "Bearer secret sk-testkey123 eyJabcdefghijklmnopqrstuvwxyz0123 user@example.org https://host/path?token=secret",
  );
  for (const secret of ["secret", "sk-test", "eyJ", "user@example", "https://"])
    assert.equal(sanitized.includes(secret), false);
});
