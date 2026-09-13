# Implementation status and next slice

Updated: 2026-09-13. Architecture: [architecture.md](architecture.md).

## Android provider and action runtime

The Compose app now uses `ProviderSessionController` and a provider-neutral
conversation port. `BrokerConversationProvider` projects the phone catalog into
Codex dynamic tools, submits ordinary text, adapts correlated events, and returns
actual dispatcher outcomes. The development `/device` WebSocket uses an ephemeral
broker code and localhost forwarding. Subscription credentials stay on the host;
there is no native OpenAI login or API-key fallback.

Seventeen bundled capabilities are described by `CapabilityDefinition` records with
closed JSON Schemas: map search, driving navigation, message drafting, sending a
text message, alarms, timers, dialing, web search, opening a URL, email drafting,
calendar events, launching an installed app, opening a settings screen, contacts
search, and three typed device-state operations. The intent operations other than
map search, navigation, and message drafting share one generic `IntentBackend`;
contacts, SMS sending, and device state have query-specific backends.
Contacts search reads phone numbers matching a
name through `ContactsContract`, requests `READ_CONTACTS` on first use through
the resumed Activity, and completes with the matches in its outcome message so
the model can pass an explicit number to the send, message, or dialer action.
The model chooses what a search matches with an optional `field`: the whole
displayed name through `ContactsContract.CommonDataKinds.Phone`, or first or last
names through the `StructuredName` data rows, whose contact IDs are then resolved
to phone numbers in one bounded `IN` query. Only the whole-name search falls back
to the query's first word; a name-part search runs exactly as asked, so the model
can retry with another field or spelling instead of accepting a poor match.
`ContactMatches` merges entries that repeat one person across accounts and ranks
the rest by how the query lines up with the stored name — exact name, whole word, name prefix, word prefix, substring — and lists
each contact's most callable number first, so the outcome message names one best
match. It asks the model to act on that match and to ask the user only when two
matches score the same. The registry and UI do not switch on
capability IDs. Generic schema validation covers closed/nested objects, bounded
scalars, and enums; arrays and nullable values are rejected.

Backends still receive flat string arguments, so `ToolSchema.coerce` restores
each property's declared scalar type before the dispatcher revalidates. Integer
and boolean parameters therefore work end to end; structured object arguments at
the execution boundary and a package importer remain follow-up work.

## Sending a text message

`eva.android.messages.send` sends an SMS through `SmsManager` without opening
another app, so a spoken request can finish hands free; `eva.android.messages.compose`
remains for the case where the user wants to review the text first. The backend
requests `SEND_SMS` on first use through the resumed Activity, splits long bodies
with `divideMessage`, and waits up to 30 seconds for the platform's per-part sent
broadcast on a package-scoped, non-exported receiver before reporting. A confirmed
send completes with the recipient and a flattened 80-character preview; a platform
error code becomes a stated reason and fails; no confirmation within the window
returns `UNKNOWN` and tells the model the text may still have been delivered
rather than claiming a send. The capability is unavailable below Android 12 or on
a device without a cellular radio, and `android.hardware.telephony` is declared
as not required.

## Settings AppFunctions through Shizuku

On Android 17/API 37, EVA now exposes three typed Settings operations:
`eva.android.device.state.get`, `eva.android.device.state.metadata`, and
`eva.android.device.state.set`. The read operation accepts only the six categories
implemented by Settings on the test phone: battery, storage, notifications, apps,
mobile data, and uncategorized. Metadata returns only writable items matching a
bounded search term. Set accepts a bounded `screen/item` key and scalar string
value. State and metadata output is reduced to useful item lines and capped at
4,000 characters before it is returned to the model.

These capabilities are registered only on API 37 or later. A resumed EVA Activity
owns Shizuku permission requests. After permission is granted, a non-daemon
Shizuku UserService runs as shell UID 2000 and invokes `/system/bin/cmd` with an
argument array; no shell interpreter is involved. The service itself rejects
anything except `app_function execute-app-function`, the Settings package, the
eight implemented function IDs, and EVA's fixed flag layout. It returns bounded
stdout/stderr, exit status, execution UID, and a host-enforced timeout. Missing,
stopped, and denied Shizuku states produce `NOT_EXECUTED` results rather than an
attempt under EVA's ordinary UID.

