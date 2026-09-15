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
focus, and teardown handling. The session owns its audible edges: a rising cue when
the transport connects and a falling one when it is torn down, played once per
session so a reconnection inside the disconnect grace period stays quiet. Cues use
sonification attributes rather than the call route, which the session has already
handed back by the time it ends. Text and voice share capability execution. Realtime
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
  visibility entries; Mova's dangerous read permission is requested only on demand.
  The host is Robolectric-tested; physical-device verification is pending. See the
  [content contract](extension-protocol.md#content-execution-and-results).
- Native adapters use intents, contacts, messaging, media sessions, media browser
  interfaces, and other implemented Android contracts. Keep native resolution
  where it needs code; pure mappings can be declarative packages.
- Media apps are surfaced as extensions (`adapters/android/MediaAdapter.kt`): each
  installed player found through its media browser service, Media3 library
  service, or play-from-search intent becomes one installed extension whose
  descriptor lists only the operations it has a route for — control, now-playing,
  and play through its media session, plus queue only where a route exists (Media3
  library search, or Spotify's own API once the account is connected). The model
  selects the app by choosing its tool; there is no app-name argument and no
  name matching in EVA. Enablement and per-operation grants, settings rows, catalog
  revisions, and stale-proposal refusal come from the extension runtime unchanged.
  Unnamed control, now-playing, volume, and play-whatever-the-phone-chooses stay
  native: they are media-button semantics and belong to no app. Notification access
  supports session inspection and result confirmation. Compatibility must be
  tested per app; an app may refuse EVA as a media client, which is remembered so
  the fallback is not delayed by asking again.
- SMS draft handoff and native direct-message sending are distinct capabilities.
  Notification replies share the same authorized messaging boundary. Do not remove
  native behavior merely because a declarative compose example exists. See
  [messaging setup](operations.md#messaging-setup-and-verification) for setup and
  current verification limits.
- `assist/` implements the Android voice-interaction service, overlay session, and
  delegated recognition service. Android's keyguard launch callback opens the
  hands-free activity above the lock screen, starts or joins voice, and hides the
  conversation. This callback is JVM-tested; locked-device voice verification is
  pending. Assistant selection does not confer unrestricted
  background launch or device access.
- Optional Shizuku adapters implement Settings AppFunctions and bounded UI observation,
  text replacement, and taps. UI mutations consume a recent observation and recheck
  target identity. Existing Shizuku grants allow device-setting actions without
  EVA's main activity; that activity is needed only to request a missing grant.
  Contact and SMS Android permission checks likewise reuse existing grants from
  the assistant without requiring the main activity. Platform restrictions on
  locked-device actions still apply. General AppFunctions discovery/execution and broader device
  automation are not implied by those implemented operations.

Paseo and general MCP adapters remain future work. They should register capabilities
through the same execution boundary. Routine phone actions must not depend on a
remote coding agent or on automating Paseo's Android UI.

## Messaging

The **Messaging** drawer destination owns phone-permission status, contact-name
lookup retries, notification-message access/reply grants, and remembered-number
management. Moving these controls does not rename their portable fields:
`voice.lookupRetries`, `messaging`, and `remembered.chosenNumbers` remain stable.

EVA exposes one search/read/send tool family with two execution paths:

- SMS/MMS: native Android conversation lookup, history and sending.
- Other apps: recent messaging notifications and their explicit text-reply
  actions. No target-app changes, extensions, Shizuku, or app-specific package
  allowlist are required.

### Shared tool contract

Existing capability IDs remain stable:

| Tool | SMS/MMS | Notification-backed app |
| --- | --- | --- |
| eva.android.messages.conversations | Omit service or use sms; optional participant query | service is notifications for discovery, exact package name, or unique visible app label; query matches conversation title |
| eva.android.messages.history | Use the returned integer conversationId | Use the returned opaque conversationRef; result is only a notification excerpt |
| eva.android.messages.send | Explicit recipient number(s) or conversationId, plus message | conversationRef and message; optional service must match |

App search results include service package name, conversation title,
conversationRef, and replyAvailable. If labels are ambiguous, use the package
name. EVA never substitutes SMS when an app was explicitly requested. Device
contacts remain useful for SMS, but a phone number is not an app reply target.

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

This is not a full WhatsApp/Telegram client: it cannot start arbitrary new app
chats, retrieve complete history, list silent/archived conversations, recover
dismissed notifications, or send attachments. Locked-device notification reads
and replies are refused. Notification visibility and action support vary by app.
“No match” means no match among available notifications, not that the chat does
not exist.

The shared interface is deliberately independent of extension files. Future
service-account adapters can provide complete history/new-chat sends where an
official API supports the user's account. They should preserve explicit service
selection, account-scoped targets, authority checks, and honest receipt statuses,
rather than replacing the common user-facing tools.

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
| `models`, `voice` | Text/realtime models, per-leg reasoning effort, lookup retry count |
| `appearance`, `capabilities` | Dynamic color and optional capability switches |
| `messaging` | Notification-read opt-in and exact app-installation reply identities |
| `prompt` | Source URL and complete ordered component list |
| `packages` | Repository, imported package bytes and origins, wait budgets, service bindings |
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

Provision the local username/password through the extension's server settings.
The **Service name** field identifies the reusable service. The repository stores
only `service/<name>/basic` and its approved origin; it never stores the credential
value. Legacy per-package service entries remain readable for migration.

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
