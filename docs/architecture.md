# EVA architecture

Read the [design philosophy](../README.org) first. This document describes the
implemented boundaries and identifies remaining work; it is not a milestone log.
The [extension reference](extension-protocol.md) owns extension formats and wire
contracts. [Operations](operations.md) owns build, release, and verification steps.

## Runtime and ownership

EVA is a native Kotlin/Compose Android app. Platform-neutral code lives in the
Kotlin/JVM `:eva-core` module: capability registry, dispatcher, grants and extension
runtime, declarative packages, conversation threads and turns, prompt and wording,
model providers, memory, and direct screen actions. `:app` holds everything that needs
Android: activities and UI, voice media, the assistant surface, native phone adapters,
the Android extension transport, persistence, and settings. It supplies the native
tool definitions (`BundledCapabilities`) and storage to the core. Package names are
the same in both modules; a class's module follows from whether it needs Android.
Another host, such as a desktop app, builds on `:eva-core` the same way.
`device-control-core` holds the Android-free device protocol and task/backend contracts.
`device-control-portal` implements same-phone Portal HTTP input independently of Android
framework types; the app includes it for instrumented backend verification.

```text
Launcher / Android assistant surface / Compose UI
                        |
                 ThreadController
                  /            \
       ConversationProvider     CapabilityDispatcher
       text or realtime voice    registry + invocation journal
                 |                       |
       provider transport          native Android adapters
       + audio lifecycle           declarative packages
                                   installed-app extensions
```

| Boundary | Responsibility | Starting point under `app/src/main/java/com/colonelpanic/eva/` |
| --- | --- | --- |
| Composition | Construct providers, stores, adapters; coordinate settings and refresh | `EvaApplication.kt` |
| Android invocation | Launcher, one-off assistant entry, system assistant panel | `MainActivity.kt`, `Launch.kt`, `assist/` |
| Presentation | Conversation, connection controls, settings, instructions, extensions | `ui/EvaApp.kt`, `ui/ConversationScreen.kt`, `ui/settings/`, `ui/prompt/` |
| Conversation | Durable threads, turn tasks, attachment lifecycle, provider events | `conversation/ThreadController.kt`, `conversation/Thread.kt` |
| Provider contract | Model session input, correlated output, tool proposals and results | `providers/ConversationProvider.kt` |
| Voice media | WebRTC capture/playback, mute, audio focus, routes, foreground service | `audio/`, `audio/webrtc/` |
| Execution | Catalog admission, validation, grants, dispatch, durable outcomes | `capability/CapabilityRegistry.kt`, `capability/CapabilityDispatcher.kt` |
| Integrations | Android operations, declarative interpreter, installed-service transport | `adapters/android/`, `adapters/declarative/`, `capability/extensions/` |
| Persistence | SQLite conversation/action history, settings, prompt files, encrypted secrets | `data/` |

Providers propose tool calls; EVA owns execution authority. UI and provider code
should not bypass the dispatcher to execute model-selected actions. Core contracts
avoid Android/vendor SDK types where practical so focused JVM tests can exercise them.

The main activity follows the system orientation on phone-sized displays. On
tablet-sized displays (smallest width at least 600 dp), it uses the orientation
sensor even when system auto-rotate is off. Its launch surface and voice permission
flow survive the activity recreation caused by rotation.

## Threads, turns, and connections

A **thread** is persistent conversation history. A **turn task** owns an accepted
request. A voice call or text connection is an **attachment** to the thread;
a **provider leg** performs the model work for a task.

`ThreadController` runs tasks in a thread-owned scope. Ending an attachment with
unfinished work can continue that turn on a background Responses leg, seeded with
thread history. Voice can also hand a long request to that leg with its
continue-in-text session tool. The text leg receives a fresh catalog from the
same capability registry, including enabled extensions, and retains the turn's
action claims and receipts. Turn IDs belong to the store rather than a provider's session-local
counter. The SQLite journal links requests, tool calls, receipts, and responses.
Each text leg is its own thread item recording the delegated task, the exact
instructions it was opened with, and how many history items seeded it; the calls
it proposes carry its ID. Every delegation opens a fresh leg seeded from thread
history rather than resuming an earlier leg, so the thread stays the one source
of context. The UI projects these records into grouped turns and session notices,
and shows each text leg as a collapsible block inside its turn, open while it works,
holding the leg's own actions and its prompt.

One attachment is live at a time. Several turn tasks can be active in the same
thread: a delegated text turn and a later foreground voice turn own their calls
independently. Ownership follows the provider leg and input ID, never whichever
turn happens to be first in the thread. User transcripts follow their committed
speech item; late captions and assistant replies keep their original turn ID,
even after text work finishes. A new spoken request gets a separate input while a
pending device task retains its original turn and device lease. Device corrections
and Stop remain available through the thread's device controls.
The UI Stop targets a running device task on the shown thread first, then its
foreground task, then its latest active background task. It cannot stop another
thread's device task. Working
indicators stay on until the thread's last active task finishes.

A voice-to-text handoff reports `HANDED_OFF` with the durable turn ID as `taskId`
only after the text provider connects and accepts `requestResponse`. Startup is
bounded to 15 seconds; a missing connection, rejected response submission, or
startup failure returns `NOT_EXECUTED`. Sibling calls retain their original voice
result sink during handoff, including calls arriving after text starts, so moving
the turn never strands their receipts. Text continuation is one-shot and never
retries an uncertain mutation. Mutations serialize across turns on the same thread;
an UNKNOWN or FAILED mutation in any still-running turn blocks further mutations
while that turn remains active. Read-only tools remain available for verification.

Voice offers `eva.session.background_status` (no arguments) and
`eva.session.background_cancel` (`taskId`). Status lists this thread's background
and delegated tasks plus any running device task, followed by up to five recently
finished tasks from the last 512 thread items. It includes task text, action count,
last action status, and three recent attributed receipts (call ID, capability,
arguments, status, and message) for verification. The result stays below the model
result budget; `tasksOmitted` and `historyLimited` expose omitted or partial history.
Task states are `CONNECTING`, `WORKING`, `ANSWERED`, `FAILED`, or
`INTERRUPTED` state. Cancel requests interruption of exactly that active task,
including its owned device task; unknown, finished, or other-thread IDs return
`NOT_EXECUTED`. Cancellation does not undo actions already started; their receipts
still determine effects. `ANSWERED` means the text leg answered, not that every
action succeeded.

When a background turn answers, fails, or is interrupted, EVA sends a lifecycle
note to a voice attachment on the same thread with its task ID and terminal state.
Answers, partial findings, and failure reasons are JSON-quoted data under EVA's
followed wording. Findings are bounded to the model result text budget. Realtime
puts the trusted instruction in a system item and the attributed JSON in a separate
assistant item. It requests an announcement with `tool_choice: none`; the controller
also marks that turn announce-only and answers stray calls by asking for a user
request. The next spoken request has the normal tools. Announcements wait until no
response, pending tool follow-up, user speech, or assistant audio is active; speech
ending waits for the server VAD response to finish. Startup failures return only
the handoff refusal, without a duplicate spoken lifecycle note. Queued notes do
not interrupt a foreground response. Providers that do not support
`submitContext` (currently Responses and Broker), and threads without voice,
retain the notification fallback. Failed or interrupted notifications and thread
notices retain accumulated partial answers and identify the interruption.

`VoiceSessionService` owns foreground voice lifetime; `TurnWorkService` covers
text and detached turns, including delegated work while voice is attached. Android can interrupt background
work, so persistence supports recovery and explicit interrupted outcomes, not a
promise of uninterrupted execution across process death.

A turn can run successive native or extension reads and mutations without another
user message. Calls execute sequentially with a ceiling of 32 admitted calls,
including at most 24 reads, per turn; the same budget follows a turn onto its
background leg. Every call retains dispatcher validation, grants, and journaling.
An unknown or failed mutation blocks further mutations in that turn because partial
external effects may exist; read-only verification remains available. Process
recovery marks interrupted work and never automatically repeats it. The legacy
SQLite side-effect reservation column is retained for database compatibility but
is no longer used for admission.

Connections wait up to 15 seconds for local configuration, initial extension
discovery, and grants before capturing their tool catalog. Managed Git startup
makes the local checkout usable before remote synchronization; a slow network
must not prevent assistant startup. A readiness timeout reports a connection
error instead of silently opening with an incomplete catalog.

## Providers and audio

`ConversationProvider` adapts session setup, input, responses, tools, and correlated
results. OpenAI Responses and Realtime have direct on-phone implementations;
`BrokerConversationProvider` supports the development host bridge. The local
command provider is a diagnostic harness.