The Settings item schemas are nested rather than flat. The working setter input is:

```json
{
  "setDeviceStateItemParams": {
    "key": "dark_ui_mode/dark_ui_activated",
    "itemizationKeys": [],
    "value": "true",
    "requestInitiatedWhileUnlocked": true
  }
}
```

The corresponding getter shape is
`{"getDeviceStateItemParams":{"key":"dark_ui_mode/dark_ui_activated","itemizationKeys":[]}}`.
It parses on the phone, but `getDeviceStateItem` is disabled on this production
build: `ro.debuggable` is `0`, the
`com.android.settings.APP_FUNCTION_ITEM_GETTER_AVAILABLE` global setting is
absent, and Settings logs `No valid executor found for GET_DEVICE_STATE`. The
command exited 255 in 173 ms. EVA therefore does not expose the item getter.
Category reads provide current values; the setter's structured response provides
its `currentValue`.

Hardware verification used device `67091FDDJ0007B`, a Pixel 11 Pro Fold running
Android 17/API 37, build `CD1A.260905.001.B1` with fingerprint
`google/yogi/yogi:17/CD1A.260905.001.B1/16238327:user/release-keys`. Shizuku
`13.6.0.r1086.2650830c` was running as shell. The read-only command
`cmd app_function list-app-functions --package com.android.settings` returned 11
functions before any setting was changed.

The debug app and instrumentation APK were installed with `adb install` from this
checkout. Focused AndroidJUnit/UI Automator tests drove the real Shizuku permission
dialog and the registered typed backends. A fresh install followed by the denial
test passed in 2.606 s and returned `NOT_EXECUTED: Shizuku access was denied. Allow
EVA in Shizuku before trying again.` A second fresh install followed by the allowed
test passed in 7.362 s. Warm operation timings recorded in that test were:

- writable metadata filtered by `dark`: 1,378 ms;
- uncategorized state: 629 ms;
- set dark theme to `true`: 216 ms, returning `Current value: true`;
- restore dark theme to `false`: 183 ms, returning `Current value: false`;
- a 1 ms host deadline: 182 ms including teardown, `timedOut=true`, exit `-1`, UID 2000.

A further typed read test passed in 5.563 s: storage took 1,160 ms,
notifications 414 ms, apps 2,236 ms, and mobile data 321 ms. The permission-grant
bootstrap also completed a battery read and returned live battery state. A direct
cross-check set dark theme to `true` in 231 ms, read it back as `true` through the
uncategorized category in 291 ms, and restored it to `false` in 247 ms.

Stopping the Shizuku server was not tested because there was no non-disruptive
way to do so without affecting other sessions on the phone. The stopped-server
mapping is implemented, but remains hardware-unverified. The server was not killed
from ADB.

EVA declares `ACTION_ASSIST` and `ACTION_VOICE_COMMAND`, so it can be selected as
the system digital assistant and launched by the assistant gesture. `MainActivity`
is `singleTask` and reuses its instance for a later assist launch. Typed-mode
instructions carry the user's current local time and Unix epoch milliseconds so
alarms and calendar events can be scheduled from relative language.

The phone pins a catalog revision to each connection and independently permits
one action per input. The dispatcher validates, claims, and durably records
`Dispatching` before entering Android. Repeated IDs replay receipts; conflicting
arguments cannot reuse a call. Process recovery marks interrupted dispatches
`Unknown` and unsent claims `NotExecuted`, without replay. A journal failure
blocks new submissions until restart. Schema version 2 preserves old receipts
and adds an action-title snapshot for generic rendering.

