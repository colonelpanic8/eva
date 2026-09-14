# Implementation status and next slice

Updated: 2026-09-14. Architecture: [architecture.md](architecture.md).

## Android provider and action runtime

The Compose app now uses `ProviderSessionController` and a provider-neutral
conversation port. `BrokerConversationProvider` projects the phone catalog into
Codex dynamic tools, submits ordinary text, adapts correlated events, and returns
actual dispatcher outcomes. The development `/device` WebSocket uses an ephemeral
broker code and localhost forwarding. Subscription credentials stay on the host;
there is no native OpenAI login or API-key fallback.

Twenty-three bundled capabilities are described by `CapabilityDefinition` records with
closed JSON Schemas: map search, driving navigation, message drafting, sending a
text message, conversation search, conversation reading, alarms, timers, dialing,
web search, opening a URL, email drafting, calendar events, launching an installed
app, opening a settings screen, contacts search, three typed device-state
operations, and four media operations. The intent operations other than
map search, navigation, and message drafting share one generic `IntentBackend`;
contacts, messaging, media, and device state have query-specific backends.
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

## Controlling media without per-app support

`eva.android.media.control`, `eva.android.media.nowplaying`, and
`eva.android.media.volume` go through `MediaControlBackend` over one
`MediaSessionAccess` port; `eva.android.media.play` is an `IntentBackend`. No
capability knows anything about Spotify or any other particular app.

Android's general mechanism is the media session. Every app that puts transport
controls on the lock screen publishes one, and `MediaSessionManager` exposes them
to an app holding either the privileged `MEDIA_CONTENT_CONTROL` permission or an
enabled notification listener of its own. EVA declares
`adapters.android.EvaNotificationListener` for the second path. The component
overrides nothing: notifications are delivered while the grant is on and ignored,
and the grant exists only so `getActiveSessions` will answer. Settings shows
whether the grant is on, says what it also gives away, and opens EVA's own row
where Android 11 or later has one.

Without the grant EVA is not helpless. `AudioManager.dispatchMediaKeyEvent`
needs no permission and reaches whatever holds the phone's media button, so
pausing and skipping still work. It returns nothing, names no app, and cannot be
targeted, so `MediaRouting.plan` chooses between the two paths and that outcome
reports `HANDED_OFF` with an explicit statement that EVA cannot see what received
the button. Naming an app is refused in that mode rather than guessed at.

A transport command is a request to another process, answered whenever that
process gets to it. `MediaControlBackend` therefore waits 600 ms and reads the
session back before reporting: `COMPLETED` carries the state the session actually
showed, and a session that ignored the command, or still shows the same track
after a skip, returns `UNKNOWN`. A session that disappeared is treated as evidence
of stopping only for stop and pause. A toggle is resolved into play or pause
against the session's own state, because a session has no toggle. An app that
declares a non-empty action set omitting the requested command is refused before
anything is sent; an app that declares no actions at all is attempted anyway,
because many report nothing rather than reporting a refusal.

None of this needs EVA on screen, unlike every intent capability: media sessions
answer from the background, so `unavailableReason` is unconditionally null and a
spoken "pause" works from a locked phone. Media volume uses `STREAM_MUSIC`
through `AudioManager` and needs no permission; a change Do Not Disturb refuses
returns `NOT_EXECUTED` rather than a claimed change. EVA's own voice runs on
`STREAM_VOICE_CALL`, so it is not what the volume capability moves.

### Starting something by name

Starting content that is not already loaded is a different problem, and the media
session API cannot do it: a session exists only once an app is running, so
"play Black Hole Sun on Spotify" from cold has nothing to talk to.
`eva.android.media.play` uses `MediaPlayBackend`, which tries two general
mechanisms in order.