On-phone ChatGPT device-code sign-in and separately billed API-key access are
distinct modes. Subscription access depends on the service; it must not silently
fall back to billable API usage. Provider-specific authentication and transport
stay behind their adapters. A working provider does not establish a public or
stable subscription protocol guarantee.

Realtime voice uses WebRTC audio and explicit microphone ownership, mute, route,
focus, and teardown handling. The session owns its audible edges: a rising cue when
the transport connects and a falling one when it is torn down, played once per
session so a reconnection inside the disconnect grace period stays quiet. Cues use
sonification attributes rather than the call route, which the session has already
handed back by the time it ends. Losing audio focus to another app, such as a phone
call or music EVA just started, ends the call instead of pausing it; a duck request
such as a notification sound does not. Text and voice share capability execution. Realtime
input identity must survive late transcripts and asynchronous tool events:
transcript arrival order alone cannot establish which request owns an action.
Tool correlation carries connection/session, input, generation, turn, catalog,
and call identity; stale calls cannot gain authority through reconnection.
Each Realtime response ID has an immutable origin. Output-item, assistant-text,
and completion events resolve their own response ID, including late events after
a newer response starts. Completion output also supplies calls, deduplicated against
item events. An unrecognized response cannot borrow the latest request: its call
receives a correlated, journaled NOT_EXECUTED result asking for a new user request.
EVA-created typed, tool-follow-up, and lifecycle responses carry request, input,
purpose, initiator, and applicable parent-response/speech-item metadata. Automatic
server-VAD responses bind to committed speech items in conversation order, without
waiting for transcription. This retains server-side response creation and barge-in;
client-owned VAD response creation would add a data-channel round trip before model
startup. These ownership rules have raw-event provider/controller replay coverage;
voice latency and barge-in have not been measured on a device for this change.
Realtime asks for a spoken follow-up once every call from the same model response
has resolved, not once all calls in the session have, so a quick lookup is answered
while a device task from an earlier response still runs. A follow-up requested while
another response is active waits for it to finish. A native tool that declares a
boolean `quiet` parameter (screen tap and text entry) lets the model skip the
follow-up: a COMPLETED quiet call ends the input silently, while any other status
is still reported for the model to explain.

Provider-independent history preserves action provenance and distinguishes
external tool content from EVA's outcome envelope. Action records separately retain
the initiator (user speech, typed request, text-agent leg, device-task worker, or
lifecycle-note reply) and the applicable input, response, item, and leg IDs. Android
and desktop journal version 8 preserves this identity across restart; older records
keep an absent initiator rather than inventing one. Action details show a small
“from” label. Provider output is not proof
that an operation completed. Interrupting speech, ending a call, canceling local
work, and undoing a remote action are distinct operations.

## Capability execution