The app owns its controller across Activity recreation. Intent adapters require
a resumed surface and hold only a weak Activity reference. Database work runs off
Main. The latest 100 action receipts appear in history; chat/transcripts are
session-only. Journal backup and device transfer remain disabled. Map/navigation
results establish handoff only, and SMS capabilities open drafts without sending.
The local parser remains available only as a test/diagnostic implementation.

## Native realtime voice

The first native audio implementation reuses the proven broker SDP path with
`io.github.webrtc-sdk:android:150.7871.01`. The phone gathers an SDP offer, the
host starts the subscription-backed realtime session, and the answer is applied
on the phone. Audio travels over WebRTC; control and transcripts use the broker.
The `oai-events` channel is negotiated before the offer.

Start voice requests microphone permission for live capture. A denial offers
retry, system settings, or a listen-only fallback. Pending permission decisions
survive Activity recreation in memory; broker codes are not saved to disk.
Listen-only mode negotiates receive-only audio without capture and omits the
microphone mute control. Local controls mute available capture and playback
independently. Audio focus loss pauses tracks, and disconnect or
backgrounding releases media; configuration changes retain the connection.
Local speaker mute is not a verified provider interruption or history truncation.

The controller owns provider teardown on disconnect, terminal events, event-stream
completion, and failures. A canceled setup that returns late is closed without
replacing a newer connection.

Voice sessions advertise the same catalog as typed mode and execute phone
actions. The provider observably starts the delegated backend turn before the
user transcript arrives, and the tool call carries that turn's ID, so the turn
is the unit of correlation: the broker names each delegated turn as the input
(`voice:<turnId>`), the phone provider adopts it when the turn starts, and the
controller dispatches through the same dispatcher and journal as typed mode.
The journaled request text is resolved at dispatch time from the latest user
transcript, with a placeholder if it has not landed. Each turn gets a fresh
one-action budget. An empty catalog still yields a chat-only session.

Phone actions open other apps. A foreground service with the microphone type
holds the voice session across that handoff so the spoken confirmation can
play and the user can keep talking; the session ends when the user stops voice
or the broker lifetime expires. Android does not allow a background app to
start an activity, so a second action requested while another app is in front
will report `NOT_EXECUTED` with a prompt to return to EVA; that is the next
gap, not a correlation problem.

No automatic reconnect, requirement tokens, or provider history seeding is
included.

## Voice action verification

On 2026-09-12 the opt-in [voice action test](native-voice-testing.md) passed
on an API 36 emulator: synthetic speech saying "set a timer for three minutes"
went through the production controller, the model delegated a timer tool call,
the Android backend handed off to Clock, which showed a running three-minute
timer, the journal recorded the utterance, the model spoke "Three-minute timer
started," and the session was still connected five seconds after Clock took
the foreground. The empty-catalog chat-only test still passes. Evidence:
[2026-09-12-voice-action.json](../experiments/voice-poc/evidence/2026-09-12-voice-action.json).
The published v0.2.4 release package, installed on the same emulator, connected
a live voice session with the foreground service running as microphone type and
its ongoing notification shown, and cleared both on Stop voice. Obtainium itself then
installed v0.2.4 on that emulator from the GitHub release, EVA took the
assistant role there, the assist gesture opened it from other apps, a typed
request handed off to Wi-Fi settings, and a live voice session ran with the
foreground service and its notification. The physical phone dropped off USB
during the first attempt and had not returned, so nothing here is a hardware
result yet.

## Direct provider on the phone

The phone no longer needs a workstation. With an OpenAI API key saved on the
device, voice opens its own WebRTC session against the Realtime API: the phone
posts its SDP offer and session configuration to `/v1/realtime/calls`, speaks
the event protocol over the `oai-events` data channel, and the speech model
calls EVA's tools directly. Typed turns go over the Responses API with function
calling and `previous_response_id` continuity. Both adapters implement the same
provider contract as the broker, so the controller, dispatcher, and journal are
unchanged; a blank host link selects the direct path.

