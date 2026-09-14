# EVA proof-of-concept strategy

Status: first browser subscription voice/action experiment implemented and
live-tested with synthetic speech on 2026-09-12. See the
[runnable harness](../experiments/voice-poc/README.md) and
[recorded results](../experiments/voice-poc/RESULTS.md). Android and natural
human interruption remain unverified; the remaining experiments below are plans.
Evidence reviewed: 2026-09-12. Parent design: [design.md](design.md).

## What we need to learn first

The biggest uncertainty is not whether Android can display a voice interface.
It is whether the desired voice service is accessible under the user's
subscription, can reliably invoke EVA-controlled actions, and stays responsive
without routing every request through another reasoning model.

Prove those properties with a small harness before building a general extension
system. Keep the native app scaffold moving independently. A successful personal
prototype and a supportable public integration are separate outcomes.

## Subscription access: evidence and unknowns

There are three distinct products/access paths:

| Path | Current evidence | Implication for EVA |
| --- | --- | --- |
| Voice inside ChatGPT | Plan-specific voice allowances | An in-app allowance is not by itself a third-party voice API |
| Voice associated with Codex / desktop | Subscription access exists; current pricing lists unlimited voice for Pro 20x ($200/month), with Codex tasks still limited | Investigate this path first, without promising that an experimental custom client has the same entitlement |
| OpenAI voice API | API billing is separate from ChatGPT subscriptions | Independently configured fallback/comparison, not a way to spend subscription allowance |

