# Implementation status and next slice

Updated: 2026-09-12. Architecture: [architecture.md](architecture.md).

## Android provider and action runtime

The Compose app now uses `ProviderSessionController` and a provider-neutral
conversation port. `BrokerConversationProvider` projects the phone catalog into
Codex dynamic tools, submits ordinary text, adapts correlated events, and returns
actual dispatcher outcomes. The development `/device` WebSocket uses an ephemeral
broker code and localhost forwarding. Subscription credentials stay on the host;
there is no native OpenAI login or API-key fallback.

Twelve bundled capabilities are described by `CapabilityDefinition` records with
closed JSON Schemas: map search, driving navigation, message drafting, alarms,
timers, dialing, web search, opening a URL, email drafting, calendar events,
launching an installed app, and opening a settings screen. All but the first
three share one generic `IntentBackend`. The registry and UI do not switch on
capability IDs. Generic schema validation covers closed/nested objects, bounded
scalars, and enums; arrays and nullable values are rejected.

Backends still receive flat string arguments, so `ToolSchema.coerce` restores
each property's declared scalar type before the dispatcher revalidates. Integer
and boolean parameters therefore work end to end; structured object arguments at
the execution boundary and a package importer remain follow-up work.

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

Voice is explicitly **no-tools conversation mode**: an empty catalog is required,
and every provider tool request is rejected. The speech/backend delegation path
has not yet been adapted to EVA's input/generation and authorization model.
Typed mode remains the verified model-to-phone action path. No background
microphone service, assistant role, automatic reconnect, requirement tokens,
provider history seeding, or physical audio quality claim is included.

## Release build constraints

Release builds are not minified. A minified APK aborts with
`JNI DETECTED ERROR IN APPLICATION: java_class == null`, raised by
`GetStaticMethodID` inside WebRTC's `JNI_OnLoad`, as soon as
`PeerConnectionFactory.initialize` loads `libjingle_peerconnection_so`.

Keep rules for `org.webrtc` are necessary but not sufficient. With them applied,
a dex comparison against the debug build showed every real `org.webrtc` class
present and unrenamed, with the 79 differences all desugaring artifacts, and
`WebRtcClassLoader` retaining its `getClassLoader` method. The abort persisted,
and also persisted with R8 full mode disabled. The same source is fine
unminified, which was confirmed on a physical phone for both the debug build and
an unminified signed release.

The measured cost of disabling it is about 7.5 MB on a roughly 50 MB APK, which
is dominated by native libraries that R8 does not touch. `-Peva.minifyRelease=true`
restores minification for anyone re-attempting it; it must be paired with a
device voice test, because no JVM or instrumentation test covers the release
variant.

## Models in use

The broker runs backend turns on `gpt-5.6-luna` at low reasoning effort and
realtime voice on `gpt-live-1-codex`, both through the host's existing ChatGPT
subscription. It reports the model it actually opened in the session-started
frame, and the phone shows that name under the connection status, labelled as
the voice model when a speech model fronts the session.

In voice mode the speech model carries conversation and delegates tool-selecting
turns to the backend model. Typed mode runs the backend model alone.

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

The integrated provider/voice build passes `just check`: 57 JVM tests, ktlint,
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
provider-independent history/notices, and authenticated remote pairing.

A [live provider contract probe](../experiments/voice-poc/evidence/2026-09-12-provider-contract.json)
verified nested schema acceptance and strict backend turn identity against the
real subscription provider. The Android emulator trial also displayed a live
model response and opened Golden Gate Park in Maps from a natural-language
request, then returned the actual handoff result to the model.

Acoustic quality, echo behaviour, natural barge-in, and voice-driven phone
actions remain unverified; the microphone and speaker paths themselves are now
established on hardware. The observed subscription bridge is an experiment, not
a promise of public API stability.