Correlation follows the voice convention already in place: a response is the
unit of correlation (`voice:<responseId>`), a tool-calling response's completion
is held until the phone's result is returned, and the follow-up response keeps
the same input. Interrupted responses finish as "Interrupted." rather than as
failures.

The key is encrypted with a non-exportable Android Keystore key and stored in a
preferences file excluded from backup and device transfer. It is billed per
token by OpenAI; it is not covered by a ChatGPT subscription. The paired host
bridge remains available as the subscription-backed alternative and is now
optional. Both models are selectable in the app and stored per device. The picker lists
what the account can actually use, fetched from `/v1/models` and split by name
into speech-capable and text-capable, and also accepts a typed name so a model
released after the app still works. Blank restores the default. Defaults are
`gpt-realtime-2.1` for speech and `gpt-6-astra` for text, with
`gpt-transcribe` for input transcription. A chosen model applies to the next
connection.

Input transcription is a second model pass that produces only the on-screen
captions; the speech model hears the audio itself, so caption errors never
reach it. That pass starts with no knowledge of the session, which is what made
its output poor, so it is given the setting as a prompt, `en` as the expected
language, `delay: high` to trade caption latency for word accuracy, and the
user's contact display names as `keywords`. Nothing is racing the captions, so
the added delay costs nothing the user sees. Contact names are read only when
contacts access has already been granted for an earlier lookup; the read never
prompts, is skipped entirely for typed sessions, and is bounded to the 200
most-contacted names.

A typed session has nothing to negotiate, so opening one lists the account's
models first: that proves the key works and the chosen model exists before the
UI claims to be connected. A realtime session already proves both by completing
its SDP exchange.

Coverage: JVM tests drive both adapters with canned HTTP and data-channel
traffic, including a tool call held open across the follow-up response. The
opt-in `OpenAiVoiceActionLiveTest` runs the spoken-timer scenario against the
real API when given `evaOpenAiKey`; no key was available on this machine, so
that run is pending.

## Subscription sign-in on the phone

A ChatGPT account can now pay for typed conversation instead of a metered key.
EVA requests a device code from `auth.openai.com/api/accounts/deviceauth/usercode`,
shows the code, and polls `/deviceauth/token` until the code is approved in a
browser on any device; a pending code answers 403 or 404, so those are wait
states. The approval returns an authorization code together with the PKCE pair
the account generated for it, which is exchanged at `/oauth/token`. Nothing is
typed on the phone and no redirect has to reach it, which is what makes this
usable from the assistant surface. The account issues tokens to the public
client id the official command-line client uses; EVA cannot register one of its
own against a ChatGPT account.

Tokens are encrypted with the same non-exportable Keystore key as an API key,
in the same backup-excluded file, and refreshed under a mutex when the access
token is within two minutes of expiry, so two sessions cannot spend one refresh
token twice. The ID token's claims supply the account id, plan, and email shown
in the app.

Subscription turns go to `chatgpt.com/backend-api/codex/responses`. Two things
differ from the public API and are handled in the same adapter: the backend
declines to store a response, so `previous_response_id` continuity is replaced
by the phone resending the conversation, and it answers as an event stream, so
output items are collected from `response.output_item.done` into the shape the
stored path returns. The model list is `models[].slug` from
`{base}/models?client_version=`, which requires a plain three-part version and
lists text models only. `SubscriptionAccess` and `ApiKeyAccess` differ only in
URL, headers, and those two flags; the session, controller, dispatcher, and
journal are unchanged.

Voice runs on the subscription as well, and needs no second credential. The
realtime call is taken by the public API host rather than the account backend,
and that host accepts the subscription's own access token: posting EVA's
existing multipart offer to `api.openai.com/v1/realtime/calls` with the
subscription bearer returns an answer, opens the `oai-events` data channel, and
delivers `session.created` with EVA's instructions, tools, transcription model,
and voice intact. So the realtime adapter changed only in where its
authorization comes from; the event protocol, correlation, and media path are
untouched. `gpt-realtime-2.1`, `gpt-realtime`, and `gpt-realtime-mini` were all
accepted on this account.