Sources: [ChatGPT Voice](https://help.openai.com/en/articles/20001274),
[desktop voice pricing](https://learn.chatgpt.com/docs/pricing#chatgpt-voice-in-desktop),
[Codex subscription access](https://help.openai.com/en/articles/11369540), and
[separate API billing](https://help.openai.com/en/articles/9039756-managing-billing-settings-on-the-chatgpt-web-and-api-platform).
Recheck these before trials and release. Do not extrapolate a single successful
call into unlimited access, general account availability, or a stable API contract.

### Concrete technical lead

The local Codex source snapshot at commit
`6219b7c40fc9c702c0aef9964e72b492558f60e4` (2026-07-30) contains experimental
`thread/realtime/*` methods, WebRTC SDP negotiation, prompt configuration,
and distinct ChatGPT-authenticated and API-key call paths. This snapshot is
older than this document; record and inspect the actual executable version
before attempting a session.

Source anchors in that snapshot:

- [App-server realtime documentation](https://github.com/openai/codex/blob/6219b7c40fc9c702c0aef9964e72b492558f60e4/codex-rs/app-server/README.md#example-start-realtime-with-webrtc):
  start, append, stop, and WebRTC signaling behavior. Inspect the realtime
  section if the heading anchor changes.
- [Call creation and backend tests](https://github.com/openai/codex/blob/6219b7c40fc9c702c0aef9964e72b492558f60e4/codex-rs/codex-api/src/endpoint/realtime_call.rs):
  separate ChatGPT backend and public API destinations.
- [Conversation routing](https://github.com/openai/codex/blob/6219b7c40fc9c702c0aef9964e72b492558f60e4/codex-rs/core/src/realtime_conversation.rs):
  `client_managed_handoffs` suppresses automatic response delivery, while the
  event fanout still routes incoming delegation text to Codex. It must not be
  treated as a switch that makes every request client-executed.

Use the official client's login/session machinery where supported. Do not
start by extracting ChatGPT browser cookies or copying private endpoint calls.
Source availability is evidence for an experiment, not a guarantee of service
access or permission to redistribute a particular integration.

## Experiment 1: subscription voice and action control

Build a disposable desktop/browser harness backed by an isolated Codex
app-server process on a user-controlled host. Reuse an existing authorized
Codex login without exposing its credentials to the browser. The harness may
use a tiny dedicated broker initially; building a Paseo extension is not a
prerequisite. Keep it separate from production app code until the protocol is
understood.

1. Record client version, account plan/authentication mode, selected voice
   model/protocol, feature requirements, and observed usage indicators.
   Inspect current method support before selecting configuration flags.
2. Establish a short voice session through the supported client path. Distinguish
   an account/feature restriction from a protocol or audio failure. Stop on an
   access denial rather than trying to bypass the restriction.
3. Test custom instructions, spoken responses, interruption, and one typed
   turn. A text-to-audio smoke test can diagnose authentication first, but is
   not evidence that microphone/WebRTC interaction works.
4. Expose two harmless harness actions, such as querying a counter and changing
   its displayed value. Require observable structured requests and results;
   speech claiming to have acted is not success.
5. Determine whether actions can be handled by the client directly, require
   Codex dynamic/MCP tools and a backend turn, or need an unsupported protocol
   change. Do not use a prose-parsing workaround as proof of native tool support.
6. Verify follow-up references, error reporting, interruption during an action,
   and reconnection without duplicate effects. Count backend turns and report
   any effect on task allowance separately from connected voice time.

Exit evidence: a repeatable recorded scenario, redacted event traces, actual
dispatch/results, observed audio path, and a compatibility note distinguishing
documented behavior, runtime observations, and unresolved support questions.
A passing result requires speech plus verified actions, not just successful login.

If delegation necessarily involves another model, measure it rather than
rejecting it automatically. If it requires a client patch, record the patch
and maintenance burden explicitly; do not present that as stock-client support.
If subscription access or action control fails, preserve the result and test
the public API path only with an explicit spending limit and user-selected
credentials. Never silently fall back to paid usage.

## Experiment 2: isolate the latency costs

Compare the same short scenarios over supported transports. Start with the
subscription path; an API baseline is optional and separately authorized.

```text
Session setup:  EVA/harness -> small broker or Paseo -> Codex/OpenAI
Live audio:    EVA/harness <------------------------> OpenAI (WebRTC)
Action path:   provider event -> dispatcher -> local action or Paseo daemon
```

This is the target topology, not a verified deployed configuration. Inspect
WebRTC connection statistics and event routing to establish the actual path.
Direct audio does not imply direct tool events: those may still take a server
hop or trigger a backend model. A WebSocket audio relay is a different case.

Measure cold connection setup, warm speech-to-response, speech-to-action,
tool-event-to-dispatch, and interruption separately. Use the same device,
network, prompt, and scenario set; report sample count, median, p95, failures,
and backend turns. Begin with a small repeated set to find gross problems,
then use the design's 30-request set before choosing the production route.

Decision: reuse Paseo as a broker if its measured overhead and coupling are
acceptable; use a small independent broker if that is simpler. Either choice
can coexist with a standalone EVA app and a separate Paseo action adapter.

## Experiment 3: Android vertical slice

Port the proven voice connection into the native shell. Keep temporary WebView
versus native WebRTC implementation a measured choice, including dependency
licensing/build implications. Implement only:

- Manual invocation, then the default-assistant invocation path.
- One standard Android action, such as opening a destination with an intent.
- One read-only Paseo action against a chosen instance.
- Voice and typed input through the same dispatcher, visible outcomes, and
  stop/interruption controls.

Exercise a physical phone with speaker and Bluetooth audio, permission denial,
background/foreground transitions, network changes, and unavailable Paseo.
Only then add a bounded remote mutation in an isolated test workspace.

Exit: one conversation actually mixes phone and Paseo actions, meets an agreed
latency baseline, and recovers without misleading success or duplicate work.

## Experiment 4: prove extensibility independently

Add one useful capability without rebuilding the core: a declarative Android
adapter or an MCP connection. Verify discovery, schema validation, enablement,
failure reporting, and removal. Defer a marketplace and arbitrary-code extension
runtime. Run AppFunctions/Shizuku compatibility experiments independently so
privilege uncertainty cannot block the ordinary voice-and-intent prototype.

## Decision record and stopping points

Store each experiment's versioned instructions and sanitized results under
`experiments/` when implemented. No credentials, raw account tokens, or default
audio recording. Keep live subscription/API tests out of routine CI.

After each experiment record: hypothesis, setup, measurements, pass/fail,
remaining uncertainty, and the resulting architecture decision. No production
commitment to a subscription backend until access and dispatch are established;
no public claim of unlimited EVA usage without confirmed applicable entitlement.
Wake words, polished settings, general extension packaging, and broad Android
coverage follow a successful vertical slice rather than preceding it.
