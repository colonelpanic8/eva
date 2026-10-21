"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");

function validateScenario(scenario) {
  const nodes = scenario.nodes;
  if (!nodes || !nodes[scenario.start] || nodes.end?.type !== "end")
    throw new Error("Scenario start or end is missing");
  if (
    !Number.isInteger(scenario.hold_segment_seconds) ||
    scenario.hold_segment_seconds < 1
  ) {
    throw new Error("hold_segment_seconds must be a positive integer");
  }
  for (const [id, node] of Object.entries(nodes)) {
    if (!["gather", "hold", "end"].includes(node.type))
      throw new Error(`Invalid node ${id}`);
    for (const link of ["next", "empty"]) {
      if (node[link] && !nodes[node[link]])
        throw new Error(`Missing ${link} target for ${id}`);
    }
    if (node.type !== "end" && !node.next)
      throw new Error(`Missing next for ${id}`);
    if (node.type === "gather") {
      if (
        !["speech", "dtmf", "dtmf speech"].includes(node.input) ||
        !node.prompt ||
        !node.empty
      ) {
        throw new Error(`Invalid gather ${id}`);
      }
      if (!(node.timeout > 0) || !(node.speechTimeout > 0))
        throw new Error(`Invalid timeout ${id}`);
      if (node.readback !== undefined && node.input !== "dtmf")
        throw new Error(`readback needs keypad-only input at ${id}`);
    }
    if (node.end_pattern) new RegExp(node.end_pattern, "i");
  }
  validateExpectations(scenario);
  // Hold nodes execute within one TwiML response, so a hold-only cycle cannot advance.
  for (const id of Object.keys(nodes)) {
    const seen = new Set();
    let current = id;
    while (nodes[current].type === "hold") {
      if (seen.has(current)) throw new Error(`Hold cycle at ${current}`);
      seen.add(current);
      current = nodes[current].next;
    }
  }
  return scenario;
}

function validateExpectations({ nodes, expect }) {
  if (!expect) return;
  for (const [id, checks] of Object.entries(expect.gathers || {})) {
    const input = nodes[id]?.type === "gather" ? nodes[id].input : "";
    for (const [kind, value] of Object.entries(checks)) {
      const source = kind === "digits" ? "dtmf" : "speech";
      if (
        !["digits", "phrase", "spoken_digits"].includes(kind) ||
        typeof value !== "string" ||
        !value ||
        !input.includes(source)
      )
        throw new Error(`Invalid expectation ${kind} at ${id}`);
    }
  }
  const spoken = Object.values(nodes).map(
    (node) => `${node.prompt || ""} ${node.reply || ""}`,
  );
  for (const fact of expect.facts || []) {
    if (!fact || !spoken.some((text) => text.includes(fact)))
      throw new Error(`Fact is never spoken: ${fact}`);
  }
}

function receipt(event) {
  return {
    kind: "gather",
    call_sid: event.CallSid,
    step: event.step,
    turn: Number(event.turn),
    digits: event.Digits ?? null,
    speech_result: event.SpeechResult ?? null,
    confidence: event.Confidence ?? null,
  };
}

function logReceipt(value) {
  // Call Events retain the full webhook; Functions retain only 3KB per invocation.
  let line = `EVA_HOTLINE ${JSON.stringify(value)}`;
  if (Buffer.byteLength(line) > 2400) {
    line = `EVA_HOTLINE ${JSON.stringify({
      kind: "gather_deferred",
      call_sid: value.call_sid,
      step: value.step,
      turn: value.turn,
      reason: "Full Gather is in Call Events; exceeds Functions log budget",
    })}`;
  }
  console.log(line);
}

function render(scenario, event, response, baseUrl) {
  const say = (target, text) =>
    target.say({ voice: scenario.voice, language: scenario.language }, text);
  let step = scenario.start;
  let turn = 0;
  if (event.step) {
    const node = scenario.nodes[event.step];
    if (
      !node ||
      node.type !== "gather" ||
      !Number.isInteger(Number(event.turn)) ||
      Number(event.turn) < 0
    ) {
      throw new Error("Unknown gather callback");
    }
    turn = Number(event.turn) + 1;
    const text = event.SpeechResult || "";
    if (event.Digits)
      say(
        response,
        node.readback
          ? node.readback.replace(
              "{last4}",
              event.Digits.slice(-4).split("").join(" "),
            )
          : `You pressed ${event.Digits}`,
      );
    else if (text) say(response, `I heard: ${text}`);
    else say(response, scenario.empty_reply);
    const empty = !event.Digits && !text;
    if (!empty && node.reply) say(response, node.reply);
    step = empty ? node.empty : node.next;
    if (
      !empty &&
      node.end_pattern &&
      new RegExp(node.end_pattern, "i").test(text)
    )
      step = "end";
  } else {
    say(response, scenario.greeting);
    say(response, scenario.recording_notice);
    if (event.recordingFailed) say(response, scenario.recording_failure);
  }
  while (true) {
    const node = scenario.nodes[step];
    if (node.type === "end") {
      say(response, node.prompt);
      response.hangup();
      break;
    }
    if (node.type === "hold") {
      response.play(`${baseUrl}/hold.wav`);
      say(response, node.interjection);
      response.play(`${baseUrl}/hold.wav`);
      step = node.next;
      continue;
    }
    const attrs = {
      input: node.input,
      timeout: node.timeout,
      speechTimeout: node.speechTimeout,
      actionOnEmptyResult: true,
      method: "POST",
      language: scenario.language,
      action: `${baseUrl}/hotline?step=${encodeURIComponent(step)}&turn=${turn}`,
    };
    for (const key of ["numDigits", "hints"])
      if (node[key]) attrs[key] = node[key];
    say(response.gather(attrs), node.prompt);
    break;
  }
  return response;
}