The account's own Codex realtime route is a different thing and is not used.
`chatgpt.com/backend-api/codex/realtime/calls` demands
`openai-alpha: quicksilver=v2` and then rejects every session shape sent to it,
including an empty one, with "Field `session.model` is not allowed for this
Codex realtime session"; its quicksilver variant on the public host answers
"Voice session access denied". That route carries Codex's own delegated
architecture and a sideband control socket, neither of which EVA needs.

Verification: the device-code request, the subscription responses call
(including a custom EVA tool being selected and its arguments returned), and a
full subscription-authorized realtime call were exercised against the live
services from a workstation before the adapters were written. The realtime run
completed the WebRTC handshake, opened the data channel, received
`session.created`/`session.updated` for EVA's session payload, and received the
remote audio track. The token endpoint rejects a bogus code with the structured
error the app surfaces. The approval-to-token exchange and refresh are covered
by JVM tests against canned traffic, not by a live approval. Signing in on a
phone, and a conversation of either kind from the app itself, are unverified.

## Development connection

1. Start `direnv exec . npm start` in `experiments/voice-poc`. Each broker gets a
   fresh random high port and temporary access code; leave other brokers alone.
2. Forward its port with `adb -s DEVICE reverse --no-rebind tcp:PORT tcp:PORT`.
3. Paste the printed localhost link into EVA. Choose Connect for text, or
   Start voice (Listen only for an emulator without audio).
4. Disconnect before changing modes. Each connection starts fresh model context;
   local action receipts remain. The broker lifetime is ten minutes.

Cleartext is permitted only for `127.0.0.1` and `localhost`, in every build type
on API 24+, because an Obtainium-installed release build reaches the broker over
the same adb-forwarded loopback socket. Every remote host still refuses
cleartext, and API 23 retains the application-wide prohibition. The device endpoint
rejects browser origins and non-loopback peers. This is a development bridge, not
a completed remote-pairing or production authentication flow.

## Verification

The integrated app build passes `just check`: 86 JVM tests, ktlint,
fatal Android lint, and APK assembly. The broker passes 17 tests, including
empty-catalog voice negotiation and unconditional voice tool rejection.
The latest focused emulator run passed four tests: receive-only/live offer
construction, invalid-answer cleanup, and permission-state retention through
Activity recreation. The retention test simulates permission results; it does
not drive the operating system's permission dialog. Earlier emulator runs also
passed three SQLite migration/recovery tests.
[Android evidence](../experiments/voice-poc/evidence/2026-09-12-android-provider.json)
records a native model reply, model-selected Maps handoff, and native receive-only
WebRTC connected to the real provider. The media connection survived rotation;
backgrounding ended it. A subsequent opt-in [native speech test](native-voice-testing.md)
sent generated speech through the native capture path, received the expected
user and assistant transcripts, and decoded nonzero returned audio. The emulator
had no host audio, so that run could not establish physical capture or playback.

A later run on a physical Pixel closed that gap, using the published release APK
launched through the assist gesture. A listen-only session connected, then a live
session reported "Voice connected" with the microphone control available.
Android's audio service showed `Recording active: true` and `Playback active: true`
for EVA, WebRTC logged `verifyAudioConfig: PASS`, and playout ran at 48 kHz on the
handset speaker. Stopping voice released the recorder. Acoustic quality, echo
behaviour, and natural barge-in are still unmeasured; only the capture and
playback paths are established.


On 2026-09-12, focused JVM tests covered duplicate/conflicting call IDs,
argument validation and immutability, cancellation, persistence failures,
local parsing, and controller submission gates. `just check` passed with 26 JVM
tests, formatting, fatal Android lint, and APK assembly. Six instrumentation
tests passed on a physical Android 17 phone: URI encoding, absent-surface
rejection, and SQLite recovery/order of unsent, uncertain, and completed records.