`MediaBrowserService` is preferred. It is the platform interface Android Auto and
Wear OS use to play content in arbitrary media apps: connecting starts the app's
media service, and the session it hands back accepts
`MediaController.TransportControls.playFromSearch`. It targets one app exactly,
needs no screen, and leaves a session EVA can read back, so the outcome can say
what actually started rather than that a request was sent. The framework
`android.media.browse.MediaBrowser` is used directly; no media-compat dependency
was added. Connection is bounded at four seconds and the browser is held briefly
before disconnecting, because an app may stop a media service that has no clients
and nothing playing yet. Disconnecting does not stop playback: the session
outlives the browser connection that revealed it.

The app decides in `onGetRoot` whether to accept the caller, and many allow-list
Android Auto, Wear OS, and Google's assistant by package and signature. A refusal
is therefore an expected answer and not a failure. `MediaPlayBackend` falls back
to `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, the documented intent any
app can register, which needs EVA on screen to launch. When the browser refused
and no screen is available, the outcome states both facts rather than one.

With no app named, an app already holding a session that declares
`ACTION_PLAY_FROM_SEARCH` is preferred, because that is the app the user is
already using and it works from a locked phone. Anything less certain is left to
the intent, where the phone's own chooser applies.

This is the part of Google's assistant that is not reproducible in full. Its
"play X on Y" also rests on App Actions built-in intents (`actions.intent.PLAY_MEDIA`),
which are Assistant-only and cannot be invoked by a third-party app, on being
allow-listed by media apps that reject unknown `MediaBrowserService` callers, and
on `MEDIA_CONTENT_CONTROL`, which is privileged. What is left to a third-party
assistant is the browser path plus the intent, which is what EVA does.

JVM tests cover routing, toggle resolution, the confirmation rules, the ungranted
fallback, the refused volume change, the media-service refusal falling back to the
intent, and target selection with and without a named app. None of the media
behaviour has been exercised against a real media app on a device yet, so which
apps accept EVA as a `MediaBrowserService` client is unmeasured.

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

## Conversations as message targets

A group text has no phone number, so the threads already on the phone are the only
way to address one. `eva.android.messages.conversations` lists them newest first
from `content://mms-sms/conversations?simple=true`, resolves each thread's
`recipient_ids` through the shared canonical-address table, and names participants
through `PhoneLookup` when contacts access was already granted; an optional query
keeps only threads with a matching participant. `eva.android.messages.history`
reads one thread back oldest message first, merging `content://sms` rows with
`content://mms` rows and their `text/plain` parts, because a group conversation is
made of MMS and an SMS-only read would show just its replies. MMS timestamps are
seconds where SMS timestamps are milliseconds, and both are normalised before the
merge. Both operations request `READ_SMS` on first use through the resumed
Activity; scans are bounded and message bodies are previewed rather than dumped.

Both message capabilities now accept either `recipient`, which may carry several
comma-separated numbers, or a `conversationId` from that search, and exactly one of
the two. `MessageTargets` resolves the argument to a recipient list; each number is
validated on its own and encoded on its own, so a multi-recipient draft URI can
still only be joined by the separator EVA writes. One recipient sends as SMS
exactly as before. Several recipients send as a single MMS `M-Send.req` built by
`MmsPdu`: WSP binary headers, an insert-address token for the sender, one `To` per
recipient, and a `multipart.mixed` body holding one UTF-8 `text/plain` part. The
PDU is written to a private cache file handed to `SmsManager.sendMultimediaMessage`
through a non-exported `FileProvider`, and deleted once the send resolves. Fanning
a group message out as separate SMS messages is deliberately not a fallback: it
would create one-to-one threads that neither side sees as the conversation the user
asked for. When `getCarrierConfigValues` reports multimedia or group messaging off,
the outcome says so and points at the draft action instead.

RCS is out of reach for what EVA sends itself: Android publishes no third-party API
for it — `android.telephony.ims` exposes only `ImsRcsManager` and `RcsUceAdapter`,
which report capability and registration — and `sendMultimediaMessage` goes through
the telephony stack rather than the user's messaging app, so a group EVA sends is
always MMS. The draft handoff is the exception: `eva.android.messages.compose` gives
a multi-recipient `smsto:` URI to whichever app owns messaging, and that app may send
it as an RCS group chat.

