# EVA extension decisions — 2026-09-14

This dated record captures the initial investigation and subsequent decisions
from the 2026-09-14 extension session; it is not a current feature inventory.
For current implementation and verification status, see [implementation.md](implementation.md).

The initial question was whether installing mova could expose capture, agenda,
search, and completion to EVA without provider-specific EVA code or registration.
Ivan subsequently prioritized controlling existing apps without changing those
apps. Mova-side extension development was parked; the common adapter work remained.

## Baseline findings at the start of the review

### The starting main branch had no open extension path

- `CapabilityRegistry` took a fixed backend map and only accepted definitions
  from `BundledCapabilities.definitions`; every backend was hand-wired in
  `EvaApplication.registry`.
- `CapabilityRegistry.REVISION` was a compile-time constant and
  `ProviderSessionController` (on `main`; the unmerged `threads` branch has
  `ThreadController`) captured its definitions map at construction. No discovery
  path could add a tool after startup.
- Every provider (`OpenAiResponsesProvider`, `OpenAiRealtimeProvider`,
  `BrokerConversationProvider`) rejected a catalog larger than 32 tools. On an
  API 37 phone with Shizuku the bundled catalog was already 26, plus
  `END_CONVERSATION` in voice mode.
- At that point, `docs/architecture.md` §6 proposed declarative intent packages, MCP, and
  AppFunctions, and explicitly deferred "separately installed Android extension
  apps ... a versioned IPC contract". The open-extension portion was not implemented; the README
  recorded that capability-package import was not integrated.
- The only third-party execution path at that point was `AppFunctionsBackend`,
  which shelled out through Shizuku to `cmd app_function`, and
  `AppFunctionsUserService` hard-allowlisted the Settings package and eight
  function ids.

### AppFunctions needs Shizuku for a sideloaded assistant

`android.permission.EXECUTE_APP_FUNCTIONS` is `protectionLevel="internal|role"`
and, per the AOSP manifest comment, "only granted to preinstalled / system apps
having the ASSISTANT role". In `roles.xml` it is granted to `SYSTEM_SHELL`. A
sideloaded EVA holding the assistant role does not get it, which is why EVA
already goes through Shizuku. Building on AppFunctions would mean:

- Android 16+ and Shizuku on the device for every extension call;
- generalising the shell allowlist to arbitrary packages and function ids;
- mova adding KSP plus the alpha `androidx.appfunctions` library to an Expo
  build.

That is an acceptable optional backend later, but it fails the "stock Android"
baseline in EVA's own AGENTS.md and cannot be the primary answer.

### Mova could supply a provider without its JS runtime

- Native Kotlin reaches org-agenda-api without the JS runtime:
  `CaptureHttp.kt` reads `mova_api_url`, `mova_username`, `mova_password` from
  `MovaSharedPrefs` and POSTs `/capture`. The same pattern covers `/agenda`,
  `/get-all-todos`, `/complete`, `/custom-views`, `/custom-view`.
- The corrected existing links are mova://create?title=... and mova://complete
  with an ID or file/pos/title (optional state). mova://capture opens the native
  quick-capture dialog and takes no title. Those intents expose no result data
  to EVA, so they cannot supply agenda/search results or completion evidence.

## Decisions (2026-09-14, after review)

Principles Ivan set: use existing apps as much as possible; do it without code
edits to those apps or to EVA; where that is not enough, a no-code extension
system whose packages live in a git repository as a starter marketplace is
acceptable. Three extension paths follow, all feeding one registry, one grant
model, one journal, and one admission policy in EVA:

1. **Declarative packages** (preferred when existing apps need no code changes). A single JSON file per package. The
   tool half of each capability (`name`, `description`, `inputSchema`) is
   byte-compatible with an MCP tool definition using EVA's JSON Schema subset.
   Bindings: `android.intent` (handoff only), `android.content` (reads via
   content providers), `http` (modeled on an OpenAPI operation; credential by
   reference; reads complete on success, writes need declared evidence).
   Packages import over HTTPS from a git-hosted repository with an index file.
   An org-agenda-api HTTP package was developed as a proving candidate, then
   removed from the APK when Ivan clarified that the test should control an
   installed Android app. It remains a separate example/test fixture. The HTTP
   approach does not require mova involvement.