A manual phone test verified sample population without execution, Send opening
Golden Gate Park in Maps through Android's chooser, the recorded handoff after
returning, keyboard insets, an unsupported-input response, and the handoff
receipt surviving an EVA process restart without reopening Maps. A second
manual test started driving navigation to the Ferry Building and opened a
self-addressed message draft with the exact test body. Navigation was stopped
after verification. The message was not sent during this test. Compact-width,
large-font, and TalkBack checks remain outstanding.

## Standalone device-control experiment

The [device-control experiment](../experiments/device-control/README.md) proved
the ordinary-app → Shizuku UserService (shell UID 2000) → UiAutomation path on
an Android 16/API 36 emulator. A separate fixture APK supplied the target UI.
The helper returned screenshots and hierarchy data, replaced a field with Unicode
text, injected a touch, and verified the changed field and counter. Landscape
execution, client/helper process cleanup, and Shizuku restart recovery also passed.
The fixture's `FLAG_SECURE` content was blank in capture but remained accessible
through its hierarchy. These are distinct disclosure surfaces.

This does not add device control to EVA's app or general capability runtime.
The [extension design](device-control-extension.md) describes that integration;
the experimental activity and Binder interface are not the public extension API.
Android 17, fold transitions, multiple displays, long-running background control,
freshness/cancellation policy, and model-driven execution remain unverified.
Reproduction commands and recorded evidence live with the standalone experiment.

## What the merged voice POC establishes

The existing [voice POC](../experiments/voice-poc/README.md) is the starting point
for the first voice provider, not a discarded experiment. Its
[recorded results](../experiments/voice-poc/RESULTS.md) establish a synthetic
browser speech/action loop using host-retained subscription authentication,
browser-to-provider WebRTC, and structured EVA counter tools. Actions traverse
a Codex backend-model turn; direct function calls from the voice model were
not established. Typed input uses an explicit backend turn, and appending
realtime text alone did not establish reliable response generation.

Preserve the evidence files and repeatable counter smoke as a regression path.
The isolated process setup, inherited-tool restrictions, SDP ordering, RPC
handling, cleanup, and explicit tool results are reusable. The counter state,
connection-local receipt cache, and browser DOM are harness components, not the
Android runtime. Physical microphone, natural barge-in, Android lifecycle,
accurate playback truncation, and durable recovery during remote actions still
need verification.

## User-facing interaction

The home screen accepts ordinary language and exposes connection and audio state.
The provider receives available capabilities and chooses structured calls; EVA
retains validation, admission, and execution. Success receipts use concise copy,
with uncertainty reserved for interrupted or unknown outcomes.

## Voice recheck

The merged POC's `npm run check` passed all nine deterministic tests. A fresh
synthetic-speech live run also passed using the host's existing ChatGPT login:
set counter to seven, read it back, final spoken confirmation, inbound audio,
and local mute. This remains counter-only browser evidence, not Android action
integration or a physical-microphone result.

For a connected development phone, `adb reverse --no-rebind tcp:PORT tcp:PORT`
can forward a newly started broker to the phone's localhost origin. Open the
broker's localhost URL and access-code fragment in the phone browser, enable
microphone, then Connect. This avoids changing global TLS or Tailscale Serve;
the USB connection and broker process must remain available. This is a testing
path, not completed network pairing or native login UI.

## Remaining provider work

The [provider contract](provider-contract.md) remains the target beyond this
restricted slice. Next work includes controlled voice tool delegation, resolution
tokens and confirmation, richer backend arguments, imported capability packages,
provider-independent history/notices, subscription-backed speech, and
authenticated remote pairing.

A [live provider contract probe](../experiments/voice-poc/evidence/2026-09-12-provider-contract.json)
verified nested schema acceptance and strict backend turn identity against the
real subscription provider. The Android emulator trial also displayed a live
model response and opened Golden Gate Park in Maps from a natural-language
request, then returned the actual handoff result to the model.

Acoustic quality, echo behaviour, natural barge-in, and voice-driven phone
actions remain unverified; the microphone and speaker paths themselves are now
established on hardware. The observed subscription bridge is an experiment, not
a promise of public API stability.
