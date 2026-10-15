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
thread history: the last 40 items, after a note saying how many earlier items are not
shown ("at least N" when the store read of 200 items was itself bounded); an earlier
answer that was cut off says so. Voice can also hand a long request to that leg with its
continue-in-text session tool. The text leg receives a fresh catalog from the
same capability registry, including enabled extensions and a reserved ask-user control slot, and retains the turn's
action claims and receipts. Turn IDs belong to the store rather than a provider's session-local
counter. The SQLite journal links requests, tool calls, receipts, and responses.
Each text leg is its own thread item recording the delegated task, the exact
instructions it was opened with, and how many history items seeded it; the calls
it proposes carry its ID. Every delegation opens a fresh leg seeded from thread
history rather than resuming an earlier leg, so the thread stays the one source
of context. The UI projects these records into grouped turns and session notices,
and shows each text leg as a collapsible block inside its turn, open while it works,
holding the leg's own actions and its prompt.

One attachment is live at a time. Selecting or creating a thread during a text
connection ends the old attachment with a switched-conversations notice and opens
a fresh text session on the shown thread using the same provider link and that
thread's history. Send waits for that session to connect. Accepted work on the old
thread continues in the background. Browsing during voice leaves the call attached
to its original thread; the composer is disabled with an explanation while another
thread is shown. Pending submission state clears when the task starts and whenever
the attachment ends or reconnects. Foreground request admission and working
indicators follow the shown thread; background work alone does not block a new
foreground text request. Answered turns without request or response text are hidden;
request-only turns show no synthetic answer bubble, while action evidence remains.

The navigation drawer links to a separate **Conversation archive** instead of
listing individual threads. The archive opens past conversations for continuation.
**Archive conversation** in the conversation's options menu starts an empty thread,
preserving prior history and accepted background work. It ends an attached voice
call so the old call cannot keep using the cleared context; text reconnects to the
new thread. This clears conversation context, not saved memory or configuration.

Typed input while the shown thread's foreground request runs steers that request
(`ConversationSession.steer`). The Responses session adds it as user input to the
turn's next model request, after any pending tool outputs; input that arrives while
the final answer is generating continues the same turn, so the model answers it
before the response ends. A steered message is stored as a user message in the same
turn and shown as its own entry, holding the answers and actions that came after it.
Input the running response cannot take (the response already ended, a provider such
as the paired broker that cannot steer, or a submission still starting) waits,
listed above the composer, and is sent as the next request when the current one
ends; anything still waiting when the attachment ends is named in the provider
message instead of dropped silently. A running device task takes typed input as a
correction first.

Several turn tasks can be active in the same
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
retries an uncertain mutation. Ordinary mutations serialize across turns on the same thread;
`eva.device.task` runs outside that lock under its own device lease, so background
SMS and HTTP actions can proceed while it runs. A mutation waiting for the thread
lock reports that it is queued after three seconds through a voice lifecycle note
and the conversation status; acquiring the lock sooner cancels that delayed notice.
Device-lease waits report their queue status immediately. An UNKNOWN or FAILED mutation in any still-running turn blocks further mutations
while that turn remains active. Read-only tools remain available for verification.

Voice offers `eva.session.background_status` (no arguments) and
`eva.session.background_cancel` (`taskId`). Status lists this thread's background
and delegated tasks plus any running device task, followed by up to five recently
finished tasks from the last 512 thread items. It includes task text, action count,
last action status, and three recent attributed receipts (call ID, capability,
arguments, status, and message) for verification. The result stays below the model
result budget; `tasksOmitted` and `historyLimited` expose omitted or partial history,
and a field shortened to fit (task 256, IDs 200, arguments 400, message 600 characters)
ends with `…[Truncated by EVA]`.
Active states follow the Running work snapshot, including waits and `RELEASING_DEVICE`;
`looksStuck` is a separate flag restricted to working or connecting tasks;
recent terminal states are `ANSWERED`, `FAILED`, or `INTERRUPTED`. Cancel requests interruption of exactly that active task,
including its owned device task; unknown, finished, or other-thread IDs return
`NOT_EXECUTED`. Cancellation does not undo actions already started; their receipts
still determine effects. `ANSWERED` means the text leg answered, not that every
action succeeded.

Background text legs (delegated or rehomed) also offer `eva.session.ask_user`.
A valid call records an independently identified question and returns immediately to
provider event collection, withholding its tool result. Responses waits locally after
its HTTP/JSON or subscription HTTP/SSE exchange has ended; no request or mutation
lock remains open for this wait. Sibling calls in the same response can execute,
but the next inference waits for every call's result. Agents are instructed to ask
before proposing actions that depend on an answer. Multiple questions, including
text and device questions on the same turn, retain separate identities.

Questions are ordered by arrival per thread. Voice is given only the oldest pending
question, followed by the next after resolution. `eva.session.background_answer`
requires `taskId`, `questionId`, and `answer`; it validates ownership and consumes the
question once. COMPLETED means the answer was accepted by the waiting agent, not
that the task completed or that all sibling calls finished. It resumes the same leg
through the withheld tool result, without a fresh delegation. Background status
includes the pending question IDs, source, and text. The composer names the current
question and pending count, accepts answers without a provider connection, and binds
a draft answer to the question shown when typing began. A stale answer never answers
a newer question. The legacy device revise tool still accepts answers.

Questions and resolutions are structured thread events, nested under their text leg
when one exists and separate from its final answer. Each resolution retains the
question, source, task/leg IDs, answer provenance, and a linked transcript item when
available; a model relay does not invent another user message. History projects these
exchanges as quoted data and keeps the full exchange even if the earlier question
event falls outside its window. Answering through another route sends attached voice
a non-responding resolution note. Pending state is in memory: after process death,
recovery records unanswered exchanges as interrupted, never resumes their calls or
replays actions. Existing thread records remain readable.

Question waits are NEEDS_INPUT, excluded from stall detection and automatic quiet
hang-up, and retain existing work-service coverage. Stop, failure, force stop, and
coverage loss invalidate questions before draining sibling actions. A provider that
ends a text response with an unanswered question fails explicitly. Questions hold no
mutation lock and answering never clears mutation uncertainty. Android can still
terminate background coverage, and API-key continuations depend on retained server
history; a failed continuation is reported rather than restarted. These flows are
JVM-tested with fake transports; device verification remains separate.

When a background turn answers, fails, or is interrupted, EVA sends a lifecycle
note to a voice attachment on the same thread with its task ID and terminal state.
Answers, partial findings, and failure reasons are JSON-quoted data under EVA's
followed wording. Findings are bounded to the model result text budget. Realtime
puts the trusted instruction in a system item and the attributed JSON in a separate
user item marked `contentTrust: external_data` with the background-task source.
It requests an announcement with `tool_choice: none`; the controller
also marks that turn announce-only and answers stray calls by asking for a user
request. The next spoken request has the normal tools. Announcements wait until no
response, user speech, or assistant audio is active. A pending device task does
not prevent announcements or other calls from receiving follow-ups. Startup
failures return only the handoff refusal, without a duplicate spoken lifecycle
note. Queued notes do
not interrupt a foreground response. Every background result retains its notification
until the announcement input ends successfully; queue acceptance alone does not
count as delivery. Rejected context items, interrupted announcements, providers
without `submitContext` (currently Responses and Broker), and threads without
voice retain that fallback. Failed or interrupted notifications and thread
notices retain accumulated partial answers and identify the interruption.

`VoiceSessionService` owns foreground voice lifetime; `TurnWorkService` reserves
execution coverage at voice-session start while the Activity or assistant window
provides start eligibility, and retains it across utterances. Voice-turn acceptance
does not wait for promotion. Typed turns and delegation still await coverage.
A single host transition coordinates both services, keeps work coverage through
the whole call, and rechecks promotion before stopping voice coverage. Work coverage survives call end and audio-focus loss and
remains until the last task's dispatched actions and terminal journal writes have
drained. `ForegroundServiceGate` still postpones an early stop until promotion.
If Android refuses renewed coverage, affected turns release their coverage demand
while actions and journal cleanup still drain. Later work can acquire coverage
again; interrupted cleanup cannot trigger a restart.
Android can still interrupt background work; process recovery records interruption
and never replays an action.

**Running work**, reached from the drawer, Settings → Background work, or the
ongoing notification, lists tasks across all threads. Its one source is
`ThreadController.taskSnapshots`: stable thread/turn IDs, thread title, request or
delegation text, kind, execution state, start and last-progress timestamps, action
count and last action status, device-lease ownership, and actual work-service
coverage. Finishing tasks remain visible while receipts drain. Voice background
status uses the same active snapshot state and retains its attributed recent
receipt/history projection. Open thread navigates without cancelling work.

Stop and Stop all use the existing interruption path: cancellation requests drain
started actions and preserve their truthful receipts. Force stop cancels the task
scope immediately and records `INTERRUPTED` with
“Force-stopped by you; actions already started may have had effects”. An already
answered turn retains `ANSWERED` and receives the notice. Device ownership remains
exclusive while backend input unwinds, shown as **Releasing device…**, for up to
ten seconds. If it does not return, the successor receives an uncertain-screen
notice and must observe afresh before ordinary device mutations; a new device
worker starts from a fresh observation. Late cleanup cannot release a successor's
lease. Post-force-stop receipt waiting is bounded to ten seconds; a second Force
stop skips the remaining receipt wait. Remaining calls are recorded `UNKNOWN`,
the task finishes and releases coverage, and late results can still update receipts.
Neither stop path retries actions or claims to undo effects.

Settings → Background work edits `capabilities.stallPeriodSeconds` in the same
portable configuration model (default 180 seconds, positive, no maximum duration
cap). The value composes, synchronizes, and restores with the other capability
settings. Provider events attributed to a task, action starts/completions, and
device progress refresh its progress clock. A one-second host tick marks inactivity
as **looks stuck** only for working/connecting tasks; waits, requests for input,
and stopping are never flagged. Device owners are working, while queued tasks
wait for device control. Only attributed provider events count as progress.
Streaming progress updates the clock silently; a one-second tick publishes it,
while state changes publish immediately. Only task-list/settings screens collect
the snapshot flow. This is advisory and never kills work. Device-task step/time
budgets continue to apply.