2. **AppFunctions** (platform standard). Apps that adopt Android's
   AppFunctions are discovered and executed through EVA's existing Shizuku
   shell bridge, generalised from the Settings-only allowlist. Needs Android
   16+ and Shizuku on every call. Verified on the Pixel: nine packages already
   expose functions, listing is JSON, execution works by function id, and a
   cold Settings read needed the 30 s default rather than 8 s.
3. **Installed extension apps** (EVA's own AIDL contract, kept because it was
   mostly built). For apps the author controls, on EVA's supported Android versions,
   without Shizuku, with structured results and revision checks. Spec:
   `docs/extension-protocol.md` in EVA.

Asynchrony: bounded synchronous execution everywhere in this pass; intents are
handoffs; expiry after submission is `UNKNOWN`, never "still running";
accepted-then-poll is deferred until the journal can hold job references.
The wait budget is layered: package or adapter default per capability, EVA
defaults of 20 s voice and 30 s typed, user override per extension and per
mode, all clamped to 60 s. Concurrency is four global and one per provider
instance, with excess refused as busy. Whether the unmerged EVA `threads`
branch merges before or after this work is still open.

Mova implementing paths 2 and 3 was an intermediate plan, subsequently parked.
The installed-service adapter stays supported, with discovery on and ordinary
per-extension/read/write authorization; it is not gated as a developer-only path.
No mova app-side extension code is required or assumed for the chosen direction.
A draft-only Messages package was explored but rejected as the final proving
case: Ivan wants actions to run without another manual Send step. EVA's existing
native contact search and direct SMS sending must be preserved. The next proving
app should expose a useful existing intent API without requiring an app update.

## Path 3 detail: the installed-app AIDL contract (first-draft notes, superseded where they conflict with the decisions above)

The contract was reviewed and frozen on the EVA side as
`docs/extension-protocol.md` in the EVA repo (branch `installed-extensions`).
That document is authoritative; the summary below records what changed from
the first draft of this plan and why.

### Contract summary

- Provider apps export a `Service` with intent-filter action
  `com.colonelpanic.eva.action.EXTENSION` and integer meta-data
  `com.colonelpanic.eva.extension.version` = 1. One such service per package.
- Two copied `oneway` AIDL interfaces: `IEvaExtension` with
  `describe(requestId, deadline, callback)` and
  `execute(invocationId, expectedRevision, capability, argumentsJson, deadline,
  callback)`, and `IEvaExtensionCallback.onResult(requestId, responseJson)`.
  Synchronous AIDL was rejected because a blocked Binder call cannot be
  cancelled by a coroutine timeout and a provider can hang in `describe` too.
- Deadlines are absolute `SystemClock.elapsedRealtime()` millis. Describe gets
  five seconds; execute gets the advertised `maxDurationMillis`, at most 60 s.
- Describe returns a descriptor with `descriptorRevision`,
  `authorizationScopeRevision`, per-capability `effects` (`read`/`write`/
  `unknown`), execution semantics, and a result contract. Execute checks
  `expectedRevision` immediately before starting and refuses with
  `stale_descriptor` on mismatch, so a provider server or account switch can
  never be executed against an old grant.
- Statuses are terminal only: `completed`, `not_executed`, `failed`,
  `handed_off`, `unknown`, with reason codes `stale_descriptor`,
  `not_configured`, `busy`, `invalid_arguments`, `unauthorized_caller`,
  `deadline_exceeded`. A lost reply after a POST is `unknown`; nobody retries.
