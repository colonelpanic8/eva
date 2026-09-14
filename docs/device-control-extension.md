# Device control through the extension API

Status: the first bundled runtime slice is implemented; the general package
loader, reusable artifact results, app-scoped disclosure grants, additional
operations, and public extension admission described below remain proposed. The
[standalone experiment](../experiments/device-control/README.md) remains the
broader platform probe. This specification builds on the
[capability and backend contracts](architecture.md#4-capabilities-planning-and-execution)
and [extension package contract](architecture.md#6-custom-extensions).
Reviewed with Fable 5.1 following the emulator proof; this remains the agreed
direction beyond the implemented slice, with prerequisites called out below.

## Decision and boundary

Ship device control as an optional bundled extension, tentatively
`eva.device-control`, with capabilities under `eva.device.*`. Its definitions
use the same package format, loader, version checks, catalog projection,
preparation, authorization, dispatcher, and outcome envelope as other extensions.
The definitions bind to installed, versioned native Android operations.

Shizuku is an implementation dependency of those operations. UiAutomation,
Binder/AIDL, helper startup, permission callbacks, input injection and capture
are private platform details. They are not a new extension protocol or a
provider-owned execution path. The experimental `IDeviceProbe` interface is a
test harness, not the proposed public API.

```text
Bundled or imported action definition
    → common capability catalog → conversation provider → ToolProposal
    → common prepare / authorize / dispatch / journal
    → installed native operation → Shizuku helper → Android
    ← common outcome + structured data + evidence / artifact references
```

Initially include the native implementation in EVA and make its package
optional to enable. This is a bundled extension, not a separately installed
extension APK. Updating the helper requires an app release; new definitions
using an existing public operation do not. Downloaded native code and a general
extension-app IPC ABI remain deferred as in the architecture.

Bundled provenance grants no extra execution authority. The installed operation
table declares `exposure: bundled | public`, controlled by the runtime rather
than package JSON. Device operations start as `bundled`: the loader can bind
their shipped definitions but rejects imported references. Execution still uses
the same contracts and grant enforcement. Once an operation is admitted to
the public operation table, imported packages may reference it subject to the
same runtime constraints, effects, availability checks, and instance-scoped
grants. Importing a JSON document cannot install Shizuku, add a native operation,
grant permissions, or expose internal helper controls.

## Reuse before adding contracts

| Concern | Existing general mechanism | Device-control responsibility |
| --- | --- | --- |
| Definition and discovery | Versioned package, `CapabilityDescriptor`, `CapabilityAdapter` | Declare schemas, native binding, execution semantics; invalidate when access changes |
| Setup and target selection | `Availability`, `Preparation`, requirement resolution, permission gateway | Resolve device/display and permitted app scope; request Shizuku access through UI |
| Authority | `AuthorizedInvocation`, scoped grants, `ProposalContext` | Recheck platform access, target scope, reference ownership and operation effect floor |
| Execution | `ExecutionBackend.execute` | Perform one bounded operation; no hidden planner or retry loop |
| Cancellation | `CancelableBackend` and dispatcher cancellation | Signal the helper, release held input, report confirmed/partial/uncertain effects |
| Recovery | Journal and optional `ReconcilableBackend` | Return retained authoritative evidence if available; a new screenshot cannot prove an old tap happened |
| Model continuation | Controller-owned lineage, chain grants and budget | No device-specific continuation exemption |
| Screen bytes | Proposed reusable result artifact references | Store bounded captures locally; disclose only through the provider's general content path |

The native backend owns its control context and opaque observation references.
Do not introduce a universal lease framework, a second dispatcher, or a
`DeviceConversationProvider`. The shared additions needed are result artifacts,
runtime-owned operation exposure, and the already-proposed general
catalog/schema/outcome/authorization contracts that the current Android harness
has not implemented yet.

## Operations and package bindings

Native operation names and version fields below are proposed spellings,
not an implemented manifest schema. The package loader resolves them through
the installed operation table; it never reflects a class name supplied by a
package.

| Capability | Native operation, version 1 | Parameters and completion criterion |
| --- | --- | --- |
| `eva.device.observe` | `device.ui.observe` | Resolved target; return a bounded observation and requested permitted capture |
| `eva.device.tap` | `device.ui.tap` | Observation and node references initially; coordinate targeting later; report input delivery and post-observation |
| `eva.device.swipe` | `device.ui.swipe` | Observation reference, endpoints and bounded duration; report delivered/partial input and post-observation |
| `eva.device.set_text` | `device.ui.set_text` | Observation reference, editable node reference and explicit text; replace the field and verify when possible |
| `eva.device.type_text`, later | `device.ui.type_text` | Insert explicit text at a revalidated caret; advertise only supported text/input methods |
| `eva.device.key`, later | `device.ui.key` | Observation reference and an allowed key enum; no arbitrary keycode/meta-state escape hatch |
| `eva.device.navigate` | `device.ui.navigate` | Observation reference and `back`, `home`, or `recents`; destination/system-surface authority is checked |

The first integrated slice should expose observation, tap and whole-field text
replacement. Other operations require their own behavior tests. Launching an
app remains an intent capability, and AppFunctions remains a distinct adapter;
neither silently falls back to UI control. Helper creation, permission grants,
shell execution and arbitrary Binder transactions are not public operations.
Observation has a bounded deadline; no operation searches for a label, waits
indefinitely for a condition, or performs another model-selected step internally.

A candidate bundled tap definition illustrates reuse of the existing format:

```json
{
  "formatVersion": 1,
  "id": "eva.device-control",
  "version": "0.1.0",
  "capabilities": [{
    "name": "tap",
    "description": "Tap the observed screen and return what was observed afterwards.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "observationRef": {"type": "string", "minLength": 1, "maxLength": 128},
        "node": {"type": "string", "minLength": 1, "maxLength": 128}
      },
      "required": ["observationRef", "node"],
      "additionalProperties": false
    },
    "binding": {
      "kind": "android.native",
      "operation": "device.ui.tap",
      "operationVersion": 1
    }
  }]
}
```

Arguments map directly into the named native operation in this first binding
form. Its runtime schema and constraints remain authoritative. The first
text-only projection uses node targets: the operation resolves the node in the
referenced observation and revalidates it before input. Coordinate targeting
can be added with negotiated image support and an appropriate operation/schema
revision. It must use bounded integers and check the referenced geometry at
runtime. If a later schema offers both node and coordinate forms, native
validation must require exactly one complete form; schema subset v1 has no
`oneOf`. The runtime also defines the
minimum effects and actual completion semantics. Definitions cannot substitute
hidden text, coordinates, or targets or describe a generic tap as a harmless read.
Imported names derive from the installed-instance ID, never the reserved `eva`
namespace or the document's self-declared ID. The normal collision rules also
protect the reserved `eva.session.*` control capabilities.

## Grants and control lifetime

Shizuku permission is platform access, not user authorization for each workflow.
The normal preparation path resolves a local device/display and app scope and
checks observation, mutation and disclosure grants separately. Missing setup or
scope produces the existing `NeedsUserAction`, `NeedsTarget`, or
`NeedsAuthorization` flow. The user can pregrant a routine scope so permitted
calls proceed without repeated confirmation.

Use the existing availability states: `NeedsPermission` for absent Shizuku
authorization, `Disconnected` for a stopped helper service, `NeedsUnlock` for
keyguard, and `NeedsForeground` when no eligible interaction can start control.
Missing platform primitives, an incompatible helper operation version or a failed
UiAutomation capability probe yields `Unsupported` with a reason. Probe these
before advertising the operation, then recheck at dispatch; passing on one OS
version does not establish another version's support.

For an app outside the disclosure scope, preparation returns the existing
resolution requirement and no screen content. Only independently permitted
identity/geometry metadata may accompany it; the trusted surface can name the
app requiring a grant. If the foreground app changes after preparation, refuse
before capture/input with `NotExecuted` and a scope-change reason, then use a
new preparation to resolve access. The backend does not grant access or invent
a second confirmation protocol in its result.

Generic screen input can send messages, submit purchases, or change settings.
Its runtime effect floor is broad UI mutation even when a definition is named
"search". Navigation and key operations share that floor: Back can discard a
draft, and a key can submit a form. Such grants disclose that effects cannot be
reliably inferred from coordinates, node labels or a package description. Prefer narrower native
or intent capabilities when their effect contracts fit the request.

For a multi-step workflow the controller owns an explicit chain grant with
permitted operations, target/app scope, deadline and remaining step budget.
The extension cannot mint that grant or turn one authorized tap into an unbounded
workflow. A model-visible reference is a locator, not a bearer authorization.
Each call still checks its originating installed instance/binding, current
authorization, conversation interaction, and target against trusted context.

The backend acquires its helper lazily within that authorized interaction and
serializes use of the controlled display. Separate definitions/instances must
not race input on the same display merely because their adapter IDs differ.
The control context expires on end of interaction, timeout, revocation, package
disable/update, or helper/connection loss. Teardown uses the existing cancellation
and invalidation paths. It prevents queued operations and does not roll back
already-delivered input.

UiAutomation ownership is limited and may contend with tests or other clients.
Report busy/unavailable rather than displacing another owner. Disconnect when
control ends. Start a session from an eligible foreground user interaction;
background support must establish an appropriate Android foreground-service type
and lifecycle before it is advertised. A running helper is not a guarantee that
the client process survives. The emulator proved short background calls only.

## Observation and action references

An observation records the selected display/window, geometry and rotation,
capture interval, content generation, permitted node summary, and optional image
artifact. The backend retains the corresponding target/node mapping. Return an
opaque `observationRef`, scoped to its installed instance, authorized interaction,
target/app scope, grant revision, boot and helper epoch. Node references are valid
only inside that observation. References from another instance, expired context,
or revoked grant are rejected before input.

An observation is single-use for mutation. Atomically reserve it for the logical
invocation immediately before attempting input, after the freshness checks.
Another call cannot spend it concurrently or after an uncertain attempt.
Duplicate delivery of the same call returns its recorded receipt, not another
injection. A successful mutation issues a fresh post-observation reference linked
to that producing invocation. A guard rejection or uncertain result requires a
new observation before a new attempt; rereading history never renews a reference.
This complements live change detection rather than replacing it.

The first public schema subset can return an explicitly bounded textual node
summary with node references, plus closed scalar/object metadata. This summary
is a presentation, not a string-encoded JSON schema workaround. Advertising a
structured node array requires a later negotiated schema subset; v1 currently
does not support array schemas. Image references belong to the generic result
content envelope, not to an Android-only transport.

Define a versioned line grammar, bounded to 100 nodes and 16 KiB of UTF-8 text
before enclosing transport serialization. Quote text/description values with
JSON-string escaping, so screen content cannot create another node/header line.
The header supplies the observation reference, permitted package identity,
display, geometry, rotation, generation, coordinate space and truncation flag.
Node lines supply an observation-local reference, class, bounded text/description,
bounds and state flags. Truncate on Unicode boundaries and mark omitted content.
The complete serialized result must still satisfy the broker's message limit;
reduce the summary or fail explicitly if the envelope would exceed it.

```text
UI1 observation="obs-17" package="com.example.fixture" display=0 width=1080 height=1920 rotation=0 generation=12 space="observation-pixels" truncated=false
NODE ref="n1" class="android.widget.Button" text="Increment" description="" bounds=48,343,1032,469 enabled=true visible=true clickable=true editable=false
IMAGE omitted="provider-does-not-support-images"
```

This is a proposed output grammar, not executable instructions. No current
integrated provider advertises screenshot support. The first conformance path
uses the declared text projection with an explicit omitted-image marker; captures
remain local for inspection. Stable node references, not inferred image pixels,
are the first model-facing targets.

For future coordinate actions, declare one observation-pixel space and render
both the node bounds and any attached image in it. A text-only projection retains
that space: switching provider must not reinterpret an existing reference. Retain
the observation-to-display transform, crop, rotation and scale in
the observation; callers cannot override them. Resolve coordinates and node
targets only after checking age, geometry, window identity, content changes,
visibility and occlusion against the live target. Capture and hierarchy are not
atomic: report inconsistency and reacquire rather than inventing a synchronized
snapshot. Do not suppress change events by a time window around our own input.

These checks reduce stale actions but cannot eliminate the final race with
another app or user. Unknown scope or a changed target refuses input and asks
for a new observation. Detecting direct user takeover remains a separate platform
test; until then, provide an explicit Stop action and do not claim automatic
takeover detection. Monotonic freshness comparisons require the same boot/helper
epoch; historical receipts continue to use wall-clock creation times.

## Results, artifacts and disclosure

Reuse `ExecutionOutcome` and its common structured data/evidence envelope.
`Completed` for a tap means the defined input sequence was acknowledged and a
post-observation was obtained. It does not mean the user's task is complete.
Keep input-delivery evidence distinct from observed state and model interpretation.
The returned post-observation is evidence of state after delivery and the basis
for the model's progress claim; acknowledgement alone is not progress evidence.
If input may have reached Android but capture or acknowledgement fails, return
`Unknown` with any known partial effects; do not retry to repair missing evidence.
Failure before injection can return `NotExecuted` with a structured reason.

Cancellation follows the existing contract: request cancellation separately
from executing work, check a per-operation cancellation generation, and release
held touch/key state. A cancellation channel must remain serviceable while a
long call is executing; `oneway` alone is not a scheduling guarantee. Report
partial text/gesture effects rather than claiming rollback. Do not automatically
switch text-entry strategies after a possibly effective attempt.

Introduce a reusable `ArtifactRef` in correlated result content: an opaque ID,
media type, byte count/digest, optional dimensions, creation/expiry metadata and
availability. The local artifact owner tracks producing invocation/instance,
target and disclosure scope. Neither the model nor package chooses a filesystem
path, arbitrary URL or Binder descriptor. Screenshot bytes move from the helper
to app storage using a descriptor; providers never see that platform transport.

Before provider submission, EVA authorizes release of each content item to the
selected provider and resolves permitted artifacts through the general content
adapter. A provider translates the image to its native representation, with
negotiated size/type limits. Reopening with another provider rechecks disclosure.
An image-incompatible provider receives an explicitly supported text-only
projection or a compatibility rejection; no silent image loss or fabricated
visual evidence. Textual UI summaries require disclosure authorization too.

Keep raw screenshots ephemeral and excluded from backups. Persist bounded,
permitted evidence metadata and digests with the journal. On expiry, historical
results remain replayable as recorded results with an unavailable artifact;
do not recapture or re-execute to recreate expired bytes. Full node text is not
automatically durable simply because it is smaller than a screenshot.

`FLAG_SECURE` blanked the test fixture's image while leaving its hierarchy
readable. Do not infer permission to disclose one surface from another, or detect
protection by black pixels. A protection signal, if used, needs its own platform
verification. Until a view can be attributed to the approved disclosure scope,
withhold it or require broader explicit scope; never silently capture overlays
from unapproved apps into an ostensibly app-scoped observation.

## Integration sequence and acceptance

1. Preserve the standalone proof and repeat it on the intended Android 17 device
   or emulator. Record API signatures, display handling, access denial, helper
   loss, secure surfaces and ownership contention without upgrading untested claims.
   Work on the generic loader can proceed independently of this compatibility run.
2. Implement the common package/native-operation loader and schema validation
   already required by the architecture. Migrate one existing intent action as
   a contract check. Add the optional device definitions through that loader;
   provider, dispatcher and receipt code must not switch on their capability IDs.
3. Add generic result artifacts and operation-owned reference validation. Prove
   one authorized observation through the common dispatcher and first provider,
   including permission denial, provider incompatibility, disclosure and expiry.
4. Add one bounded input operation with existing preparation/journaling and
   cancellation/recovery semantics. Prove it against the fixture, including
   stale references, scope mismatch, partial input and process death.
5. First prove a human-stepped flow: one user input observes; a later input performs
   one action and receives its post-observation. Keep the authorized interaction
   open across those turns; an expired reference yields a new observation request.
   Implement general chain authorization/budgets before enabling multi-step model
   control. The current broker permits one phone call per input, including reads;
   preserve that restriction until both phone and broker enforce the broader
   controller-owned policy. Negotiate a controller-authorized per-input budget
   with the relay, enforced as the minimum of that budget and the relay's hard
   cap. Keep trusted accounting at both ends; results report the remaining
   budget, but model arguments cannot increase it. Reads also consume steps.
   Do not exempt device tools by name.
6. Promote an operation from bundled to public only after imported-binding
   conformance passes. An import may narrow allowed targets; it cannot hide fixed
   node references, coordinates, or text in binding constants. Prove that an
   imported definition can narrow an installed operation without changing provider/dispatcher code,
   cannot lower its effect/permission floor, cannot reuse another instance's
   references or grants, and stops executing when disabled or updated.

Conformance also covers input after expired image/observation, rotation between
capture and dispatch, out-of-scope overlays, duplicate call delivery, cancel
versus completion, loss of ownership, and unchanged behavior of stock Android
actions when the device extension is unavailable. A general extension package
is the implementation destination; the fixed experimental workflow is evidence
for its native mechanism only.
