# Declarative capability packages

Status: codec implemented; execution and import integration are being built. No declarative adapter or import UI is wired
into EVA yet. Packages, AppFunctions, and [installed extension apps](extension-protocol.md)
are three supported extension paths. Packages are the next implementation slice;
installed extension apps already have executable code and focused JVM tests.

## Package and repository identity

A package is one self-contained JSON file, without scripts or embedded secrets.
Its `formatVersion` identifies the codec, `id` is a publisher-chosen descriptive
name, and `version` is a three-part `MAJOR.MINOR.PATCH` version (no prerelease/build suffix in v1). Each capability's `name`, `description`,
and `inputSchema` form an MCP-compatible tool definition using EVA's supported
JSON Schema subset. Binding metadata and effect declarations are separate from
that tool definition. Unsupported schema features are rejected, not silently
dropped.

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

The planned index envelope is:

```json
{
  "formatVersion": 1,
  "packages": [{
    "id": "community.org-agenda",
    "version": "0.1.0",
    "title": "Org agenda",
    "url": "packages/org-agenda.json",
    "sha256": "<SHA-256 of the exact package file bytes>"
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
can also be copied or imported as a file without its repository.

## Binding boundaries

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

## Execution and waiting

`ExecutionSemantics` declares `mode` (bounded synchronous or handoff),
`requiresForeground`, optional `maxWaitMillis`, and `cancellation`, `idempotency`,
and `reconciliation`, all three restricted to `none` in this pass. No accepted
jobs or polling are inferred from a 202 response or result prose.

The effective wait is the user override for the extension instance, otherwise
the capability's package/adapter default, otherwise the EVA interaction-mode
default. Voice defaults to 20 seconds and typed to 30 seconds; global per-mode
settings can change either. All paths clamp the effective wait to 60 seconds.
Receipts record all layers, the chosen layer, and any clamp. Voice gets a brief
displayed or spoken waiting cue halfway through the effective budget. An expired
submitted action reports unknown, not failed or known still-running.

Four calls may run globally, one per extension instance, with busy refusal rather
than queuing. Interruption is not undo. No uncertain write is retried. Later
completion has a delivery seam for future thread notifications, but this branch
does not promise background answers or integrate the threads branch.

## Investigation: App Actions shortcuts XML

Future discovery could inspect installed packages' launcher activities for
`android.app.shortcuts` metadata, then read the referenced shortcuts XML and
its capability/intent declarations. This is an investigation target, not an
implemented parser or an assumption that every declaration is usable by EVA.
Verify Android package/resource visibility, capability parameter mappings,
intent targets, and documented fulfillment semantics on real apps first.
Reject unsupported mappings rather than guessing; discovery contributes
untrusted definitions and never grants execution authority. This may let EVA
offer intents from existing apps without a hand-authored package or app edits.

References for the investigation: [App Actions XML schema](https://developer.android.com/develop/devices/assistant/action-schema) and [static shortcut declarations](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts).

## V1 package fields

The root contains exactly `formatVersion: 1`, `id`, `version`, `title`, and
`capabilities` (1–64 entries); the full UTF-8 document is bounded to 256 KiB.
Duplicate JSON keys, unknown fields, invalid Unicode, and unsupported versions
are rejected. Object ordering does not affect the canonical contract digest.
The package ID is a lowercase dotted name; capability names are ASCII identifiers.

Each capability contains `tool`, `title`, `execution`, `binding`, and optionally `validators`, `receipts`, and
`effects` (`read`, `write`, `external_handoff`, or `unknown`; omission means
unknown). `tool` is exactly an MCP tool object: `name`, `description`, `inputSchema`.
Inputs are a closed object with scalar string/integer/number/boolean properties,
explicit `required`, and `additionalProperties: false`. The existing EVA subset
supports descriptions, enums, string length and numeric bounds. Nested objects,
arrays, null, schema defaults, and extra keywords are not accepted as inputs.
Descriptions are bounded to 2,000 characters, titles to 120, and names to 64.

Execution has required `mode` (`synchronous` or `handoff`), `requiresForeground`
(boolean), `cancellation`, `idempotency`, and `reconciliation` (all `none`).
Optional `maxWaitMillis` is a positive integer or null. Intent bindings require
handoff plus foreground; HTTP and content bindings require synchronous mode.
The common wait policy, rather than the file codec, applies the 60-second clamp.

A typed slot is exactly `{"argument":"title","type":"string"}` or
`{"value":"default","type":"string"}`. Argument types must match the tool
schema; literal types must match their values. Argument slots never change
binding authority. Optional query slots omit a missing argument. Required path
and selection slots must have a value before anything is submitted.

Intent fields: `kind`, `action`, optional `uri: {base, query?}`, `extras`,
`package`, `mimeType`, and `packageByName`. `query` and `extras` are maps of fixed names to typed slots. The base
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
`requestBody` is `{fields: {...}}`, recursively containing fields objects or
scalar slots; GET/HEAD have no body. This mirrors OpenAPI operation structure
without claiming to accept an entire OpenAPI document.

`credential` is a named basic-auth reference such as `org-agenda`, limited to
lowercase letters, digits, underscores, and hyphens. It is resolved in the
extension credential namespace, never EVA's model credential namespace.
`maxResponseBytes` is 1–1,048,576. `result` contains a JSON Pointer `pointer`,
`maxBytes` (1–16,384), and optional `evidence: {pointer, equals}` for a terminal
write result. Empty pointer selects the whole JSON response; `equals` is a
non-null scalar. No scripts, filters, inferred success from prose, or polling
expressions are supported. HTTP execution is not wired yet.

The [org-agenda example](examples/org-agenda.json) contains agenda, default-template
capture with `values.Title`, and a mova capture handoff. Replace the example HTTPS
origin and configure the named basic-auth credential in EVA. It contains no
credentials. Search is omitted because v1 has no client-side text filter.

## Named validation, receipt copy, and bundled migration

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

After declarative and generalized AppFunctions adapters land, migrate bundled
pure intent capabilities into bundled files using this codec, preserving native
operations in Kotlin. The migration includes map search, navigation, SMS compose,
alarm, timer, dial, web search, URL opening, email compose, calendar event, and
settings, plus the generic share-to-app handoff. Report those commits separately
with the before/after Kotlin line count. Preserve existing behavior: conversation
recipient resolution, calendar end-time calculation, and fixed settings-action
selection must not silently disappear during the template migration. Any remaining
native resolution belongs behind a declared native operation, not arbitrary
scripts or model-controlled intent fields. No bundled capability has migrated yet.