Adapters contribute capabilities to a registry snapshot. Admission is deterministic
with a shared 512-tool safety bound, including session controls: the highest count
verified with subscription Responses and Realtime, not a published provider maximum. Voice
reserves four base session controls (end, defer, background status, background cancel),
plus device-task revise/stop when `eva.device.task` is offered. Bundled native tools
come first, then whole installed-service extension groups, then remaining whole
extension/package/media groups sorted by stable source identity (capability ID prefix
when source metadata is absent). Tools within each group are sorted by ID. Installed
services therefore precede declarative link/intent packages, including packages for
the same app. A group that cannot fit is skipped and smaller later groups are tried;
admission never splits an extension or package instance's offered workflow. Overflow
reasons report typed and voice admission separately, since skipping a larger group
can leave room for a smaller group in only one mode. Unavailable or excess entries
remain explainable in the UI. Extensions shows admitted phone/extension counts,
reserved voice metadata bytes, and an always-visible list of excluded actions with
mode-specific reasons. An attached session or background text leg with exclusions persists “N tools unavailable — see
Extensions” in its session-start notice. The same selection supplies the session
catalog, exclusion IDs and model note (`catalog-unavailable` in `eva-wording.yaml`);
Settings uses the same selection and removes prompt-hidden entries from its preview.
No speculative cost/response-quality warning band is used: the measured latency is
configuration latency, not response latency. New tools reach the model on the next connection;
revocation blocks new execution immediately even if the model still sees an older catalog. For packages
refreshed from a followed repository, newly named actions are granted when that
package's auto-enable switch is on; explicitly disabled actions remain disabled.
Manual imports do not gain new grants this way. Installed Android providers do not
either, except the pinned default providers (Mova and Paseo): they start enabled
with every action unless the user turned them off. See
[default providers](extension-protocol.md#7-identity-grants-and-untrusted-text).
An app's own installed extension takes over same-named actions from declarative
packages that target that app; the package's other actions stay available and are
listed under the app.

The old 64-tool cap originated in EVA (`4ecc650`, replacing earlier 32-tool
provider guards). The
[Responses reference](https://developers.openai.com/api/reference/python/resources/responses/methods/create)
and [Realtime session reference](https://developers.openai.com/api/reference/resources/realtime/subresources/client_secrets/methods/create)
describe function tools without publishing a numeric tool-count ceiling. Live
subscription probes, using the in-memory existing OAuth token and EVA's `originator`
and account headers, completed Responses requests with 100, 128, 200, 256 and 512
functions. The expanded requests used `gpt-6.1-sol`, 300-character descriptions,
small closed JSON schemas, `tool_choice: none`, streaming and `store: false`:

| Responses functions | Request UTF-8 bytes | Completion latency | Result |
| --- | ---: | ---: | --- |
| 200 | 98,500 | 2.47 s | HTTP 200, `response.completed` |
| 256 | 126,052 | 3.60 s | HTTP 200, `response.completed` |
| 512 | 252,004 | 4.82 s | HTTP 200, `response.completed` |

Realtime WebSocket `session.update` accepted 70, 100, 128, 200, 256 and 512
functions, echoing the exact tool counts. With 300-character descriptions, compact
updates for 70/100/128 tools were 41,539/59,090/75,498 bytes; three alternating
rounds took 0.30–0.34 / 0.43–0.59 / 0.56–2.91 seconds. For 512 tools, updates were
300,522 bytes and took 1.52–1.60 seconds. These are small samples of configuration
latency, not controlled response benchmarks.

The WebRTC probe matched EVA's multipart `POST /v1/realtime/calls` session config
(`gpt-realtime-2.1`, low reasoning, Marin, audio output, `gpt-transcribe`, English)
and used synthetic silence, without opening a microphone. It also sent three
matching `session.update` events per successful call. The initial aiortc offer
advertised `max-message-size:65536`: 128 tools with shorter descriptions succeeded,
and a 65,532-byte acknowledgement arrived, but the next 128-byte size step did
not. This was the probe's negotiated receive limit, **not an OpenAI count limit**.
Changing only the advertised receive size to 1 GiB allowed all 128/256/512 tools
with 300-character descriptions; their initial configurations were
75,431/150,439/300,455 bytes and all echoed the exact tool counts.

EVA's pinned native WebRTC SDK uses the upstream
[256 KiB SCTP bound](https://webrtc.googlesource.com/src/+/refs/heads/main/api/sctp_transport_interface.h),
which [offer generation advertises](https://webrtc.googlesource.com/src/+/refs/heads/main/pc/media_session.cc).
The probe repeated the test with this advertised limit:

| Functions / description characters | Initial config bytes | Update request / acknowledged event bytes | Result |
| --- | ---: | ---: | --- |
| 128 / 300 | 75,431 | 75,513 / 76,028 | Created and three updates, all 128 tools |
| 256 / 300 | 150,439 | 150,521 / 151,036 | Created and three updates, all 256 tools |
| 512 / 200 | 249,255 | 249,337 / 249,852 | Bare created, then initial configured update; three matching updates |
| 512 / 224 | 261,543 | 261,625 / 262,140 | Created and three updates, all 512 tools |
| 512 / 225 | 262,055 | 262,137 / no acknowledgement | Bare initial session; update timed out after 8 s |
| 512 / 300 | 300,455 | 300,537 / no acknowledgement | Bare initial session; update timed out after 8 s |

The failed 225-character update would add 512 bytes to the successful echo
(inferred 262,652 bytes), crossing 262,144 bytes. HTTP 201 and an open data channel
therefore do not establish that the tool configuration succeeded. This is a
transport constraint, not a lower tool-count ceiling.

Voice admission allocates 224 KiB to serialized tool metadata (including quoted
source metadata and one guidance note per source), leaving 32 KiB
below the native transport bound for instructions, session controls and server
fields. Whole groups that cannot fit either this byte budget or the verified
512-tool safety bound are skipped with a visible explanation. The provider also
checks the **final** session configuration after prompt/wording/bridge changes:
at most 248 KiB, or the SDP-advertised receive size minus 8 KiB, whichever is smaller
(absent SDP size defaults to 64 KiB). Oversize configuration fails before the HTTP
call with the tool count, byte size and remediation. Missing acknowledgement of
the expected tool names within eight seconds emits a connection error naming the
catalog/prompt size cause; a bare session with missing tools is not accepted as
configured. Existing history acknowledgement remains a separate gate.

These probes verify configuration acceptance, not tool-selection quality, Android
execution or audio behavior. Public API-key live acceptance and the pinned Android
SDK's actual on-device SDP remain unverified. Prompt `hide` still applies after
admission, by ownership agreement with the controller's concurrent changes; hidden
entries are excluded from warnings, but can still consume reserved capacity. Moving
`assembled.hidden` filtering before selection is the remaining integration hook.

The dispatcher validates identity, arguments, binding revision, availability, and
grants, then journals a claim before dispatch. Duplicate call IDs cannot execute
twice with different arguments. A post-submission transport failure produces an
unknown outcome unless there is evidence the action did not start.

Receipts distinguish `COMPLETED`, `HANDED_OFF`, `NOT_EXECUTED`, `FAILED`, and
`UNKNOWN`. An intent launch is a handoff. Extension wait budgets are bounded;
timeout does not prove failure or cancellation. Changes to an extension's approved
contract invalidate grants. An outcome carries attributed text and, when the
integration supplies it, structured JSON data; both are journaled, replayed into
resumed conversations, and delivered to the model within a result budget equal to
the extension result limit. See the [extension protocol](extension-protocol.md)
for concrete identity, schema, waiting, and authorization rules.

## Android capabilities

- Declarative `android.content` reads use bounded background `ContentResolver`
  queries with typed URI query/path slots, declared columns and bound selection.
  Whole rows become attributed text and structured data. Extension settings report
  missing providers and offer supported Android permission requests; requirements
  join portable `device.authorizations`. Mova and Paseo authorities have explicit
  visibility entries; Mova's dangerous read permission is requested only on demand,
  and Mova 7.2.1 no longer requires it from EVA.
  The host is Robolectric-tested; physical-device verification is pending. See the
  [content contract](extension-protocol.md#content-execution-and-results).
- Native adapters use intents, contacts, messaging, media sessions, media browser
  interfaces, and other implemented Android contracts. Keep native resolution
  where it needs code; pure mappings can be declarative packages.
- Mova 7.1.1 or later executes its native todo intents without a target-app
  confirmation sheet. They still launch an Android activity, so Android decides
  whether that handoff can start from the current assistant and keyguard state.
  EVA records a successful launch as `HANDED_OFF`, not a verified todo change.
- Alarms and timers come from the shipped Clock catalog package, not native tool
  definitions. It uses standard Android intents with bounded integer extras and
  optional labels, retaining EVA's existing `SET_ALARM` manifest permission.
  Default adoption and action grants use the same portable configuration as Maps;
  removal or disablement is preserved. Results are handoffs, not verified alarm
  creation or timer start. Package device verification is pending.
- Media apps are surfaced as extensions (`adapters/android/MediaAdapter.kt`): each
  installed player found through its media browser service, Media3 library
  service, or play-from-search intent becomes one installed extension whose
  descriptor lists only the operations it has a route for — control, now-playing,
  and play through its media session, plus queue only where a route exists (Media3
  library search, or Spotify's own API once the account is connected). With the
  Spotify account connected, play also goes through Spotify's Web API first. EVA
  starts the best match on the active device, else this phone, else the only
  device. A stopped Spotify is woken with a media-button press so the phone
  appears as a device. This needs no screen and works while the phone is locked;
  it needs Spotify Premium. The receipt is `HANDED_OFF` with the device name, and
  a failure falls back to the session, browser, and intent routes. The model
  selects the app by choosing its tool; there is no app-name argument and no
  name matching in EVA. Enablement and per-operation grants, settings rows, catalog
  revisions, and stale-proposal refusal come from the extension runtime unchanged.
  Unnamed control, now-playing, volume, and play-whatever-the-phone-chooses stay
  native: they are media-button semantics and belong to no app. Notification access
  supports session inspection and result confirmation. Compatibility must be
  tested per app; an app may refuse EVA as a media client, which is remembered so
  the fallback is not delayed by asking again.
- `eva.android.phone.dial` places the call. With `CALL_PHONE`, which EVA asks for with
  its other permissions when the app opens, it calls `TelecomManager.placeCall`, so
  Android's phone service dials without any EVA screen, including over the lock
  screen. The receipt is `HANDED_OFF`: EVA does not observe whether the call
  connects. Without the permission, or for an emergency number (only the dialer app
  may place those), the dialer opens with the number and the user presses call. The
  call takes audio focus, which ends EVA's voice call.
  An API 36 emulator placed a call from EVA's process with the keyguard showing;
  physical-device verification is pending.
- `eva.android.location.current` reads the phone's location from `LocationManager`
  (fused provider where present), falling back to the newest cached fix, and adds
  the nearest address from the platform `Geocoder`. It needs `ACCESS_COARSE_LOCATION`
  or `ACCESS_FINE_LOCATION`, requested with EVA's other permissions; with only
  approximate access the result says so. A voice session adds the `location`
  foreground-service type when the grant exists, so the tool answers while another
  app has the screen; outside a session in the background Android withholds the fix.
  The OpenStreetMap places package's `nearby` search takes a bounding box around them. Declarative
  HTTP requests identify themselves as EVA, since public services such as Nominatim
  refuse anonymous library clients. JVM-tested under Robolectric; device verification
  is pending.
- SMS draft handoff and native direct-message sending are distinct capabilities.
  Notification replies share the same authorized messaging boundary. Do not remove
  native behavior merely because a declarative compose example exists. See
  [messaging setup](operations.md#messaging-setup-and-verification) for setup and
  current verification limits.
- `assist/` implements the Android voice-interaction service, overlay session, and
  delegated recognition service. Android's keyguard launch callback opens the
  hands-free activity above the lock screen, starts or joins voice, and hides the
  conversation. Every voice launch follows the prompt's call slot (below); the
  in-app **Ask once** action always uses one-request behavior. The keyguard callback is JVM-tested;
  locked-device voice verification is
  pending. Assistant selection does not confer unrestricted
  background launch or device access.
- Optional Shizuku adapters implement Settings AppFunctions and a screen-control
  backend shared by device tasks and the direct screen tools described below. Existing
  Shizuku grants allow device-setting and screen actions without EVA's main
  activity; that activity is needed only to request a missing grant, which Screen
  control settings can do ahead of time.
  Contact and SMS Android permission checks likewise reuse existing grants from
  the assistant without requiring the main activity. Platform restrictions on
  locked-device actions still apply. General AppFunctions discovery/execution and broader device
  automation are not implied by those implemented operations.

The installable Paseo package discovers workspaces and agents, reads recent
messages through Paseo's Android provider, and opens or prompts them through
links. Paseo's installed extension service (in development, see below) creates
agents and sends prompts without its UI. General MCP adapters remain future work. They should
register capabilities through the same execution boundary. Routine phone
actions must not depend on a remote coding agent or on automating Paseo's Android UI.

### Web research

The read-only native `eva.web.research` capability researches a question (and optionally
reads a public HTTPS `sourceUrl`) without launching a browser or touching the screen.
Both text and voice propose it through `CapabilityDispatcher`; the answer, sources,
search queries/opened URLs, and retrieval timestamp are journaled with external-content
provenance. Page content is data, not instructions or execution authority.

Each invocation makes one isolated OpenAI Responses request with only the hosted
`web_search` tool and no conversation history or phone tools. Responses supports hosted
search; Realtime supports function tools, so voice calls this EVA capability. Existing
ChatGPT subscription access is preferred; a configured API key is used only when no
subscription is signed in, never as a silent fallback after subscription failure.
Research starts enabled for both access modes. API-key research calls are billed for
a search fee plus tokens, stated in settings; receipt data identifies `accessMode`
as `api_key` or `subscription`. The subscription protocol is service-dependent.

`capabilities.webResearch` in portable configuration holds `enabled` (default true),
`model` (`gpt-6-sol`), `effort` (`low`), and `timeoutSeconds` (45, bounded to 5–60).
Research efforts are `none`, `low`, `medium`, `high`, and `xhigh`; unsupported saved
efforts (including `minimal`) load as `low` with a visible configuration notice,
while preserving the other settings. Each field composes independently across
included files. The Web research settings section edits those same settings; the
timeout slider saves on release. Disabling hides the tool and rejects new execution.
The total deadline includes authorization and transport;
coroutine cancellation cancels the HTTP call. Missing access is `NOT_EXECUTED`; HTTP
errors, read-only timeouts, and incomplete streams are `FAILED`, without retries.
Caller cancellation retains the dispatcher's `UNKNOWN` receipt after submission.
A finished response without web evidence is still `COMPLETED` but explicitly says no
sources were retrieved (or no citations were returned after search). Source lists are
optional upstream; citations and available source lists are deduplicated by URL.
The answer appears once in the receipt message, followed by a readable source list.
Structured data references it with `answerLocation: message` and retains source and
search metadata. Their combined content is bounded to the existing tool-result budget,
with truncation identified in the data.

The declarative web package's search/open actions remain browser handoffs without
retrieved content. Device-task browsing remains an explicit fallback for interactive
or authenticated sites. Research has focused JVM verification; live API-key and
Android-device verification are separate and have not been performed.

### Device-control tasks and backend boundary

`:device-control-core` is a plain Kotlin/JVM library containing the protocol v1
revision 1 types, compact observation renderer, `DeviceBackend`, `TaskAgent`,
`WorkerModel`, `TextTaskAgent`, and a shared execution lease. Protocol fixtures
round-trip on the JVM. `:device-control-portal` is also JVM-only: OkHttp transport,
tree mapping, target rechecks, action planning, quiet-window settling, launch
verification, and text read-back all run unchanged on a JVM host over a forwarded
loopback port. Both modules can be consumed by `:device-control-host` as-is.
`:device-control-host` is a JVM command-line application (`eva-device`) over the
same modules: `observe`, `act`, and an eval runner that reads voice-device-agent's
case files and applies the same resets and independent checkers. Its `--agent worker`
mode waits for the OpenAI client to be extracted into a JVM module.

Task backends are an ordered preference list, `[portal, shizuku]` by default.
Admission skips backends that are not ready: Portal is probed with an authenticated
`/version` request, and Shizuku with its binder and EVA's grant. A task is refused only
when none is ready, with each backend's reason. `PreferredDeviceBackend` also falls
back when the chosen backend cannot read the screen, but only until the first action;
after that the task stays on that backend, so no mutation is repeated elsewhere. The
conversation bar shows each backend's readiness, which direct screen tools share,
refreshed every five seconds while EVA is in front, and a running task's phase.

Portal on the same phone is the first default backend. Its full typed action set
includes Unicode replace/append text, password redaction, screenshot PNGs, Enter
for IME actions, URLs, and notifications. Mutating HTTP requests are not retried;
loss after dispatch remains uncertain. A cancelled input drains its bounded
request/settle exchange before releasing device ownership. The Shizuku backend is
the same `PortalBackend` over `ShizukuPortalTransport`: EVA's shell-UID helper
serves Portal's state shape from UiAutomation and executes the same fixed
primitives (touch gestures, global actions, Enter/Move-End keys, focused-field text,
launcher/VIEW `am start` with validated arguments, and screenshots streamed over a
pipe). Rechecks, settling, read-back and receipts are therefore shared. Append into a
password field types characters with the virtual key map, so it rejects characters
that map has no keys for. The helper keeps one UiAutomation connection across
consecutive calls and releases it after 20 idle seconds. It connects and calls as shell,
not as EVA's calling UID, because the platform lets only the connecting UID disconnect;
a leaked connection blocks every later one.

The text-first worker executes one primitive per model turn, asks for missing or
ambiguous choices, reports scroll progress, restricts reversals, detects repeated
or unchanged actions and repeated refusals, and bounds steps, active time,
screenshots and retained context. Append context preserves a cacheable prefix
until its screen limit or a revision rebuilds it from the goal, corrections and
recent step summaries and the last correlated native call/result exchange. Model
output items are retained for provider reasoning continuity. Scroll previews quote
up to three 30-codepoint labels; tables retain their 200-element/80-codepoint
limits. Screenshots are limited model attachments and are not
journaled. There is no approval, risk classification, evidence gate or injection
subsystem. A future vision-first agent can implement `TaskAgent` independently.

`eva.device.task` is one native capability admitted and journaled by
`CapabilityDispatcher`. It counts once against the outer turn's tool budget.
Primitives execute inside that admitted task under the device lease, rather than
being independently dispatched. The ordinary failed/unknown-mutation barrier
remains in `ThreadController`; it does not block recovery attempts inside the
worker. An unresolved primitive effect remains UNKNOWN at terminal task receipt,
even if later actions succeed. The task uses neither `BudgetedBackend` nor
`BoundedExecution`, returns no startup handoff, and holds its lease until terminal
completion/drain. Its one receipt includes task/revision identity, effects, and
per-step kind, result and observation/model/action timings. Step receipts also retain
the device-worker initiator, task/leg ID, call ID, and available response/output-item
IDs, separate from the outer call's initiator. A stop before any
completed effect is NOT_EXECUTED; known partial work is FAILED; unresolved work is
UNKNOWN. Journal recovery never replays a task.

The conversation model can also act on the screen itself, one input per call,
without the worker: `eva.device.observe`, `tap`, `set_text`, `scroll`, `press_enter`,
and `navigate` (Back, Home, notification shade). They run through
`devicecontrol/ScreenActions.kt` on the same `PortalBackend` as tasks, so rechecks,
settling and text read-back are shared. They use the first ready backend in the same
order as tasks, without a task's mid-call fallback. Element inputs name an observation
reference and element number from a projection of at most 60 addressable elements
and 3,500 characters; a reference serves one input, expires after three minutes,
and is dropped when a device task starts or the backend configuration changes, and
the backend itself accepts only its latest screen. References carry a per-process
namespace, so one kept in conversation history cannot name a screen recorded after a
restart. A tap activates a clickable element and touches anything else at its centre,
so a label inside a row presses the row while the backend rechecks the label that
was named. A scroll without an element binds to the largest scrollable one; with
none, it proceeds only while the screen still matches the one the model saw, and
otherwise returns the new screen. The Shizuku helper reports editable fields' text in
full (up to 10,000 characters) and streams its screen state through a pipe, as it
does screenshots, so long entries read back and a large tree cannot exceed Binder's
reply limit. `navigate` binds to a screen read in the same call. Each tool is its own
dispatched, journaled capability under the device lease and turn budget: a delivered
input is COMPLETED only when the backend reports success, delivered input that did
not take effect, such as text that does not read back, is
FAILED, refusal before dispatch is NOT_EXECUTED, and a lost exchange is UNKNOWN. The
wording steers a few visible steps to these tools and longer searches or multi-screen
work to `eva.device.task`, which keeps the realtime context small.

`DeviceTaskCoordinator` owns the task's thread and turn independently of its
voice/provider attachment. Competing tasks, ordinary mutations and direct UI
reads queue in arrival order for the device lease rather than being rejected;
one cancelled while queued is journaled NOT_EXECUTED. Android intent launches
also recheck whether a task is running. Within a turn, mutations run one at a
time in proposal order, while read-only calls run without waiting behind them, so
the voice model can look things up during a long task. Progress reaches the
owning thread independently of the pending tool result. Typed corrections go
straight to the running task's mailbox. In voice, the model routes new speech:
the intercepted `eva.device.task.revise` and `eva.device.task.stop` tools revise
or stop the running task (stop leaves queued work in place), and anything else
becomes its own queued action. Only two cases bypass the model: a spoken answer
that began while the worker waited on `ask_user`, bound to the owner captured when
speech began, and a bare stop/cancel utterance.
Revision bumps and stop latches are synchronous, bypassing the turn mutex and
provider result queue. Obsolete inference is cancelled and its plan discarded;
revision forces fresh observation. Provider speech `cancelled` does not complete
a running device task. Controller stop, explicit stop/cancel input and assistant
panel Stop use this control path before asynchronous persistence/cleanup.

`OpenAiWorkerModel` is a thin app adapter using the existing subscription/API-key
selection, a task-owned subscription Responses WebSocket, and shared cancellable
HTTP/SSE for API keys. Default model/effort are
`gpt-6-sol`/`low`. The shared transport explicitly calls `OkHttp Call.cancel()` on
coroutine cancellation, including while reading SSE. The task socket preserves
cache routing across turns, is cancelled on interrupted inference and closed when
the task ends. Both transports still reside in `:app`;
extracting that transport, access contracts and credential injection remains the
OpenAI-client branch's work before a JVM host can use this adapter.

Backend preference, local Portal port, credential reference, launch aliases, model/effort and worker/context
budgets live in portable `capabilities.deviceTask`. Settings edit that model;
Portal's bearer token is Keystore-backed and excluded from shared configuration.
Restore retains `device/portal` and reports local provisioning. Worker instructions,
notices and tool descriptions are injected from followed `eva-wording.yaml`, with
the shipped baseline as fallback. Ordinary requests retain direct tool calling;
the task capability explicitly requests the worker subsystem. See
[device-control acceptance](operations.md#device-control-parity) for device results
and remaining verification limits.

### Background execution and locked devices

EVA should do as much as possible while the phone is locked and in a pocket, a
car mount, or across the room: a hands-free assistant that needs an unlock for
routine actions is failing at its main job. When adding or changing a capability,
pick the route that works locked, in this order:

1. A platform API called from EVA's process with no Activity: `TelecomManager.placeCall`,
   media sessions, SMS, `ContentResolver` reads, HTTP, Spotify's Web API.
2. The target app's installed extension service
   ([durable writes](extension-protocol.md#9-durable-writes-receipt-states-and-locked-devices)).
   If an app we control lacks one, adding it there beats working around it here.
3. An intent handoff, as a fallback or when the user wants the app open. Mark
   targets that are useless behind the keyguard `requiresUnlock`, and report the
   result as a handoff.

Why a locked action can still fail, and what helps:

| Cause | Remedy |
| --- | --- |
| Activity launches are deferred behind the keyguard or refused from the background | Use a route above that needs no Activity; otherwise `requiresUnlock` or a handoff message that says to unlock |
| A permission dialog can't appear over the keyguard or from a background voice session | Request permissions when EVA's app opens, not on first use |
| Before first unlock after reboot, credential-encrypted storage is unavailable and neither EVA nor the providers run | None today; direct-boot support would mean keeping credentials in device-protected storage, which we don't do |
| EVA refuses locked-device notification reads and replies on purpose | Unlock. Keep this refusal unless the security model changes |
| A provider's keystore key requires an unlocked device | The provider reports `needs_unlock`; avoid unlock-bound keys for background writes |
| Foreground-service start or promotion is rejected | Reported, not crashed (below); start voice from a visible assistant session |
| Screen observation and input can't reach UI behind the keyguard | Prefer AppFunctions or a service route |
| Target app or OEM restrictions | Test on devices, and record the result in [device verification](operations.md#device-verification) |

Verify locked behavior with the keyguard showing and a PIN set, and say which
states were covered (emulator or physical device, warm or force-stopped provider).
JVM tests don't show that anything works on a locked phone.

Granted native operations, HTTP requests, content reads, and installed-service
calls do not require the main Activity. Intent handoffs prefer a resumed Activity,
then a visible assistant session, then an application-context launch when EVA is
the selected system assistant. The fallback sets `FLAG_ACTIVITY_NEW_TASK`; it
relies on Android's assistant launch eligibility and does not grant that privilege
to extension providers. Android and target-app restrictions still apply. A launch
receipt is a handoff request, not verified target visibility or completion.

Writes that must work on a locked phone use installed extension services rather
than intents. EVA binds the provider's service, which cold-starts its process
without an Activity. The provider journals the invocation ID before any side
effect and reports a receipt state: completed, durably accepted (`HANDED_OFF`),
uncertain (`UNKNOWN`), or a `NOT_EXECUTED` setup need such as unlock or opt-in.
Mova 7.2.0 implements this contract and Paseo implements it on a development
branch; neither is device-verified against a real server. See [durable writes](extension-protocol.md#9-durable-writes-receipt-states-and-locked-devices)
for the states, the device-state matrix, and the one-time opt-in rule. A
before-first-unlock phone runs neither EVA nor these providers, because none is
direct-boot aware.

Intent handoffs defer lock-screen launch eligibility to Android instead of
blanket-blocking every intent while locked. A locked handoff explains that unlock
may be needed to view or finish in the target app and does not claim completion.
An explicit Android security rejection returns `NOT_EXECUTED` with unlock guidance;
the secure hands-free screen offers Android's authentication UI. Unlocking does
not repeat a previous action. Existing notification-message lock restrictions remain intact.
A missing permission still requires device-local setup, while already-granted
execution remains independent of Activity lifetime.

Foreground-service start or promotion rejection reports the restriction rather
than crashing or silently losing its service observer. Voice rejection ends the
attachment and permits accepted work to continue in text; rejection of the
background-work service rechecks ownership and interrupts only turns without live
voice coverage. A text attachment relies on the work service too. It stops a device
task only by the affected turn's ID. Coverage refusal is terminal interruption,
with partial answers in the notice and notification; it never retries an action.
`interruptAll` remains the separate controller-shutdown path used by `drain()`.
The work service remains active while any non-voice turn needs coverage. On Android's
`shortService` timeout it calls `startForeground` again to renew coverage when Android
allows it (a visible app or a foreground-start exemption), independently of changes
to the working-thread set. Only actual promotion/start refusal interrupts dependent
work; completed work simply stops coverage. The type and permissions are unchanged;
renewal is subject to Android eligibility, not guaranteed indefinite execution. Voice and
background work remain non-sticky; force-stop and process death do not trigger action replay. The assistant launch fallback and unlock UI
require physical-device verification; JVM checks cannot establish OEM behavior.

## Memory

EVA exposes bundled `eva.memory.search`, `eva.memory.save`, `eva.memory.learn`, and
`eva.memory.forget` tools through the capability dispatcher; their model-facing
wording lives in `eva-wording.yaml` like other native tools.

- **Kept notes** are ones the user asked EVA to remember or correct (`save`), or
  learned notes the user kept. The same name replaces the entire note.
- **Learned notes** are ones EVA saved on its own (`learn`) from what the user said.
  They wait in an inbox on the **Memory** drawer screen, where the user keeps or
  dismisses each one. Until then they are searchable and marked `reviewed: false`.
  A learned note never replaces a kept note of the same name; saving a name held in
  the inbox settles it as kept. Each learned note records its conversation.

Search is a case-insensitive substring match on name and text over both tiers, in
pages of ten. `forget` removes a note from either tier. `save` and `forget` are
ordinary mutations. `learn` is marked `bookkeeping`: it is still journaled, but
completing it does not count as serving the request (so it cannot arm a one-request
call's quiet hang-up), and its failure does not block later actions as uncertain.

Notes contain a name, text, and last-updated timestamp. They persist across
threads in private app storage (`memories.json` and `memory-inbox.json`, each
atomically replaced), separate from shareable configuration. They are not synced
or restored by the user configuration repository. Kept notes are limited to 200;
reaching capacity refuses new notes rather than evicting any. The inbox holds 50
and drops its oldest note when full. Names are limited to 120 characters and bodies
to 1,000. Storage errors do not silently reset memory.

Tool wording requires explicit user intent for saving, correction, and deletion,
limits learning to what the user said (not tool results), excludes credentials, and
treats retrieved notes as data rather than instructions. These are model
instructions, not a semantic authorization classifier. There is no prompt injection
of notes, expiry, or semantic search. Forgetting removes the saved note but does
not erase prior conversation or action history. The implementation has JVM
coverage; physical-device verification is pending.

## Messaging

The **Messaging** drawer destination owns phone-permission status, contact-name
lookup retries, notification-message access/reply grants, and remembered-number
management. Moving these controls does not rename their portable fields:
`voice.lookupRetries`, `messaging`, and `remembered.chosenNumbers` remain stable.
Phone numbers compare in E.164, reading a number without a country code as one
from the SIM's country (`PlatformPhoneNumberKey`); `remembered.chosenNumbers` keys
are E.164, and keys in any other form are dropped when the configuration loads.

EVA exposes one search/read/send tool family with three execution paths:

- SMS/MMS: native Android conversation lookup, history and sending.
- Bridge services: a self-hosted
  [messaging bridge](https://github.com/colonelpanic8/google-messages-multidevice-bridge)
  holding one linked account, such as WhatsApp, reached over HTTPS with a
  device-local bearer token. It provides durable conversation references,
  contacts, recent history, new chats, and an outbox whose states EVA reports as
  they are. Implemented in `messaging/BridgeMessaging.kt`; its HTTP and outcome
  mapping have JVM coverage against a scripted bridge, and it has not been
  verified against a live bridge or on a device.
- Other apps: recent messaging notifications and their explicit text-reply
  actions. No target-app changes, extensions, Shizuku, or app-specific package
  allowlist are required.

### Shared tool contract

Existing capability IDs remain stable:

| Tool | SMS/MMS | Bridge service | Notification-backed app |
| --- | --- | --- | --- |
| eva.android.messages.conversations | Omit service or use sms; optional name query (commas require every person) or participants phone numbers; threads with only the asked-for people rank first | service is the bridge's configured name or label; query is passed to the bridge's name/number search and its contacts; participants filters to chats holding every number | service is notifications for discovery, exact package name, or unique visible app label; query matches conversation title |
| eva.android.messages.history | Use the returned integer conversationId | Use the returned durable conversationRef (`bridge:<service>:<id>`) | Use the returned opaque conversationRef; result is only a notification excerpt |
| eva.android.messages.send | Explicit recipient number(s) or conversationId, plus message | conversationRef, or recipient as E.164 numbers to reuse or start a chat, plus message | conversationRef and message; optional service must match |

App search results include service package name, conversation title,
conversationRef, and replyAvailable. If labels are ambiguous, use the package
name. EVA never substitutes SMS when an app was explicitly requested. Device
contacts remain useful for SMS, but a phone number is not an app reply target.

Routing is by explicit naming: a `service` equal to a configured bridge's name
or label selects that bridge, and a `bridge:` reference selects its bridge even
without `service`; a reference paired with a different explicit service, an SMS
thread ID, or a bridge no longer configured is refused rather than redirected.
Service names are matched exactly (case-insensitively); EVA does not guess which
service a spoken name means. When at least one bridge is configured, EVA appends
the `messaging-bridges` note from `eva-wording.yaml`, listing the configured
names, to the three messaging tools at connection time.

Bridge search results list conversations newest first with name, participants
(name and E.164 number), group flag, recency, unread state, and an attributed
preview, followed by matching bridge contacts without a chat and the number to
send to. History returns the bridge's stored recent messages oldest first with
sender, elapsed time, direction, the bridge's status string for sent messages
(shown as received; unknown values are passed through), attachment kinds, and
reactions. Both are quoted as external data, like every other messaging read.

The controller allows native reads before one send in a request. Every send
still uses the dispatcher, journal, schema validation, and correlated receipt.
Messaging content is quoted and attributed as external data, including resumed
thread history. It does not become a model instruction.

### Results and authorization

SMS keeps its existing sent-callback result handling. App notification replies
return **HANDED_OFF**, not delivered or read: Android accepted the app's reply
action, but does not expose a reliable cross-app server-delivery receipt.
Expired/cancelled targets or missing permission return **NOT_EXECUTED**.
Uncertain submission returns **UNKNOWN** and is not automatically retried.

A bridge send first reads `/v1/status`: `authentication_required` or
`storage_failed` refuses with **NOT_EXECUTED** and says the bridge needs
re-pairing; an unreachable bridge is **FAILED** with nothing queued. A recipient
send reuses the chat holding exactly those numbers, otherwise queues chat
creation under `<key>-chat` and waits for its conversation ID; a rejected,
ambiguous, canceled, or still-pending creation is **NOT_EXECUTED** because no
message was queued. The message is queued with an `Idempotency-Key` derived
from the invocation's call ID and argument fingerprint (SHA-256), so a
re-delivered invocation maps to the same bridge operation and the bridge
deduplicates it; EVA never mints a fresh key to retry. EVA then polls
`/v1/outbox/{key}` for a bounded wait and reports:

| Outbox state | Result |
| --- | --- |
| `accepted`, `confirmed` | **COMPLETED**: the service's server accepted it; not delivered or read |
| `rejected` | **NOT_EXECUTED** with the bridge's detail |
| `canceled` | **NOT_EXECUTED** |
| `ambiguous` | **UNKNOWN**; never resent |
| `queued`, `sending` at the deadline | **HANDED_OFF**: queued at the bridge, with its current state; history shows the outcome later |

A lost answer to the queue request is resolved by reading the key back: a
record means it was queued, no record is **NOT_EXECUTED**, and an unreadable
bridge is **UNKNOWN**. A 401/403 reports the saved token as unusable; 409 means
the bridge holds a different operation for the key and nothing new was sent.
Transport details and bridge error bodies beyond a short quoted text never reach
the model. Bearer tokens are sent only in the `Authorization` header over HTTPS
without redirects, and a response echoing the token is rejected, as for
declarative packages.

Only the current Android user's non-summary messaging notifications are
considered. Android must expose exactly one eligible freeform reply action owned
by the notification's package/UID; modern actions must declare reply semantics
and use a mutable PendingIntent. Unsupported actions remain readable but cannot
be replied to.

References live only in memory, expire after 15 minutes, and are invalidated by
replacement, removal, refresh, notification-listener disconnect, or process restart. A reference is
consumed before submission, even if the result becomes unknown. The service
rechecks the current notification, package identity, notification access,
unlocked device, and reply grant before invoking its PendingIntent.

Reply grants persist by app UID, package, signing certificate set, and first
installation time. Reinstalls or changed signers do not inherit permission.
Grant changes are serialized against durable dispatcher admission and rechecked
at submission. Disabling message access clears captured notifications; it does
not erase previously requested conversation receipts or undo sent messages.

### Limits

The notification path is not a full WhatsApp/Telegram client: it cannot start
arbitrary new app chats, retrieve complete history, list silent/archived
conversations, recover dismissed notifications, or send attachments.
Locked-device notification reads and replies are refused. Notification
visibility and action support vary by app. “No match” means no match among
available notifications, not that the chat does not exist.

A bridge service covers one linked account per bridge and only what that bridge
has stored: history is the bridge's recent local snapshot, not the phone's
complete archive, and search relies on the bridge's `q`/`limit` parameters.
EVA sends text only; attachments are described, never downloaded or sent.
Status strings are the bridge's own. A queued message stays at the bridge if the
bridge is disconnected, so a handed-off send may still fail later; EVA does not
watch the outbox afterwards. Reaching a bridge needs the phone on its network
(for example the tailnet). The path has JVM tests against a scripted bridge and
no live-bridge or device verification yet. The bridge holds the account; it is
the operator's trust decision, and EVA's token grants no action by itself.

The shared interface is deliberately independent of extension files. Bridge
services are the first service-account adapters behind the common tools; they
preserve explicit service selection, account-scoped durable targets, and honest
receipt statuses rather than replacing the user-facing tools.

## Configuration and restoration

The design requirement is a single user-owned repository capable of restoring
**every EVA setting**. Files are readable, deterministic, composable, and usable
through either a linked folder or EVA's app-managed Git checkout. UI controls edit
the same configuration model in both modes.

A complete configuration includes model choices, appearance, capability switches,
instructions and their sources, extension definitions and identities, grants,
repository sources, service endpoints, wait budgets, and saved user preferences.
Configuration import must validate before replacing working settings and preserve
identity-dependent authorization. Invalid or incompatible input must be visible.

Shipped default packages (Google Maps, OpenStreetMap places, Web, Email, Calendar, Settings, Clock, Waze
once Waze is installed, and Paseo once its provider is present) are adopted once per
configuration: after the desired configuration is attached at startup, EVA
installs and approves each default not yet listed in `packages.appliedDefaults`
and records it there. The result is an ordinary installation and grant, so the
same restore, update, and removal rules apply, and a removed default stays removed
on every device. See the [extension protocol](extension-protocol.md#shipped-default-packages).

Credentials require protected provisioning and references; neither a package nor
a shareable configuration may embed application-owned secrets. Android permission
grants, assistant selection, document access, and installed applications are
properties of the destination device. Restoring EVA's desired configuration cannot
grant these platform permissions; the app must identify remaining setup explicitly.
Conversation history and transient connections are runtime data, not settings.

### Portable configuration format

The portable format uses a root `eva.yaml`. Folder mode selects its directory
through Android's Storage Access Framework; synchronization and version control
remain the responsibility of the user's tools. Managed Git mode uses JGit 6.10.1
and an app-owned checkout under external-files storage (or internal files when
external storage is unavailable), including real `.git` metadata. Java NIO
desugaring supplies JGit's required APIs below Android 8; host
tests and Android packaging cover that integration, while physical-device
verification remains separate. The implementation lives in `data/configuration/`.

To use a single user repository:

1. For folder mode, make a directory available through Android's file picker,
   directly or through a directory-sync tool, then choose it under **Settings →
   User configuration**.
2. For managed Git, enter an HTTPS remote, branch, author identity, optional Git
   username, and device-local token, then select **Save & connect**. Remote URLs
   with credentials, queries, fragments, non-HTTPS schemes, or invalid refs are
   rejected. SSH is not supported.
3. An empty folder, checkout, or remote branch gets an `eva.yaml` snapshot of the
   current settings. An existing graph is validated and restored.
4. On another device, link or connect the same repository.
   Complete the listed account, app, and device-authorization requirements.
5. Edit settings in EVA. Folder mode atomically updates the root and offers
   **Reload**. Managed mode atomically updates the root, creates a scoped commit,
   and attempts to push; **Sync** retries pending work and pulls remote changes.

To extract a shared base, move the generated complete file to `shared/base.yaml`
and replace the root with a small override file, for example:

```yaml
format: eva
version: 2
include:
- shared/base.yaml
voice:
  lookupRetries: 3
```

Each included file also declares its format and version. This lets devices or
forks reuse a base while overriding selected settings. Managed Git stages only
the current and previously committed root/include graph paths; unrelated files
in the repository are never added to EVA's commits.

### Managed Git synchronization

The remote URL and branch select an isolated checkout identity, so changing either
cannot silently reuse another remote's local history. Author name/email and Git
username are explicit nonportable bootstrap inputs with non-personal defaults.
They and the enabled mode live in local preferences; the token lives only in
`SecretStore`. JGit receives credentials directly for each transport operation.
Neither credential helpers nor repository credential configuration participate.
EVA pins TLS certificate verification on and disables HTTP redirects in the
managed repository.

Connect and Sync fetch only the exact configured branch without tags or
submodules. Before checkout, EVA bounds the tree to 4,096 entries and 64 MiB of
blobs, rejects symlinks, gitlinks/submodules, and Git LFS attributes or pointers,
then resolves the configuration using the normal 4 MiB/file, 8 MiB/graph,
32-visit, and eight-level include limits. The fetched pack itself cannot currently
be byte-limited by JGit's high-level fetch API; validation occurs before materializing
its tree. Git hooks are disabled.

Pull is fast-forward-only. EVA validates the fetched commit and then updates the
checkout; if applying the complete configuration to app stores fails, it restores
the prior checkout head and the existing transactional restore keeps active settings
unchanged. Divergence, a dirty checkout blocking pull, or an exact-head mismatch is
reported without merge, rebase, retry, or force push. Before every commit EVA
fetches again, clears the index, stages only owned graph paths, and passes the
configured identity directly to the commit. Push uses the expected remote head;
server rejection or transport failure leaves the local commit for a later Sync.

An interrupted atomic YAML write is recovered before reading. Every successful
connect or Sync also retries the scoped commit/push step, including when local and
remote heads were initially equal. At startup an already-enabled managed checkout
is validated and attached before network access, so its last good local configuration
remains usable offline while sync failure and pending commits stay visible. Git
operations and configuration callbacks share one serialization boundary.

The schema separates these groups:

| Group | Settings |
| --- | --- |
| `models`, `voice` | Text/realtime models, per-leg reasoning effort, lookup retry count, quiet hang-up delay, per-action call endings |
| `appearance`, `capabilities` | Dynamic color, optional capability switches, and device-task backend/model/budgets |
| `messaging` | Notification-read opt-in, exact app-installation reply identities, and `bridges`: service name to label and HTTPS origin of a messaging bridge |
| `prompt` | Source URL and complete ordered component list |
| `packages` | Repository, imported package bytes and origins, wait budgets, service bindings, applied shipped defaults |
| `services.http` | Named HTTPS origins with optional scoped local credential references |
| `extensions` | Grants bound to exact identity, digest, and mutation names |
| `spotify` | Public client ID |
| `credentials` | Required scoped secret references and endpoints, never credential values |
| `remembered` | Saved number-choice preferences |
| `device` | Desired device authorizations to check on the destination |

An `include` list composes relative files under the selected folder. Includes are
applied in order and the including file overrides them. Scalar settings merge by
field; collections replace as a whole, so an explicit empty collection clears
inherited entries. Includes cannot escape the folder, form cycles, or exceed
eight include levels, 32 file visits, 4 MiB per file, or 8 MiB across the graph.
Every file must declare `format: eva` and a supported version; unknown fields are
rejected. EVA writes version 3. Versions 1 and 2 remain readable; legacy bundled
package references are reported for manual reinstall and omitted on the next complete write.
UI writes retain includes and store local overrides.
They normalize the root YAML and remove its comments; included files are not rewritten.

Imported extension JSON remains exact text rather than a re-encoded approximation:
restoring configuration must preserve the bytes whose digest and identity were
approved. Missing credentials or target applications must not erase desired
configuration. Restored grants authorize only the matching installed identity and
contract; they cannot confer Android permissions or trust a different signer.
Messaging reply grants likewise require the exact installed app identity. Missing
identities remain in the repository and are reported for setup; a different device
or reinstalled app requires fresh approval.

### Reusable HTTP services

`services.http` defines named origins and optional credential references.
`packages.serviceBindings` maps each package's declared source origin to one of
those services. Several packages can share a service; a package can map each of
its HTTP origins separately. A binding must name an origin declared by its
package, and credentials remain restricted to the destination service origin.

For example, an override can bind two packages from a shared base to one service:

```yaml
format: eva
version: 2
include:
- shared/base.yaml
services:
  http:
    agenda:
      origin: https://agenda.example.net
      credential: service/agenda/basic
packages:
  serviceBindings:
  - packageInstance: 00000000-0000-0000-0000-000000000021
    sourceOrigin: https://agenda.example.org
    service: agenda
  - packageInstance: 00000000-0000-0000-0000-000000000022
    sourceOrigin: https://agenda.example.org
    service: agenda
credentials:
  required:
  - id: service/agenda/basic
    kind: http-basic
    endpoint: https://agenda.example.net
```

Use the package instance IDs from your own export. The example assumes both
packages are defined in the base and declare `https://agenda.example.org`.
Collections replace inherited collections, so retain other bindings and credential
references you still need when constructing an override.

A messaging bridge is declared under `messaging.bridges` and requires a matching
`messaging/<name>/bearer` entry of kind `http-bearer` in `credentials.required`
with the bridge origin as its endpoint. The name is lowercase and cannot be `sms`
or `notifications`. The token is entered under **Messaging → Messaging services**
and stored only in the encrypted secret store, scoped to that origin; changing
the origin invalidates it. Restoring the configuration keeps the bridge and
reports the token as a provisioning need until it is entered on that device:

```yaml
messaging:
  enabled: false
  replies: []
  bridges:
    whatsapp:
      label: WhatsApp
      origin: https://bridge.example.ts.net
credentials:
  required:
  - id: messaging/whatsapp/bearer
    kind: http-bearer
    endpoint: https://bridge.example.ts.net
```

Provision the local username/password or Bearer token through the extension's server settings.
The **Service name** field identifies the reusable service. The repository stores
only `service/<name>/basic` (`http-basic`) or `service/<name>/bearer`
(`http-bearer`) and its approved origin; it never stores the credential
value. The package declares `credentialScheme` (omission retains Basic auth);
service bindings must match it, and execution rechecks the credential scheme and
origin. Bearer tokens use the encrypted extension secret store, never the model
credential namespace. Restores retain references and report missing local secrets.
Legacy Basic records and per-package service entries remain readable for migration.
Changing the approved origin changes the package digest and grant boundary.
Dawarich is an optional catalog package, not a shipped default, because each user
must configure a personal reachable HTTPS origin and API key. Its HTTP and
configuration paths have JVM coverage; this does not establish phone verification.

### Restore and edit contract

Resolve and validate the complete include graph before changing the active setup.
An invalid folder selection must leave the previous link and configuration usable.
Apply package definitions and refresh discovered descriptors before restoring grants;
match the live instance, authenticated identity, contract digest, and mutation names.
Retain unavailable desired settings and report setup still required rather than
silently removing them on the next save. Removed bundled package references are
reported for manual reinstall and reapproval.

A restore spans several existing stores. Preserve a before-state for rollback,
including the prompt location; importing configuration must not overwrite a
previously selected standalone prompt document. In-memory state and persisted
settings must agree after a successful restore or rollback. If a rollback write
also fails, EVA attempts the remaining independent rollback steps and reports
which stores could not be restored. This is exception recovery across stores; it
does not provide a single crash-atomic transaction across Android preferences,
package files, and prompt files.

Linked edits are serialized and compare the resolved content fingerprint before
writing. A newer external change produces a visible conflict and reload path;
it must not be silently overwritten by an older in-memory snapshot. A pending
UI edit must not cancel an in-progress commit or its rollback. File replacement
uses a verified temporary file and recoverable prior copy; readers must recover
an interrupted replacement before beginning another write.

### Prompt composition

The prompt is an ordered YAML list of components. EVA assembles enabled components
applicable to the session, and does not silently append hidden default instructions.
First run and reset use compiled defaults in `PromptDefaults.kt`. Components can
supply instructions, replace tool descriptions, or hide existing tools; they cannot
create executable capabilities or permissions.

```yaml
components:
- id: identity
  title: Identity
  instruction: Help conversationally and report what the action result says.
- id: spoken-style
  applies: voice
  instruction: Keep spoken replies short.
- id: clock
  instruction: '{{clock}}'
```

| Field | Meaning |
| --- | --- |
| `id` | Required unique identifier; lowercase letters, digits, dashes, slashes |
| `title`, `summary` | UI text; title defaults to the ID |
| `enabled` | Defaults to true |
| `applies` | `voice`, `text`, or `both` (default) |
| `slot` | Alternative group; at most one member may be enabled |
| `instruction` | Text; wrapped lines join, blank lines separate paragraphs |
| `describe` | Tool ID to description override |
| `hide` | Tool IDs withheld from the model |

`{{clock}}` and `{{lookup_retries}}` are the supported variables. Unknown keys,
variables, duplicate IDs, and incompatible enabled slots are errors.
The stock call instructions offer one-request and open-conversation alternatives in
the `call` slot; the Settings switch "End calls after one request" edits that slot,
and every voice launch follows it. A retired `voice.oneShotExternal: false` is
migrated into the slot when a configuration file is read. The voice control
`eva.session.end` ends the attachment, not remote work. A request may take several
exchanges, and the model decides when it is fully served. As a backstop in
one-request mode, once the request's phone action completed or was handed off and
the response reporting it has finished playing, EVA hangs up if the user does not
start speaking within `voice.quietHangUpSeconds` (default 5; 0 disables it). A hang-up the model proposes
in the same response as an action, or while an action result is unreported, is
answered as not executed and happens after the next response instead, so the
result is spoken on the call rather than re-homed. Each attachment's closing
notice says who or what ended it.

Some actions hand the phone to something that needs its audio or screen, so a
successful one ends the call in either call mode. A capability declares
`endsVoiceCall` (`never`, `after_reply`, or `immediately`); placing a phone call
and playing media declare `immediately`. `voice.endCallAfter` maps capability IDs
to the same values and overrides the declaration; the settings pickers beside each
extension action and under Device assistant edit that map. The effective choice is
fixed when a voice call opens, and EVA appends a note from `eva-wording.yaml` to that
tool's description so the model says its closing line first. With `immediately`,
EVA withholds the result from the model (the receipt is still journaled), completes
the turn, and hangs up once current speech has played. If other actions were
proposed in the same response, their results must still be spoken, so all results
are delivered and EVA hangs up after the reply instead. `after_reply` delivers the result
and hangs up when the response answering it ends. User speech before then cancels
the pending hang-up. A refused, failed, or uncertain action never ends the call.

The Instructions screen supports a user-picked YAML file or EVA's own external-files
copy, and a raw HTTPS source the prompt follows. The default source is
`https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-prompt.yaml`.
Following it is a trust decision like following an extension repository: when EVA
starts or comes to the foreground, at most every 15 minutes, it fetches the source
and merges it three ways against what the source said last time (kept locally; the
shipped copy until the first fetch). Components the user has not edited take the new
wording; edited and user-added components, deletions, and every on/off switch are
kept; a new component never turns on beside an enabled slot member. A background
failure leaves the file as it is. The stock prompt is a byte-identical copy of the
catalog file shipped as a resource, so first run and reset work offline; reset
drops edits and the next follow brings the copy up to date. Trailing line breaks in
text are not significant.

`eva-wording.yaml`, beside the prompt in the same source directory, holds the
model-facing wording of EVA's own tools (description and parameter descriptions by
tool ID) and of the notes EVA sends the model, such as the continuation note for a
re-homed turn. It is shipped the same way and followed with the prompt; a source
without one leaves the last wording in place, and a missing note falls back to the
shipped copy. Code keeps tool identity, schema structure, validation, and the
refusal messages that state enforced policy. Extension and media-app tools keep
their own untrusted wording. A prompt component's `describe` still rewords a tool on
top of this file. UI writes normalize YAML and remove comments; file-based
editing is preferable if comments must survive.

## Desktop host

`:eva-desktop` is a text-mode EVA for a desktop computer, built on `:eva-core` like
the phone: the same `ThreadController`, `CapabilityDispatcher` and journal semantics,
OpenAI Responses provider, and memory tools. `eva-desktop` (`just desktop`) offers
`tray`, `summon`, `chat`, `threads`, `login` (ChatGPT device-code sign-in), and
`logout`. One single-threaded dispatcher confines controller calls, as the phone's main
thread does: the Swing event thread in the tray app, a dedicated thread in the terminal.

- `tray` is a Compose Multiplatform Desktop window (Material 3, the toolkit the phone
  uses) with a panel icon that toggles it. The icon is a StatusNotifierItem over D-Bus
  (pure Kotlin through dbus-java) where a panel runs a StatusNotifierWatcher, as on
  Wayland compositors' bars and KDE; otherwise the X11 system tray; otherwise none, and
  closing the window quits. `summon` asks the running app, through a socket in the
  private data directory, to show its window, for a desktop keybinding.
- Compose's Skia renderer needs `libGL`, `libX11`, `fontconfig`, and `libstdc++` at run
  time. The dev shell exports them as `EVA_DESKTOP_LIBRARY_PATH`, kept out of the
  Android tools' environment, and `just desktop-run tray` uses it.
- One process owns the storage: the tray app, a chat, `login`, and `logout` exclude each
  other through the lock; `threads` and `summon` do not need it.
- Local MCP servers listed in `$XDG_CONFIG_HOME/eva/mcp-servers.json` (the
  `mcpServers` form other MCP clients use) run over stdio as extensions of the shared
  `ExtensionRuntime`. Their tools pass EVA's extension schema rules: nullable optional
  parameters become optional, optional parameters EVA cannot express are left out, and a
  tool whose required parameter cannot be expressed is listed as unavailable. A plain MCP
  server makes no effect claims EVA relies on, so every tool's effect is unknown and needs
  its own grant (`eva-desktop allow SERVER [TOOL...]`, `deny`, `tools`). Grants bind to the
  server's command and arguments and to its tool contract, and live in the private data
  directory. Calls run through the dispatcher and journal: the tool's own error flag is
  FAILED, a request that never left EVA is NOT_EXECUTED, and a lost reply is UNKNOWN.
  Text results reach the model; images and other blocks are counted, not shown.

- Storage follows the XDG base directories under `eva/`. The journal is SQLite through
  JDBC with the phone's schema (version 7), so claims, expected-state transitions,
  startup recovery, and uncertain outcomes behave the same; an executed call ID is not
  run again after a restart. ChatGPT tokens and memory notes are files only the user
  can read, replaced atomically.
- Tools are the memory tools and `eva.desktop.open_url`, which hands an http(s)
  address to `xdg-open` (or `open` on macOS) and reports `HANDED_OFF`, not a loaded page.
- The prompt is the catalog's `eva-desktop-prompt.yaml`, shipped byte-identical in
  `eva-desktop/src/main/resources`, because prompt components cannot yet vary by
  platform and the phone prompt names Android. Tool wording is shared
  `eva-wording.yaml`.

Not yet on the desktop: following the instruction catalog, `eva.yaml` configuration,
declarative packages, HTTP MCP servers, MCP image results, voice, a D-Bus tray menu, and
a grant screen in the tray window. The terminal host is verified live against the subscription backend on Linux.
The tray window is verified on an X11 desktop without a tray (window-only), and the
StatusNotifierItem against an embedded D-Bus daemon; a real panel on Wayland and the
X11 system tray are not yet verified, nor is macOS. The MCP client is verified live with
`computer-use-linux`: EVA listed its tools, ran a granted read through the dispatcher,
and answered from the result.

## Verification boundaries

Focused JVM tests cover core behavior and Android hosts through fakes/Robolectric.
Synthetic speech instrumentation exercises live providers and action dispatch;
it does not establish acoustic quality or natural interruption behavior.
Recorded physical-device checks cover selected voice, assistant, and extension
flows, not every API surface. See [Operations](operations.md#device-verification)
for repeatable procedures and evidence limits.

When changing behavior, update this document's current contract rather than adding
a new status appendix. Keep proposed APIs visibly separate from implemented ones.
