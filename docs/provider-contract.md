# Provider adapter contract

Status: target contract, partially implemented; see [implementation status](implementation.md). This
specifies the provider boundary in [architecture.md](architecture.md#3-conversation-provider-contract).
Reviewed with Fable 5.1; the provider and action-definition boundaries below are
the agreed implementation direction.

## Purpose

EVA supplies the same domain objects to every conversation provider. Each
provider adapter translates those objects into its API's requests and translates
responses back into EVA events. Capability discovery is a separate adapter
boundary: extension packages, AppFunctions, and MCP produce capability records
without knowing which model will consume them.

```text
Bundled/imported definitions ─┐
AppFunctions discovery ───────┼─> EVA capability catalog ─> provider adapter ─> model API
MCP discovery ────────────────┘                              <─ normalized events <─
```

The model selects an advertised tool and supplies arguments. EVA retains its
execution binding and authorizes and executes the call. Providers never receive
an Android launcher, MCP connection, extension interpreter, or dispatcher handle.
A development host broker is a transport within a provider adapter, not a new
capability owner or the canonical definition of the model interface.

## Input supplied by EVA

The `ConversationProvider` and `ConversationSession` ports in the architecture
remain the public interface. Their supporting records have these responsibilities:

| Record | Contents |
| --- | --- |
| `SessionOpenRequest` | Conversation/session identity, new connection epoch, provider configuration reference, attributed instructions, bounded canonical history, initial tool catalog, requested modalities, optional audio lease |
| `ProviderToolCatalog` | Immutable revision and a list of model-facing tool definitions; no execution bindings or credentials |
| `ProviderToolDefinition` | Stable EVA capability ID, title, description, input JSON Schema, optional output JSON Schema, result-content requirements/projections for compatibility, and concise execution limits such as draft versus send or handoff versus completion |
| `ConversationInput` | EVA input/turn identity and user text or the appropriate audio input reference; source and order are explicit |
| `CorrelatedToolResult` | Original call identity, logical invocation identity where claimed, outcome, structured result/evidence or a resolution requirement, optional artifact content references, and a bounded explanatory message |
| `ResponseRequest` | Identity of the response generation EVA authorizes and the input/results/notices it follows |

Schemas use JSON values; the current Android harness's `Map<String, String>`
is not the provider contract. Which schema features can be advertised is
versioned separately, as specified below. Instructions
from skills or extension bundles are attributed context. They do not become
tools unless an installed capability supplies an executable definition.

For example, the catalog's model-facing projection of a map action is:

```json
{
  "capabilityId": "eva.android.maps.search",
  "title": "Search maps",
  "description": "Open a map search for a place or address.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "destination": {"type": "string", "minLength": 1, "maxLength": 500}
    },
    "required": ["destination"],
    "additionalProperties": false
  },
  "executionDescription": "Opens another app; the result confirms handoff only."
}
```

The private registry binding contains the intent mapping. An equivalent
definition from an imported package follows the identical projection path.
Sending an SMS and preparing an SMS draft have distinct definitions and effects.

Canonical history contains ordered user content, assistant content, tool calls,
tool results, and notices. Correlation and ordering survive translation. The
adapter may need to flatten system instructions or serialize a tool result into
an API's text field, but cannot merge tool evidence with user instructions or
turn an old user request into new executable input on reconnect. Missing source
content, such as unavailable voice transcripts, is represented as missing.

## Adapter responsibilities

1. Report capabilities before opening: text/audio support, structured tool path,
   schema subset and size limits, response control, interruption, catalog updates,
   resumption, and authentication requirements.
2. Check catalog compatibility. Give each tool a protocol-safe name and retain an
   immutable mapping from that name to its EVA capability ID and catalog revision.
   Scope names so identically named tools from two integrations cannot collide.
3. Translate descriptions and schemas into the API's tool declarations. Translate
   history and input into the API's message/item format. The controller contains
   no provider-name branches.
4. Assemble streamed argument fragments before emitting `ToolCallReady`. Never
   dispatch from partial JSON, assistant prose, or a claimed tool execution in
   ordinary text. Tool calls contain JSON values, not a second command language.
5. Submit each result using the provider's original correlation identity. A tool
   result may describe handoff, failure, uncertainty, or required user resolution;
   transport success is not action completion.
6. Translate provider responses, errors, and lifecycle events into the common
   event stream. SDK types and raw wire messages stay inside the adapter.

MCP is one discovery/execution protocol; it is not required as the universal
model-facing transport. A provider with native function calling receives native
tool declarations. A provider with a supported bridge receives the same catalog
through that bridge. A provider without a structured tool path cannot advertise
action support.

## Compatibility and acknowledgement

Use a versioned JSON Schema subset and explicit per-adapter compatibility
checking. Subset v1 covers closed objects, nested properties, required fields,
strings with length bounds, integers/numbers with bounds, booleans, and scalar
enums. Arrays and nullable values require a later subset version; reject them in
v1 rather than coerce them to strings. External references and executable
validation are not part of the portable definition format. The phone must
implement and test a schema-keyword whitelist and generic argument validation
before advertising v1 support. The preliminary broker's object-shape check
alone does not establish schema compatibility.
Validation recurses through nested objects; closed-object rules apply at every
level. A nested schema's round-trip through the real provider is required
conformance evidence, separate from a fake transport accepting its JSON.

Adapters may apply semantics-preserving transformations, such as expressing an
optional field in a provider's required-plus-nullable representation, with a
deterministic reverse mapping. They must retain the distinction between omitted
and explicitly null values where the source schema makes it meaningful; if the
provider cannot express that distinction, the tool is incompatible. Never
silently remove validation constraints, flatten structured arguments into prose,
or replace an unsupported tool with model-generated commands. EVA validates the
original schema again before execution.

`CatalogAck` reports `Accepted(revision)` or `Rejected(revision, reasons)`, with
incompatibilities keyed by capability ID. Rejection does not activate the proposed
revision. The controller must explicitly build an acceptable catalog under a new
revision or reject the configuration; an adapter cannot silently advertise fewer
tools than EVA believes it has installed. For the first provider slice, use one
fixed compatible catalog for the connection and reject incompatible setup. No
live replacement, automatic truncation, or automatic reconnect is required.

## Output consumed by EVA

Normalize the existing architecture's events: connection readiness, finalized
input/transcripts, response start, text deltas and final content, complete tool
calls, response end, interruption acknowledgement, usage when available, and
provider failures. Text-only adapters emit no fabricated speech events.

Every actionable event is correlated with its connection epoch, EVA turn,
response generation, and acknowledged catalog revision. A tool call additionally
retains its provider-session/call identity. The epoch rejects stale events; the
stable provider-session/call identity deduplicates replay on resumable transports.
Neither a fresh epoch nor a differently formatted tool name makes an old action
safe to execute again.

The result envelope carries the complete architectural outcome vocabulary:
`Completed`, `Accepted`, `HandedOff`, `NotExecuted`, `Failed`, `Canceled`, and
`Unknown`, plus `RequiresResolution` replies. It preserves structured result
data, evidence, and resolution details. Adapters must not hardcode success to
mean an Android intent handoff; new tools use the same result envelope.

The controller owns response ordering, authorization, dispatch, and the durable
reply. It may receive a tool call, return a requirement, receive the user's answer,
and continue through the reserved resolution capability. Native provider-side
tool execution must not bypass this loop. The initial typed provider slice can
use ordinary conversational clarification before proposing a complete action;
it must not claim that token-based confirmation is implemented until it is.

## Result artifacts and media compatibility

The proposed common result-content envelope can carry `ArtifactRef` values for
bounded binary content, including screenshots returned by a
[device-control extension](device-control-extension.md). This is a reusable
provider feature, not a device-specific tool protocol. A reference identifies
locally owned content and carries media type, byte count/digest, optional
dimensions, expiry and availability. Its owner retains producing invocation,
installed instance and target/disclosure scope; possession of the ID is not
authority to retrieve or disclose its bytes.

EVA authorizes disclosure before submission. The provider adapter resolves only
approved content through an app-owned content access port and translates it to
the provider's supported image/file representation. Platform file descriptors,
app-private paths, native handles and provider-specific upload URLs remain behind
their respective boundaries. Model arguments cannot choose a local file or fetch
URL. These content variants are separate from a capability's JSON data schema.

Report supported tool-result media types and byte/dimension limits in provider
capabilities. The catalog's `ResultContentContract` supplies required/optional
media types and supported projections; it is compatibility metadata, not a
provider-specific upload instruction. Existing data-only tools require no media.
Catalog compatibility includes required result modalities. EVA may
select a declared text-only projection where the capability supports it, or
reject the configuration. Do not silently omit a required screenshot or imply
that text evidence is visual evidence. Provider changes recheck disclosure before
including old results. UI text and images both count as potentially sensitive
tool content and remain untrusted data rather than user instructions.

For replay, preserve the original call/outcome and evidence metadata. An expired
or removed artifact is reported as unavailable; never execute the tool again or
capture a new image under the original reference. Raw media retention is separate
from durable receipts. Image transport, expiry/disclosure enforcement, and a
real-provider media round trip need conformance tests before being advertised.
The current text-only broker does not implement this content extension. The
first device-extension conformance case keeps images local and returns its
declared textual hierarchy projection with an explicit omitted-image marker.
Test that a text-only provider receives that marker and that an image-required
capability is rejected rather than silently downgraded.

## First implementation and conformance evidence

Implement a text-capable conversation adapter using the proven subscription
broker. The phone supplies the catalog; the host translates it into dynamic
tools and routes calls back to the phone. Subscription credentials remain on
the host. Its WebSocket envelope is an adapter detail, with explicit version,
session/input/call correlation, bounded messages, authenticated setup, and no
automatic resubmission after a disconnect. The first development connection can
use explicit USB forwarding to a loopback-only device endpoint.

The phone-side provider adapter owns the protocol-safe name mapping; the broker
relays that catalog without inventing app-specific tool names. Any unresolved
name collision rejects setup. Start acknowledges the fixed catalog revision
only after provider setup succeeds, and associates it with the broker session
ID. Each explicit input has one EVA response generation in this first slice;
retain the corresponding provider turn ID in events and tool calls. The initial
wire protocol uses `generationId = inputId` for this one-generation restriction.
The phone journals calls under broker-session plus provider-call identity. Both phone and
broker enforce a single new phone action per input, independently of prompts,
until the broader continuation policy is implemented. This restriction applies
to read-only calls too in the initial slice.

This adapter reports coupled provider response control: `submit` buffers input,
and `requestResponse` starts the provider turn. The controller supplies the
assistant instructions; the broker retains only transport/isolation policy.
Provider context currently starts empty on each ephemeral connection, with no
resumption or history seeding. Show that session boundary in the UI; local
action receipts survive. Provider text truncated by a transport limit carries
an explicit truncation indicator. A late phone outcome remains in the local
journal; delivering it as a notice on a later session requires the notice/context
path and must not be claimed by this initial bridge.

The first slice is a restricted, incomplete action runtime: preparations are
Ready or Rejected, and the resolution tool is absent. It uses ordinary
conversational clarification before dispatch and has no token-based `NeedsTarget`
or `NeedsAuthorization` continuation. These remain required by the full
architecture, including the reserved resolution
tool. Native networking needs `INTERNET`; permit cleartext only for the explicit
loopback development endpoint, and verify that configuration on the phone.

This comes before additional action-specific implementations or a complete
extension importer. Existing native handlers can remain transitional bindings;
the catalog and provider interfaces must not embed their capability IDs in
protocol logic. Native audio now uses the same control adapter in a restricted no-tools mode.
The native WebRTC media port handles offer/answer and local audio lifecycle; the
broker requires an empty catalog and rejects all tool requests. Controlled voice
actions await input/generation correlation for delegated backend turns.
The [live correlation probe](native-voice-testing.md#voice-action-correlation-gate)
observed a finalized user transcript after its own handoff and backend turn
started. Transcript arrival alone must not open a new input or invalidate an
existing one.

Conformance tests should establish:

- The same canonical request works through a fake adapter and the first real
  adapter; provider wire names never change the selected EVA capability.
- A tool supplied through the catalog needs no change to provider routing code.
- Schema incompatibility is reported; malformed/unknown calls never execute.
- Fragmented arguments produce one complete call, and duplicate call IDs return
  the recorded result without repeating the action.
- The model receives actual tool evidence and continues its response; ordinary
  assistant text is rendered separately from execution receipts.
- Disconnects, timeouts, stale events, and mismatched result IDs cannot initiate
  or replay an action. Connection failure is not reported as successful action
  completion.