A turn can run successive native or extension reads and mutations without another
user message. EVA does not cap the number of calls a turn or its background leg
makes; the user stops runaway work from Running work. Every call retains
dispatcher validation, grants, and journaling. EVA sets no length limit on typed
requests, handoff tasks, device-task goals or corrections, and receipts record the
request whole. The model's context window is the only bound: when OpenAI reports
an overflow, EVA says the request is too long for that model's context, with the
window size when OpenAI states it.
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
session so a reconnection inside the disconnect grace period stays quiet. The start
cue plays on the live call route and the end cue on the assistant stream, since the
session has already handed the call route back by then; neither is muted by silent or
vibrate mode, which silences sonification. Losing audio focus to another app, such as a phone
call or music EVA just started, ends the call instead of pausing it; a duck request
such as a notification sound does not. Text and voice share capability execution. Realtime
input identity must survive late transcripts and asynchronous tool events:
transcript arrival order alone cannot establish which request owns an action.
Tool correlation carries connection/session, input, generation, turn, catalog,
and call identity; stale calls cannot gain authority through reconnection.
Each Realtime response ID has an immutable origin. Output-item, assistant-text,
and completion events resolve their own response ID, including late events after
a newer response starts. Completion output also supplies calls, deduplicated against
item events by session-unique call ID. Calls wait for a completed response and a
completed item with valid arguments; cancelled or incomplete output returns
NOT_EXECUTED. An item missing a response ID can acquire its exact origin from the
final response output before it is answered. An unrecognized response cannot
borrow the latest request: its call
receives a correlated, journaled NOT_EXECUTED result asking for a new user request.
Every EVA-created response, including speech, carries request, input, purpose,
initiator, and applicable parent-response/speech-item metadata. Server VAD keeps
`interrupt_response: true` for barge-in and uses `create_response: false`; EVA
requests each speech response immediately on the committed audio item's explicit
ID, without waiting for transcription or guessing response ownership from FIFO
order. This adds a data-channel exchange before response creation. On speech start,
EVA cancels active responses and clears their buffered audio. It also marks requests
still awaiting acknowledgement: if one is created after the user resumes speaking,
EVA cancels it immediately and treats its eventual output as interrupted even if
the server reports completion. Interrupted tool-result follow-ups and lifecycle
announcements stay queued behind the resumed speech, preserving their original
input; tools from the interrupted response cannot execute. These rules have raw-event
provider/controller replay coverage; device latency and barge-in remain unmeasured.
Speech waiting state clears on commits, buffer clearing, transcription failures,
and seed cancellation. A 30-second recovery timer starts only after speech stops,
so uninterrupted dictation keeps queued replies silent. A transcription failure
for committed audio reports only an unavailable caption and cannot release a newer
speech input.
Unacknowledged response requests release the queue after 10 seconds with a visible
notice. Errors tied to EVA's response/item event IDs are recoverable notices;
unattributed session errors still end the connection. User captions are retained,
and assistant transcripts without a response ID use the sole active response when
unambiguous. Completed correlation records retain a bounded 256-entry history while
pending work keeps its identities. Only the provider's terminal input event ends a
voice turn; starting another response cannot close its queued follow-up.
Realtime asks for a spoken follow-up when the non-device calls from that response
resolve. A pending device task does not block sibling results or lifecycle notes.
A follow-up requested while
another response is active waits for it to finish. A native tool that declares a
boolean `quiet` parameter (screen tap and text entry) lets the model skip the
follow-up: a COMPLETED quiet call ends the input silently, while any other status
is still reported for the model to explain.

Provider-independent history preserves action provenance and distinguishes
external tool content from EVA's outcome envelope. Action records separately retain
the initiator (user speech, typed request, text-agent leg, device-task worker, or
lifecycle-note reply) and the applicable input, response, item, and leg IDs. Android
and desktop journal version 8 preserves this identity across restart; older records
keep an absent initiator rather than inventing one. Android refuses a newer journal
schema with a visible instruction to install a newer build, preserving the database.
Action details show a small
“from” label. Provider output is not proof
that an operation completed. Interrupting speech, ending a call, canceling local
work, and undoing a remote action are distinct operations.

## Capability execution

Adapters contribute capabilities to a registry snapshot. Settings switches and the
assembled prompt's `hide` lists remove tools first, so hidden tools take no capacity
(a hidden `eva.device.task` also drops its revise/stop controls). Admission is
deterministic with a shared 512-tool safety bound, including session controls: the
highest count verified with subscription Responses and Realtime, not a published
provider maximum. Voice
reserves five base session controls (end, defer, background status, background cancel, background answer),
plus device-task revise/stop when `eva.device.task` is offered. Bundled native tools
come first, then whole installed-service extension groups, then remaining whole
extension/package/media groups sorted by stable source identity (capability ID prefix
when source metadata is absent). Tools within each group are sorted by ID. Installed
services therefore precede declarative link/intent packages, including packages for
the same app. A group that cannot fit is skipped and smaller later groups are tried;
admission never splits an extension or package instance's offered workflow. Overflow
reasons report typed and voice admission separately, since skipping a larger group
can leave room for a smaller group in only one mode. Unavailable or excess entries
remain explainable in the UI. Extensions shows admitted phone/extension counts and an
always-visible list of excluded actions with mode-specific reasons. An attached session
or background text leg with exclusions persists “N tools unavailable: A, B, C and K more
— see Extensions” in its session-start notice, which opens Extensions when tapped. The
same selection supplies the session catalog, the excluded IDs and titles, and the model
note (`catalog-unavailable` in `eva-wording.yaml`), which lists up to 20 excluded titles
as quoted data plus a count of the rest. Settings runs the same selection per mode after
removing that mode's prompt-hidden tools.
No speculative cost/response-quality warning band is used: the measured latency is
configuration latency, not response latency. New tools reach the model on the next connection;
revocation blocks new execution immediately even if the model still sees an older catalog. For packages
refreshed from a followed repository, newly named actions are granted when that
package's auto-enable switch is on; explicitly disabled actions remain disabled.
Manual imports do not gain new grants this way. Installed Android providers do not
either, except the pinned default providers (Mova, Paseo, and Soundcore Actions): they start enabled
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

