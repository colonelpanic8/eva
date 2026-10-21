"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const twilio = require("./.cache/node_modules/twilio");
const {
  validateScenario,
  render,
  receipt,
  logReceipt,
  handler,
} = require("./hotline");
const scenario = JSON.parse(
  fs.readFileSync(`${__dirname}/scenario.json`, "utf8"),
);
const callSid = "CA" + "a".repeat(32);
const base = "https://example.twil.io";
const xml = (event) =>
  render(scenario, event, new twilio.twiml.VoiceResponse(), base).toString();

test("scenario rejects broken transitions, hold cycles, and invalid Gather input", () => {
  validateScenario(scenario);
  for (const mutate of [
    (s) => {
      s.nodes.menu.next = "missing";
    },
    (s) => {
      s.nodes.hold.next = "hold";
    },
    (s) => {
      s.nodes.menu.input = "invalid";
    },
  ]) {
    const copy = structuredClone(scenario);
    mutate(copy);
    assert.throws(() => validateScenario(copy));
  }
});

test("greeting and menu accept speech or one keypad digit", () => {
  const result = xml({});
  assert.match(result, /Thanks for calling Test Company/);
  assert.match(result, /input="dtmf speech"/);
  assert.match(result, /numDigits="1"/);
  assert.match(result, /actionOnEmptyResult="true"/);
  assert.match(result, /step=menu&amp;turn=0/);
});

