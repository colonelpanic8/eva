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
thread history. Voice can also hand a long request to that leg with its
continue-in-text session tool. The text leg receives a fresh catalog from the
same capability registry, including enabled extensions, and retains the turn's
action claims and receipts. Turn IDs belong to the store rather than a provider's session-local
counter. The SQLite journal links requests, tool calls, receipts, and responses.
The UI projects these records into grouped turns and session notices.

One attachment is live at a time. Background work can coexist with it.
`VoiceSessionService` owns foreground voice lifetime; `TurnWorkService` covers
short background turn work when voice is absent. Android can interrupt background
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
and bounded to 64 model-facing tools, reserving two voice session controls and bundled tools
before sorted extension tools. Unavailable or excess entries remain explainable
in the UI. New tools reach the model on the next connection; revocation blocks new
execution immediately even if the model still sees an older catalog. For packages
refreshed from a followed repository, newly named actions are granted when that
package's auto-enable switch is on; explicitly disabled actions remain disabled.
Manual imports do not gain new grants this way. Installed Android providers do not
either, except the pinned default providers (Mova and Paseo): they start enabled
with every action unless the user turned them off. See
[default providers](extension-protocol.md#7-identity-grants-and-untrusted-text).
An app's own installed extension takes over same-named actions from declarative
packages that target that app; the package's other actions stay available and are
listed under the app.

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
  call takes audio focus, which pauses EVA's microphone and playback until it ends.
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
- Optional Shizuku adapters implement Settings AppFunctions and bounded UI observation,
  text replacement, and taps. UI mutations consume a recent observation and recheck
  target identity. Existing Shizuku grants allow device-setting actions without
  EVA's main activity; that activity is needed only to request a missing grant.
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

### Background execution and locked devices

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
background-work service interrupts the affected work. Voice and background work remain non-sticky; force-stop and process
death do not trigger action replay. The assistant launch fallback and unlock UI
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

EVA exposes one search/read/send tool family with two execution paths:

- SMS/MMS: native Android conversation lookup, history and sending.
- Other apps: recent messaging notifications and their explicit text-reply
  actions. No target-app changes, extensions, Shizuku, or app-specific package
  allowlist are required.

### Shared tool contract

Existing capability IDs remain stable:

| Tool | SMS/MMS | Notification-backed app |
| --- | --- | --- |
| eva.android.messages.conversations | Omit service or use sms; optional name query (commas require every person) or participants phone numbers; threads with only the asked-for people rank first | service is notifications for discovery, exact package name, or unique visible app label; query matches conversation title |
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
| `appearance`, `capabilities` | Dynamic color and optional capability switches |
| `messaging` | Notification-read opt-in and exact app-installation reply identities |
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

## Verification boundaries

Focused JVM tests cover core behavior and Android hosts through fakes/Robolectric.
Synthetic speech instrumentation exercises live providers and action dispatch;
it does not establish acoustic quality or natural interruption behavior.
Recorded physical-device checks cover selected voice, assistant, and extension
flows, not every API surface. See [Operations](operations.md#device-verification)
for repeatable procedures and evidence limits.

When changing behavior, update this document's current contract rather than adding
a new status appendix. Keep proposed APIs visibly separate from implemented ones.
