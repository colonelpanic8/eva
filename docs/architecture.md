# EVA architecture

Read the [design philosophy](../README.org) first. This document describes the
implemented boundaries and identifies remaining work; it is not a milestone log.
The [extension reference](extension-protocol.md) owns extension formats and wire
contracts. [Operations](operations.md) owns build, release, and verification steps.

## Runtime and ownership

EVA is a native Kotlin/Compose Android app. Most code lives in one `:app` module;
packages separate responsibilities without requiring a module for every interface.

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

## Threads, turns, and connections

A **thread** is persistent conversation history. A **turn task** owns an accepted
request. A voice call or text connection is an **attachment** to the thread;
a **provider leg** performs the model work for a task.

`ThreadController` runs tasks in a thread-owned scope. Ending an attachment with
unfinished work can continue that turn on a background Responses leg, seeded with
thread history. Turn IDs belong to the store rather than a provider's session-local
counter. The SQLite journal links requests, tool calls, receipts, and responses.
The UI projects these records into grouped turns and session notices.

One attachment is live at a time. Background work can coexist with it.
`VoiceSessionService` owns foreground voice lifetime; `TurnWorkService` covers
short background turn work when voice is absent. Android can interrupt background
work, so persistence supports recovery and explicit interrupted outcomes, not a
promise of uninterrupted execution across process death.

The current turn policy permits at most one side-effecting action and bounded
read lookups (up to eight). Imported mutations after a tool result are refused;
search-then-mutate workflows may require separate requests. These are current
execution limits, not the long-term product philosophy.

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
focus, and teardown handling. Text and voice share capability execution. Realtime
input identity must survive late transcripts and asynchronous tool events:
transcript arrival order alone cannot establish which request owns an action.
Tool correlation carries connection/session, input, generation, turn, catalog,
and call identity; stale calls cannot gain authority through reconnection.

Provider-independent history preserves action provenance and distinguishes
external tool content from EVA's outcome envelope. Provider output is not proof
that an operation completed. Interrupting speech, ending a call, canceling local
work, and undoing a remote action are distinct operations.

## Capability execution

Adapters contribute capabilities to a registry snapshot. Admission is deterministic
and bounded to 64 model-facing tools, reserving session controls and bundled tools
before sorted extension tools. Unavailable or excess entries remain explainable
in the UI. New tools reach the model on the next connection; revocation blocks new
execution immediately even if the model still sees an older catalog.

The dispatcher validates identity, arguments, binding revision, availability, and
grants, then journals a claim before dispatch. Duplicate call IDs cannot execute
twice with different arguments. A post-submission transport failure produces an
unknown outcome unless there is evidence the action did not start.

Receipts distinguish `COMPLETED`, `HANDED_OFF`, `NOT_EXECUTED`, `FAILED`, and
`UNKNOWN`. An intent launch is a handoff. Extension wait budgets are bounded;
timeout does not prove failure or cancellation. Changes to an extension's approved
contract invalidate grants. See the [extension protocol](extension-protocol.md)
for concrete identity, schema, waiting, and authorization rules.

## Android capabilities

- Native adapters use intents, contacts, messaging, media sessions, media browser
  interfaces, and other implemented Android contracts. Keep native resolution
  where it needs code; pure mappings can be declarative packages.
- Media transport can work across apps exposing Android media controls. Notification
  access supports session inspection and result confirmation. Queue insertion needs
  a provider that supports it; Spotify has a dedicated API route and Media3 library
  discovery is implemented. Compatibility must be tested per app.
- SMS draft handoff and native direct-message sending are distinct capabilities.
  Notification replies share the same authorized messaging boundary. Do not remove
  native behavior merely because a declarative compose example exists. See
  [Messaging](messaging.md) for setup and current verification limits.
- `assist/` implements the Android voice-interaction service, overlay session, and
  delegated recognition service. Assistant selection does not confer unrestricted
  background launch or device access.
- Optional Shizuku adapters implement Settings AppFunctions and bounded UI observation,
  text replacement, and taps. UI mutations consume a recent observation and recheck
  target identity. General AppFunctions discovery/execution and broader device
  automation are not implied by those implemented operations.

Paseo and general MCP adapters remain future work. They should register capabilities
through the same execution boundary. Routine phone actions must not depend on a
remote coding agent or on automating Paseo's Android UI.

## Configuration and restoration

The design requirement is a single user-owned repository capable of restoring
**every EVA setting**. Files should be readable, deterministic, composable, and
usable through a synced folder or checkout. EVA does not need to own Git to use
such a repository. UI controls must edit the same configuration model.