test("full keypad and speech scenario reads back input and holds within TwiML", () => {
  const menu = xml({ step: "menu", turn: "0", Digits: "1" });
  assert.match(menu, /You pressed 1/);
  assert.match(menu, /account holder's name/);
  assert.match(menu, /step=verification&amp;turn=1/);
  const verify = xml({
    step: "verification",
    turn: "1",
    SpeechResult: "Ivan Malison, 4417",
  });
  assert.match(verify, /I heard: Ivan Malison, 4417/);
  assert.equal((verify.match(/<Play>/g) || []).length, 2);
  assert.match(verify, /important to us/);
  assert.match(verify, /step=representative&amp;turn=2/);
  const rep = xml({
    step: "representative",
    turn: "2",
    SpeechResult: "What are your hours?",
  });
  assert.match(rep, /I heard: What are your hours/);
  assert.match(rep, /Monday through Friday/);
  assert.match(rep, /step=anything_else&amp;turn=3/);
  const end = xml({
    step: "anything_else",
    turn: "3",
    SpeechResult: "No, that's all, thank you",
  });
  assert.match(end, /<Hangup\/>/);
  assert.doesNotMatch(end, /<Gather/);
  assert.match(
    xml({ step: "menu", turn: "0", SpeechResult: "billing" }),
    /I heard: billing/,
  );
});

test("speech is XML escaped; silence ends politely; further requests return to the representative", () => {
  assert.match(
    xml({ step: "menu", turn: "0", SpeechResult: "billing <&>" }),
    /billing &lt;&amp;&gt;/,
  );
  assert.match(
    xml({ step: "menu", turn: "0" }),
    /did not receive a response.*<Hangup\/>/,
  );
  assert.match(
    xml({
      step: "anything_else",
      turn: "8",
      SpeechResult: "Yes, more questions",
    }),
    /step=representative&amp;turn=9/,
  );
  assert.throws(() => xml({ step: "menu", turn: "-1" }));
});

test("receipt retains all Gather fields; oversized immediate logs explicitly defer", () => {
  const value = receipt({
    CallSid: callSid,
    step: "menu",
    turn: "0",
    Digits: "1",
    SpeechResult: "billing",
    Confidence: "0.91",
  });
  assert.equal(value.confidence, "0.91");
  assert.equal(value.digits, "1");
  assert.equal(value.speech_result, "billing");
  const original = console.log;
  const lines = [];
  console.log = (line) => lines.push(line);
  try {
    logReceipt({ ...value, speech_result: "a".repeat(5000) });
  } finally {
    console.log = original;
  }
  assert.match(lines[0], /gather_deferred/);
  assert.ok(Buffer.byteLength(lines[0]) < 3000);
});

async function invoke(
  event,
  { recordingError = false, syncError = false, existing = false } = {},
) {
  const saved = new Map();
  const creations = [];
  const recordings = {
    list: async () => (existing ? [{ sid: "REexisting" }] : []),
    create: async (options) => {
      creations.push(options);
      if (recordingError)
        throw Object.assign(new Error("must not log this secret"), {
          code: 21220,
          status: 403,
        });
      return { sid: "REnew" };
    },
  };
  const maps = () => ({
    syncMapItems: {
      create: async ({ key, data }) => {
        if (syncError)
          throw Object.assign(new Error("must not log this secret"), {
            code: 50000,
            status: 503,
          });
        if (saved.has(key))
          throw Object.assign(new Error("duplicate"), { status: 409 });
        saved.set(key, data);
      },
    },
  });
  maps.create = async ({ uniqueName }) => {
    assert.equal(uniqueName, `call-${callSid}`);
    assert.doesNotMatch(uniqueName, /^[A-Z]{2}[a-f0-9]{32}$/);
  };
  global.Runtime = {
    getAssets: () => ({
      "/scenario.json": { path: `${__dirname}/scenario.json` },
    }),
  };
  global.Twilio = twilio;
  const context = {
    DOMAIN_NAME: "example.twil.io",
    SYNC_SERVICE_SID: "IStest",
    getTwilioClient: () => ({
      calls: () => ({ recordings }),
      sync: { v1: { services: () => ({ syncMaps: maps }) } },
    }),
  };
  const original = { log: console.log, error: console.error };
  const logs = [];
  console.log = console.error = (line) => logs.push(line);
  let response;
  try {
    await handler(context, { CallSid: callSid, ...event }, (error, value) => {
      assert.equal(error, null);
      response = value.toString();
    });
  } finally {
    console.log = original.log;
    console.error = original.error;
  }
  assert.ok(!logs.join(" ").includes("must not log this secret"));
  return { response, saved: [...saved.values()], creations };
}

test("initial webhook answers before any realtime recording operation", async () => {
  const result = await invoke({});
  assert.equal(result.creations.length, 0);
  assert.match(result.response, /<Say[^>]*>Connecting your test call\.<\/Say>/);
  assert.match(result.response, /kind=answered/);
});

test("answered webhook starts dual-direction recording and saves an immediate durable receipt", async () => {
  const result = await invoke({ kind: "answered" });
  assert.equal(result.creations[0].recordingChannels, "dual");
  assert.equal(result.creations[0].recordingTrack, "both");
  assert.equal(result.saved[0].kind, "recording_started");
  assert.equal(
    (await invoke({ kind: "answered" }, { existing: true })).creations.length,
    0,
  );
});

test("recording restrictions and Sync failures are audible and do not break Gather", async () => {
  const restricted = await invoke(
    { kind: "answered" },
    { recordingError: true },
  );
  assert.match(restricted.response, /Call recording is unavailable/);
  assert.match(restricted.response, /<Gather/);
  assert.equal(restricted.saved[0].error_code, 21220);
  const unavailable = await invoke(
    { step: "menu", turn: "0", SpeechResult: "billing" },
    { syncError: true },
  );
  assert.match(unavailable.response, /Durable receipt storage is unavailable/);
  assert.match(unavailable.response, /I heard: billing/);
});

test("handler saves complete long speech in Sync and recording status callbacks", async () => {
  const text = "long speech ".repeat(300);
  const result = await invoke({
    step: "verification",
    turn: "1",
    SpeechResult: text,
    Confidence: "0.8",
  });
  assert.equal(result.saved[0].speech_result, text);
  const callback = await invoke({
    kind: "recording",
    RecordingSid: "REnew",
    RecordingStatus: "completed",
    RecordingChannels: "2",
  });
  assert.equal(callback.saved[0].status, "completed");
  assert.doesNotMatch(callback.response, /<Gather/);
});

test("role-play keeps the ZIP question and confirmation read-back separate", () => {
  const roleplay = validateScenario(
    JSON.parse(fs.readFileSync(`${__dirname}/scenario-roleplay.json`, "utf8")),
  );
  const response = (step, text) =>
    render(
      roleplay,
      { step, turn: "1", SpeechResult: text },
      new twilio.twiml.VoiceResponse(),
      base,
    ).toString();
  assert.match(response("request", "Please cancel the membership"), /zip code/);
  assert.match(response("zipcode", "I do not have that information"), /no fee/);
  assert.match(response("agreement", "Yes please"), /Please read that back/);
  assert.match(
    response("confirmation", "Two two three four"),
    /Yes, that is correct/,
  );
  assert.match(
    response("anything_else", "No thank you, goodbye"),
    /<Hangup\/>/,
  );
});

test("card services reads back only the last four digits and walks to the annual-fee facts", () => {
  const card = validateScenario(
    JSON.parse(
      fs.readFileSync(`${__dirname}/scenario-card-services.json`, "utf8"),
    ),
  );
  const response = (step, input) =>
    render(
      card,
      { step, turn: "0", ...input },
      new twilio.twiml.VoiceResponse(),
      base,
    ).toString();
  const greeting = render(
    card,
    {},
    new twilio.twiml.VoiceResponse(),
    base,
  ).toString();
  assert.match(greeting, /<Gather input="dtmf"[^>]*step=card&amp;turn=0/);
  assert.match(greeting, /16 digit card number, followed by the pound key/);
  const entered = response("card", { Digits: "4111111111111111" });
  assert.match(entered, /Thank you\. I found the card ending in 1 1 1 1\./);
  assert.doesNotMatch(entered, /4111|You pressed/);
  assert.match(entered, /press 0 or say representative/);
  assert.match(
    response("menu", { Digits: "0" }),
    /You pressed 0.*full name and the billing ZIP code/,
  );
  assert.match(
    response("identity", { SpeechResult: "Casey Sample, 90210" }),
    /I heard: Casey Sample, 90210.*How can I help you today\?/,
  );
  assert.match(
    response("help", { SpeechResult: "Tell me about the annual fee" }),
    /The annual fee is 95 dollars, billed each March\. The annual fee cannot be waived\..*Is there anything else\?/,
  );
  assert.match(
    response("anything_else", { SpeechResult: "No, that's all" }),
    /Test Card Services\. Goodbye\.<\/Say><Hangup\/>/,
  );
});

test("scenario expectations must name a matching Gather and facts the line speaks", () => {
  const card = JSON.parse(
    fs.readFileSync(`${__dirname}/scenario-card-services.json`, "utf8"),
  );
  for (const [mutate, message] of [
    [
      (s) => {
        s.expect.gathers.missing = { digits: "1" };
      },
      /Invalid expectation digits at missing/,
    ],
    [
      (s) => {
        s.expect.gathers.identity.digits = "1";
      },
      /Invalid expectation digits at identity/,
    ],
    [
      (s) => {
        s.expect.gathers.card.spoken_digits = "1";
      },
      /Invalid expectation spoken_digits at card/,
    ],
    [
      (s) => {
        s.expect.facts.push("The fee is waived.");
      },
      /Fact is never spoken: The fee is waived\./,
    ],
    [
      (s) => {
        s.nodes.menu.readback = "Ending in {last4}";
      },
      /readback needs keypad-only input at menu/,
    ],
  ]) {
    const copy = structuredClone(card);
    mutate(copy);
    assert.throws(() => validateScenario(copy), message);
  }
});
