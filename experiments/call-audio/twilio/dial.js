"use strict";

// The voice URL of EVA's TwiML app: a call from the Twilio Voice SDK in EVA dials its To
// parameter from CALLER_ID. answerOnBridge keeps EVA's side ringing until the other party
// answers, so the SDK's "connected" means they did; a busy line is rejected as busy.

const E164 = /^\+[1-9][0-9]{6,14}$/;

// The SDK's call is unanswered until the bridge, and TwiML can end an unanswered call only by
// rejecting it (busy: SIP 486, the SDK's 31486) or hanging up (SIP 603, the SDK's 31603). So
// only "busy" reaches EVA distinctly; no answer and a failed dial (for example Twilio error 13227,
// a country missing from the account's geo permissions) both arrive as 603, and Twilio's call log
// keeps the reason.
function dial(context, event, response) {
  if (event.DialCallStatus) {
    if (event.DialCallStatus === "busy") response.reject({ reason: "busy" });
    else response.hangup();
    return response;
  }
  if (event.From !== `client:${context.CLIENT_IDENTITY || "eva"}`) {
    response.reject();
    return response;
  }
  if (!E164.test(event.To || "")) {
    response.say("EVA's Twilio route needs an international number.");
    response.hangup();
    return response;
  }
  const dialing = response.dial({
    callerId: context.CALLER_ID,
    answerOnBridge: true,
    timeout: 60,
    action: `https://${context.DOMAIN_NAME}/dial`,
    method: "POST",
  });
  dialing.number(event.To);
  return response;
}

exports.handler = function (context, event, callback) {
  callback(null, dial(context, event, new Twilio.twiml.VoiceResponse()));
};

exports.dial = dial;