Voice sends the whole session configuration, including instructions and every tool, in
the multipart `POST /v1/realtime/calls` request
([WebRTC guide](https://developers.openai.com/api/docs/guides/realtime-webrtc),
[create call](https://developers.openai.com/api/reference/resources/realtime/subresources/calls/methods/create)).
That request has no message-size limit and OpenAI applies it in full. Only the
`session.created`/`session.updated` echo travels over the data channel, so only the echo
is bound by the offer's advertised `max-message-size`. EVA's pinned native WebRTC SDK
advertises the upstream
[256 KiB SCTP bound](https://webrtc.googlesource.com/src/+/refs/heads/main/api/sctp_transport_interface.h)
([offer generation](https://webrtc.googlesource.com/src/+/refs/heads/main/pc/media_session.cc)).
OpenAI does not send an echo larger than that: an earlier probe at the native bound
received a 262,140-byte echo for 512 tools with 224-character descriptions and none
for 225, and a probe advertising aiortc's 64 KiB default stopped at a 65,532-byte echo.
A missing echo is therefore not evidence that the configuration failed.

A live subscription probe on 2026-10-01 (aiortc advertising the native 262,144 bytes,
synthetic silence, EVA's model, voice, transcription and headers, with a code word in
the instructions and a per-tool suffix in each description) confirmed that oversized
configurations are applied:

| Functions / description characters | Initial config bytes | Data-channel echo | Sideband echo | Model call to the last tool |
| --- | ---: | --- | --- | --- |
| 128 / 300 | 59,050 | created with 128 tools | 128 tools | `eva_tool_127`, code word and suffix correct |
| 512 / 300 | 235,306 | bare created, then configured update | — | `eva_tool_511` |
| 512 / 600 | 388,906 | bare created only | 512 tools | `eva_tool_511`, code word and suffix correct |
| 1024 / 300 | 470,338 | bare created only | 1,024 tools | `eva_tool_1023`, code word and suffix correct |
| 2048 / 200 | 736,605 | bare created only | 2,048 tools | response `incomplete` (`max_output_tokens`) at ~106k input tokens |
| 4096 / 100 | 1,064,285 | bare created only | 4,096 tools | not measured |

The data channel kept working after a suppressed echo. The call request took 0.9,
1.5–1.8, 3.0–3.4, 5.1–5.5 and 9.8 seconds at 128, 512, 1,024, 2,048 and 4,096 tools.

Voice admission has no byte budget. A configuration whose echo fits the advertised
receive size minus 8 KiB (64 KiB when the offer names none) is confirmed by that echo.
A larger one is confirmed through a
[sideband WebSocket](https://developers.openai.com/api/docs/guides/realtime-server-controls)
to the same call (`wss://…/v1/realtime?call_id=` from the response's `Location` header,
same authorization; subscription access works): EVA sends an unchanged `session.update`
(`tool_choice: auto`), checks the echoed tool names, and closes the sideband, which
leaves the call running. The sideband is a second network dependency of setup, so its
failure is loud but not fatal: when it errors, does not answer within the
eight-second window, or cannot be addressed because the call ID is missing, the call
continues on the session OpenAI created and EVA shows a notice naming the tool count,
size and cause, also kept in the thread after the session-start notice, since the configuration was sent whole and nothing indicates it was
rejected. Falling back to a byte budget instead would drop tools OpenAI already
applied and would need a second call. A confirmation that names the wrong tools, or no
session at all within eight seconds, still fails the connection with the tool count,
byte size and cause. History acknowledgement remains a separate gate. EVA sends no later
`session.update` over the data channel.

The 512-tool bound stays because it is the highest count verified with both providers;
Realtime accepted more, but at 2,048 tools the prompt left the model no room to answer,
so the context window, not a count, is the real ceiling beyond it.

These probes verify configuration acceptance and that the model can call the last
tool, not tool-selection quality, Android execution or audio behavior. Public API-key
live acceptance, the sideband on a phone, and the pinned Android SDK's actual on-device
SDP remain unverified.

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
after that the task stays on that backend, so no mutation is repeated elsewhere.

The conversation bar and Settings show each backend's health, which direct screen
tools share, and a running task's phase. Health is failure-driven
(`ScreenControlMonitor`): a set-up backend is assumed ready. Every device backend is
wrapped in `ReportingDeviceBackend`, so a real screen read or input that fails because
of the backend (an exception, `BackendUnavailable`, or a timeout; not a protected or
changed screen) marks it unhealthy with the backend, reason and time, such as
"Shizuku couldn't read the screen: … · 3 min ago"; the next successful read or input
through it, including a plain direct observe, clears that. Missing
setup (Shizuku not installed, stopped or not allowed; no Portal token) is a separate
setup-needed state with its fix-it action. The five-second probe while EVA is in front
is secondary evidence: a failing probe marks a backend degraded only when nothing has
worked through it since the probe started failing, and a passing probe never clears a
real failure. A backend that was never checked reads "Not checked yet."

Tapping a route that is not ready repairs it (`ScreenControlRepair`); Settings has a
matching Reconnect button. Missing or rejected Portal tokens open Screen control
settings. If Portal is unreachable, or answers but cannot read the screen, EVA's
Shizuku helper turns Portal's accessibility service on. If the service was already
listed, or Portal stays silent after it is turned on, the helper turns it off and on
again. Then EVA waits for Portal's probe and a screen read. When neither fixes it, the
tap opens Portal with what to check, never just a message. Without Shizuku access, EVA
opens Accessibility settings instead. If Shizuku is allowed, the repair replaces the helper process, which
also drops a wedged UiAutomation connection. It does not do this while a device task
is running. Missing access prompts for it. A stopped server opens Shizuku. Shizuku
resends its binder only on EVA's process or UID transitions (its process and UID
observers), not to an EVA that stays in front. Returning from Shizuku is such a
transition. A repair reports fixed only after a real screen read through the backend
succeeds, and that read updates the route's health.

Android drops a force-stopped app's accessibility services from the enabled list, and
Play Store force-stops sideloaded Portal every few days. So when Portal's check finds
it unreachable and its service is no longer listed, EVA puts the service back through
Shizuku without a tap. This happens at most once a minute, both when a device task or
screen action is admitted and on the probe while EVA is in front. A service that is
listed but silent is left for the tap repair, so that EVA does not toggle it repeatedly.

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
per-step kind, a readable description (password text redacted), the worker's stated
intent, the serving backend, result and observation/model/action timings. Step receipts
also retain the device-worker initiator, task/leg ID, call ID, and available
response/output-item IDs, separate from the outer call's initiator. The conversation
lists every step under the task's action row, live from the running task and
afterwards from the receipt. A stop before any
completed effect is NOT_EXECUTED; known partial work is FAILED; unresolved work is
UNKNOWN. Journal recovery never replays a task.

The conversation model can also act on the screen itself, one input per call,
without the worker: `eva.device.observe`, `tap`, `set_text`, `scroll`, `press_enter`,
and `navigate` (Back, Home, notification shade). They run through
`devicecontrol/ScreenActions.kt` on the same `PortalBackend` as tasks, so rechecks,
settling and text read-back are shared. They use the backends in the same order as
tasks: each call starts at the preferred backend, and one that is not ready or passes
its check but cannot read the screen hands over to the next before any input of that
call is sent. An input goes to the backend whose observation it names and is never
repeated on another; when none can read the screen, nothing is sent and each backend's
reason is returned. Element inputs name an observation
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
voice/provider attachment. Competing tasks and operations that use the foreground
UI queue in arrival order for the device lease with an immediate queued notice;
background SMS, HTTP, and other operations that do not use that UI can continue;
one cancelled while queued is journaled NOT_EXECUTED. Android intent launches
also recheck whether a task is running. Within a turn, mutations run one at a
time in proposal order except for the separately leased device task. Read-only
calls run without waiting behind mutations, so
the voice model can look things up during a long task. Progress reaches the
owning thread independently of the pending tool result. Typed corrections go
straight to the running task's mailbox. In voice, the model routes new speech:
the intercepted `eva.device.task.revise` and `eva.device.task.stop` tools revise
or stop the running task (stop leaves queued work in place), and anything else
becomes its own queued action. A bare stop/cancel utterance, bound to the owner
captured when speech began, bypasses the model. The worker cannot hear the call,
so its `ask_user` joins the same ordered question queue as background text agents.
The voice model answers with `eva.session.background_answer`; the coordinator checks
the owning turn, worker task ID, question step, and revision before waking the worker.
The worker receives revisions made during its wait as its `ask_user` result.
A call attaching while a question waits receives it on connecting. Failed deliveries
are not resent; while voice does not hold the current question, speech that starts
during that wait is forwarded only if the same question is still current when its
transcript arrives. Duplicate model answers then receive NOT_EXECUTED. The Broker
provider supports neither context notes nor correlated speech starts: answer by typing.
The work notification shows the current question outside a call, and the shared voice
notification shows it during a call. Neither adds an alerting notification.
Realtime cannot withdraw queued notes; a resolution note and wording discourage
obsolete questions, while exact question IDs prevent stale answers affecting new work.
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

The work service uses Android's `specialUse` foreground-service type, declares
`FOREGROUND_SERVICE_SPECIAL_USE`, and describes user-requested assistant tasks,
delegated research, and device automation in `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
It has no EVA-side duration cap. Android currently gives `specialUse` no fixed
three-minute timeout. The manifest also declares `shortService` solely for fallback:
if initial long-running promotion is refused, EVA tries short-service promotion.
Only tasks without live voice coverage receive restriction notices; repeated limit
notifications coalesce. Opening EVA retries upgrading short coverage to specialUse.
A timeout tries only specialUse: repeating shortService promotion cannot be relied
on to extend its expired timer. If start or both initial promotions are refused,
or a timeout cannot obtain long-running coverage, the service stops promptly and only
turns without live voice coverage are interrupted, with partial findings retained.
The service handles both Android timeout callbacks without assuming their type.
Voice shutdown rechecks coverage, including when an earlier attempt was refused.

The ongoing notification shows the active task count, a task's text and elapsed
time, any **looks stuck** marker or fallback limit, Stop background work (which
leaves a live call's turn running), and a link to Running work. It uses Android's
deferred foreground-service display (about ten seconds on Android 12 and later),
so short typed work does not flash it, and it is reposted only when its text changes. During a call the work service
holds the voice notification's ID instead, so the call shows one notification;
the work notification returns when the call ends. Notification permission can hide Android notifications; thread notices and
the in-app task surface still expose restrictions. Services remain non-sticky;
force-stop and process death do not trigger replay. A foreground service is not a
wake lock, a lock-screen bypass, or a guarantee against OEM/process termination.
The assistant launch fallback, locked execution, foreground eligibility, and audio
handover require physical-device verification; JVM checks cannot establish OEM
behavior. See the [verification checklist](operations.md#background-work-and-task-manager).

### Watch wrist gesture

`:wear` is a Wear OS companion (minimum API 34, Wear OS 5; compile/target API 37)
for Pixel Watch 3-era watches with Google Play services. It shares the phone's
application ID and signing identity, including `.debug` for debug builds.
`:wear-core` holds the Android-free detector, wire types and configuration;
`:diagnostics-core` holds the existing shared redactor and trace primitives.
The provider-aware watch-origin session trace remains in `:eva-core`. `app/wear/` owns phone admission and sync; `wear/`
owns watch sensing, its UI and Ongoing Activity. No wake word or watch microphone
is used. Starting a call is the only gesture action; ending it is unchanged.

**Gesture:** two quick full wrist twists: **out, back, out, back** about the
watch's strap/forearm axis (either sign first). Each twist consists of an outward
pulse and a return pulse; the default requires all four alternating pulses.
The default is sensor Y (`axis: 1`), along the strap on a normally worn watch; a snug strap matters. Handedness and a 180-degree watch
orientation reverse signs and do not change acceptance. Axis alignment and comfort
with hands on handlebars still need Pixel Watch 3 measurements.

Every knob is under portable `wearGesture`; these are the shipped defaults:

```yaml
wearGesture:
  enabled: false
  axis: 1
  requiredPulses: 4
  candidateRate: 2.5
  peakRate: 5.5
  releaseRate: 1.5
  minPulseMillis: 45
  maxPulseMillis: 180
  minAngleRadians: 0.45
  minAxisDominance: 0.85
  minIntervalMillis: 120
  maxIntervalMillis: 400
  maxGestureMillis: 1100
  refractoryMillis: 2500
  maxSampleGapMillis: 60
  maxAccelerationDeviation: 8.0
```

Rates are rad/s; angle is radians (0.45 is about 26 degrees per pulse). A candidate
starts above `candidateRate`, ends below `releaseRate` or on sign reversal, and
must attain `peakRate`, last 45–180 ms and integrate at least the minimum angle.
Axis dominance is absolute axis rate divided by the three-axis magnitude, with a
minimum of 0.85 throughout the pulse. Peak acceleration-magnitude deviation from
gravity must stay at or below 8 m/s², using a fresh accelerometer sample. Start-to-start
spacing between every consecutive pair of pulses is 120–400 ms; first start to
fourth completion is at most 1100 ms. `requiredPulses` must be even and at least 2:
2 permits one out-and-back twist, 4 requires two, and 6 requires three. A direction
repeat or bad interval resets the sequence. `maxGestureMillis` remains an explicit
whole-sequence budget and must allow the requested count at the minimum interval
and pulse duration. Its default doubled from 550 to 1100 ms to accommodate two full
twists; all other detector defaults are unchanged. Larger counts may need an
explicitly larger whole-sequence budget and refractory period.
Long lobes are rejected whole; their tails cannot start a fresh candidate. Gaps over
60 ms, invalid samples and nonmonotonic sensor time reset partial gestures. After
a hit, 2500 ms of refractory time prevents another hit. Sustained high-frequency
angular buzz fails pulse duration/angle, broadband/off-axis motion fails dominance,
and linear buzz fails the acceleration bound. These are conservative initial
heuristics, not measured false-positive or detection rates.

The phone's `WearGestureSettings` participates in snapshot, field-by-field include
composition, overrides, restore and rollback. Missing fields in older configurations
take defaults; a missing `requiredPulses` now means 4. Existing explicit tuning,
including an old 550 ms whole-sequence budget, is preserved; set that budget to
1100 ms to use the new default timing. No schema bump is needed. Phone Settings →
**Wrist gesture** edits the enable flag; thresholds can be edited in `eva.yaml`.
`DataClient` sends the effective complete configuration at `/eva/wrist/config`.
The watch caches that exact configuration for offline/boot use. A monotonic phone
revision rejects out-of-order snapshots. Its toggle requests a phone edit over
`MessageClient`; the correlated reply carries that same effective snapshot so a
stale local DataItem cannot undo a confirmed edit. Only the latest toggle's reply
can clear a local pause. An offline watch can **pause** locally immediately;
that device-local suspension survives reboot, does not rewrite phone configuration,
and is cleared by a confirmed enable request. The ongoing notification's Pause
has the same local effect. **Enable** resumes with phone-confirmed settings.

`GestureService` is a foreground service with a Wear `OngoingActivity`, gyroscope
and accelerometer registration requesting 100 Hz and zero batching latency. It
prefers wake-up sensor variants and holds a `PARTIAL_WAKE_LOCK` for its entire
enabled lifetime, releasing it and unregistering sensors on stop. It deliberately
keeps the CPU awake, not the display. The [SensorManager contract](https://developer.android.com/reference/android/hardware/SensorManager)
requires that wake lock for prompt non-wake-up sensor delivery with the screen off;
a foreground notification alone is insufficient. A sensor-delivery watchdog stops
with an error if either stream stops for three seconds. Partial gestures never
bridge a screen-off delivery gap. Android can still terminate the process, suppress
sensors under battery restrictions, or ignore wake locks during Doze; wake-up sensors
help, but screen-off and deep-idle behavior require physical-watch verification.

The service uses Android 14+ `specialUse`, declares
`FOREGROUND_SERVICE_SPECIAL_USE` and a descriptive subtype, matching EVA's existing
assistant-work service. This is a deliberate choice over `health`: Android's
[service-type contract](https://developer.android.com/develop/background-work/services/fgs/service-types)
describes health for fitness apps, and EVA is a general assistant. Health's
`HIGH_SAMPLING_RATE_SENSORS` prerequisite would not by itself make EVA a fitness
app. EVA needs no body/health permissions and samples below 200 Hz. Publishing this
service through a store would require that store's special-use review; none was
performed here. Boot-completed and own-package-replaced receivers restore an enabled,
unpaused listener after first unlock. Sticky service recovery rechecks the cached
flag. Android force-stop prevents automatic recovery until the app is opened.
A phone-side enable arriving while the watch app is backgrounded can be refused by
Android's foreground-start rules; the watch reports that it must be opened to resume.

**Battery:** continuous CPU wakefulness plus two unbatched 100 Hz sensors and
Data Layer diagnostics will cost substantially more than passive watch sensing.
No battery percentage/hour or all-day lifetime is established. The wake lock has
no arbitrary expiry: silent sensing failure after a timeout would break the feature.
Disable or pause when unneeded; measure screen-off battery consumption on the actual
watch before leaving it enabled all day. Health Services' passive fitness metrics
cannot supply the raw low-latency rotation stream this gesture needs.

A detection gives one 35 ms haptic immediately, then sends a fresh UUID and wall
time at `/eva/wrist/start` to the uniquely reachable EVA phone capability. Messages
are not queued or retried as session starts. The phone reserves the UUID durably
before asking the active `EvaVoiceInteractionService.showSession` to show a system
assistant window. That window opens voice using the existing `AssistantStart` and
`ThreadController.connectVoice` path, including over the keyguard where Android
permits it, with the configured call mode. There is no background Activity fallback.
Before first unlock after reboot, EVA's credential storage is unavailable.

Admission checks the effective enable flag, assistant role, microphone grant,
existing voice call and pending watch startup. Stale messages (over 60 seconds or
clock mismatch) fail rather than launching later. Duplicate UUIDs return the stored
outcome; process recovery never retries a reserved start. `requested` means only
showSession was requested; `connected` is sent only on the provider's actual
Connected event. Session-show rejection, unready assistant service, loading/setup
failure and provider startup failure produce specific phone notification/trace
errors and a watch reply. Notification denial can hide the phone notification;
the watch and trace still carry the failure. Triple 100 ms haptics distinguish errors
from detection. After 30 seconds without a connected/failure reply, both sides label
the outcome unknown, with no automatic retry or cancellation of a possibly live call;
a later receipt may still update it. Service/process termination can also lose result
delivery, so a detection haptic alone never proves that voice started.

Every completed candidate is traced as `wear.gesture.detected` or
`wear.gesture.near_miss`, including reason, watch wall/sensor timestamps, axis,
peak rates, durations, integrated angles, dominance, acceleration deviations and
every consecutive inter-pulse interval when available. Traces retain all accepted
pulse features and a separate rejected candidate. Near-miss reasons include the
valid count before failure (for example `next_pulse_missing (3/4 valid pulses)`),
and trace fields include `validPulseCount` and `requiredPulses`. Candidates below
the admission rate are not near misses. An incomplete sequence expires when the
next pulse's start interval or the whole-gesture budget runs out. The watch
uses EVA's existing `EvaTrace`/`TraceLog` ring, and Data Layer DataItems act as the
offline telemetry outbox (2000 rotating slots). The phone imports them into its same
trace and deletes delivered items; its standard thread/recent-log exports include
them. These are bounded recent logs, not a complete long-term sensor recording;
offline overflow overwrites older entries. Raw samples are not stored. The watch
shows the last detection time and the rolling five-minute near-miss count since
process start. No user transcript or audio is put into gesture telemetry.

A watch UUID follows its own voice attachment. `wear.session.connected` confirms
provider connection; `wear.session.closed` retains the thread, startup/call durations,
reason, user-speech and nonempty-transcript flags. A connected call ending within
five seconds or with no observed user speech is marked `likelyFalsePositive`,
including `endedImmediately`. Failed startup is distinct and is not a false-positive
label. This is a tuning hint, not ground truth: VAD can mistake noise for speech,
and someone can intentionally open and close a call without talking.

Synthetic JVM streams cover four-pulse gestures, incomplete/late/same-sign sequences,
configurable two- and six-pulse sequences, whole-gesture expiry, road buzz,
walking swings, off-axis motion, long rotations, sample gaps, threshold changes and
refractory behavior. Configuration composition/restore and phone admission/failure
paths have JVM coverage. These do not establish real-world walking/cycling rejection,
watch-to-phone latency, Bluetooth/audio routing, keyguard startup or screen-off
reliability. See [watch setup and verification](operations.md#wear-os-watch-companion).

## Delegated phone call tasks

`com.colonelpanic.eva.call` implements the platform-neutral call-task boundary,
verified with JVM fakes. Android registers `eva.call.start`, `.status`, `.instruct`,
`.floor`, `.listen`, and `.cancel`, and composes the provider registry (cellular, then
WhatsApp, then Twilio; see the [call engine](#android-call-engine-and-transports)) with
`OpenAiCallAgentBrainFactory`. `eva.call.start` takes an optional `provider`; without
it the first available route places the call. An unknown or unavailable route is
rejected naming the routes the phone has or why the chosen one cannot call, and nothing
is journaled or dialed. With more than one route, the start tool's description lists
them (the `call-providers` wording note). A provider that is certain it dialed nothing
throws `CallNotPlaced`; the task ends NotAccomplished with that reason and the leg
Ended FAILED, instead of an uncertain setup failure. Live cellular calls are
device-verified on the owner's Pixel 11 Pro Fold against the NIST time line and the
Twilio test hotline. Started with the phone locked and its screen off
(`CallTaskLiveTest`, no UI), a NIST call task dialed, heard the announcement, finished
and hung up; the dialer's in-call screen lit the display over the lock screen for the
call, so screen-off operation itself is not shown. No speech detector
is connected: HOLD stays advisory and the brain keeps listening. Credential DTMF
resolves the owner's saved call keypad credentials (below).

`CallTaskCoordinator` owns a thread/turn's long-running start action independently
of the owner's EVA attachment. Separate state machines describe:

- Task: Briefing → Dialing → InCall → WrappingUp → Accomplished (transcript item
  refs, resolved from IDs or quotes, and attributed claim), NotAccomplished, Uncertain, or Interrupted (possibly with
  an Uncertain outcome). Claims are never independent verification.
- Leg: provider-only Placing, Ringing, Connected (with precise call-state,
  inferred-downlink or provider-assertion answer evidence), and Ended with cause.
  Goodbye and `finish` cannot end it; `hang_up` journals a request, not an observed end.
  The agent's hang-up goes through once its queued speech has played or been cleared
  (`LocalPcm.awaitAgentAudioPlayed`): tools arrive with the completed response, before the
  real-time uplink has played the goodbye spoken in it. An owner cancel hangs up at once.
  A `hang_up` before any `finish` is not executed: the task ends with the leg, so the
  outcome would be lost (seen on the device). The next `finish` hangs up instead.
  When the leg ends with no outcome while the agent has the floor (the Twilio hotline
  hung up right after goodbye), the task stays WrappingUp: the brain alone is told the
  call ended, so a callback seeded from the transcript is not told to finish, and it has
  30 s to `finish`. Without one the task ends NotAccomplished. Agent audio and captions
  after the end are dropped: nobody heard them.
- Advisory phase: IVR, HOLD, HUMAN, TRANSFERRING, VERIFYING, UNKNOWN; no authority.
- Floor: AGENT, OWNER, AGENT_STALLING, MUTED. `ownerSpeechStarted` cancels model
  output and queued local uplink audio before journal I/O, then routes the owner
  microphone. `ownerTranscript` supplies OWNER_TO_REMOTE text context. Handback is
  explicit; whispers and monitoring never acquire the speaking floor.
- Brain attachment/replacement and owner monitoring are independent of the leg.

The restricted `eva.call.agent.*` catalog is `dtmf`, `ask_owner`, `commit`,
`report_phase`, `keep_listening`, `hang_up`, `finish`. These run through `CapabilityDispatcher`, the
shared invocation journal and CALL_AGENT provenance. The brain receives no general
owner catalog or memory. Mandates are exact owner-approved commitment identifiers;
anything outside that set becomes an owner question requiring the exact answer
`approved`. Disclosures and calling `commit` before binding speech are instructions:
core cannot inspect arbitrary generated audio for agreements. A production brain
must honor these controls. All stock instructions/descriptions live in
`eva-wording.yaml`; the local additions still need upstreaming to the instruction
catalog before publication.

The agent introduces itself as calling on behalf of `callBrief.onBehalfOf`, the person it
represents by name. EVA keeps no profile of its owner, so `eva.call.start` takes an optional
`onBehalfOf` that the main agent fills from what it knows (memory, the conversation); without
it the instructions use the account holder's name from the goal and never say "the owner"
aloud. By deliberate invariant the agent does not announce up front that it is an AI, but
when the other party sincerely asks it never denies it: it answers briefly and honestly and
continues (`CallAgentInstructionsTest` checks the shipped wording). It never speaks about its
own tools, steps or state; everything it says is addressed to the other party, it answers
every direct question or request (saying it does not have or cannot share something rather
than staying silent), reads back critical values before finishing, and never reads out the
owner's whispers or answers.

`dtmf` takes one ordered `sequence` of string steps so an IVR entry needs no model
turn between keys: keys (`"1"`, `"4417#"`), waits (`"wait:2s"`, `"wait:1500ms"`) and
credential references (`"credential:bank/pin"`). Separate items keep keys and pauses
unambiguous within EVA's scalar-array input schema. Every step is parsed and every
credential resolved before anything is sent; A–D need `dtmfLetterKeys` in the
traits. The coordinator presses key by key outside the task lock while the brain's
pending tool keeps it silent. Owner takeover, mute, cancellation or the leg ending
stops it between keys or mid-wait, and the receipt names exactly the steps sent, why
it stopped and the provider's sending method; the dispatch survives task teardown so
that receipt is journaled. Each step becomes a DTMF transcript item, credentials only
as `[credential <ref>]`. Length and waits are unbounded.

Call keypad credentials are account numbers and PINs the owner saves under **Messaging →
Call keypad credentials**: a reference (`bank/pin`, lowercase letters and digits split by
`-`, `_`, `.` or `/`), a label, and a value of digits, `*` and `#`. `CallCredentialSettings`
keeps the value only in the encrypted `SecretStore`; the reference and label are portable
(see [call keypad credentials](#call-keypad-credentials)). The settings screen masks
entry and never shows a saved value; a blank value keeps the saved one. At connection
time EVA appends the saved references and labels to `eva.call.start`'s description (the
`call-credentials` wording note), and the main agent names the ones a call needs in its
optional `credentials` parameter. Start resolves each named credential before placing the
call, so an unknown reference, a reference whose value this phone lacks, or a value that
is not keypad keys is rejected with no call placed, naming the credential and where the
owner sets it. The brief carries only references and labels (`callBrief.credentials`), and
`dtmf` accepts only those: another reference, even a saved one, is rejected before any key
is sent, so remote speech cannot steer the agent into entering an unrelated secret. Each
`dtmf` call resolves again, so a value removed mid-call fails the same way. Values never
enter the journal, transcript, brief, tool results or invocations; the call agent cannot
speak them, only enter them on the keypad.
JVM tests cover the store, the configuration round trip and restore report, start and
keypad rejection, and the privacy of a sent credential through the coordinator. On the
device: adding and removing one in the settings screen, and a model-driven call to the
Twilio hotline entering it on the account prompt (Gather `Digits="4417"`) while EVA's
transcript, journal, invocations and logs show only `[credential test/account]`; the
hotline's spoken read-back of the digits was redacted to `[credential]` too. Android's
autofill (Bitwarden) offered to handle the masked value field.

`ask_owner` reuses ordered, one-shot question records and composer/voice answers,
with connected-call urgency. It withholds the tool result in the same brain while
AGENT_STALLING; multiple questions retain that floor until all resolve, and an
owner takeover remains in force. Recovery interrupts unanswered questions. Speakers
are REMOTE, AGENT, OWNER_TO_REMOTE, OWNER_TO_AGENT, SYSTEM_IVR and DTMF;
REMOTE/SYSTEM_IVR carry `contentTrust: external_data`. Stable transcript IDs
prevent replay duplicates. AGENT events describe completed generation; cancelled
response captions are suppressed, and a caption does not establish that queued
playback was heard by the remote party. `ThreadCallTaskJournal` stores structured
CALL_TASK thread records holding the task and only the transcript items the thread
does not already hold (`transcriptFrom`); a record is complete on its own when first
written in a process, when the task state changes, and every 32 records. Records from
before this format are whole snapshots and still fold. Recovery folds every record;
the thread projection folds a call onto its `eva.call.start` entry, and history tells
each call once, at its latest record, with the number of earlier transcript items a
bounded read could not include. Running work labels call turns PHONE_CALL, holds
ON_HOLD, and shows the call's summary.

A cellular implementation plugs in `CallProvider` (traits, availability, placement,
reattachment), `CallLeg` (state/events, DTMF, hang-up, owner microphone and monitoring
routes), and `CallMedia.LocalPcm` (PCM16 mono remote frames at 8–48 kHz, agent frames,
queued-audio cancellation). `PlaceCallRequest.legId` is journaled before dialing;
providers must use it as the stable idempotency/reattachment identity even after a
lost placement reply. A provider whose media a hosted model reaches directly (SIP or Twilio
Media Streams) would use `CallMedia.ModelHosted.sidebandSessionId` and its own owner
conference/monitor routes; EVA's [Twilio route](#twilio-provider) is `LocalPcm`, since the
SDK hands the media to EVA. Traits expose caller ID (whether it is the owner's, and the
number when a provider knows it), locked support, process survival and any connected-duration
limit to owner status and brain requests. Core adds no call-duration timeout.

### Android call engine and transports

Every Android call provider is one `CallEngine` (`adapters/android/CallEngine.kt`)
composed with a `CallTransport`. The engine is written once; a transport supplies only
what differs between ways of calling. Transports belong to one of two media families, which
decide where the call's audio meets EVA:

- `HelperAudioTransport` (cellular, WhatsApp): Android carries the call through Telecom, and
  EVA reaches its audio by redirecting it through the Shizuku call-audio helper.
- `InAppAudioTransport` (Twilio): an SDK in EVA's own process carries the call and hands EVA
  its media as PCM. No Telecom call, no Shizuku, no redirection; the call ends with EVA's
  process.

| Member | Family | Meaning |
| --- | --- | --- |
| `id`, `traits` | both | Route name for `eva.call.start.provider`, and `CallProviderTraits` (keypad, caller ID, locked support, process survival) |
| `lostCheck`, `unavailableReason()` | both | What the owner checks when EVA loses a call; transport readiness (a permission, an app, its configuration) |
| `redirect` | helper | `PSTN` or `VOIP`: which platform audio redirection applies |
| `dial(number, helper)` | helper | Start the call; throw `CallNotPlaced` when certain nothing was dialed |
| `owns(call, number)` | helper | Which Telecom calls are this transport's, and which dials `number` |
| `observer()` | helper | A per-call `CallObserver` turning plain `CallSignals` into `LegObservation`s |
| `needsCallControl`, `grantNote`, `recoveryDoubt(signals)` | helper | Whether its calls can be followed only through Telecom; receipt wording; why recovery cannot tell whether a live call is this transport's |
| `dial(number)` → `InAppCall` | in-app | Start the call; the `InAppCall` reports `LegObservation`s, presses keys, hangs up and carries the SDK's `SdkAudioPort` |
| `busy()` | in-app | Whether the transport already has a call in EVA's process |

`CallSignals` is the audio mode, telephony's precise call states, the transport's
Telecom call (state, disconnect cause, removed) and the time since dialing, so observers
are pure and JVM-tested. `CallEngine.telecomObservation` maps a Telecom call to leg
observations for any transport that is followed through Telecom.

The engine does the rest. Both families share a process-wide admission reservation acquired
before dialing, including while an SDK's connect is suspended. This excludes cellular calls
while an EVA SDK call is active even though Telecom cannot see that SDK call. External calls
are also checked through IN_CALL audio mode, Telecom and the transport's busy state.
One leg, `EngineCallLeg`, folds polled observations (every 300 ms) through `LegStateTracker`
into Placing → Ringing → Connected (with answer evidence) → Ended with cause. Losing the
observer requests hang-up and releases media, but records FAILED with `endConfirmed=false`:
a request is not proof that Android ended the call. The task becomes Uncertain, preserving
any earlier outcome claim separately. Admission remains reserved until the route can again
positively observe the old call is gone. Restore its observer and check the line before retrying.
An explicit FAILED cause stays FAILED rather than reading as no answer. The leg opens a `CallAudioPath` when the call is
first live, delivers the other party's frames, paces queued agent audio and in-band tones
into the uplink at real time (`PacedUplink`, `:eva-core`), applies the floor's microphone
routing, plays the other party locally when the owner would not otherwise hear them, runs
listen-in (gating the agent's input while it plays when capture can hear EVA's playback; see
the call card below), records the agent's input and the uplink for demos in debuggable
builds when the phone tool asks (`CallAudioCapture`), and on the end releases audio and
playback. Brain connection setup runs outside the task lock, so takeover and cancellation
remain available while the realtime connection is stalled; late sessions are discarded.

For helper transports the engine also checks availability (Android 13, then the transport,
then Shizuku and the helper, and PSTN interception for `PSTN`); allows `MANAGE_ONGOING_CALLS`
before dialing (refusing to dial when a transport needs it and it cannot be allowed); finds
the leg's Telecom call (the one dialing the number, else the transport's only call that was
not live before dialing, kept once found); presses keys through Telecom DTMF with an in-band
fallback, refused for a transport without a keypad; hangs up through `Call.disconnect`, else
the helper's end-call key; and recovers (`reattach` builds a leg only for a live
observation). Its `HelperAudioPath` reads and writes the helper's socket and routes the
microphone by redirect mode, below. An in-app transport needs none of that: its readiness is
its own, recovery never finds its calls, and its `InAppAudioPath` drives the SDK's audio
device on EVA's clock, below.

What stays per transport, and why: dialing (Telecom `placeCall`, an app's own entry point,
or an SDK's connect), recognizing its calls (a cellular call is the non-self-managed one
dialing the number; WhatsApp's are self-managed calls on WhatsApp's phone account), and
reading state (cellular uses telephony's precise call state, which device verification relied
on; WhatsApp's only signal is its Telecom connection; Twilio's are the SDK's call events). The
redirect mode is a property of the platform route, not of code: it decides what the owner
hears and how floor routing works, below.

`CallRedirect.PSTN` taps the call at the telephony HAL: the owner keeps hearing the other
party and the injection is mixed with the phone's microphone, so the agent's floor mutes
that microphone (global `AudioManager` mute). `CallRedirect.VOIP` registers an audio
policy loopback (AOSP `AudioRecord.buildCallExtractionRecord` and
`AudioTrack.buildCallInjectionTrack`, chosen when the audio mode is IN_COMMUNICATION):
playback with USAGE_VOICE_COMMUNICATION goes **only** to EVA (loop-back, not render), so
the owner stops hearing the other party, and any VOICE_COMMUNICATION capture reads the
injection, which **replaces** the app's microphone. The engine therefore plays the other
party on the call's communication device, and the owner's floor releases the injection
track so the app hears the real microphone again; the agent's floor recreates it.
Because the loopback captures any voice-communication playback, EVA's own playback is
pinned to that device with `setPreferredDevice`, which AOSP routes around the loopback.
The platform releases a VoIP redirection when the audio mode leaves IN_COMMUNICATION.

`InAppAudioPath` redirects nothing. EVA is the SDK's audio device (`SdkAudioPort`, 24 kHz
mono PCM16 in 10 ms chunks): it pulls the other party on its own real-time clock, padding
with silence while the SDK is not rendering, and pushes each paced uplink frame as two
chunks. The owner hears the other party only through EVA's local playback (the call's
communication device, else the earpiece), and nothing EVA plays reaches the SDK's capture,
so no listen-in gate runs and the other party can always interrupt the agent. With the owner on the floor the phone's microphone
(VOICE_COMMUNICATION capture, so the platform removes the other party's playback) is mixed
into the uplink, saturating rather than wrapping; with the agent on the floor it is closed.

### Twilio provider

`TwilioCallProvider` is the engine with `TwilioTransport`, an in-app transport over the
Twilio Voice Android SDK (6.10.4): a VoIP call from EVA to Twilio, which dials the phone
network. It needs no Shizuku, no telephony audio interception and no Telecom call, so it works
on stock Android and on an emulator. `TwilioAudioDevice` replaces the SDK's microphone and
speaker with EVA's `SdkAudioPort`; the SDK's own echo cancellation, gain control and noise
suppression are off, since the uplink is synthesized speech or tones.

To place a call the transport normalizes the destination to E.164 (refusing a number it
cannot, such as a short code), mints an access token on the phone (`TwilioAccessToken`,
`:eva-core`: HS256 over the owner's API key, byte-identical to Twilio's server library, one
hour, identity `eva`, only an outgoing grant for one TwiML app, so it cannot receive calls)
and connects with the `To` parameter. The owner's TwiML app must dial `To` from the caller
ID with `answerOnBridge`, so the SDK's call stays ringing until the other party answers
(`experiments/call-audio/twilio/dial.js` does, and rejects EVA's call as busy when the line
was). State comes from the SDK's `Call.Listener`: ringing is Live, connected is Live with
PROVIDER_ASSERTION evidence, reconnecting stays live, and a failure or disconnect is Gone with
the SDK's code, message and explanation as detail. Busy and busy-everywhere are BUSY. A 603
Decline names no cause: an unanswered SDK call ends that way whenever the TwiML hangs up,
which `dial.js` does both after no answer and after Twilio failed to place the dialed call
(error 13227 when the account's geo permissions do not allow the country), so the detail says
the console's notifications tell which. Temporarily unavailable is NO_ANSWER; a connection, signalling or media failure is
DROPPED once answered and FAILED before; access-token, authorization, application and
destination errors are FAILED. `dial` waits up to 15 s for the first event, and a token,
authorization or application refusal before ringing throws `CallNotPlaced`: Twilio refused
before running the TwiML that dials. Keys go out one at a time with `Call.sendDigits` (RFC
2833, 400 ms per key) once the call is answered; hang-up is `Call.disconnect`. The SDK refuses
to connect without the RECORD_AUDIO permission even though it never opens the microphone, so
availability asks for it. Its log level is lowered to errors: at its default it logs every
ICE candidate, the phone's public address included.

Traits: a keypad without A–D, not the owner's caller ID, the configured caller ID as
`callerId` (shown on the call card), does not survive EVA's process (the SDK's call dies with
it; recovery treats the task as interrupted and Twilio ends the orphaned call), and claimed to
work locked: nothing needs an Activity, audio is EVA's own playback and capture, and the
owner's microphone is opened only on takeover, which needs EVA's foreground service as any
background capture does. Locked use is not verified.

Configuration: `capabilities.twilioCalls` holds `accountSid`, `twimlAppSid` and `callerId`
(E.164; a Twilio number or verified caller ID), with a `provider/twilio-calls` reference of
kind `twilio-api-key` in `credentials.required`; validation requires the two together. The
API key SID and secret are saved only in the encrypted `SecretStore` under **Settings > Phone
call agent > Twilio calls**, where a blank key keeps the saved one. A restored configuration
keeps the route and reports the missing key as setup still required. Removing the route
forgets the key.

The SDK is a proprietary binary under Twilio's terms of service (see
[operations](operations.md#twilio-voice-sdk)). It adds about 42 MB of native libraries across
four ABIs, 11.7 MB for arm64, and the `ACCESS_WIFI_STATE` and `MODIFY_AUDIO_SETTINGS`
permissions its manifest declares.

Verification: token minting against Twilio's Python library, the transport's readiness,
E.164 normalization, refusals, event mapping, keypad gating and hang-up with a fake SDK, the
in-app path's pacing and mixing, the engine's in-app leg (state, remote frames, agent uplink,
owner-microphone mixing on the floor, hang-up), and the configuration and restore round trip
are JVM-tested. On an Android 36 emulator (`experiments/call-audio/emulator`): the settings
screen saving the route and key, and a call task over the route with well-formed fake
credentials, where the SDK's native library loaded, connected to Twilio's signalling with the
custom audio device, and Twilio's refusal (20103) ended the task "No call was placed". With the
owner's real resources, a first call's dial failed with 13227 (US not yet enabled in the account's
geo permissions). After that, on the emulator against the Twilio hotline: connected calls with
audio both ways, the hotline's Gather receiving `Digits="1"` from the agent's `dtmf` (RFC 2833
through `sendDigits`), the agent's spoken name and account number, its question and its goodbye;
hold music heard and sat through with `keep_listening`; the main agent choosing the twilio route
from a typed request; listen-in playing both sides on the emulator's speaker; and both a remote
hang-up and the agent's own hang-up ending the leg. Not yet verified: busy and no-answer through
`dial.js`, owner takeover with the microphone mixed in, locked use, and a physical phone.

The consolidated build's fictional membership role-play answered an unavailable ZIP-code
question, read back confirmation 2234, recorded an evidenced accomplishment and hung up.
The far-end Gather receipts independently confirm the ZIP answer and read-back. Dialogue
polish remains: the model can repeat a request or skip a spoken goodbye even with explicit
instructions. A quote using “canceled” when the transcript says “cancelled” is intentionally
not accepted as verbatim; the rejection provides the actual item ID, and the tested retry
succeeded. These are model behavior/quote-matching limits, not proof that every conversation
or every evidence claim succeeds on its first attempt.

### WhatsApp provider

This unverified route is disabled by default. Settings → Phone call agent exposes an
explicit **WhatsApp calling (unverified)** switch, persisted and restored as
`capabilities.callAgent.whatsAppEnabled`. Enabling it takes effect for the next call.

`WhatsAppTransport` calls a saved contact whose WhatsApp row WhatsApp has synced: it
finds the `vnd.android.cursor.item/vnd.com.whatsapp.voip.call` data row whose account
ID (`<digits>@s.whatsapp.net`) equals the destination in E.164, and the helper opens it
with `am start` as shell, which may start activities while EVA is in the background.
Without such a contact the call is refused, saying how to fix it. WhatsApp registers its
calls with Telecom as self-managed connections (public reports; not yet seen on this
phone), so `CallControlService` declares `INCLUDE_SELF_MANAGED_CALLS` and, with the
`MANAGE_ONGOING_CALLS` app op, sees and disconnects them; this also binds the service for
the owner's own WhatsApp calls, which it only tracks. The provider needs that app op and
refuses to dial without it. A call that has not appeared in Telecom 45 s after dialing
ends the leg FAILED, saying WhatsApp may be waiting on a screen or calling without EVA.
Traits: no keypad (the agent's `dtmf` is rejected before anything is sent and the call
card says "no keypad"), the owner's WhatsApp identity, survives EVA's process, not
claimed to work locked. Recovery refuses to guess when a VoIP call is live but Telecom
shows no WhatsApp call.

Verification: the observers, account-number parsing and engine refusals, hang-up,
keypad gating, recovery and loss handling are JVM-tested with a fake helper
(`CallEngineTest`). Nothing WhatsApp-specific is device-verified yet: whether the row
intent dials without a confirmation screen, whether it works locked, the self-managed
Telecom call, the VoIP extraction and injection on the Pixel, the owner hearing the
other party through EVA's playback, the pinned playback staying out of the loopback, and
microphone handback on takeover.

### Cellular provider and the owner's call card

`CellularCallProvider` is the engine with `CellularTransport`: it places an ordinary
call on the owner's line through Telecom and reaches its audio through a Shizuku helper
running as shell, which holds CALL_AUDIO_INTERCEPTION: a TELEPHONY_RX downlink capture
and a USAGE_CALL_ASSISTANT uplink injection at 24 kHz, carried over one socket. The owner hears the other party
natively. The coordinator applies the floor's microphone routing at every live leg
state, since telephony can reset mute: with the agent on the floor the phone's
microphone is muted to the remote party (global `AudioManager` microphone mute); OWNER
opens it. The leg clears the mute when the call ends. On handback, when no owner speech
reached the transcript (cellular has no owner-speech capture), the agent is told it
missed what the owner said.

Before dialing, the provider allows EVA the `MANAGE_ONGOING_CALLS` app op through
that helper (once; the DTMF receipt says so). While the op is allowed, Telecom binds EVA's non-UI
`CallControlService` alongside the owner's dialer for **every** call, which also
starts EVA's process for calls it did not place; the service only tracks the calls.
The op applies only to calls added after it is allowed, and reinstalling the app can
reset it, in which case the next placement grants it again. Keys go out with
`Call.playDtmfTone`/`stopDtmfTone` (250 ms on, 150 ms gap), which Telephony sends as
the network's out-of-band DTMF; hang-up uses `Call.disconnect`. When the service is not
bound for the leg, keys fall back to in-band tones in the uplink (160 ms on, 120 ms
gap, returning only once played), and the receipt says they were in-band and that
many systems, Twilio included, ignore them. Without Shizuku the provider is
unavailable and nothing is bound.

Device-verified on the owner's Pixel 11 Pro Fold (Android 17): call audio (the NIST
time line heard, injected speech recognized by the Twilio test hotline); Telecom
binding the service after the Shizuku grant, and IMS `startDtmf`; Twilio Gather
recognizing `Digits="1"` from a single key, and `Digits="1"` then `"4417"` from one
`dtmf` sequence (`wait:12s, 1, wait:3s, credential, #`) sent through the coordinator
by a scripted brain (`CellularDtmfLiveTest`); and model-driven call tasks started from
EVA Debug's composer that pressed 1 at the hotline menu with `dtmf` (`Digits="1"`), spoke
the name and account, stayed silent through hold, asked the hours and finished with them
as quoted evidence. Not yet device-verified: in-band fallback on a live call. Call state
still comes from polling, not Telecom, and the polled disconnect cause is gone once the
call ends (`-1`), so a remote hang-up after an answer is recorded as DROPPED rather than
REMOTE_HANGUP (the NIST line's 3-minute cut-off and the hotline's goodbye both were);
Telecom's `DisconnectCause` through `CallControlService` would tell them apart.

Each call shows as a card on its start entry: number, task state, leg state with
elapsed time, floor and advisory phase, the agent's pending questions (answered through
the composer), a speaker-attributed transcript (credential keypad entries show their
reference, never digits), and the outcome presented as the agent's claim with the
transcript lines it cites. Take over / Hand back, Listen in, Whisper and Hang up run
`ThreadController.callControl`, which proposes the owner capability to the shared
dispatcher with an OWNER_CONTROL initiator and needs no EVA connection; a control that
does not complete is shown in the provider message. Whisper routes the composer to
`eva.call.instruct` until the owner returns it to EVA.

The card shows the agent's words as it speaks, whether or not the owner listens in. The
brain streams `response.output_audio_transcript.delta` as `CallBrainEvent.Caption`s, and
remote `input_audio_transcription.delta`s when the transcription model sends them
(otherwise remote lines appear once transcribed). `CallTaskCoordinator.captions` holds
them in memory, apart from the journaled snapshot: a caption is provisional, so it never
reaches the journal, callback seeds or outcome evidence, and the finalized transcript
item with the same ID replaces it. An agent utterance cancelled before its response
completes (barge-in, takeover, a lost session, the call ending) stays on the card,
italic and marked "cut off", rather than disappearing or being recorded as said in full.
The model writes the transcript faster than the paced uplink plays the audio, so a
caption can run slightly ahead of the voice; and playback cleared after a response
completed is not marked, since its final item already exists.

Listening in plays the injected uplink (agent speech and keypad tones) on a local
USAGE_VOICE_COMMUNICATION track routed with the call. It is off for new calls by default
(portable `capabilities.callAgent.listenInByDefault`, the "Listen in on new calls"
switch under Settings → Phone call agent; a configuration without the field resolves to
off), and the card's Listen in turns it on or off for that call; playback starts when the
leg connects. The track sits on the call's voice output, so it follows the call's route:
on the device it moved from the earpiece to the speaker when the owner switched the call
to speaker (AudioFlinger output devices); Bluetooth and wired headsets were not available
to test.

On the Pixel the TELEPHONY_RX capture includes that playback: PSTN extraction is a HAL
capture, not the framework loopback mix used for VoIP, so no playback attribute excludes
it, and the agent heard and answered itself. While listen-in plays, the cellular monitor
therefore silences the agent's input wherever echo of its playback may be (until 1 s
after it stops); with listen-in off nothing is gated and the agent hears everything
(`EvaCallMonitor` logs which applies). The cost of listening in is that the other party
cannot interrupt the agent while it speaks, which is why it is off by default.

`CallEchoCanceller` still runs during listen-in, for its logged measurements only. It
works against the exact samples the track accepted: a 1 kHz envelope cross-correlation
finds the bulk delay, a 256-tap NLMS filter models the path around it, adaptation freezes
when the capture carries energy the echo model does not explain, playback resuming after
a silence longer than the longest delay must measure removal again, and silent capture
only counts as "no leak" once a full delay has elapsed. On the Pixel it reached only
0–21 dB of removal on NIST calls. When its output was passed to the agent, the residual
at an 18 dB threshold reached the brain as a remote "Eight." while the agent counted
aloud; on a Twilio call a residual after a 25 dB pass was detected as remote speech and
cut the agent off mid-sentence twice (the hotline heard only "I'm an AI assistant calling
on behalf"); and an agent turn that began with a stale delay passed as echo-free until
its late echo arrived. Listening in without losing barge-in remains open: it needs much
better removal on this path, or a capture point that excludes the local playback.

Verification: the incremental journal, folding, projection, control dispatch, mic
routing and the canceller (linear paths at 40–600 ms, delay shifts, unmodellable paths
and double talk) are JVM-tested. On the device: the card and its controls, default mute,
takeover unmute and handback re-mute (read from `dumpsys audio`), whisper, hang-up
recorded as an owner cancel, mute cleared after the call, the earpiece routing of the
monitor and its move to the speaker, the leak, and the silence gate removing it; that the
other party hears the agent while the owner's microphone is muted (Twilio Gather
recognized the agent's speech with `dumpsys audio` showing the API mute in every sample
while connected); listen-in on with no self-echo (no agent text came back as
REMOTE once the gate decided, and the agent's whole utterances reached the uplink and the
hotline); and the canceller's measurements, which show it converging only intermittently
(0–21 dB) on that phone. Not verified on the device: owner speech after takeover; live
captions; and that with listen-in off (now the default) no gate engages. The last two are
JVM-tested and pending one NIST run (`just phone run nist-listen --install`; see the
call-audio README for what to check).
`experiments/call-audio/phone` reruns these device scenarios and records demos; see the
[call-audio README](../experiments/call-audio/README.md).

`OpenAiCallAgentBrainFactory.open(CallBrainRequest)` implements a direct GA
Realtime WebSocket using `OpenAiAccess.authorize`, including subscription token
refresh/account headers. Its endpoint is the public `/v1/realtime?model=...`, derived
from the access object's realtime base; it never switches payment modes. Android
uses the existing portable realtime-model setting. The 24 kHz mono PCM16
little-endian stream is resampled incrementally to/from the leg's rate. The brain
does **no playback pacing**: `LocalPcm.sendAgentFrame` must promptly enqueue bursts,
and the provider must pace its uplink at the advertised sample rate and clear all
queued audio on `cancelAgentAudio`. Capture supplies real-time downlink frames.

The brain uses server VAD with `create_response: false` and `interrupt_response: true`.
Its tuning is portable `capabilities.callAgent`: `vadThreshold` (default 0.6, 0.0–1.0),
`vadPrefixPaddingMillis` (300, the API default) and `vadSilenceMillis` (800); the
milliseconds must not be negative and have no maximum. Each field composes separately
across includes and restores with the other capability settings; Settings → Phone call
agent edits the threshold and silence with sliders, and the file accepts values outside
them. Each brain reads the values when it opens, so a change applies to the next call or
brain replacement, not to a live session. Replaying a captured NIST time-line downlink
offline, the 0.5/500 ms API defaults committed ten tick/noise segments in 85 s at +12 dB;
the stock values commit four and keep the spoken announcement one turn at both levels,
at the cost of 300 ms more end-of-turn delay. `semantic_vad` with low eagerness dropped
the announcement. Committed remote speech triggers an explicit
`response.create`; metadata correlates late responses with their originating
output epoch. EVA requests a response only for a new committed remote segment, a
non-quiet tool result, an owner whisper or an owner handback; a replacement brain
responds only when its seeded transcript ends with such input that no quiet tool
answered. The voice model, which hears the audio, decides whether a segment needs
a reply: for noise, hold music, recordings and announcements not waiting for it, it
calls the quiet `keep_listening` instead of speaking. Quiet results never prompt another
response, so an idle line cannot loop: `keep_listening` always, `report_phase`
beside speech or another tool, and a completed `finish` whose response also spoke (on a
Twilio call, answering that receipt made the agent narrate the outcome aloud; a rejected
`finish` or one made without speech still prompts a reply). A phase report on its own is answered, since on the
device the agent reported IVR alone, was never asked to act, and the menu timed out.
Once a `hang_up` completes the brain requests no further responses, since the agent
otherwise answered its own `finish` and `hang_up` results by reading the time aloud.
Transcription is recorded for context and evidence but never gates a response. OWNER/MUTED/AGENT_STALLING suppress new inference. Tool continuation
waits for all withheld results; explicit owner handback requests continuation
without requiring another remote utterance. Preemption sends `response.cancel`,
discards buffered/late audio, and requests provider queue clearing through
`CallBrainEvent.OutputCancelled`. Remote barge-in clears playback without acquiring
the owner's floor. Tools are released only after an uncancelled completed response.

Session configuration and all attributed seeds are acknowledged before PCM starts.
Stock instructions/tool descriptions come from wording; brief/traits and transcript
JSON are user-role data messages, with explicit speaker/ID/contentTrust. Remote
captions also supply their IDs as text context for outcome evidence references.

A response starts on the committed segment, usually before its caption (and ID)
exists, so `finish.evidence` takes item IDs or quotes. An entry equal to an item ID
cites that item. Any other entry is a quote: it cites every spoken or keyed item (not
SYSTEM_IVR records, whose tool receipts echo the agent's own arguments) containing it
on word boundaries, ignoring case, punctuation and numeric separators. Ordinary word
boundaries remain significant ("now here" cannot match "nowhere"). Numeric formatting can
differ because the model quotes what it heard rather than the caption's formatting (on
a Twilio call it cited the caption "It's 22:34." as "it's 2234"). A quote no single item
holds may span a run of consecutive items by one speaker (a caption split mid-sentence),
citing each item it touches; a leading speaker label is ignored. A paraphrase, words from
different speakers, or words nobody said match nothing, and the whole `finish` is rejected
with no outcome recorded; the rejection names each such entry and the two items sharing
the most words with it, with their IDs and exact text, so the agent can retry with IDs.
The receipt lists each entry as `id` or `quote` with the items it resolved to; the outcome
keeps the claim separate from those item IDs. While the task is open, a later `finish`
replaces the recorded claim (the call can still correct a reference) and its receipt
says so; the same claim again changes nothing and says that. After the task ends no claim
is accepted. The
brain also holds a response's tool calls when they include `finish` until every remote
segment committed so far has a caption (or a transcription failure), so a quote of the
latest utterance resolves; the pending tool keeps the agent silent. A caption that never
arrives releases them after 10 s, and the coordinator then rejects anything unmatched.
Completed tool receipts enter the durable transcript before result delivery, so
replacement can reseed an owner answer/action receipt even if its original socket
has disconnected. Normal session close, transport loss and session-expiry errors
emit an attributed diagnostic and `Expired`; the coordinator replaces the brain
without redial, deferring replacement while an owner question/tool result is pending.
Intentional detach does not expire the brain. Initial authentication/configuration
failures still fail setup explicitly. See the [GA Realtime WebSocket guide](https://developers.openai.com/api/docs/guides/realtime-websocket).

Hosted sideband media needs a separate brain implementation. Local HOLD still gates
PCM, detaches after tool-result delivery, and resumes through the injected
`CallSpeechDetector` with a sample-accurate two-second pre-roll. Hosted HOLD gating
belongs to its remote implementation. `CallCredentialResolver` privately resolves
DTMF references; matching later captions are redacted in core. Local monitoring
exposes the remote side as `ownerAudio` for providers whose owner does not hear it
natively; no host plays it, since the Android engine plays the other party itself where
the owner would not hear them (VoIP redirection and in-app calls). Replay tests use
sanitized event shapes from the working Python experiment and a fake WebSocket:
no live calls, tokens or provider requests are involved.

A call often ends on the other side's schedule: the far end says goodbye and hangs up while
the agent is still speaking, before it has had a turn to call `finish` (seen on the Twilio
hotline). When the leg ends while the agent is attached, the owner did not cancel and no outcome
is claimed yet, the task stays open for `claimWindowMillis` (30 s): the brain gets EVA's
`call-ended-claim` note (`CallAgentBrain.callEnded`, which also clears a remote speech turn that
will never end) and one response request, `finish` is accepted with the usual evidence rules,
and the task then closes exactly as it would have. The stock instructions also tell the agent
to call `finish` before its goodbye and `hang_up`.

Drops around pending/accepted commitments or incomplete verification are Uncertain,
naming what is unknown, with no redial. A callback is a new owner-approved task:
`previousTaskId` seeds an ended same-thread call's transcript and reference numbers,
while mandate/disclosures are fresh. Recovery reattaches hosted or process-surviving
legs, requests hang-up and tracks asynchronous end evidence. Missing providers or
unconfirmed hang-ups remain explicitly Uncertain and require provider intervention.
Local PCM tasks are Interrupted with an Uncertain outcome for pending/accepted
commitments. JVM tests cover these transitions, receipts, question ordering and
interruption, transcript persistence/trust, preemption, credential redaction, brain
replacement, pre-roll, callback seeds, owner-attachment independence, and keypad
sequences: step order and waits in virtual time, stopping on takeover or leg end
with partial receipts, credential privacy, and rejection before sending.

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

## Skills

A skill is a procedure the user wrote for a kind of request, in the
[Codex skill format](https://learn.chatgpt.com/docs/build-skills): a `SKILL.md`
with `name` and `description` frontmatter and a Markdown body, and optionally the
`agents/openai.yaml` beside it. EVA keeps both files as their exact text so a skill
moves between EVA and Codex unchanged. It reads `name`, `description`, and the body
from `SKILL.md`, and `interface.display_name`, `interface.short_description`,
`policy.allow_implicit_invocation`, and `dependencies.tools` from `openai.yaml`;
other keys are ignored, as Codex ignores them. Names and descriptions follow the
Agent Skills limits Codex enforces (64 and 1,024 characters); the body has none.

Loading is on demand, as in Codex. While any skill is enabled, sessions offer the
bundled `eva.skills.use` tool, whose description lists each enabled skill's name
and description after the `skills` note from `eva-wording.yaml`. A skill whose
`openai.yaml` disallows implicit invocation is marked `onlyWhenNamed`. Using a
skill returns its body, prefixed by the `skill-loaded` note, as a read-only
result; results longer than the model result budget are cut with a visible marker.
With no skill enabled the tool is not offered. A skill is text for the model: it
cannot add tools, grant actions, or change confirmation rules, and `scripts/`,
`references/`, and `assets/` are not read. `dependencies.tools` are shown on the
skill as tools EVA does not provide.

A linked configuration repository can also hold skills as folders, in Codex's
repository layout: `.agents/skills/<name>/SKILL.md`, or `skills/<name>/SKILL.md`,
each with an optional `agents/openai.yaml`. Every folder directly under either
location that has a `SKILL.md` is loaded when the configuration is read, so adding,
editing, or deleting a skill is a commit to the repository, and the same folder works
when Codex opens the repository. EVA never writes these files. Their text is part of
the configuration fingerprint, so an edit arriving by Sync or Reload is applied, but it
is not copied into `eva.yaml`. A folder whose `SKILL.md` cannot be read, or whose name
is already taken, is skipped and reported on the Skills screen and as a configuration
notice; everything else still loads. Turning a folder skill off writes its name to
`skills.disabled`. Disabling Managed Git with no folder linked drops folder skills.

The drawer's **Skills** screen lists installed skills with an on/off switch and
Remove (folder skills show their path instead and change only in the repository),
installs a pasted `SKILL.md` and `openai.yaml`, and fetches a skill from an
HTTPS address: a raw `SKILL.md`, its folder, or the GitHub page of either (read from
`raw.githubusercontent.com`). A fetch also tries `agents/openai.yaml` beside it and
says when none was found. The source address is recorded, not followed. Installing
a skill of the same name replaces it and keeps its switch. Skills are portable
settings in the `skills` group, so they sync and restore with the configuration
repository. The implementation has JVM coverage; physical-device verification is
pending.

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
remain the responsibility of the user's tools. Managed Git mode uses JGit 6.10.1,
with its Apache MINA sshd transport for SSH remotes, and an app-owned checkout
under external-files storage (or internal files when external storage is
unavailable), including real `.git` metadata. Java NIO
desugaring supplies JGit's required APIs below Android 8; host
tests and Android packaging cover that integration, while physical-device
verification remains separate. The implementation lives in `data/configuration/`.

To use a single user repository:

1. For folder mode, make a directory available through Android's file picker,
   directly or through a directory-sync tool, then choose it under **Settings →
   User configuration**.
2. For managed Git, enter a remote, branch, and author identity, then select
   **Save & connect**. An HTTPS remote takes an optional Git username and a
   device-local token. An SSH remote (`git@host:owner/repo.git` or
   `ssh://git@host[:port]/path`) uses EVA's device SSH key instead: Managed Git
   shows its public key to copy or share, to be added to the repository with write
   access (on GitHub, a deploy key with **Allow write access**). Remote URLs with
   passwords or credentials, queries, fragments, other schemes, SSH remotes without
   a user, or invalid refs are rejected.
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

For SSH remotes EVA generates an Ed25519 key on first use (or when **Create SSH
key** / **Regenerate key** is chosen) with the pure-Java `net.i2p.crypto:eddsa`
implementation. SSH remotes need Android 8 or newer, because MINA sshd's
transport uses asynchronous socket channels; older phones get a specific error
and keep HTTPS with a token. Only the key's 32-byte
seed is stored, in `SecretStore`; the private key never enters `eva.yaml`, logs,
diagnostics, or tool results, and diagnostics redact it like other secrets.
Regenerating replaces the key immediately; the old public key stops working.
**Import key file** instead uses an existing private key the user picks with the
document picker (OpenSSH, PKCS#8, or PEM; Ed25519, ECDSA, or RSA), decrypted with
the passphrase typed beside it. EVA stores it re-encoded as unencrypted OpenSSH
text in `SecretStore` and keeps neither the passphrase nor the file grant, so a
key the server already accepts needs no deploy key.
The SSH transport reads no `~/.ssh` files, uses no agent, and offers only EVA's
key over public-key authentication. Host keys are verified, never skipped:
`github.com` and `ssh.github.com` must present one of GitHub's published host
keys (pinned from `api.github.com/meta`; a GitHub rotation needs an EVA update),
and any other host is trusted on first use, with its key recorded in local
preferences and its fingerprint shown under Managed Git. A different key later
fails the connection with both fingerprints; **Forget host key** clears the record
so the next connection trusts the presented key. Authentication failures name the
deploy-key step, and a read-only deploy key is reported as such. The SSH key and
known host records are device-local and nonportable, like the token; another
device generates its own key, which needs its own deploy key.

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
| `packages` | Repository sources and commit pins, installed package references, wait budgets, service bindings, applied shipped defaults |
| `services.http` | Named HTTPS origins with optional scoped local credential references |
| `extensions` | Grants bound to exact identity, digest, and mutation names |
| `spotify` | Public client ID |
| `credentials` | Required scoped secret references and endpoints, and labelled call keypad credential references, never credential values |
| `remembered` | Saved number-choice preferences |
| `device` | Desired device authorizations to check on the destination |
| `wearGesture` | Watch enablement, axis, rates, timing, angular excursion, dominance and acceleration rejection thresholds |
| `skills` | `installed`: exact `SKILL.md` and `openai.yaml` text with the address it came from; `disabled`: names switched off. Skill folders in the repository are read in place, not listed here |

An `include` list composes relative files under the selected folder. Includes are
applied in order and the including file overrides them. Scalar settings merge by
field; collections replace as a whole, so an explicit empty collection clears
inherited entries. Includes cannot escape the folder, form cycles, or exceed
eight include levels, 32 file visits, 4 MiB per file, or 8 MiB across the graph.
Every file must declare `format: eva` and a supported version; unknown fields are
rejected. EVA writes version 4. Versions 1–3 remain readable; legacy bundled
package references are reported for manual reinstall and omitted on the next complete write.
UI writes retain includes and store local overrides.
They normalize the root YAML and remove its comments; included files are not rewritten.

A skill switched off in a root file over a shared base needs only its name in the
root's `skills.disabled`; the base's `skills.installed` is inherited. A disabled name
with no installed skill is kept, so a base can bring the skill back already off.

Catalog extension definitions live in their source repositories. `packages.revisions`
maps each repository URL to a full Git commit ID. Each `packages.installed` entry
keeps its `instance`, `source`, `url` (a `packages/*.json` path), and declared `id`;
it has no `document`. An optional entry-level `revision` overrides the shared pin,
so a package whose update failed can retain its previous version. For example:

```yaml
packages:
  repositories:
  - https://github.com/colonelpanic8/eva-extensions.git
  revisions:
    https://github.com/colonelpanic8/eva-extensions.git: 2b7e9b52d325feba747ae0805c4dff20a1d9eadc
  installed:
  - instance: 00000000-0000-0000-0000-000000000091
    source: https://github.com/colonelpanic8/eva-extensions.git
    url: packages/clock.json
    id: android.clock
```

Use an actual catalog commit and retain the installation's existing instance ID.
Restore resolves and validates definitions before changing settings. It checks the
package ID and retains grants only for the same identity and canonical contract
digest. Reads use immutable Git objects, including when the browsing checkout has
moved; cached objects, installed definitions, and matching shipped defaults work
offline. A missing uncached revision fails the restore with its cause and leaves
the prior configuration in place. No fallback to the latest branch is made.

Opening Extensions lists the pinned revision. **Update** and **Update all** fetch
the catalog branch and advance the pin, using the existing install/enable policy.
New catalog installs and shipped defaults export references immediately; older
inline installations become references when updated from their catalog. One-off
file/raw-URL imports have no immutable Git source and retain their `document`.
`default-package-revisions.json` records the verified catalog commit for each
shipped default; update it whenever changing a shipped package. References and
pins survive include composition, settings edits, and re-export.

Missing credentials or target applications must not erase desired
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

### Call keypad credentials

A call keypad credential is a `credentials.required` entry with ID `call/<reference>`,
kind `call-keypad`, a `label`, and no endpoint. Only these entries take a label. Every
credential saved under **Messaging → Call keypad credentials** is written, whether or
not this device holds its value, and removing one there removes its entry. Restoring
the configuration restores the references and reports each value this device lacks
as setup still required, naming the credential and where to enter it:

```yaml
credentials:
  required:
  - id: call/bank/pin
    kind: call-keypad
    label: Bank PIN
```

The value is entered on each device and stored only in the encrypted secret store.

### Twilio call route

The [Twilio call route](#twilio-provider) is `capabilities.twilioCalls` plus its API key
reference; the key itself is entered on each device and stored only in the encrypted secret
store. The two must appear together:

```yaml
capabilities:
  twilioCalls:
    accountSid: <Twilio account SID>
    twimlAppSid: AP0123456789abcdef0123456789abcdef
    callerId: "+15592493930"
credentials:
  required:
  - id: provider/twilio-calls
    kind: twilio-api-key
```

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
**Instructions → Preapprove requested actions** enables the optional
`preapprove-actions` component, off by default. It supplies standing authorization
for steps within a requested task, including final checkout, orders, bookings,
messages, account changes, and password or verification-code entry from an
authorized source. It respects the user's scope, spending limits, corrections,
and unresolved material choices. It asks the model to avoid redundant approval
questions; it cannot override provider restrictions or Android access requirements,
create capabilities, or grant extensions.

The component and its on/off choice live in `prompt.components` in portable
configuration, so export, restore, and source following preserve the choice and
custom wording. Older or custom prompts without it show **Add preapproval option**
in Instructions, which adds the disabled stock component without resetting other
instructions. Conversation sessions include it through normal prompt composition.
New device tasks also receive that component's enabled text instructions directly,
using the loaded prompt's current choice; unrelated conversation and hang-up
instructions are not sent to the worker. Changes take effect on the next session
or device task, not a task already running. This wiring has JVM coverage; actual
provider compliance and checkout/login flows require live verification.

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

## Diagnostics

EVA keeps two kinds of evidence. The **journal** (threads, turns, items, invocation
records) is the durable record. The **trace** is a lifecycle log for diagnosing what
happened around it.

`EvaTrace` events carry identities, kinds, statuses, counts, and EVA-authored reasons:
session connecting/connected/closed (with reason), the offered catalog's size and
excluded count, response started/ended, tool calls proposed, admitted, rejected,
completed or cancelled (call ID, capability ID, initiator kind, status), handoff
started/connected and task failure or interruption, task-state changes from the
Running work snapshot, force stop, turn closure, work and voice foreground-service
start, refusal, timeout, and stop, device-task start, end, queueing and backend
fallback, readiness timeouts, and exports. Events never contain message or transcript
text, tool arguments, or credentials; field values still pass through the redactor
and are capped at 240 characters. Verbose events (speech starts, transcript and reply
lengths, assistant audio) are recorded only while `diagnostics.verboseLogging` is on.
On Android each event goes to Logcat at INFO under `EvaTrace` and into a ring of the
latest 2,000 events, persisted as two rotating JSON-lines files under the app's
private `files/diagnostics/`, so a restarted process restores the full ring.

Journal version 9 (Android and desktop) adds `session_catalogs`: when a connection or
background text leg connects, EVA records its kind, model, catalog revision, offered
tool IDs and titles, and excluded tool IDs. Writing it is best effort; a failure is
traced and never ends the session. Each insert drops the thread's records beyond the
newest 50. Older journals upgrade in place.

Realtime sessions also feed an in-memory ring of the last 1,000 raw wire events, both
directions (`ProviderEventLog`). Each keeps its type, event/response/item/call IDs,
response metadata, item type, role, name and status, response status with its reason,
error type and code, and up to 2,000 characters of transcript, text, arguments, or
error text. Audio payloads and session bodies (instructions, tool schemas) are never
kept; a session event records only its tool count, and outbound events over 64 KiB
keep only their type. Consecutive deltas for one item coalesce into one entry with a
count and first/last times. Recording only reads fields from the already-parsed
event; the ring never goes to Logcat and is lost when the process ends.

A thread export (`DiagnosticsExport`) is one JSON document:

- `summary` lines, `environment` (app version, version code, build type, application
  ID, device, Android version, access mode, configured models, verbose flag), and the
  thread's ID and title.
- `items` in thread order with their absolute index and turn ID: user and assistant
  messages with `spoken`; notices; text legs with task, exact instructions, and seeded
  history size; action calls with arguments, leg ID, and initiator, joined to their
  invocation record (status, message, structured data, provenance, initiator,
  request, catalog revision, claim time). A call without a record says so.
- `turns`, `sessions` (the catalog records above), the thread's `tasks` from the
  Running work snapshot, `deviceTasks` (goal, status, effects, and each step's kind,
  result, and timings from the receipt), the whole `trace` ring, and the
  `providerEvents` wire ring. The recent-logs export includes the wire ring without
  its text.

Bounds are explicit: the newest 400 items (`bounds.items` gives the total, the
omitted count, and an “N earlier items omitted” statement), the newest 50 session
records, and 32,000 characters per text field, cut with a “…[N more characters
omitted]” marker. Device-step results are cut at 600 characters.

Redaction runs over the finished document. A value under a credential-like key
(token, secret, password, API key, authorization, cookie, credential, signature, PIN,
OTP, in any naming style) is replaced whole. Every value in the device's secret store
is replaced wherever it appears. Free text loses bearer/basic authorization,
credential headers and `key=value` assignments, credential URL query parameters, URL
user info, OpenAI/GitHub/Slack/Google key shapes, and JWTs. Message text, arguments,
and results are otherwise kept: they are the user's own evidence. The export is
written to the app cache, replaced by the next export, and leaves the device only
through Android's share sheet with a one-time read grant.

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
