# EVA: design outline and implementation goals

Status: proposed design, not a claim of implemented functionality.
Prepared: 2026-09-12.

EVA means **Extensible Voice Assistant**: an open, customizable Android
assistant that turns conversation into actions across the phone, installed
applications, and services the user chooses. Its first substantial external
integration is controlling the user's Paseo instances.

This document is an annotated outline for implementation. Goals inherited from
the product discussion are distinguished from proposed engineering choices and
experiments. Refine individual sections into specifications as their milestone
begins; record decisions and measured results beside the relevant section.

## 1. Product goals and boundaries

### Goals established in the product discussion

- Replace the everyday Android assistant experience: summon EVA, speak, perform
  useful actions, and continue the conversation without manually navigating apps.
- Deliver fluid voice interaction with low latency, interruption, and reliable
  tool use. Good speech alone is insufficient; actions must actually happen.
- Let the user choose models, endpoints, instructions, and capabilities.
- Make extension development practical for users and contributors independently
  of the developers of the applications being integrated.
- Reuse Android's existing integration surfaces. A new protocol such as OACP
  must not be a prerequisite for useful app coverage.
- Make multiple Paseo instances accessible through one conversation, including
  existing sessions and ongoing work.
- Keep the ordinary voice/action path short. Using Paseo must not require
  navigating or automating the Paseo Android app.
- Support a power-user configuration, including optional Shizuku, while keeping
  a useful baseline on stock Android without extra privileges.
- Publish source and maintain installable updates through GitHub releases and a
  self-hosted F-Droid repository, following the existing Android app conventions.

### Proposed initial boundaries

Android is the first platform. A native Kotlin/Compose shell is the starting
point. One working voice provider and one useful Paseo adapter precede a broad
provider matrix. Open-source application code does not imply that every model
runs locally; cloud processing must be visible and configurable.

Initial delivery does not promise complete Gemini privilege parity, arbitrary
control of every installed app, a marketplace, a new universal app protocol,
offline conversational reasoning, Wear OS support, or an always-listening wake
word. These remain possible follow-on directions rather than launch gates.

## 2. Reference workflows and acceptance scenarios

| Scenario | Required behavior | Evidence of success |
| --- | --- | --- |
| "Set a timer for ten minutes" | Resolve a supported clock action and invoke it | Show confirmed result when available; otherwise accurately describe the handoff |
| "Open my navigation app to this address" | Resolve an address and launch an existing Android contract | Correct app/destination opens; no new app-side integration |
| "What is my EVA agent working on?" | Resolve host and session, retrieve current status/context | Answer identifies the exact session and freshness of the result |
| "Tell that agent to add a settings screen" | Send a follow-up to the resolved existing session | Daemon acceptance is recorded; acceptance is not described as completion |
| "Start a task on my laptop, in the EVA project" | Resolve host/project/profile and create work | Persist the returned remote identifiers; reconnect without duplicating the task |
| "Stop talking; what about the other instance?" | Interrupt playback, retain useful context, change target | Speech stops promptly; ambiguity is resolved before a mutation |
| Add an integration | Import an adapter or connect an MCP endpoint | New tools appear without recompiling EVA where the transport permits it |
| Disconnect during a mutation | Detect uncertain completion and reconcile | No silent retry that could create a second message, agent, or event |

The first useful release should exercise a phone action and a Paseo action
through the same voice session. Typed input should exercise the same dispatcher
and remain available when speaking is inconvenient.

## 3. Architecture and ownership

Proposed responsibility boundaries:

```text
Android invocation / UI / audio
             |
       EVA session controller <----> selected voice/model provider
             |
       capability registry + dispatcher
             |
       +-----+----------+-----------+-------------+
       |                |           |             |
 Android APIs      Paseo adapter   MCP client   declarative adapters
 and intents            |                         |
       |           remote daemons           existing app contracts
       |
 optional AppFunctions / Shizuku backend
```