What EVA can read is the messaging app's storage decision rather than a platform
guarantee. Google Messages keeps RCS in its own store, which is why backup tools
cannot see RCS either; an app that wrote RCS into the telephony provider would simply
appear. The case to design for is partial visibility, not absence: a chat that has
moved to RCS usually still has older SMS or MMS history, so its thread is listed with
a stale snippet and reads back missing everything recent. `ConversationSummaries`
therefore marks every read as the text-message side only, and an empty read says an
RCS chat looks like this rather than reporting an empty conversation.

The group send path is implemented and unit-tested at the encoding and result
layers, but it has not yet been exercised against a carrier on a device; the PDU
layout, carrier acceptance, and the sent-broadcast timing still need a device pass.
That pass should also settle two open questions: whether a sent group MMS appears in
the sender's own messaging app, and whether a recipient's client shows it inside an
existing RCS conversation or beside it.

Verified on the Pixel 11 Pro Fold (Android 17, API 37) from the registered
backend: one text to the tester's own number completed in 1,030 ms with the
platform's sent confirmation, and the message appears in `content://sms/sent`.
Contacts search ran in the same pass at 14 ms for the whole displayed name, 13 ms
for first names, and 9 ms for last names; searching a surname as a first name
correctly returned no match, which shows the field is honoured rather than
widened.

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
an assist target and launched by the assistant gesture. `MainActivity` is
`singleTask` and reuses its instance for a later assist launch. Typed-mode
instructions carry the user's current local time and Unix epoch milliseconds so
alarms and calendar events can be scheduled from relative language. The digital
assistant role itself is served by a voice interaction service; see
[the assistant role](#the-assistant-role).

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
results establish handoff only; the send capability reports what the platform
confirmed, and the compose capability only opens a draft.
The local parser remains available only as a test/diagnostic implementation.

## Native realtime voice

The first native audio implementation reuses the proven broker SDP path with
`io.github.webrtc-sdk:android:150.7871.01`. The phone gathers an SDP offer, the
host starts the subscription-backed realtime session, and the answer is applied
on the phone. Audio travels over WebRTC; control and transcripts use the broker.
The `oai-events` channel is negotiated before the offer.

Opening EVA asks for every runtime permission it uses at once: microphone,
contacts, reading and sending text messages, and notifications. A session that
keeps running while the phone is locked or another app is in front cannot show a
permission dialog, so a grant requested when a capability first runs would
arrive too late. A request launched while another is open loses its answer, so
an in-flight sweep is left to deliver the result.

Voice always captures; there is no microphone-less mode. A microphone denial
offers retry or system settings, and voice does not start without it. Pending
permission decisions survive Activity recreation in memory; broker codes are not
saved to disk. Local controls mute capture and playback independently. Audio
focus loss pauses tracks and disconnect releases media; backgrounding does not,
and configuration changes retain the connection. The foreground service claims
both the microphone and media-playback types so capture and playout continue
once another app takes the screen.
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
holds the voice session across that handoff so the spoken confirmation can play
and the user can keep talking; the session ends when the user stops voice, the
model ends the conversation, or the broker lifetime expires.

A spoken session also advertises one tool that is not a phone action: ending the
conversation. The model is told to say a brief goodbye and then call it when the
user says goodbye, says they are done, or asks it to hang up. The controller
handles it without the dispatcher or journal and returns no result, because a
result would prompt the model to speak again. It hangs up once the provider
reports that the goodbye has finished reaching the phone, plus a short playout
tail, and at most ten seconds after the call if that report never comes. The
direct realtime provider reports this from the WebRTC `output_audio_buffer`
events; the broker reports no playback, so there the call hangs up at once.
Typed sessions do not offer the tool. Android does not allow a background app to start an
activity, so a second action requested while another app is in front reports
`NOT_EXECUTED` with a prompt to return to EVA whenever EVA's own screen is the
only surface available. A shown assistant panel provides another launch surface;
see [the assistant role](#the-assistant-role).

No automatic reconnect, requirement tokens, or provider history seeding is
included.

## The assistant role

`EvaVoiceInteractionService` is what makes EVA selectable as the phone's digital
assistant, which an `ACTION_ASSIST` activity filter alone does not do. The service
holds no logic; the system keeps it bound for as long as EVA holds the role, which
also keeps the application object and its controller resident. Android exposes no
role request for the assistant the way it does for the default dialer or SMS app,
so settings reports whether EVA holds it and opens the system screen where the
user can grant it.

`EvaVoiceInteractionSessionService` builds an `EvaVoiceInteractionSession` that
the system can show and hide repeatedly. The session draws a Compose panel over
whatever app is in front rather than replacing it. A session is not an Activity,
so nothing supplies the lifecycle, view-model, and saved-state owners a
`ComposeView` resolves from its view tree; `SessionViewOwners` drives them from
the session callbacks instead. On show, the panel waits for stored conversations
to load and then connects voice, joins a session that is already live, or
reports that the microphone grant is missing -- a session has no activity from
which to request one, so it offers the app instead. A locked phone shows
transport only, the same as the keyguard launch. Screen context is never read:
`onHandleAssist` and `onHandleScreenshot` are deliberately not implemented, so
nothing about the app underneath reaches a provider.

Dismissing the panel does not end the conversation, the same as backgrounding EVA's
own screen: the foreground service, its ongoing notification, and the system
microphone indicator all remain, and the panel's own control is how a session is
stopped.

While the panel is shown it is registered with `AndroidIntentHost` as a launch
surface; EVA's resumed activity is still preferred. Android rejects
`startAssistantActivity` from hidden or inactive sessions, so hiding or
destroying the session removes that surface and a raced rejection reports
`NOT_EXECUTED`. This does not close the second-action gap once the panel has
been dismissed. Pending startup is cancelled when the panel hides; an already
connected voice conversation continues.

A voice interaction service must declare a recognition service, and selecting an
assistant also makes that recognizer the device-wide default, so a stub here
would break dictation in unrelated apps. EVA does not transcribe speech itself
-- its own audio goes straight to a realtime provider -- so
`EvaRecognitionService` forwards each request to on-device recognition where the
platform offers it, and otherwise to another installed recognizer, preferring a
preinstalled one and excluding both EVA build variants. With nothing to forward
to it reports `ERROR_CLIENT` immediately rather than leaving the caller waiting.
The manifest declares speech-service visibility for Android 11+. Delegates are
released after terminal callbacks and cancellation; late callbacks from a
released delegate are ignored.

On 2026-09-14, a signed 0.13.0 release candidate was installed over the existing
production package on a Pixel 11 Pro Fold running Android 17. Refreshing the
assistant role selected `EvaVoiceInteractionService`; the system reported the
interaction and session services bound, and `KEYCODE_ASSIST` showed the Compose
panel over Recents with voice connected. The panel remained shown after a tap
inside it, while a tap on the surrounding scrim dismissed it and restored focus
to Recents. No EVA crash appeared in logcat.

The physical assistant gesture, keyguard behaviour, background activity handoff
through the session, and third-party recognition through the delegate remain
unverified. JVM tests cover the decision logic, recognizer selection, lifecycle
transitions, and launch-surface dispatch.

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
`gpt-realtime-2.1` for speech and `gpt-5.6-sol` for text, with
`gpt-transcribe` for input transcription. Typed turns send the chosen reasoning
effort as `reasoning.effort` (`low`, `medium`, `high`, `xhigh`, `max`, or
`ultra`; default `low`). A chosen model applies to the next
connection.

Input transcription is a second model pass that produces only the on-screen
captions; the speech model hears the audio itself, so caption errors never
reach it. That pass starts with no knowledge of the session, which is what made
its output poor, so it is given the setting as a prompt, `en` as the expected
language, and the user's contact display names as `keywords`. The WebRTC call
omits `delay`: adding `delay: high` made otherwise identical live subscription
calls time out on 2026-09-14. Contact names are read only when
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
lists text models only. The backend answers an empty list below version 1.0.0
(verified live: the app's own 0.8.0 returns nothing, 1.0.0 returns the full
list), so the phone clamps the request to that floor until its own version
passes it; it reads only slugs. Live slugs on the test account are
`gpt-6-astra`, `gpt-reserve`, `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`,
`gpt-daybreak-blue-latest`, `gpt-5.5`, `gpt-5.3-codex-spark`, and
`codex-auto-review`, each carrying its own supported reasoning levels and
default. `SubscriptionAccess` and `ApiKeyAccess` differ only in
URL, headers, and history handling; the session, controller, dispatcher, and
journal are unchanged.

Direct voice uses the signed-in subscription token on the public
`https://api.openai.com/v1/realtime/calls` endpoint, with EVA's existing
`originator` and `chatgpt-account-id` headers. Access is decided by the server;
EVA does not reject subscription accounts locally or require a paired host.
An API key remains a separately billed alternative.

On 2026-09-14 live signaling accepted the subscription bearer for
`gpt-realtime-2.1`, `gpt-realtime`, and `gpt-realtime-mini`. Reproducing the app's
full payload isolated the timeout to `audio.input.transcription.delay: "high"`.
With `gpt-transcribe`, the model alone, `languages: ["en"]`, and prompt/keyword
hints each returned HTTP 201; adding only `delay: "high"` timed out. Multipart
part content types did not change the outcome. A separate Codex quicksilver
route's `403 Voice session access denied` does not establish that the public
Realtime route rejects subscription credentials.

The opt-in native speech test can exercise the phone's saved ChatGPT login
without a host or exported token; see [native voice verification](native-voice-testing.md).
Both native speech and a voice-driven timer handoff passed on the Pixel 11 Pro
Fold with the saved subscription. `just check` passed all 129 JVM tests,
formatting, fatal Android lint, and debug assembly. Release lint and production
APK signature verification also passed before installing the local signed fix.

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
launched through the assist gesture. A live session reported "Voice connected"
with the microphone control available.
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

The first integrated slice now registers `eva.device.observe`,
`eva.device.tap`, and `eva.device.set_text` through the same capability registry,
provider catalog, dispatcher, journal, and outcome types as other bundled actions.
Shizuku and the AIDL helper remain private implementation details. Observation
results are bounded text; screenshots are not sent to providers. Mutations accept
an observation-local node number, consume that observation reference once, and
revalidate the foreground package, class, label, bounds, and visibility before
delivering input. The existing one-call-per-input limit remains in force, so the
initial model flow is human-stepped across turns.

On 2026-09-14, the integrated debug APK passed a focused Android instrumentation
test on the same Android 16/API 36 emulator. The production host and helper read
the fixture, replaced its field with `EVA integration ✓`, tapped its increment
button, and returned a post-action observation containing `Count: 1`. After the
Shizuku server was killed and restarted, the complete test passed again in 2.031
seconds. The visible Shizuku authorization prompt was also exercised. UIAutomator
cannot remain connected during this test because Android permits only one
UiAutomation owner; the behavioral test therefore runs after the setup grant.

This slice does not yet implement the general package/native-operation loader,
screenshots or artifact references, per-app disclosure grants, cancellation,
swipes, keys, global navigation, multi-step control within one input, or secondary
displays. Android 17, fold transitions, long-running background control, and a
live model-driven workflow remain unverified. The [extension design](device-control-extension.md)
describes those remaining contracts; reproduction commands and broader platform
evidence live with the standalone experiment.

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

The conversation is rendered as grouped turns rather than a flat stream. Each session
is bracketed by dividers naming its mode and model ("Text session ·
gpt-5.6-sol", "Session ended"), and each turn shows its request, the actions the
model ran for it on a branch beneath, then the answer. Actions carry the ID of
the turn that ran them in memory only; restored history still renders one card
per receipt. There is one provider session at a time; the model cannot open a
second one.

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