A complete configuration includes model choices, appearance, capability switches,
instructions and their sources, extension definitions and identities, grants,
repository sources, service endpoints, wait budgets, and saved user preferences.
Configuration import must validate before replacing working settings and preserve
identity-dependent authorization. Invalid or incompatible input must be visible.

Credentials require protected provisioning and references; neither a package nor
a shareable configuration may embed application-owned secrets. Android permission
grants, assistant selection, document access, and installed applications are
properties of the destination device. Restoring EVA's desired configuration cannot
grant these platform permissions; the app must identify remaining setup explicitly.
Conversation history and transient connections are runtime data, not settings.

### Portable configuration format

The portable format uses `eva.yaml` in a folder selected through Android's
Storage Access Framework. The folder can be a synced Git checkout; syncing,
committing, and resolving Git conflicts remain the responsibility of the user's
tools. The configuration implementation lives in `data/configuration/`.

To use a single user repository:

1. Put the checkout in a folder available through Android's file picker, directly
   or through a directory-sync tool.
2. Open **Settings → User configuration → Choose folder**. An empty folder gets
   an `eva.yaml` snapshot of the current settings; an existing file is validated
   and restored. Keep this folder under version control with your usual tools.
3. On another device, sync the same repository and choose that folder in EVA.
   Complete the listed account, app, and device-authorization requirements.
4. Edit settings in EVA or edit the files. Returning to EVA checks for external
   edits; **Reload** explicitly reloads the linked configuration.

To extract a shared base, move the generated complete file to `shared/base.yaml`
and replace the root with a small override file, for example:

```yaml
format: eva
version: 1
include:
- shared/base.yaml
voice:
  lookupRetries: 3
```

Each included file also declares its format and version. This lets devices or
forks reuse a base while overriding selected settings. No Git client, remote
repository write, or automatic commit is performed by EVA.

The schema separates these groups:

| Group | Settings |
| --- | --- |
| `models`, `voice` | Text/realtime models, reasoning effort, lookup retry count |
| `appearance`, `capabilities` | Dynamic color and optional capability switches |
| `prompt` | Source URL and complete ordered component list |
| `packages` | Repository, bundled instance IDs, imported package bytes and origins, wait budgets, service bindings |
| `extensions` | Grants bound to exact identity, digest, and mutation names |
| `spotify` | Public client ID |
| `credentials` | Required scoped secret references and endpoints, never credential values |
| `remembered` | Saved number-choice preferences |
| `device` | Desired device authorizations to check on the destination |

An `include` list composes relative files under the selected folder. Includes are
applied in order and the including file overrides them. Scalar settings merge by
field; collections replace as a whole, so an explicit empty collection clears
inherited entries. Includes cannot escape the folder, form cycles, or exceed
bounded depth/file/size limits. UI writes retain includes and store local overrides.
They normalize the root YAML and remove its comments; included files are not rewritten.

Imported extension JSON remains exact text rather than a re-encoded approximation:
restoring configuration must preserve the bytes whose digest and identity were
approved. Missing credentials or target applications must not erase desired
configuration. Restored grants authorize only the matching installed identity and
contract; they cannot confer Android permissions or trust a different signer.

### Restore and edit contract

Resolve and validate the complete include graph before changing the active setup.
An invalid folder selection must leave the previous link and configuration usable.
Apply package definitions and refresh discovered descriptors before restoring grants;
match the live instance, authenticated identity, contract digest, and mutation names.
Retain unavailable desired settings and report setup still required rather than
silently removing them on the next save. Matching bundled identities should survive
app upgrades that add or remove bundled definitions.

A restore spans several existing stores. Preserve a before-state for rollback,
including the prompt location; importing configuration must not overwrite a
previously selected standalone prompt document. In-memory state and persisted
settings must agree after success or a reported failure.

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
The stock call instructions offer one-request and open-conversation alternatives.
The voice control `eva.session.end` ends the attachment, not remote work.

The Instructions screen supports a user-picked YAML file, EVA's own external-files
copy, and explicit updates from a raw HTTPS source. Source updates preserve enabled
choices for matching IDs; compiled defaults work offline. The default source is
`https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-prompt.yaml`.
No background fetch silently changes instructions. UI writes normalize YAML and
remove comments; file-based editing is preferable if comments must survive.

## Verification boundaries

Focused JVM tests cover core behavior and Android hosts through fakes/Robolectric.
Synthetic speech instrumentation exercises live providers and action dispatch;
it does not establish acoustic quality or natural interruption behavior.
Recorded physical-device checks cover selected voice, assistant, and extension
flows, not every API surface. See [Operations](operations.md#device-verification)
for repeatable procedures and evidence limits.

When changing behavior, update this document's current contract rather than adding
a new status appendix. Keep proposed APIs visibly separate from implemented ones.