The session controller owns conversation state, model events, foreground audio,
and presentation. The dispatcher owns executable capabilities, authorization,
argument validation, result tracking, and selection of an invocation backend.
The provider proposes tool calls; executable code determines whether and how
they run. Providers should be replaceable without rewriting Android integrations.

These are logical boundaries, not a requirement to create a Gradle module for
every box. Start small and extract modules when boundaries are exercised.

EVA communicates directly with the selected voice service and with the relevant
Paseo daemon. A remote coding agent is used when the request is coding work;
reading daemon status or setting a timer should not require an extra planning
agent. An optional credential/session service may assist connection setup
without relaying every audio packet or local action.

## 4. Android invocation, audio, and interaction

Use the supported assistant-role and voice-interaction APIs for system
invocation. Provide an ordinary launcher entry and manual microphone control
as well. Device-specific power-button and gesture behavior requires hardware
verification; becoming the selected assistant is not blanket system privilege.
[Android VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
is the platform entry point to evaluate.

Specify and implement:

- A compact assistant surface, transcript, typed input, tool status, and a route
  into the full app for configuration/history.
- Explicit listening, connecting, thinking, speaking, and error presentation.
  Execution jobs have their own lifecycle and may outlive the current turn.
- Audio focus, microphone ownership, Bluetooth/headset routing, call
  interruption, foreground lifecycle, and release of resources when dismissed.
- Barge-in that stops audible output and keeps provider context consistent with
  what the user actually heard. Interrupting speech is distinct from canceling
  a remote task or reversing an already-completed action.
- Optional screen context, requested when relevant, with a visible indication
  of what is sent to the selected provider.
- Locked-device behavior and permission-denied paths that explain the specific
  unavailable operation rather than failing the whole session.

Wake-word activation is a later, separately measurable feature. CPU-based
detection can have material battery cost; measure it on the target device
before choosing an engine or promising all-day listening. The
[Home Assistant Android guide](https://www.home-assistant.io/voice_control/android/)
documents the practical difference from Google's hardware-assisted path.

## 5. Voice provider and conversation runtime

First experiment: subscription-authenticated voice through Codex app-server,
with a separately billed OpenAI voice API adapter as the comparison/fallback.
The subscription route is technically promising, not yet validated for EVA.
Prove action dispatch as well as speech: Codex's voice delegation is not
automatically equivalent to arbitrary client-side function calling. See the
[proof-of-concept plan](proof-of-concept.md) for evidence, gates, and limits.
Select and pin the actual model/protocol during the voice experiment;
the product name must not depend on a model identifier. Compare direct realtime
speech against an STT/text-model/TTS pipeline only where the latter adds useful
provider choice or local processing without unacceptable delay.

The current [OpenAI WebRTC guide](https://developers.openai.com/api/docs/guides/voice-webrtc)
supports direct client audio sessions established using short-lived credentials.
Its [tool guide](https://developers.openai.com/api/docs/guides/realtime-mcp)
distinguishes client-executed function tools from provider-executed remote MCP
tools. EVA's proposed default is to execute integrations through its own
dispatcher, keeping private phone and tailnet access on the client side.

Define a small provider interface covering session setup, supported modalities,
tool schemas, text/audio input, output events, interruption, reconnection, and
usage reporting. Do not force every provider to pretend it supports realtime
audio or identical tool behavior.

Credential choices need an explicit decision before implementation:

- For the subscription experiment, prefer the existing Codex login and
  app-server on a user-controlled host. Keep account credentials there; broker
  session setup rather than copying subscription tokens to the phone.
- For an API-backed provider, prefer a user-controlled token issuer for
  short-lived mobile session credentials; it need not be an EVA-operated service.
- Evaluate an advanced user-supplied key configuration where the provider
  supports it. Store credentials with Android Keystore-backed encryption,
  exclude them from backups/logs, and document the mobile key exposure tradeoff.
- Never ship an application-owned secret in the APK. An API session is not the
  ChatGPT app's voice session or an assumed entitlement from a chat subscription.

Model subscription and API backends as distinct authentication and usage modes.
Current desktop voice pricing lists unlimited voice for Pro 20x, but not
unlimited Codex task execution. It does not establish that an experimental EVA
client inherits the same entitlement. Check account access and supported use;
never silently switch to billable API usage after a subscription failure.
[Current desktop voice pricing](https://learn.chatgpt.com/docs/pricing#chatgpt-voice-in-desktop)

Keep three costs separate: establishing a session, transporting its audio,
and delegating a tool request to another model. A Paseo-hosted session broker
could still allow direct phone-to-provider WebRTC audio. Measure each path
before deciding whether Paseo involvement adds unacceptable latency.

Persist user configuration and useful conversation/job identifiers locally.
Keep raw audio retention off by default. Specify retention, deletion, export,
session-expiry recovery, and bounded context before enabling long-lived memory.

## 6. Capability model and extension contract

Every adapter should produce the same internal capability description:

- Stable namespaced ID, adapter/version, display name, and concise description.
- Input schema and, when supported, output schema.
- Availability and reason when unavailable; required permissions, connection,
  installed package, OS feature, or Shizuku state.
- Side-effect classification, foreground/UI requirements, supported
  cancellation, and idempotency/reconciliation behavior.
- Execution result: completed, accepted/running, handed off, failed,
  canceled, or outcome unknown; optional remote job ID and result evidence.

Discover tools at startup and refresh on relevant configuration/package changes.
Advertise a small useful set for the active context; add tool search only when
the catalog warrants it. Recheck availability at execution time. Cache discovery
off the voice path and do not turn every request into a full app scan.

Three extension forms should share that contract:

1. Built-in native adapters for Android APIs and integrations requiring code.
2. User-imported declarative adapters that describe existing intent/deep-link or
   documented HTTP actions, typed arguments, and result behavior. A contributor
   can write one without modifying the target application.
3. MCP server connections exposing existing tools. EVA acts as the client to
   reachable servers; Android should not assume desktop-style stdio processes
   or a JavaScript runtime are available.

Instruction/skill bundles may add vocabulary and workflows on top of installed
tools. They do not create Android permissions or executable APIs by themselves.
Arbitrary downloaded native code is not necessary for the initial extension
format. More capable separately installed extension apps can follow later.

## 7. Reusing Android's existing app interfaces

| Surface | Planned role | Limitation to preserve in the design |
| --- | --- | --- |
| Standard intents | Baseline alarms, navigation, sharing, dialing, opening apps | Often a UI handoff rather than structured completion |
| Documented deep links | Specific actions through community adapters | Links rarely describe complete input/output semantics |
| App Actions/static shortcut metadata | Investigate importing existing semantic declarations | Reading metadata does not establish that another assistant can invoke its target |
| Dynamic shortcuts | Investigate user-specific destinations | Enumeration/launch APIs have role and permission restrictions |
| Media sessions, notification actions, content providers | Native adapters for established contracts | Each has distinct access grants and lifecycle constraints |
| Tasker/automation integrations | User-defined actions and access to existing automation | Verify actual supported invocation/results; no assumed universal schema |
| AppFunctions | Structured discovery/execution where available | OS/version, caller authorization, and target-app adoption gates |
| AppFunctions through Shizuku | Optional personal-device experiment | Shell commands and privileges vary; not yet validated on the target phone |
| OACP | Optional protocol adapter | Useful only for participating apps; not required for EVA adoption |
| Accessibility automation | Potential later fallback for uncovered workflows | More brittle and intrusive than supported app contracts |

Use [Android common intents](https://developer.android.com/guide/components/intents-common)
for documented mappings. Account for
[package visibility](https://developer.android.com/training/package-visibility)
when discovering handlers; do not confuse a filtered query with an uninstalled
app. [App Actions metadata](https://developer.android.com/develop/devices/assistant/action-schema)
may be useful input, but automatic reuse is an experiment requiring package and
endpoint validation, not an established compatibility guarantee.

## 8. Paseo: the first substantial remote adapter

EVA is an independent client of the daemon. The official
[Paseo SDK](https://paseo.sh/docs/sdk.md) is TypeScript, not a Kotlin library.
Before implementing transport, inspect the supported wire protocol and decide
between a minimal native client and a small optional bridge using that SDK.
Prefer a direct native connection if the necessary protocol is manageable;
measure the bridge alternative rather than assuming every network hop dominates.

Support named hosts, stable daemon identity, projects/workspaces, agent IDs,
and a remembered active target. An agent reference is qualified by host; labels
alone are insufficient for mutations. Ask one short disambiguating question
when the intended host or agent cannot be resolved.

First tools, expressed here as proposed semantic operations rather than wire
API names: list hosts, list agents, inspect status, read recent output, send a
follow-up, create an agent in a selected workspace, and interrupt a task.
Discover available profiles/settings from the daemon; keep user-configured
defaults rather than hardcoding execution permissions or model choices.

Use daemon events for progress where available. Deduplicate them across
reconnections, bound history retrieval, and announce meaningful completion
without narrating every log line. Closing the voice session must not kill
remote work. Notifications/background delivery need a separate lifecycle design.

Start with a supported direct connection over the user's tailnet. Preserve
authentication and server identity. Relay support includes pairing and
end-to-end encryption work and must not be represented as a plain WebSocket
URL substitution. See [Paseo connectivity](https://paseo.sh/docs/connectivity.md).

## 9. AppFunctions and Shizuku experiment

Do not carry the earlier conversational hypothesis forward as proven behavior.
Google documents shell discovery/execution in its
[AppFunctions integration guide](https://developer.android.com/ai/appfunctions/add-appfunctions),
while its [developer article](https://developer.android.com/blog/posts/build-intelligent-android-apps-integrate-into-android-s-intelligence-system-using-app-functions)
describes those testing commands on Android 17 or newer. AppFunctions API
availability from Android 16 does not establish shell-command support on every
Android 16 build.

[Shizuku](https://github.com/RikkaApps/Shizuku) can proxy work under shell or root
identity, depending on how it starts. It is plausible that a Shizuku user service
can invoke the documented shell testing path. Actual identity checks, enabled
function state, OEM differences, and restrictions on target packages remain to
be tested. This does not make the assistant package itself allowlisted.

Experiment acceptance:

1. Record phone model, build/API version, Shizuku mode, and service availability.
2. Inspect command help and list functions without changing configuration.
3. Use a controlled sample app with a reversible test function to compare
   ordinary client invocation, ADB invocation, and Shizuku invocation.
4. Capture structured results and failures; test timeout, denied access,
   Shizuku restart, and function unavailability.
5. Record the exact compatibility envelope. Retain a useful stock-Android
   baseline if this path is unavailable.

The adapter must expose typed operations rather than a model-controlled shell.
Keep function identifiers and parameter serialization separate from command
syntax; do not interpolate spoken text into shell scripts.

The 2026-09-13 Pixel hardware results for this experiment, including the one
untested restart case, are recorded in [implementation.md](implementation.md#settings-appfunctions-through-shizuku).

## 10. Execution correctness and user control

Use explicit authority and evidence instead of expecting a system prompt to
make execution reliable. Users enable capabilities and select defaults;
routine permitted actions should be low friction. Ambiguous targets and actions
that require confirmation should get a concise contextual prompt. Persisted
choices should not create repeated approval loops.

Track invocation IDs and exact targets. Validate arguments before calling an
adapter. Retry reads when appropriate; reconcile uncertain writes before
retrying. Track dispatch, acceptance, and verified completion separately.

Treat imported metadata, remote output, notifications, and screen text as
untrusted content rather than authority to expand permissions. Credentials stay
scoped to the owning adapter. Bound tool outputs and redact secrets in logs.
These controls belong in the dispatcher and credential boundary.

## 11. Performance, diagnostics, and evaluation

Proposed targets, to validate and revise after measuring a named physical
device and network:

- Visible invocation feedback within 300 ms for a warm launch.
- Stop local playback within 200 ms of a detected user interruption.
- Warm-session first useful spoken response within 1.5 s median / 3 s p95
  after the measured end of speech, excluding genuinely long remote operations.
- Local broker dispatch under 100 ms p95 after receiving a complete tool call,
  excluding permission dialogs and target-app work.
- At least 95% correct tool/target/argument selection on a fixed initial set of
  30 ordinary voice requests, with no duplicate writes in recovery scenarios.

Measure cold startup separately. Record timestamps for input end, model tool
selection, dispatch, target acceptance/completion, and audio start. An empty
acknowledgment is not a useful response. Report sample count and failure rate
alongside percentiles. Measure battery separately for idle, an active session,
and optional wake-word listening.

Unit and integration tests should cover routing, argument validation, ambiguous
targets, cancellation, reconnects, and uncertain writes. Use a fake provider and
fake adapters for deterministic state-machine tests. Use device tests for
assistant invocation/audio/permissions and an isolated Paseo workspace for
remote mutations. Live provider tests are a deliberate acceptance run, not a
requirement for every CI job.

## 12. Build, distribution, and implementation sequence

The scaffold owns the pinned Gradle/JDK/SDK setup, Nix/direnv environment, just
commands, CI checks/artifacts, release signing, and F-Droid metadata/index
publication. Follow its README and reusable Android ingredients document.
Source availability and a self-hosted index do not imply acceptance into the
official F-Droid catalog. Published APK identity and signing continuity matter
for upgrades regardless of download channel.

| Milestone | Deliverable | Exit criterion |
| --- | --- | --- |
| M0: foundation | Scaffold, CI, release/index configuration, design outline | Local checks and debug build pass; external setup state documented |
| P0: voice feasibility | Small disposable harness following the POC plan | Account access, tool/delegation control, audio path, and usage model evidenced before committing to a runtime |
| M1: voice + one phone action | Assistant invocation, provider session, interruption, typed path, one native adapter | Real phone executes the same operation through voice and text; first latency measurements |
| M2: Paseo daily use | Host/session targeting, status, follow-up, create, interrupt | One voice session mixes phone and Paseo actions; reconnect does not duplicate remote work |
| M3: extensibility | Declarative adapter import and MCP connection | A new useful integration is added without rebuilding the core app |
| M4: broader Android coverage | Selected metadata/automation adapters; optional Shizuku experiment | Compatibility recorded per surface and device; unavailable features degrade clearly |
| M5: daily-driver polish | Persistence, lifecycle, notifications, battery, optional wake word | Repeated real-world use meets measured reliability and latency targets |

Run [risk-first proof-of-concept experiments](proof-of-concept.md) alongside
the scaffold; polished UI, general plugin packaging, and wake words are not
prerequisites for proving the voice/action loop.

Run the Shizuku discovery experiment early if the target phone is available,
but do not block M1 or M2 on it. Before M1, resolve the initial voice model,
credential setup, minimum Android version, WebRTC dependency, and target test
device. Before M2, resolve the native Paseo protocol versus bridge choice.
Before M3, specify adapter packaging/versioning and connection authentication.

## 13. Design decisions to carry forward

Proposed defaults: native Kotlin shell; EVA-owned conversation and dispatch;
direct voice audio; standard Android contracts first; Paseo as an adapter;
optional privileged integration; declarations and MCP before a plugin runtime.

Each implementation milestone should add its decisions, measured evidence, and
compatibility limits here or in a linked focused document. Keep aspirations,
documented platform support, and device-tested behavior visibly distinct.