exports.handler = async function (context, event, callback) {
  try {
    const scenario = validateScenario(
      JSON.parse(
        fs.readFileSync(Runtime.getAssets()["/scenario.json"].path, "utf8"),
      ),
    );
    const baseUrl = `https://${context.DOMAIN_NAME}`;
    if (!event.step && !event.kind) {
      const response = new Twilio.twiml.VoiceResponse();
      response.say(
        { voice: scenario.voice, language: scenario.language },
        scenario.answer_prompt,
      );
      response.redirect({ method: "POST" }, `${baseUrl}/hotline?kind=answered`);
      return callback(null, response);
    }
    const client = context.getTwilioClient();
    const maps = client.sync.v1.services(context.SYNC_SERVICE_SID).syncMaps;
    const mapName = `call-${event.CallSid}`;
    const map = maps(mapName);
    let persistenceFailed = false;
    const save = async (value) => {
      logReceipt(value);
      const hash = crypto
        .createHash("sha256")
        .update(JSON.stringify(value))
        .digest("hex")
        .slice(0, 16);
      try {
        await map.syncMapItems.create({
          key: `${value.kind}-${value.turn ?? value.recording_sid ?? "start"}-${hash}`,
          data: { ...value, received_at: new Date().toISOString() },
        });
      } catch (error) {
        if (error.status !== 409) {
          persistenceFailed = true;
          console.error(
            `EVA Sync receipt failed; Twilio code=${error.code || "none"}`,
          );
        }
      }
    };
    if (event.kind === "recording") {
      await save({
        kind: "recording",
        call_sid: event.CallSid,
        recording_sid: event.RecordingSid,
        status: event.RecordingStatus,
        channels: event.RecordingChannels,
        error_code: event.ErrorCode,
      });
      return callback(null, new Twilio.twiml.VoiceResponse());
    }
    if (event.step) {
      await save(receipt(event));
    } else {
      try {
        await maps.create({ uniqueName: mapName });
      } catch (error) {
        if (error.status !== 409) {
          persistenceFailed = true;
          console.error(
            `EVA Sync map failed; Twilio code=${error.code || "none"}`,
          );
        }
      }
      const recordings = client.calls(event.CallSid).recordings;
      // A webhook retry must not start a second recording.
      try {
        const existing = await recordings.list({ limit: 1 });
        if (!existing.length) {
          const recording = await recordings.create({
            recordingChannels: "dual",
            recordingTrack: "both",
            trim: "do-not-trim",
            recordingStatusCallback: `${baseUrl}/hotline?kind=recording`,
            recordingStatusCallbackMethod: "POST",
            recordingStatusCallbackEvent: ["completed", "absent"],
          });
          await save({
            kind: "recording_started",
            call_sid: event.CallSid,
            recording_sid: recording.sid,
          });
        }
      } catch (error) {
        await save({
          kind: "recording_failed",
          call_sid: event.CallSid,
          error_code: error.code,
          status: error.status,
        });
        event.recordingFailed = true;
      }
    }
    const response = new Twilio.twiml.VoiceResponse();
    if (persistenceFailed)
      response.say(
        "Durable receipt storage is unavailable. This test will use Twilio logs and call events instead.",
      );
    callback(null, render(scenario, event, response, baseUrl));
  } catch (error) {
    // Do not serialize SDK errors: their request data can contain credentials.
    console.error(`EVA hotline failed; Twilio code=${error.code || "none"}`);
    const response = new Twilio.twiml.VoiceResponse();
    response.say(
      "The test hotline failed. Please inspect the Twilio call events and Function logs.",
    );
    response.hangup();
    callback(null, response);
  }
};

exports.validateScenario = validateScenario;
exports.render = render;
exports.receipt = receipt;
exports.logReceipt = logReceipt;
