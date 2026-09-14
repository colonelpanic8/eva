# Declarative capability packages

Status: the shipped Messages package controls an existing Android messaging app
with an SMS-compose intent. It uses settings grants and attributed handoff
receipts, with no target-app changes or server credentials. Focused JVM tests
cover it; [Pixel Caffeine and Messages verification](device-validation-2026-09-14.md) passed.
The org-agenda HTTP example remains a non-bundled interpreter fixture. HTTPS index
and raw-package imports and Android document-picker imports are implemented with preview
and explicit install. Content-provider execution remains unimplemented. Packages, AppFunctions, and
[installed extension apps](extension-protocol.md) are the three extension paths;
generalized AppFunctions is still planned.

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
The Settings tab also offers Import extension file. The system document picker grants
temporary read access; EVA bounds the stream to the same package size limit and
copies its exact bytes before preview. No persistent file permission is needed.
Every file import gets a fresh source and instance identity; reimporting a file
creates a separate disabled installation. Use a stable HTTPS source for updates.
Packages optionally declare `androidPackages`, a list of up to 16 Android package
IDs used only as matching hints. The index requires this field (empty for a
server-only extension), and its value must match the downloaded package.

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
expressions are supported. HTTP execution is wired for shipped packages.

The [org-agenda example](examples/org-agenda.json) contains agenda, default-template
capture with `values.Title`, and a mova create handoff. Replace the example HTTPS
origin and configure the named basic-auth credential in EVA. It contains no
credentials. Search uses the server-side `q` and `limit` parameters; it does not filter items locally.

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

After the declarative adapter and proving package land, migrate bundled
pure intent capabilities into bundled files using this codec, preserving native
operations in Kotlin. The migration includes map search, navigation, SMS compose,
alarm, timer, dial, web search, URL opening, email compose, calendar event, and
settings, plus the generic share-to-app handoff. Report those commits separately
with the before/after Kotlin line count. Preserve existing behavior: conversation
recipient resolution, calendar end-time calculation, and fixed settings-action
selection must not silently disappear during the template migration. Any remaining
native resolution belongs behind a declared native operation, not arbitrary
scripts or model-controlled intent fields. No bundled capability has migrated yet.

## Bounded item projection and proving package

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

The mapping cannot filter, sort, join records, calculate values, run regexes,
execute scripts, fetch additional pages, poll jobs, or infer completion from text.
It does not silently flatten arbitrary objects or guess alternate response paths.

Argument slots optionally carry `default` (a scalar satisfying the argument's
schema) and `required: true` (refuse before submission if neither input nor default
provides a value). These are binding rules, not additions to the MCP input schema.
A single `select` binding has `argument`, `present`, and `absent` branches; it
chooses between two fully declared bindings based only on argument presence.
Nested selects are rejected. Both branches must use the capability's execution
mode, and effect floors account for both. It cannot construct new destinations.

The non-bundled org-agenda package is an HTTP example and test fixture. It exposes `agenda`, `search_todos`,
`capture`, `complete_todo`, `custom_view`, and a mova create handoff. Capture
always supplies `template` (default `default`) and `values.Title`. Completion
selects an ID-only request, otherwise requires file, position, and exact title;
both branches send the literal `strict: true`. The confirmed server contract is
implemented and tested but not yet deployed:
a missing/mismatched ID or stale position/title returns HTTP 409 with
`status: "error"`, `code: "strict_lookup_conflict"`, `message`, and
`foundTitle` (string or null), without completing anything. The binding declares
`result.notExecutedStatuses: [409]`;
these are explicit documented pre-execution rejections (4xx only), never inferred
from a generic HTTP error. Other uncertain write errors remain `UNKNOWN`.

Search sends `q` and `limit` to `/get-all-todos`. Its `/total` projection follows
the additive server contract: total matches before limiting, present when q or
limit was supplied. Search is case-insensitive over title, tags, todo state,
category, and effectiveCategory; exact titles rank first, then title prefixes,
then other matches. Positive integer limits apply after ranking. Invalid limits
return HTTP 400 with `status: "error"`, `code: "invalid_query_parameter"`, and
`message`.
Item lines carry state, priority, title, scheduled/deadline date and time, tags,
ID, file, and position. Those fields support strict completion in a subsequent
request. Omitted custom-view key lists `/custom-views`; a supplied key runs the
fixed `/custom-view?key=` route. No app-side mova extension code is needed.

