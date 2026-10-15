"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const twilio = require("./.cache/node_modules/twilio");
const { dial } = require("./dial");

const context = {
  CALLER_ID: "+15592493930",
  DOMAIN_NAME: "eva-voice-sdk.twil.io",
};
const xml = (event) =>
  dial(context, event, new twilio.twiml.VoiceResponse()).toString();

test("dials the To number from the caller ID and rings EVA until it answers", () => {
  const out = xml({ From: "client:eva", To: "+15551234567" });
  assert.match(out, /<Dial [^>]*callerId="\+15592493930"/);
  assert.match(out, /answerOnBridge="true"/);
  assert.match(out, /action="https:\/\/eva-voice-sdk.twil.io\/dial"/);
  assert.match(out, /<Number>\+15551234567<\/Number>/);
});

test("refuses other callers and numbers that are not E.164", () => {
  assert.match(
    xml({ From: "client:mallory", To: "+15551234567" }),
    /<Reject\/>/,
  );
  const local = xml({ From: "client:eva", To: "555-1234" });
  assert.match(local, /<Say>/);
  assert.doesNotMatch(local, /<Dial/);
});

test("ends EVA's call as busy only when the line was busy", () => {
  assert.match(xml({ DialCallStatus: "busy" }), /<Reject reason="busy"\/>/);
  assert.match(xml({ DialCallStatus: "no-answer" }), /<Hangup\/>/);
  assert.match(xml({ DialCallStatus: "completed" }), /<Hangup\/>/);
});
