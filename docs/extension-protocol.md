# EVA extension protocol

EVA integrations share one capability registry, grant model, dispatcher, and
invocation journal. This reference covers the two extensible formats implemented
in the app. [Architecture](architecture.md) explains the runtime;
[Operations](operations.md#device-verification) records verification limits.

| Path | Use it for | Current boundary |
| --- | --- | --- |
| Declarative JSON packages | Existing app intents or HTTP APIs, without app changes | Import, preview, install, grants, intent and HTTP execution implemented; content-provider binding parses but has no execution host |
| Installed Android extension service | Code and structured protocol responses supplied by an app author | AIDL runtime implemented and JVM-tested; no real provider device verification |
| Native Android adapters | Operations requiring EVA code or platform privileges | Existing adapters include messaging, Settings AppFunctions and Shizuku device control; no universal AppFunctions adapter |
| Media apps | Any installed player, through the routes it already registers | Discovered, not authored: each player is one extension with per-operation grants (see [Architecture](architecture.md#android-capabilities)); nothing to import or install |

Prefer declarative packages when an existing interface can do the work. Packages
cannot download arbitrary code or expand Android permissions. A new execution
mechanism requires an EVA implementation. General MCP connectivity is planned,
not supplied by accepting MCP-shaped tool definitions.

## Shared shapes

Both formats describe a capability the same way, and both return results the same
way. The Kotlin codecs are the executable oracle; the JSON Schemas under
[`docs/schemas/`](schemas/) document the accepted shapes for authors and
validators, and [`docs/examples/`](examples/) holds fixtures that the JVM tests
decode.

| Schema | Describes |
| --- | --- |
| [`tool.schema.json`](schemas/tool.schema.json) | The MCP tool object embedded in every capability |
| [`package.schema.json`](schemas/package.schema.json) | A declarative package file |
| [`index.schema.json`](schemas/index.schema.json) | A repository index |
| [`extension-descriptor.schema.json`](schemas/extension-descriptor.schema.json) | An installed-app `describe` reply |
| [`extension-result.schema.json`](schemas/extension-result.schema.json) | An installed-app `execute` reply |

### Capability

A capability object contains:

- `tool`: an [MCP](https://modelcontextprotocol.io) tool object: `name`,
  `title`, `description`, `inputSchema`, and optional `outputSchema`,
  `annotations`, and `_meta`. EVA requires `title`, which MCP leaves optional. EVA bounds it: names match
  `[A-Za-z_][A-Za-z0-9_]{0,63}`, titles are at most 120 code points, descriptions
  at most 2,000. `annotations` may carry MCP's `title`, `readOnlyHint`,
  `destructiveHint`, `idempotentHint`, and `openWorldHint`; a hint that
  contradicts `effects` is rejected. The MCP tool is what a future MCP surface
  would export unchanged.
- `effects`: `read`, `write`, `external_handoff`, or `unknown`. This is EVA's
  authority for grants; the annotations are hints for models and other clients.
- `execution`: `mode` (`synchronous` or `handoff`), `requiresForeground`, and
  optional `maxWaitMillis` (1–60,000; null or omitted means the mode default).
  Cancellation, idempotency, and reconciliation promises do not exist in v1; a
  later version adds fields for them rather than reserving names now.
- A format-specific execution part: `binding` for declarative packages, `result`
  for installed-app services.
- Optional `_meta`: an object EVA digests into the contract but does not
  interpret. Any other unknown field is rejected. `_meta` is the only place for
  vendor or future-version data.

### Input and output schemas

`inputSchema` is a closed object (`additionalProperties: false`, explicit
`required`) of 0–64 properties named `[A-Za-z][A-Za-z0-9_]{0,63}`. Each property is
a scalar (`string`, `integer`, `number`, `boolean`) or an `array` of one scalar
`items` type with optional `minItems`/`maxItems` (0–64). Scalars accept
`description` and `enum`; strings accept `minLength`/`maxLength` (code points,
0–65,536); integers and numbers accept finite `minimum`/`maximum`; integers stay
within ±9,007,199,254,740,991. No nested objects, nulls, unions, references,
patterns, formats, or defaults. Optional means omitted, not null.

`outputSchema` is optional and describes `structuredContent`. Its root is an
object; properties may be scalars, arrays (up to 4,096 items), or nested objects
to eight levels, and objects may set `additionalProperties: true`. A result whose
structured content violates a declared output schema is malformed.

### Results

Every execution produces EVA's outcome envelope: `status` (`completed`,
`not_executed`, `failed`, `handed_off`, `unknown`), an attributed text `message`,
and optional structured `data`. Declarative bindings build both from the declared
projection; installed-app services return MCP-style `content` text blocks and
`structuredContent`. The model receives both the text and the data, quoted as
untrusted external content, within a 16,384-character result budget that matches
the extension result limit, so provider truncation notes survive. Structured data
that would not fit is omitted whole rather than cut. Receipts persist the data
beside the text, and a resumed conversation replays both.

## Declarative packages

Packages are single JSON files. Starter packages and an index live separately in
[eva-extensions](https://github.com/colonelpanic8/eva-extensions). In EVA, use Extensions →
Browse for repository refresh or URL/file import, inspect the preview, and install.
Installed holds enablement and action grants; Settings holds service configuration
and wait budgets. Refresh alone does not install or authorize anything.

Repository matching happens locally using Android package IDs; it does not upload
the app inventory. Match metadata and icons aid discovery, not trust. Manual imports
remain useful for apps Android does not make visible and for server-only packages.

The shipped [Caffeine](../app/src/main/assets/caffeine.json) and
[Messages](../app/src/main/assets/messages.json) definitions are executable examples.
The [org-agenda fixture](examples/org-agenda.json) exercises HTTP mappings but is
not shipped and is not a claim of a configured server or verified device workflow.

### Package and repository identity

A package is one self-contained JSON file, without scripts or embedded secrets.
Its `formatVersion` identifies the codec, `id` is a publisher-chosen descriptive
name, and `version` is a three-part `MAJOR.MINOR.PATCH` version (no prerelease/build suffix in v1). Each capability embeds the
[shared MCP tool object](#capability); binding metadata and effect declarations
are separate from that tool definition. Unsupported schema features are rejected,
not silently dropped.

On import EVA assigns an instance ID. The file's ID cannot replace an unrelated
installation. Grants bind to that instance and the full canonical content digest;
changed content requires re-enablement. Updates target an existing instance only
through an explicit preview and install action. Historical receipts retain the
previous identity and revision.

A starter repository needs no executable server or git client:

```text
index.json
packages/org-agenda.json
packages/maps.json
```

The index envelope is:

```json
{
  "formatVersion": 1,
  "packages": [{
    "id": "community.org-agenda",
    "version": "0.1.0",
    "title": "Org agenda",
    "url": "packages/org-agenda.json",
    "sha256": "<SHA-256 of the exact package file bytes>",
    "androidPackages": []
  }]
}
```

EVA fetches a raw package URL or an index over HTTPS with bounded response sizes.
Relative package URLs resolve against the index URL; index entries stay on its
origin. A selected package must match the indexed ID, version, and byte digest.
The preview shows the source, operations, effects, destinations, and data disclosure.
Installation uses those exact previewed bytes, without a second download.
Source and package ID are retained for explicit update checks. A changed version
does not retain grants, and a digest is not publisher authentication. A package
can also be copied and hosted independently at a raw HTTPS URL.
The Extensions tab also offers Import extension file. The system document picker grants
temporary read access; EVA bounds the stream to the same package size limit and
copies its exact bytes before preview. No persistent file permission is needed.
Every file import gets a fresh source and instance identity; reimporting a file
creates a separate disabled installation. Use a stable HTTPS source for updates.
Packages optionally declare `androidPackages`, a list of up to 16 Android package
IDs used only as matching hints. The index requires this field (empty for a
server-only extension), and its value must match the downloaded package.

### Binding boundaries

- `android.intent`: fixed action, optional fixed package, fixed URI base with
  typed encoded query slots, and fixed extra names with scalar values/typed slots.
  No model-controlled components, flags, or parsed intent URIs. Minimum effect:
  external handoff. A launch means `HANDED_OFF`, never verified completion.
- `android.content`: fixed content authority and URI, declared projection with
  scalar column types, fixed selection template with typed bound arguments,
  bounded row count, and a declared projection of rows to text. Model arguments
  cannot supply SQL fragments, columns, authorities, or permissions. Queries use
  existing Android permission grants; inaccessible providers are unavailable.
  Minimum effect: read/data disclosure. No insert, update, delete, or provider
  `call()` binding is included.
- `http`: fixed approved origin and OpenAPI-style method, path, parameters
  (`in`), and `requestBody` mappings. Named credential references resolve through
  scoped EVA secret storage; files contain no credentials. Requests, responses,
  and text projection are bounded. Redirects cannot escape the origin or forward
  credentials elsewhere. POST/PUT/PATCH/DELETE floor at write. HTTP success alone
  establishes completion only for reads; writes require declared evidence or
  return `UNKNOWN`.

### Execution and waiting

`ExecutionSemantics` declares `mode` (bounded synchronous or handoff),
`requiresForeground`, and optional `maxWaitMillis`, as described under
[shared shapes](#capability). No accepted jobs or polling are inferred from a 202 response or result prose.

The effective wait is the user override for the extension instance, otherwise
the capability's package/adapter default, otherwise the EVA interaction-mode
default. Voice defaults to 20 seconds and typed to 30 seconds; global per-mode
settings can change either. All paths clamp the effective wait to 60 seconds.
Receipts record all layers, the chosen layer, and any clamp. Voice gets a brief
displayed or spoken waiting cue halfway through the effective budget. An expired
submitted action reports unknown, not failed or known still-running.

Four calls may run globally, one per extension instance, with busy refusal rather
than queuing. Interruption is not undo. No uncertain write is retried. Thread
lifetime and background continuation are described in [Architecture](architecture.md).


### V1 package fields

The root requires `formatVersion: 1`, `id`, `version`, `title`, and
`capabilities` (1–64 entries), with optional `androidPackages`; the full UTF-8 document is bounded to 256 KiB.
Duplicate JSON keys, unknown fields, invalid Unicode, and unsupported versions
are rejected. Object ordering does not affect the canonical contract digest.
The package ID is a lowercase dotted name; capability names are ASCII identifiers.

Each capability contains `tool`, `execution`, `binding`, and optionally
`validators`, `receipts`, `_meta`, and `effects` (`read`, `write`,
`external_handoff`, or `unknown`; omission means unknown). `tool` is the
[shared MCP tool object](#capability): `name`, `title`, `description`,
`inputSchema`, and optional `outputSchema`, `annotations`, `_meta`. Inputs follow the
[shared input schema rules](#input-and-output-schemas): scalars and scalar
arrays in a closed object.

Execution has required `mode` (`synchronous` or `handoff`) and `requiresForeground`
(boolean); `maxWaitMillis` is an optional positive integer or null. Intent bindings require handoff plus foreground; HTTP and content
bindings require synchronous mode. The common wait policy, rather than the file
codec, applies the 60-second clamp.

A typed slot is exactly `{"argument":"title","type":"string"}` or
`{"value":"default","type":"string"}`. Argument types must match the tool
schema; literal types must match their values. Argument slots never change
binding authority. Optional query slots omit a missing argument. Required path
and selection slots must have a value before anything is submitted. An
`array`-typed argument can only fill a `requestBody` slot
(`{"argument":"tags","type":"array"}`, optional array `default`), where it becomes
a JSON array; path, query, intent, and selection slots stay scalar.

Intent fields: `kind`, `action`, optional `uri: {base, query?, opaque?}`, `extras`,
`package`, `class`, `mimeType`, and `packageByName`. `query` and `extras` are maps of fixed names to typed slots. The base
has no existing query, fragment, or user info. Parsed intent, file, content,
JavaScript, and data URI schemes are rejected by this binding; use the content
binding for provider reads.

Content fields: `kind`, `authority`, fixed `uri`, `projection` (map of column name
to scalar type), optional `selection`, `maxRows` (1–100), `maxBytes` (1–16,384).
Selection is a list of `{column, operator, value}` predicates joined with AND;
operators are `=`, `!=`, `<`, `<=`, `>`, `>=`, and string-only `LIKE`.
The compiler produces a fixed selection template with bound selection arguments.
Columns must be declared in the projection. There is no free-form SQL, sorting,
subquery, caller-supplied column, or mutation operation. All projected columns
are rendered to bounded text; extra rows/bytes must be reported as truncated.

HTTP fields: `kind`, `origin`, `method`, `path`, `parameters`, `maxResponseBytes`,
`result`, optional `requestBody` and `credential`. Origins are HTTPS scheme/host
with optional port and no path, credentials, query, or fragment. Methods are GET,
HEAD, POST, PUT, PATCH, DELETE. Paths start with `/`; typed `{name}` placeholders
must have matching `parameters` entries. Each parameter has `in` (`path` or
`query`), `name`, and a typed-slot `value`. No header parameter slots exist.
`requestBody` is `{fields: {...}}`, recursively containing fields objects,
scalar slots, or array argument slots; GET/HEAD have no body. This mirrors OpenAPI operation structure
without claiming to accept an entire OpenAPI document.

`credential` is a named basic-auth reference such as `org-agenda`, limited to
lowercase letters, digits, underscores, and hyphens. It is resolved in the
extension credential namespace, never EVA's model credential namespace.
`maxResponseBytes` is 1–1,048,576. `result` contains a JSON Pointer `pointer`,
`maxBytes` (1–16,384), and optional `evidence: {pointer, equals}` for a terminal
write result. Empty pointer selects the whole JSON response; `equals` is a
non-null scalar. No scripts, filters, inferred success from prose, or polling
expressions are supported. HTTP execution is implemented by the declarative
backend; the bundled Caffeine and Messages packages use intents.

Optional `result.notExecutedStatuses` lists explicit 4xx statuses whose documented
server contract guarantees rejection before execution. Do not add a status merely
because it is an HTTP error. Uncertain write failures remain `UNKNOWN`.

The [org-agenda example](examples/org-agenda.json) contains agenda, default-template
capture with `values.Title`, and a mova create handoff. Replace the example HTTPS
origin and configure the named basic-auth credential in EVA. It contains no
credentials. Search uses the server-side `q` and `limit` parameters; it does not filter items locally.


### Named validation and receipt copy

Optional `validators` maps string argument names to a closed set of validator
names. V1 names are `phoneNumber` (EVA's existing single-phone syntax), `httpUrl`
(absolute HTTP(S) URL with a host and no user info), and `emailAddress` (one
bounded address, without whitespace or recipient-list separators). Unknown names
are rejected at import; validators cannot supply code, regular expressions, or
weaken schema checks. An omitted optional argument is not validated. Named
checks run again before constructing any request.

Optional `receipts` contains `success` and/or `handlerMissing`, each at most 1,000
characters. These are attributed display data, not model instructions, executable
templates, or proof of completion. Handoff copy cannot upgrade `HANDED_OFF` to
`COMPLETED`. Receipt fields and validators participate in the contract digest.

`mimeType` is a fixed MIME type. `packageByName` names a string tool argument
containing the target app's visible name, and is mutually exclusive with fixed
`package`. The Android host must resolve a unique eligible installed app by its
visible label and explicitly set that package; missing or ambiguous matches are
refused. It must not interpret the argument as a component or package identifier.
If declared, an absent or blank app name is refused, never changed to a chooser.
This supports an untyped `ACTION_SEND` / `text/plain` handoff after host integration.
A URI may be omitted for intents that carry only extras.


### Bounded item projection

HTTP `result` chooses exactly one of `pointer` (existing text/JSON projection) or
`items`, along with `maxBytes`. `items` contains:

- `arrayPaths`: 1–4 explicit JSON Pointer-like paths, tried in order until a
  declared array location exists. A single `*` segment may enumerate an array or
  object's values, allowing `/days/*` to collect the grouped agenda arrays.
  Traversal is bounded to 4,096 nodes and the response byte limit still applies.
- `line`: one fixed line template using `{fieldName}` slots, with no control
  characters or executable expressions.
- `fields`: a map of slot names to `{pointer, type, required?}`. Pointers resolve
  relative to the current item. Types are scalar string/integer/number/boolean
  or `stringArray`; missing/null optional fields render `null`. Required or
  wrongly typed fields fail validation. Every declared slot must occur in `line`.
- `maxItems`: 1–100. `truncationNote`: bounded display text, shown when items or
  bytes were capped. Only whole lines are emitted, so identifiers never become
  partial references. Strings and string arrays use JSON quoting, preserving
  exact content while keeping embedded newlines on one output line.
- Optional `totalPointer`: a pointer to a nonnegative integer count in the root
  response. A total greater than the returned array item count means the server
  truncated its response, independently of EVA's item/byte cap.
- The same whole items also become structured `data`:
  `{"items": [{slot: value, ...}], "truncated": bool, "sourceTruncated": bool,
  "total": n?}`, with each item keyed by slot name and carrying the typed JSON
  values (strings, numbers, booleans, string arrays, or null). Items that did not
  fit the text budget are absent from the data as well, so text and data never
  disagree. A `pointer` result whose selected value is an object is likewise
  attached as data, and content queries attach `{"rows": [...], "truncated": bool}`.
  Data larger than 16,384 bytes is omitted whole.

Apart from the explicit local filter described below, the mapping cannot sort,
join records, calculate values, run regexes,
execute scripts, fetch additional pages, poll jobs, or infer completion from text.
It does not silently flatten arbitrary objects or guess alternate response paths.

Argument slots optionally carry `default` (a scalar satisfying the argument's
schema) and `required: true` (refuse before submission if neither input nor default
provides a value). These are binding rules, not additions to the MCP input schema.
A single `select` binding has `argument`, `present`, and `absent` branches; it
chooses between two fully declared bindings based only on argument presence.
Nested selects are rejected. Both branches must use the capability's execution
mode, and effect floors account for both. It cannot construct new destinations.


### Encoded opaque intent values

An intent URI may declare optional `opaque`, a string scalar slot, with a
scheme-only fixed `base` such as `smsto:` or `tel:`. It is mutually exclusive with
`query`. The interpreter percent-encodes the entire value and appends it to the
fixed scheme; the model never supplies a parsed URI, scheme, component, or flags.
The existing forbidden-scheme rules still apply. The shipped
[Messages package](../app/src/main/assets/messages.json) references the named
phoneNumber validator and maps the message into the fixed sms_body extra. It
uses ACTION_SENDTO so Android chooses an installed messaging handler. No package
name, app modification, extension service, or privileged API is required.

### Local result filtering

An item projection may contain:

```json
"filter": {"fields": ["/title", "/category"], "argument": "q"}
```

`argument` must name a string input in the tool schema. `fields` is a nonempty,
distinct list of at most 16 JSON pointers relative to each item. An item matches
when any pointed-to string contains the argument, ignoring case using Unicode
simple case comparison. Missing, null, numeric, object, and array values do not
match. An omitted optional argument disables the filter; an empty string matches
any present string. Input schema constraints can require a nonempty query.
There are no scripts, regexes, normalization, ranking, or recursive array searches.

Filtering occurs before maxItems and line/byte projection. An unfiltered source
with many rows does not imply truncation if all matching rows fit. More matching
rows than maxItems, or exhausted line/byte space, does imply truncation. A declared
totalPointer always describes the source rows before local filtering: when it
exceeds the returned source count, EVA explicitly warns that additional matches
may exist, including when the local result is empty. Without source completeness
metadata, the filter makes claims only about the returned data. It does not fetch
other pages or recover data excluded by a server cap or response-size limit.

A read-only HTTP package can therefore GET an unfiltered collection and define q
only in its tool schema/filter, omitting it from HTTP query mappings. That needs
no server-side search changes. The existing org-agenda fixture still records its
confirmed q/limit/total and strict server contract; its physical-device execution remains unverified.

### Fixed activity components

An `android.intent` binding may add `"package": "moe.zhs.caffeine"` and
`"class": "moe.zhs.caffeine.ToggleActivity"`. The class must be a fully qualified
activity class name and requires a fixed package; it cannot be combined with
packageByName. These are literal approved destinations, never argument slots.
Action, scalar extras and encoded URI slots work as before. Component identity
participates in the package digest and grants. Effects still floor at external
handoff, and packages may declare write. The Android host attempts the explicit
launch without a package visibility pre-query. A missing/disabled component
returns NOT_EXECUTED with install/enable/update guidance; no registry revision
is changed merely because a target is temporarily unavailable. Android permission
and export checks still apply. A successful launch is HANDED_OFF, not proof of
completed work or resulting state.

The bundled example lives in `app/src/main/assets/caffeine.json` and is loaded
by the same codec as other packages. It pins Caffeine's ToggleActivity and integer
Status 1/0 for enable/disable. The documented API provides no state query; EVA
therefore offers no read capability. The package is a concrete example of fixed-component configuration.


## Installed-app AIDL protocol v1

Use this for structured result envelopes from an independently installed app.
Discovery is enabled; providers start disabled and require explicit grants.
This protocol needs neither Shizuku nor an EVA library in the provider app.

An independently installed Android app advertises actions without registration
or app-specific code in EVA. EVA discovers it automatically. The user must enable
it in EVA settings before execution. The provider owns credentials, network
access, validation, and execution; EVA owns grants, dispatch, journaling, and
attributed receipts.

The wire shapes are the [shared shapes](#shared-shapes): a capability is an MCP
tool object plus `effects`, `execution`, and `result`; a reply is EVA's outcome
envelope around MCP `content` and `structuredContent`. See
[`extension-descriptor.schema.json`](schemas/extension-descriptor.schema.json),
[`extension-result.schema.json`](schemas/extension-result.schema.json), and the
fixtures [`extension-describe.json`](examples/extension-describe.json) and
[`extension-result.json`](examples/extension-result.json).

### 1. Discovery and copied AIDL

Add inside the provider manifest's `application` element:

```xml
<service android:name=".eva.EvaExtensionService" android:exported="true">
    <intent-filter>
        <action android:name="com.colonelpanic.eva.action.EXTENSION" />
    </intent-filter>
    <meta-data android:name="com.colonelpanic.eva.extension.version"
               android:value="1" />
</service>
```

Exactly one enabled, exported service per package may advertise this action.
EVA rejects ambiguity rather than choosing the first service. Metadata must be
integer 1. EVA uses action-scoped package visibility and PackageManager discovery,
then binds the explicit discovered component with `BIND_AUTO_CREATE`. No custom
Android permission is defined in v1. Binding can cold-start the provider process.

Copy both files verbatim and enable AIDL generation in the Android build. No EVA
library is required. Package, method order, and signatures are part of the ABI.

`src/main/aidl/com/colonelpanic/eva/extension/IEvaExtension.aidl`:

```aidl
package com.colonelpanic.eva.extension;

import com.colonelpanic.eva.extension.IEvaExtensionCallback;

oneway interface IEvaExtension {
    void describe(String requestId, String requestJson,
                  long deadlineElapsedRealtimeMillis,
                  IEvaExtensionCallback callback);
    void execute(String invocationId, String expectedRevision,
                 String capability, String argumentsJson,
                 long deadlineElapsedRealtimeMillis,
                 IEvaExtensionCallback callback);
}
```

`src/main/aidl/com/colonelpanic/eva/extension/IEvaExtensionCallback.aidl`:

```aidl
package com.colonelpanic.eva.extension;

oneway interface IEvaExtensionCallback {
    void onResult(String requestId, String responseJson);
}
```

All arguments are non-null. IDs are opaque EVA-generated ASCII strings, 1–256
bytes, echoed exactly in the callback. An invocation ID is correlation, not a
credential or a promise of persistent idempotency. `capability` is the descriptor's
local tool name, not its EVA-qualified ID. Each request receives at most one
terminal callback. There is no streaming, progress, accepted-job response,
cancellation method, or callback-initiated execution. EVA ignores duplicate,
unknown-ID, wrong-provider, and already-expired callbacks.

`requestJson` tells the provider what EVA speaks:

```json
{"protocolVersion": 1, "supportedProtocolVersions": [1]}
```

A provider answers with a descriptor whose `protocolVersion` is one EVA listed.
Future EVA versions extend the list; a provider that only knows a version EVA no
longer lists replies `not_executed` with a `not_configured` reason and a message,
rather than guessing.

Authenticate and capture identity in each Binder entry point before scheduling
bounded background work. Binder methods, callbacks, service creation, and
`onBind` must return promptly. Do not perform network work or wait for execution
on Binder/main threads. Bound queues and concurrency; reject excess work as
`busy`. Avoid starting React Native/JS merely to service native extension calls.

### 2. Encoding and bounds

Size accounting uses UTF-8 bytes, even though AIDL transports Java strings.
Reject duplicate object keys, invalid Unicode, nonfinite numbers, unknown fields,
wrong types, and omitted required fields. Object key order is insignificant;
array order is significant. Unknown fields are rejected everywhere except inside
`_meta` objects, which are digested but not interpreted.

| Payload/value | Maximum |
| --- | --- |
| Entire describe callback JSON, including envelope | 65,536 UTF-8 bytes |
| Entire arguments JSON | 16,384 UTF-8 bytes |
| Entire execute callback JSON, including envelope | 16,384 UTF-8 bytes, further bounded by `result.maxBytes` |
| Capabilities in a descriptor | 64 |
| Tool name | 64 ASCII bytes, `[A-Za-z_][A-Za-z0-9_]{0,63}` |
| Revision | 128 ASCII bytes, `[A-Za-z0-9._:-]+` |
| Title | 120 Unicode code points |
| Description or schema description | 2,000 Unicode code points |
| Content blocks per reply | 64 |

Titles/descriptions are nonempty; content text may be empty. Both sides enforce
limits. Truncate human text at Unicode boundaries and set `truncated: true`.
Never truncate JSON, IDs, item references, or schemas: if structured content would
not fit, omit whole items and say so. Reject an oversized catalog rather than
installing a partial descriptor. Limits are below Binder's shared
transaction-buffer limit, but cannot guarantee delivery under pressure.
Diagnostics must not log credentials or raw arguments/results.

### 3. Describe callback

`describe` reads local metadata only: no network request, operation execution,
credential disclosure, or UI launch. Missing credentials do not prevent catalog
description. Changing template/view choices belong in read operations rather
than a network-dependent catalog.

Successful response (one capability shown; 1–64 unique tool names are allowed):

```json
{
  "protocolVersion": 1,
  "status": "completed",
  "reasonCode": null,
  "truncated": false,
  "content": [],
  "descriptor": {
    "protocolVersion": 1,
    "descriptorRevision": "catalog-1.account-1",
    "authorizationScopeRevision": "account-1",
    "title": "Example agenda",
    "capabilities": [{
      "tool": {
        "name": "agenda",
        "title": "Read agenda",
        "description": "Read a bounded agenda for the requested day.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "date": {"type": "string", "minLength": 10, "maxLength": 10}
          },
          "required": [],
          "additionalProperties": false
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "entries": {"type": "array", "items": {"type": "object",
              "properties": {"id": {"type": "string"}, "title": {"type": "string"}},
              "required": ["id", "title"], "additionalProperties": true}}
          },
          "required": ["entries"],
          "additionalProperties": false
        },
        "annotations": {"readOnlyHint": true, "openWorldHint": true}
      },
      "effects": "read",
      "execution": {"mode": "synchronous", "requiresForeground": false, "maxWaitMillis": 30000},
      "result": {"maxBytes": 16384}
    }]
  }
}
```

`effects` is exactly `read`, `write`, `external_handoff`, or `unknown`. An
unclassifiable operation uses `unknown`; omitted effects are malformed, never
implicitly read-only. Effects cover external behavior: writing a remote agenda is
a write even if it changes nothing on the phone. `external_handoff` means the
service passes the request to another component and cannot observe completion.
Effects are provider claims, not execution grants.

`execution.mode` must be `synchronous` and `requiresForeground` must be `false`
in v1: EVA neither launches provider UI nor grants background-activity
privileges. `maxWaitMillis` is an optional integer 1–60,000 (default 30,000).
`result.maxBytes` is an integer 1–16,384 bounding the entire execute
callback envelope. V1 has no binary payloads, URI grants, or non-text content
blocks; `outputSchema` and `structuredContent` carry machine-readable data.

Failure has the same outer fields, `descriptor: null`, status `not_executed`
or `failed`, an applicable reason code (or null for an uncategorized internal
failure), and a bounded explanation in `content`. `truncated` applies only to
that explanation. Success requires a descriptor, null reason, and `truncated: false`.

No authoritative package ID comes from the descriptor. EVA derives user, package,
component, and signer from Android and exposes `extension.<package>.<name>`.
Debug packages have distinct identities.

### 4. Arguments

Arguments are an object of actual JSON values matching `inputSchema`:
numbers/booleans are not quoted strings, and array properties arrive as JSON
arrays of scalars. EVA restores types before IPC. Providers independently validate
arguments and operation semantics (a ten-character date still needs calendar
validation).

### 5. Execute callback, statuses, and reasons

An execute reply is EVA's outcome envelope around MCP tool-result content:

```json
{
  "protocolVersion": 1,
  "status": "completed",
  "reasonCode": null,
  "truncated": false,
  "content": [{"type": "text", "text": "Created the requested entry."}],
  "structuredContent": {"id": "4f2c", "title": "Taxes"}
}
```

`content` is a required array of `{"type": "text", "text": ...}` blocks (optional
`_meta` per block); EVA joins the texts with newlines as the attributed message.
Other MCP block types (`image`, `audio`, `resource_link`, `resource`) are reserved
and rejected in v1. `structuredContent` is an optional object (or null). When the
tool declares `outputSchema`, structured content must satisfy it; a violation makes
the whole reply malformed. Without an output schema any object is accepted. EVA
does not add MCP's `isError`; `status` carries that and more.

| Status | Meaning |
| --- | --- |
| `completed` | Provider has operation-specific completion evidence. A read finished; text may be capped. |
| `not_executed` | Provider guarantees no operation was started and no operation side effects occurred. |
| `failed` | A definitive unsuccessful outcome is known. Partial effects may exist and must be explained. This is not retry authorization. |
| `handed_off` | Another component accepted a handoff; final completion is not known. This grants no UI-launch privileges. |
| `unknown` | Operation may have started/completed; its outcome cannot be established. |

Completed/handed-off responses require null reason. Other statuses may use null
for uncategorized operation failures with an explanatory message. Do not invent
additional v1 codes.

| Reason code | Meaning/status |
| --- | --- |
| `stale_descriptor` | Expected revision differs; `not_executed`. |
| `not_configured` | Required account/configuration absent, or no shared protocol version; `not_executed`. |
| `busy` | Cannot admit request to bounded queue; `not_executed`. |
| `invalid_arguments` | Invalid request, unknown capability, or failed argument/operation validation; `not_executed`. |
| `unauthorized_caller` | UID/package/certificate authentication failed; `not_executed`, without sensitive details. |
| `deadline_exceeded` | `not_executed` before side effects; otherwise `unknown` unless a definitive outcome is already known. |

HTTP success alone does not prove completion of an asynchronous job. A timeout
or lost response after POST normally means unknown. Providers must not retry
uncertain writes, including through HTTP clients/work queues. EVA never retries
execution to repair a lost reply; even reads receive no automatic execution
retry in this pass.

Truncated means incomplete information, including capped ingestion before
filtering. Do not claim exhaustive search or definitive absence from a capped
set. Keep item references whole. EVA attributes provider-reported evidence rather
than independently certifying its truthfulness.

### 6. Revisions and deadlines

`descriptorRevision` is provider-owned and stable across identical descriptions
and process restarts. Change it when any descriptor content changes, including
schema, effects, semantics, or authorization scope. Never reuse it for different
content. Changing server/account/destination authority changes
`authorizationScopeRevision` and therefore descriptorRevision. These opaque,
non-secret tokens must not encode credentials.

`expectedRevision` is the exact descriptorRevision EVA approved. Check it
immediately before beginning the operation, coordinated with configuration
changes, and fix that configuration for execution. Mismatch returns
`not_executed/stale_descriptor`; never execute against a new account/binding.
Checking only when queueing is insufficient. EVA also computes a canonical
contract digest so claimed revision equality cannot hide descriptor changes.

Deadlines are absolute Android `SystemClock.elapsedRealtime()` milliseconds on
this device/boot, including sleep, not Unix/wall time. Never persist them across
reboot. EVA allows at most five seconds for describe and the capability's
`maxWaitMillis` (never over 60 seconds) for execute, starting at submission.
Reject expired requests before work; cap excessively distant deadlines to the
provider's own ceiling. Queueing and every network hop share the same remaining
budget.

Expiration is not cancellation evidence. EVA may stop waiting/unbind without
undoing work. Binder death, oversized/malformed replies, or timeout after execute
submission mean unknown unless there is positive evidence nothing started. V1
has no cancellation/reconciliation API. Late callbacks never trigger a new model
response or repeat execution.

### 7. Identity, grants, and untrusted text

Authenticate every describe/execute transaction using `Binder.getCallingUid()`
before switching threads or clearing identity. `onBind` cannot authenticate the
originating app. Verify installed package AND trusted signing certificate using
Android package/signing APIs, with an explicit signing-rotation policy. Package
name alone is insufficient. Reject ambiguous shared-UID identities unless the
provider explicitly trusts every member. EVA authenticates callbacks against the
bound provider UID and outstanding request.

Initial interoperability policy: production providers accept
`com.colonelpanic.eva` with the separately published/pinned EVA release certificate.
Never learn the pin from an untrusted caller. Development provider builds may
also accept `com.colonelpanic.eva.debug` signed with the same certificate as the
provider itself (shared debug keystore). Production must not inherit that debug
exception. Pin distribution is provider build/configuration, not a descriptor
field; private keys never enter this protocol. Providers may explicitly support
other trusted clients under their own documented authority policy.

EVA automatically lists extensions disabled. Enabling grants explicitly claimed
reads; each write, handoff, or unknown capability has its own persistent grant
switch, off by default. Grants key on Android user, package, component, signer,
and approved contract digest including authorization scope and `_meta`. Changed
contracts require renewed enablement. Removal discards grants; reinstall must not
inherit removed grants. Temporary outages/missing configuration do not silently
change grants.

This trusts the user's provider choice for claimed reads; it cannot prove an app
harmless. Reads may disclose private data to EVA's configured model; settings
must explain this. Unknown effects never get read grants. EVA rechecks grants
and registry revision immediately before durably committing dispatch.

All provider titles, descriptions, schema descriptions, annotations, references,
content, and structured content are untrusted data. They cannot change
instructions, grants, outcomes, model settings, or execution destinations. EVA
separates its receipt envelope from quoted external content in live results and
restored history. No model-controlled shell, arbitrary IPC component, callback
execution, or credential forwarding is part of the contract.

### 8. EVA v1 lifecycle and conversation guarantees

Discovery refreshes at startup/resume and debounced package changes, off the turn
path. Validated snapshots install atomically. Additions/new grants become visible
to the model on the next connection open; no mid-session provider rotation/tool
replacement. Removal, disabling, revocation, and invalidated bindings block new
execution immediately even if a model still sees the old catalog. Temporary bind
failure means unavailable without catalog revision churn.

The model-facing limit is 64 tools including controls. Reserve session controls,
then bundled tools, then extensions sorted by fully qualified capability ID.
Overflow remains discovered and is shown unavailable with an explanation; it
never silently evicts bundled tools or crashes the conversation.

**Bounded reads, one mutation per request.** `ThreadController` allows bounded
read chaining, but imported mutations proposed after a tool result are refused.
Search then complete therefore requires separate user requests in v1. Enabling
an extension does not authorize autonomous follow-up mutations. No in-turn/spoken
confirmation or pending approval tokens are implemented.

Dispatched operations are journaled independently of result delivery, text and
structured data alike. Removal or conversation close does not undo external work.
Historical receipts retain original attribution/outcomes. Recovery/reconnect never
repeats uncertain work.