- Input schemas are flat scalar objects (EVA's `ToolSchema` subset, no arrays).
  There is no comma-separated-list convention; none of mova's operations need
  one.
- Caller identity is verified on every AIDL method from `Binder.getCallingUid()`
  by package plus signing certificate. The proposed mova release policy accepted
  `com.colonelpanic.eva` signed with the EVA release certificate (SHA-256
  `68:8D:F1:78:27:DD:9A:00:27:05:BA:F0:40:0C:80:F8:F4:65:0C:6E:87:C3:FC:91:D4:1F:32:BE:28:7F:8B:68`,
  recorded during the original review). The proposed debug policy also accepted
  `com.colonelpanic.eva.debug` when it is signed with the same certificate as
  mova itself. A package-name allowlist alone was rejected because it does not
  establish the publisher of a fresh install.
- Installing a provider grants nothing. EVA lists it disabled; enabling grants
  claimed reads; each write or unknown-effect capability has its own grant
  switch. In-turn confirmation is deferred, so this pass has no spoken
  approval flow.

### EVA implementation decisions recorded during this session

The first three commits (e16ca1a, bce240f, efd8f05) established strict protocol
codecs, immutable registry snapshots with string revisions and journal migration,
and receipt provenance separating external prose from developer/system authority.
Later decisions were implemented in discovery/AIDL execution, persistent grants,
and 64-tool admission (154e612 through 3874ee5), superseding the initial fixed
registry and 32-tool limit described in the baseline findings above.

The adapter boundary and namespaced instance identities were kept independent of
transport (a56b82c, c8e9c0e). Declarative codecs, bounded projections, effect floors,
and typed host ports followed. Bounded waits and receipt-budget persistence
(77c6619), HTTP/intent hosts (cfb8dd3), and asset/settings integration (1c7d984)
carried the same grant and journal policy into packages. The org-agenda asset was
subsequently replaced with a Messages intent example (025b689); that handoff
example did not satisfy Ivan's later requirement for a fully executed action.

The deliberate v1 limits were no live catalog rotation, no polling/reconciliation,
and no automatic retry of uncertain writes. Additions appear at the next
connection; revocation and changed contracts block dispatch. The controller's
one-action-per-request gate includes reads, despite its contact-lookup prompting;
this prevents lookup→send in one request on this branch and must not be mistaken
for the intended contact-search capability being absent. The unmerged threads
branch was not integrated.

### Parked mova-side proposal

A separate agent reported work under android/app/src/main/java/com/colonelpanic/mova/eva/
and JVM tests during the intermediate plan. That work is parked and is not an
assumed deployed provider or a prerequisite for EVA. The proposed responsibilities
were:

- `OrgAgendaClient.kt`: basic-auth client sharing the widget credentials; a
  POST that fails before the body is sent raises a distinct exception so
  `not_executed` and `unknown` stay separate. `CaptureHttp` delegates to it.
- TodoRef.kt: self-sufficient encoded references containing an ID, or file,
  position, and exact title, revalidated before mutation. The earlier
  pos:<pos>@<file-hash> sketch was insufficient on its own and was superseded.
- `EvaCapabilities.kt`: transport-agnostic catalog, argument validation driven
  by the emitted schemas, and the five capabilities.
- `EvaExtensionService.kt`: thin binder implementing the AIDL, caller
  verification, bounded executor, deadline budgeting.

| name | args | backing call | effects |
| --- | --- | --- | --- |
| `capture` | `title` (req), `template` | `GET /capture-templates`, `POST /capture` | write |
| `agenda` | `date`, `span`, `includeOverdue` | `GET /agenda` | read |
| `search_todos` | `query` (req), `limit` | `GET /get-all-todos`, filtered locally; reports when the set was capped | read |
| `complete_todo` | `ref` (req) | `POST /complete` by id or file+pos+title | write |
| `custom_view` | `key` (omit to list views) | `GET /custom-views`, `GET /custom-view` | read |

`capture` dropped `body`: `/capture` returns no position, so attaching a body
needs a follow-up `/update` located by title, which is too fragile for v1.

### Verification decisions and deferred proving case

Each implementation slice requires focused JVM tests, just format after Kotlin
edits, and just check before handoff. Fake-host and Robolectric results are not
device verification. The earlier plan to install a mova provider was parked.

The org-agenda Pixel test was deferred, not failed: Ivan is not deploying the
server changes now. It awaits q/limit/total search and strict exact-reference
completion. Nothing in this extension work has a physical-phone verification
result. Existing unrelated Android-feature evidence does not validate extensions.

The agreed continuing sequence was client-side result filtering, bundled intent
migration/share-to-app, repository import, then generalized AppFunctions using
the supplied samples. Client-side filtering removes the need for server-side
search for suitable packages; it cannot make an old mutation endpoint honor
strict lookup. Installed-app intent candidates are being evaluated separately.

## Out of scope for the first pass

- Accepted-then-poll HTTP jobs and OpenAPI callbacks (needs journal job
  references first).
- Mid-session catalog rotation; additions appear at the next connection open.
- An MCP client and any MCP server for org-agenda-api; the declarative HTTP
  package covers the same endpoints without a new process.
- Auto-discovering App Actions capability declarations from installed apps'
  shortcuts XML (investigation note only).
