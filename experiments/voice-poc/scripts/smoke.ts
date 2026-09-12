import assert from "node:assert/strict";
import { chromium } from "playwright-core";

const url = process.env.EVA_SMOKE_URL;
const syntheticAudio = process.env.EVA_SMOKE_AUDIO;
if (!url || !/^http:\/\/(localhost|127\.0\.0\.1):\d+\/#/.test(url))
  throw new Error("Set EVA_SMOKE_URL to this harness's localhost startup link");
const browser = await chromium.launch({
  executablePath: process.env.EVA_CHROME_BIN ?? "/run/current-system/sw/bin/google-chrome",
  args: [
    "--autoplay-policy=no-user-gesture-required",
    ...(syntheticAudio
      ? [
          "--use-fake-device-for-media-stream",
          "--use-fake-ui-for-media-stream",
          `--use-file-for-fake-audio-capture=${syntheticAudio}%noloop`,
        ]
      : []),
  ],
});
const page = await browser.newPage();
const readDiagnostics = () =>
  page.evaluate(
    () => (window as unknown as { evaDiagnostics: Record<string, unknown>[] }).evaDiagnostics,
  );
try {
  await page.goto(url);
  assert.equal(await page.locator("#mic").isChecked(), false);
  if (syntheticAudio) await page.locator("#mic").check();
  await page
    .locator("#instructions")
    .fill(
      "After completing a counter action, say EVA confirms and the counter value. Use one short sentence.",
    );
  console.log(
    `[smoke] Connecting with ${syntheticAudio ? "a synthetic WAV fake microphone" : "generated silence"}; no ambient microphone access`,
  );
  await page.locator("#connect").click();
  await page.waitForFunction(
    () => {
      const rows = (window as unknown as { evaDiagnostics: Record<string, unknown>[] })
        .evaDiagnostics;
      return rows.some((row) => row.kind === "started" || row.kind === "error");
    },
    {},
    { timeout: 70_000 },
  );
  const initial = await readDiagnostics();
  const error = initial.find((row) => row.kind === "error");
  if (error) throw new Error(String(error.message));
  console.log("[smoke] Subscription accepted; waiting for WebRTC");
  await page.waitForFunction(
    () => document.getElementById("status")?.textContent?.includes("Connected"),
    {},
    { timeout: 30000 },
  );
  if (syntheticAudio) {
    console.log("[smoke] Waiting for counter set + read from synthetic speech");
    await page.waitForFunction(
      () => {
        const rows = (window as unknown as { evaDiagnostics: Record<string, unknown>[] })
          .evaDiagnostics;
        return (
          rows.filter((row) => row.kind === "dispatch").length >= 2 ||
          rows.some((row) => row.kind === "error")
        );
      },
      {},
      { timeout: 60000 },
    );
    const dispatches = (await readDiagnostics()).filter((row) => row.kind === "dispatch");
    assert(
      dispatches.some((row) => row.tool === "eva_counter_set" && row.value === 7 && row.success),
    );
    assert(
      dispatches.some((row) => row.tool === "eva_counter_get" && row.value === 7 && row.success),
    );
  } else
    for (const [prompt, expected] of [
      ["Set the EVA counter to 7 using eva_counter_set. Report the tool result.", 7],
      ["Read the EVA counter now using eva_counter_get. Report its value.", 7],
    ] as const) {
      console.log(`[smoke] ${prompt}`);
      const previous = (await readDiagnostics()).filter((row) => row.kind === "dispatch").length;
      await page.locator("#text").fill(prompt);
      await page.locator("#send").click();
      await page.waitForFunction(
        (count) => {
          const rows = (window as unknown as { evaDiagnostics: Record<string, unknown>[] })
            .evaDiagnostics;
          return (
            rows.filter((row) => row.kind === "dispatch").length > count ||
            rows.some((row) => row.kind === "error")
          );
        },
        previous,
        { timeout: 60000 },
      );
      const rows = await readDiagnostics();
      const result = rows.filter((row) => row.kind === "dispatch").at(-1);
      assert.equal(result?.success, true);
      assert.equal(result?.value, expected);
      await page.waitForFunction(
        (count) =>
          (
            window as unknown as { evaDiagnostics: Record<string, unknown>[] }
          ).evaDiagnostics.filter((row) => row.kind === "backend-completed").length > count,
        previous,
        { timeout: 30000 },
      );
    }
  await page.waitForFunction(
    (isVoice) =>
      [...document.querySelectorAll("#transcript p")].some((row) => {
        const text = row.textContent ?? "";
        return (
          text.startsWith(isVoice ? "assistant:" : "executor:") &&
          /\b(seven|7)\b/i.test(text) &&
          /eva/i.test(text)
        );
      }),
    !!syntheticAudio,
    { timeout: 30000 },
  );
  const rows = await readDiagnostics();
  if (syntheticAudio)
    assert(
      rows.some(
        (row) =>
          row.kind === "webrtc-audio" && row.direction === "inbound-rtp" && Number(row.bytes) > 0,
      ),
    );
  await page.locator("#interrupt").click();
  assert.equal(await page.locator("#audio").evaluate((el) => (el as HTMLAudioElement).muted), true);
  const finalRows = await readDiagnostics();
  console.log(
    JSON.stringify(
      {
        status: "PASS",
        inputSource: syntheticAudio
          ? "synthetic WAV through Chrome fake capture"
          : "generated silence + typed input",
        finalCounterAndCustomInstructionsConfirmed: true,
        localInterruptMuted: true,
        events: finalRows.filter(
          (row) => !["webrtc-audio", "selected-pair", "provider-event"].includes(String(row.kind)),
        ),
        audio: rows.filter((row) => row.kind === "webrtc-audio").slice(-2),
        selectedPair: rows.filter((row) => row.kind === "selected-pair").at(-1),
      },
      null,
      2,
    ),
  );
} catch (error) {
  console.log(
    JSON.stringify(
      {
        status: "FAIL",
        error: error instanceof Error ? error.message : String(error),
        audio: (await readDiagnostics()).filter((row) => row.kind === "webrtc-audio").slice(-2),
        events: (await readDiagnostics()).filter(
          (row) => !["webrtc-audio", "selected-pair"].includes(String(row.kind)),
        ),
      },
      null,
      2,
    ),
  );
  process.exitCode = 1;
} finally {
  await page
    .locator("#disconnect")
    .click()
    .catch(() => {});
  await browser.close();
}