Order after the Messages device test: bundled intent migration and untyped share, then
generalized AppFunctions. Installed-service AIDL remains supported and discovered
but currently has no device-test vehicle. Device verification will be performed
by the operator using the linked device-test procedure. No device result is claimed yet.

The `open_create` handoff uses `mova://create?title=<encoded text>`. Mova
creates through its default template and must already be signed in. EVA reports
only `HANDED_OFF`; opening the link is not creation evidence. The separate
`mova://capture` route opens the native quick-capture dialog and accepts no title.

## Encoded opaque intent values

An intent URI may declare optional `opaque`, a string scalar slot, with a
scheme-only fixed `base` such as `smsto:` or `tel:`. It is mutually exclusive with
`query`. The interpreter percent-encodes the entire value and appends it to the
fixed scheme; the model never supplies a parsed URI, scheme, component, or flags.
The existing forbidden-scheme rules still apply. The shipped
[Messages package](../app/src/main/assets/messages.json) references the named
phoneNumber validator and maps the message into the fixed sms_body extra. It
uses ACTION_SENDTO so Android chooses an installed messaging handler. No package
name, app modification, extension service, or privileged API is required.

## Local result filtering

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
confirmed q/limit/total and strict server contract; its device test remains deferred.

## Fixed activity components

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
therefore offers no read capability. See [Caffeine's contract](https://lab.zhs.moe/caffeine/guide/advanced/).

## Extensions page and installed-app matching direction

The Extensions destination owns installed extensions/providers, grants, configuration
and wait budgets. It also supports repository refresh, package preview, explicit
installation/update, and removal. JVM tests exercise a new remote listing reaching
the registry without rebuilding EVA, plus update grant revocation.

Extension definitions live in the separate [eva-extensions repository](https://github.com/colonelpanic8/eva-extensions).
Its index and self-contained package files are
fetched over HTTPS. Codec-compatible extension changes require no EVA release.
New execution mechanisms or unsupported schema features still require app support.
Bundled files are initial examples/seeds, not the long-term update channel.

The Extensions tab contains listings, previews, installed extensions, grants, and per-extension configuration. Settings contains the
repository URL, Refresh extension repository, imports, and global defaults.
server credentials and wait budgets. Refreshing listings never installs or grants actions.

The browser prioritizes matching installed apps and labels available updates. Match locally
using explicit Android package IDs in repository/package metadata, not display
names. Caffeine identifies `moe.zhs.caffeine`; Android supplies its visible label
and icon. Include declared version compatibility when needed. Index match hints
are for discovery only: the downloaded package's identity, digest, destinations,
and compatibility must be validated before preview/install. Do not infer a
trusted publisher from either an app-name match or a file's declared ID.
Matching uses the optional package `androidPackages` field and required index
`androidPackages` field. Matched app icons come from Android locally; separate
filtered views remain follow-up work. Installed packages may also use their fixed
intent targets to find an icon, without changing the approved package digest.

Download the shared index and perform matching on-device; never upload the user's
app inventory. Android filters package visibility, so distinguish "not detected"
from proven incompatible. Preserve manual URL/file import for undetected apps and
server-only extensions. Reuse generic visibility needed by supported app interactions;
repository entries cannot add Android manifest queries at runtime. See
[Android package visibility](https://developer.android.com/training/package-visibility).

A match suggests an extension; it never installs or enables it automatically. Preview
shows the source/version, target apps, capabilities and effects. Keep the installed
instance ID across explicit updates, retain the previous working bytes until
replacement validates, and require re-enablement when the approved digest changes.
Users can remove an extension without uninstalling its target app. Installed-service
and AppFunctions providers appear on the same page with their source clearly shown;
a provider APK update is distinct from a declarative extension-file update.
