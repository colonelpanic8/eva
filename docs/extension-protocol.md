# EVA extension protocol

EVA integrations share one capability registry, grant model, dispatcher, and
invocation journal. This reference covers the two extensible formats implemented
in the app. [Architecture](architecture.md) explains the runtime;
[Operations](operations.md#device-verification) records verification limits.

| Path | Use it for | Current boundary |
| --- | --- | --- |
| Declarative JSON packages | Existing app intents, content providers or HTTP APIs | Import, preview, install, grants, intent, HTTP and bounded content-provider reads implemented; content host JVM-tested, device verification pending |
| Installed Android extension service | Code and structured protocol responses supplied by an app author; background and locked-screen writes | AIDL runtime implemented and JVM-tested; Mova 7.2.0 provider released, Paseo in development; transport checked on an emulator, no physical-device verification |
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
a scalar (`string`, `integer`, `number`, `boolean`), an `array` of one scalar
`items` type with optional `minItems`/`maxItems` (0–64), or a string map: an
`object` with no `properties`, an `additionalProperties` schema of type `string`,
and a required `maxProperties` (1–64). A map lets the request choose the keys
(1–64 characters each) when the target defines them at runtime, as Mova's capture
prompts do; the package still bounds their number and value shape. Scalars accept
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

Packages are single JSON files. A catalog is a Git repository whose `packages/`
directory holds them; EVA follows a list of them, by default the one
[eva-extensions](https://github.com/colonelpanic8/eva-extensions) publishes. Each is
refreshed on its own from Extensions → Extension repositories, and opening the tab
refreshes any this run has not listed yet.

**Following a repository is the trust decision.** Refreshing one installs every
package it publishes and replaces every package whose content changed, with no
per-package review. A package that is new is enabled outright unless its
auto-enable switch is off. An update keeps actions already enabled and automatically
enables newly named actions when that switch is on; actions explicitly switched off
stay off. Changing an existing read action to a mutation does not auto-enable it.
Turning an extension off is remembered: it also clears auto-enable, and later
refreshes leave it off. Changed actions reach the model on the next connection.
Removing a repository stops refreshes; what it installed stays until removed.

A URL or file import belongs to no repository, so nothing refreshes it and it is
still previewed and installed by hand. Installed holds enablement and action grants;
Settings holds service configuration and wait budgets.

Repository matching happens locally using Android package IDs; it does not upload
the app inventory. Match metadata and icons aid discovery, not trust. Manual imports
remain useful for apps Android does not make visible and for server-only packages.

The [Caffeine](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/caffeine.json) and
[Messages](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/messages.json) packages are executable examples in the default catalog.
The [org-agenda fixture](examples/org-agenda.json) exercises HTTP mappings but is
not shipped and is not a claim of a configured server or verified device workflow.

### Shipped default packages

Some catalog packages are useful enough on stock Android that EVA installs and
approves them without a browse step. The current defaults are
[Google Maps](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/google-maps.json)
(map search through `geo:`, turn-by-turn navigation through `google.navigation:`
with a travel mode), [OpenStreetMap places](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/openstreetmap-places.json)
(nearby places from the public Nominatim search, kept separate so it can be
turned off without losing Maps), [Web](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/web.json)
(web search, open an http/https page),
[Email](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/email.json)
(a `mailto:` draft), [Calendar](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/calendar.json)
(a prefilled event insert),
[Settings](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/settings.json)
(open a settings screen, a quick panel, or one app's own page), and
[Clock](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/clock.json)
(alarms and timers through Android's standard intents),
[Waze](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/waze.json)
(driving navigation through `waze.com/ul` links pinned to Waze, to coordinates, a
search, or the Home or Work saved in Waze), and
[Paseo](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/paseo.json)
(project, workspace, agent, and message lookups plus link fallbacks). The Waze and
Paseo defaults are conditional: Waze installs only once the `com.waze` app is
installed, and Paseo only once the `sh.paseo.assistant` provider resolves. Until
then each stays out of `appliedDefaults`, so a later install still gets it. `adapters/declarative/DefaultPackages.kt` lists each default
with a byte-identical copy of the catalog file under `app/src/main/assets/packages/`
and a fixed, name-derived instance ID. After the desired configuration is attached
at startup, EVA installs each default whose package ID is not yet in
`packages.appliedDefaults`, records it there, and grants every action; from then on
it is an ordinary installation with the catalog repository as its source, so a
later catalog update targets the same instance and follows the same action grant policy.
`appliedDefaults` is portable: removing or disabling a default is a configuration
change that other devices restore, and a removed default is never reinstalled.
All of them are handoffs, so the model still reports an opened app rather than a
completed result. Clock replaces the native alarm and timer tools with `set_alarm`
and `set_timer`. Its fixed integer extras preserve the native input bounds,
optional labels remain strings, and `SKIP_UI: false` requests the clock UI. The
existing `com.android.alarm.permission.SET_ALARM` manifest permission remains
required; importing JSON cannot add it. A clock handoff does not verify that an
alarm was created or a timer started. Physical-device verification of the package
is pending.

Settings declares four capabilities, all navigation: `open` maps 48 readable screen
names onto public `Settings.ACTION_*` actions, `quick_panel` opens one of Android's
four floating `Settings.Panel` actions so the user can flip internet, wifi, NFC, or a
volume slider without leaving what they were doing, and `open_app_settings` and
`open_app_notification_settings` reach one installed app by its exact package name,
through a `package:` opaque slot and the `android.provider.extra.APP_PACKAGE` extra
respectively. None of them reads or changes a setting: a declarative intent binding
floors at external handoff, so changing device state remains the Shizuku AppFunctions
adapter's job (see [Android capabilities](architecture.md#android-capabilities)).
Screens added after a phone's Android version resolve to no handler and report the
screen as unavailable. The public `ACTION_VOICE_CONTROL_AIRPLANE_MODE`,
`ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE`, and `ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE`
actions do change those settings, but Android requires them to be started with
`startVoiceActivity` from a voice-interaction session, which no declarative binding can
express; reaching them would be EVA code on top of the existing `assist/` session, and
it is unverified.

### Package and repository identity

A package is one self-contained JSON file, without scripts or embedded secrets.
Its `formatVersion` identifies the codec, `id` is a publisher-chosen descriptive
name, and `version` is a three-part `MAJOR.MINOR.PATCH` version (no prerelease/build suffix in v1).
Optional `description` (up to 2,000 characters) states what the package is for and
optional `setup` is 1–8 strings (up to 300 each) naming what the user must do
outside EVA first; EVA shows both above the capability list in the install
preview, and both participate in the canonical digest. A package documents itself
this way rather than in a separate file: the same capability descriptions the
model receives are what the user reads before granting. Requirements belong here;
assurances do not, since a package cannot establish its own trustworthiness.
Optional `guidance` (up to 1,500 characters) tells the model how the package's
tools fit together, such as which lookup identifies the target and which action
takes its identifier. While any of the package's tools is offered, EVA appends
it to the session instructions as a JSON-quoted entry attributed to the package,
under EVA's own header from `eva-wording.yaml`; it is external data and cannot
change instructions, grants, or confirmation rules. Extension tools reach the
model under opaque function names, so each tool's metadata carries its `name` for
guidance to refer to. Guidance participates in the digest. Each capability embeds the
[shared MCP tool object](#capability); binding metadata and effect declarations
are separate from that tool definition. Unsupported schema features are rejected,
not silently dropped.

On import EVA assigns an instance ID. The file's ID cannot replace an unrelated
installation. Grants bind to that instance and the full canonical content digest;
manual imports of changed content require re-enablement. Updates target an existing
instance only through an explicit preview and install action or a followed repository
refresh. A followed repository carries grants to the new digest under the policy
above. Historical receipts retain the previous identity and revision.

A catalog needs nothing but a Git repository with one file per package:

```text
packages/google-maps.json
packages/org-agenda.json
```

EVA keeps a read-only clone of the configured catalog remote under app storage
(`extension-catalogs/`). Refresh fetches the clone's branch, fast-forwards to the
remote head, and lists every `packages/*.json` that decodes; a file that does not
decode is reported by name and skipped rather than hiding the rest, and duplicate
package IDs fail the refresh. Preview reads the listed file from the clone, and
installation uses those exact bytes. The remote must be an HTTPS Git URL without
credentials; an index-era `raw.githubusercontent.com/…/index.json` source, and
installations recorded against one, are read as that repository so they keep
matching catalog updates. There is no separate index or digest to regenerate:
the repository's own history is the integrity record.

An import preview shows the source, operations, effects, destinations, and data
disclosure. Source and package ID are retained so a followed catalog can replace the
package it installed: the instance ID survives, grants move onto the new content,
and actions the new content no longer declares are dropped. A catalog is not
publisher authentication; following one says the publisher is trusted. A single
package can also be previewed from a raw HTTPS URL of the file itself.
The Extensions tab also offers Import extension file. The system document picker grants
temporary read access; EVA bounds the stream to the same package size limit and
copies its exact bytes before preview. No persistent file permission is needed.
Every file import gets a fresh source and instance identity; reimporting a file
creates a separate disabled installation. Use a stable HTTPS source for updates.
Packages optionally declare `androidPackages`, a list of up to 16 Android package
IDs used only as matching hints.

### Binding boundaries

- `android.intent`: a fixed action or one chosen from a closed publisher map,
  optional fixed package, a fixed URI base with typed encoded query/path slots (or
  a whole URI from one argument restricted to declared schemes), and fixed extra
  names with scalar values/typed slots. No model-controlled components, flags, or
  parsed intent URIs. Minimum effect: external handoff. A launch means
  `HANDED_OFF`, never verified completion.
- `android.content`: fixed content authority and URI base, typed encoded query/path slots, declared projection with
  scalar column types, fixed selection template with typed bound arguments,
  bounded row count, and a declared projection of rows to text. Model arguments
  cannot supply SQL fragments, columns, authorities, or permissions. Queries use
  Android permission grants; extension settings expose supported runtime permission requests and missing-provider guidance.
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
(boolean); `maxWaitMillis` is an optional positive integer or null. Optional `endsVoiceCall`
(`never`, the default; `after_reply`; or `immediately`) says whether a successful call of this
action ends a voice call, as for an action that hands the phone's audio or screen to another app;
the user's configuration can override it per action, and it grants nothing. Installed-provider
descriptors accept the same field. Intent bindings require handoff plus foreground; HTTP and content
bindings require synchronous mode. An intent capability may set optional `requiresUnlock: true` when its target
can do nothing while waiting behind the lock screen, as Maps navigation can't. On a locked phone showing
EVA's own screen, EVA then asks Android to unlock (`requestDismissKeyguard`) and opens the target only
once that succeeds. A declined unlock is `not_executed` with nothing opened. Without an EVA screen to
ask from, the launch proceeds and Android decides, as for any other intent. The flag participates in
the digest, and other binding kinds reject it. The common wait policy, rather than the file
codec, applies the 60-second clamp.

A typed slot is exactly `{"argument":"title","type":"string"}` or
`{"value":"default","type":"string"}`. Argument types must match the tool
schema; literal types must match their values. A string argument slot whose
tool property declares an `enum` may add `values`, a map from every enum value to
the string the binding sends (`{"driving":"d","bicycling":"b"}`); the map must
cover the enum exactly, so the model chooses readable names and the target's codes
never appear in the tool schema. Argument slots never change
binding authority. Optional query slots omit a missing argument. Required path
and selection slots must have a value before anything is submitted. An
`array`-typed argument can only fill a `requestBody` slot
(`{"argument":"tags","type":"array"}`, optional array `default`), where it becomes
a JSON array; path, query, intent, and selection slots stay scalar.

Intent fields: `kind`, `action`, optional `uri: {base, query?, path?, opaque?}`, `extras`,
`package`, `class`, `mimeType`, `packageByName`, and `querySpread`. `query`, `path`, and `extras` are maps of fixed names to typed slots. The base
has no existing query, fragment, or user info. `querySpread: {argument}` names a
string-map argument whose entries are appended as further encoded query
parameters after the fixed ones; an entry whose key matches a fixed query name in
any letter case is refused, so a request can add a parameter the package left open
but never replace one it fixed. `action` may instead be a string
argument slot whose `values` map covers the argument's enum, each value an intent
action (`{"argument":"screen","values":{"wifi":"android.settings.WIFI_SETTINGS"}}`);
the publisher still declares every action that can launch, the model only names
one. `uri` may instead be `{"argument":"url","schemes":["http","https"]}`, in
which case the argument supplies the whole data URI and is launched as given
only when it parses as absolute with one of the listed schemes; this is the one
binding where the model chooses the destination, so it is reserved for tools such
as opening a web page whose purpose is exactly that. A `content:` base is allowed
only when the URI object holds nothing but the base, so a provider insert can be
targeted but never shaped by an argument. `path` fills `{name}` placeholders
written into the base after its scheme, as the content binding does; each value is
percent-encoded whole, so `google.navigation:q={destination}&mode={mode}` keeps
the publisher's `q=`/`&mode=` structure while the model supplies only the two
scalars. Every placeholder needs exactly one mapping, unused mappings are rejected,
a placeholder cannot form the scheme, and an empty value refuses to launch. Parsed intent, file, content,
JavaScript, and data URI schemes are rejected by this binding; use the content
binding for provider reads.

Content fields: `kind`, `authority`, `uri: {base, query?, path?}`, `projection` (map of column name
to scalar type, or `json` for a text cell that decodes into structured data),
optional `selection`, `maxRows` (1–100), `maxBytes` (1–16,384).
Selection is a list of `{column, operator, value}` predicates joined with AND;
operators are `=`, `!=`, `<`, `<=`, `>`, `>=`, and string-only `LIKE`.
The compiler produces a fixed selection template with bound selection arguments.
Columns must be declared in the projection. There is no free-form SQL, sorting,
subquery, caller-supplied column, or mutation operation. All projected columns
are rendered to bounded text; extra rows/bytes are reported as truncated.

The content URI base is `content://<authority>/<path>`, with the same literal
`authority` as the binding. No user info, port, existing query, fragment, encoded
base path, or dot segments are allowed. `query` maps at most 64 fixed names
(1–200 characters, no control characters) to scalar argument/literal slots, using
the same types, defaults and `required` rules as intent query slots. Both names
and values are percent-encoded; an absent optional argument omits the parameter.
Thus booleans become `true`/`false` and integers remain decimal values, without
letting an argument create another parameter or change the destination.
Legacy fixed string `uri` values remain readable.

Optional `path` maps names to scalar slots for `{name}` placeholders in the base
path, like HTTP path parameters. Every placeholder needs exactly one mapping,
and unused mappings are rejected. For example,
`{"base":"content://com.colonelpanic.mova.provider/todos/{id}",
"path":{"id":{"argument":"id","type":"string"}}}`.
A path value is required at invocation; empty strings, `.`, `..`, forward slashes
and backslashes are refused before submission. Other characters are encoded.
There are at most 64 path mappings; the expanded URI is at most 16,384 UTF-8 bytes.
Authorities, columns, SQL fragments and permission names never accept slots.

#### Content execution and results

EVA calls `ContentResolver.query` on a background worker with exactly the declared
projection, compiled selection and bound arguments, no sort order, and a
`CancellationSignal`. It reads at most `maxRows` rows plus one lookahead to detect
truncation, closing the cursor on success, failure and late completion. Only
projected columns are accessed even if the provider returns additional columns.
Nulls remain JSON null. Strings require string cells, integers require integer
cells within the tool schema's exact-integer range, numbers require finite numeric
cells, and booleans require integer 0/1. A `json` column requires a string cell
that parses as JSON within the result budget and places the decoded value in the
row, so a provider's embedded document reaches the model as data rather than as an
escaped string. Blobs and coercions are rejected.

`maxBytes` bounds the UTF-8 JSON row array, including JSON escaping and separators.
Rows are omitted whole at either cap; text and structured
`data: {"rows": [...], "truncated": true|false}` contain the same rows. The
structured object is also kept under 16,384 bytes by reserving metadata space.
The empty `[]` and EVA-owned truncation/wait annotations are envelope overhead
when a byte budget is too small even for an empty array. EVA never cuts an ID or
returns half a row. A cap only describes EVA's cursor consumption: an undocumented
provider-side `limit` cannot be detected, so results must not imply exhaustive
search. Providers should also bound their own query work and cursor windows;
EVA cannot limit allocations inside another app or prevent one large cursor cell
from crossing Binder.

A valid read returns `completed`. Missing/invisible/disabled providers and missing
Android grants return `not_executed` with setup guidance (`not_configured`);
`SecurityException` becomes `not_executed` with `unauthorized_caller` guidance.
A null cursor, unknown declared column, wrong column type or other definitive query
failure returns `failed`, with no rows and no provider exception details. These
reason names are included in the declarative receipt message; the local outcome
has no separate AIDL `reasonCode` field.

The deadline includes worker admission, provider acquisition and cursor traversal,
and is capped at 60 seconds. Expiration before submission returns
`not_executed/deadline_exceeded`; after submission it returns
`unknown/deadline_exceeded`, with no partial rows. Cancellation is requested but
cannot prove that the provider stopped. Four process-wide query workers, with no
waiting queue, bound stuck providers; excess calls return `not_executed/busy`.
Late results are discarded and their cursor closed. The common dispatcher wait
may expire first and report its usual unknown outcome. No read is retried.

#### Content visibility and Android permissions

Android 11+ applies package visibility independently of content read permission.
EVA declares these provider authorities in its manifest:

```xml
<queries>
    <provider android:authorities="com.colonelpanic.mova.provider;sh.paseo.assistant" />
</queries>
<uses-permission android:name="com.colonelpanic.mova.permission.READ_TODOS" />
```

These are **per-authority visibility entries**, so both `sh.paseo` and
`sh.paseo.assembly` work when they own `sh.paseo.assistant`. They require neither
an AIDL service nor another exported advertisement component in the provider app.
Provider `<meta-data>` alone is not a package visibility mechanism. EVA deliberately
uses the concrete authorities for this executable feature, rather than requiring
a new action convention in existing apps. No `QUERY_ALL_PACKAGES` is used.
See Android's [visibility declarations](https://developer.android.com/training/package-visibility/declaring).

Other authorities work if their app is already visible (for example through EVA's
existing launcher or service queries). For an otherwise invisible app, an EVA
manifest update adding its authority is required; importing JSON cannot extend
`<queries>`. A missing provider and an invisible provider cannot reliably be
distinguished, so settings explain both possibilities.

A provider must be enabled and exported and its Android read permission must be
granted. EVA declares Mova's dangerous `READ_TODOS` permission for reads from Mova
before 7.2.1; it is requested only from **Extensions → expand the extension → Allow provider
reads**, never in the startup permission sweep or by a model tool. The screen
reports current access and links to Android permission settings for denial or
revocation. A grant is checked again before every query, and the resolver enforces
access at submission. Provider caller checks can still deny the operation.
Paseo's permissionless provider needs no Android prompt; its own caller
identity policy remains authoritative. Visibility and an EVA extension grant
are never substitutes for that policy.

Android requires a permission declaration in EVA's installed manifest before a
runtime request can succeed. Arbitrary permissions in imported packages cannot
be requested: there is no permission field in the binding. EVA currently offers
Mova's permission and its existing declared runtime permissions, after verifying
the installed provider's requirement is dangerous and declared by EVA. Another
custom dangerous permission needs an EVA manifest/allowlist update; signature
permissions cannot be granted by a runtime prompt. See Android's
[custom permission contract](https://developer.android.com/guide/topics/permissions/defining)
and [runtime requests](https://developer.android.com/training/permissions/requesting).

The installed package bytes and existing capability grants remain the portable
settings. Content dependencies add supported permissions to the existing
`device.authorizations` list in `eva.yaml` when the installed provider declares
one. A provider that enforces its caller in code, as Mova 7.2.1 does for EVA,
adds none. A saved provider permission is reported as setup still needed only
while an installed provider declares it. No new device-only enablement switch is introduced. Restoration
keeps that requirement and the exact package bytes, lists missing providers and
permissions, and requires local Android authorization on each device. Editing or
restoring YAML cannot grant a permission. Removing a package does not revoke an
Android grant or erase a previously saved desired authorization; manage these
through Android settings and `device.authorizations` respectively.

The mixed fixtures [Mova](examples/mova-content.json) and
[Paseo](examples/paseo-content.json) are snapshots of the installable
[`eva-extensions` packages](https://github.com/colonelpanic8/eva-extensions/tree/main/packages).
They demonstrate read/discover followed by an intent using the returned ID,
with no AIDL extension service. JVM tests decode these exact definitions;
fake-provider Robolectric tests cover Android execution. Device verification of
these integrations remains pending.

Mova 7.0.1 exposes `/templates`, `/todos`, `/todos/{id}` and `/agenda`. The package
uses the provider's actual columns, string `day`/`week` agenda span and boolean
query flags. Template `key` feeds capture's `template` slot; todo `id` feeds
`mova://open?id=` and other actions. Todos without an Org ID retain `file`, `pos`
and `title` for those intent actions. Mova uses its active server and credentials;
its provider ignores SQL selection, so these reads use URI parameters.

Paseo's `android-intents` branch implements `/projects`, `/workspaces`, `/agents`,
and live `/messages` at `sh.paseo.assistant`. Project rows (`id`, `serverId`,
`name`, `kind`) supply the project ID that new-agent creation takes. Workspace rows identify the project and a
credential-free repository host/path, alongside the branch and host. Keep each
row's `serverId` with its workspace or agent `id` when filtering agents, reading
messages, or opening an intent. The catalog tables reflect Paseo's last publish
and can be incomplete. `/messages` needs the host connected. Paseo's locked-extension branch answers it
from a cold process by starting its JS runtime headless, so the package allows 20
seconds;
it reads one agent or recent messages across a workspace's active agents and
returns a notice row when the transcript is unavailable. The provider rejects
SQL filtering and enforces its own EVA package-family caller check. Its debug
authority `sh.paseo.debug.assistant` is outside this shared package and EVA's
explicit visibility entries. Voice and text legs may read Paseo's catalog and
then perform granted handoffs in the same turn, under the common multi-action
budget described below.
This provider and package combination
has JVM verification; device verification remains pending.

HTTP fields: `kind`, `origin`, `method`, `path`, `parameters`, `maxResponseBytes`,
`result`, optional `requestBody`, `credential`, and `credentialScheme`. Origins are HTTPS scheme/host
with optional port and no path, credentials, query, or fragment. Methods are GET,
HEAD, POST, PUT, PATCH, DELETE. Paths start with `/`; typed `{name}` placeholders
must have matching `parameters` entries. Each parameter has `in` (`path` or
`query`), `name`, and a typed-slot `value`. No header parameter slots exist.
`requestBody` is `{fields: {...}}`, recursively containing fields objects,
scalar slots, or array argument slots; GET/HEAD have no body. This mirrors OpenAPI operation structure
without claiming to accept an entire OpenAPI document.

`credential` is a named reference such as `org-agenda`, limited to
lowercase letters, digits, underscores, and hyphens. It is resolved in the
extension credential namespace, never EVA's model credential namespace.
`credentialScheme` is `basic` (default, preserving existing packages) or `bearer`;
when supplied it requires `credential`. Each source origin uses one scheme.
Bearer tokens are provisioned through the extension's server settings and sent
only as `Authorization: Bearer <token>`, never in query slots or portable files.
A missing, wrong-origin, or wrong-scheme credential refuses before submission.
Redirects and automatic follow-ups are disabled. Responses echoing the literal
authorization payload (including JSON-escaped strings) are rejected before result
projection and journaling; arbitrary transformations of a secret by a server
cannot be detected. Transport exception details are not returned to the model.
Existing Basic credentials and references keep their storage keys and behavior.
Bearer references use `service/<name>/bearer`, kind `http-bearer`; Basic uses
`service/<name>/basic`, kind `http-basic`. Restoring either reference reports local
provisioning needs. A shared service must match every bound package's scheme.

The optional [Dawarich package example](examples/dawarich.json) is a byte-identical
catalog fixture, not a shipped default: it requires a personal HTTPS server and
API key. It uses bounded pages of points, visits, and places from Dawarich 1.14.0.
Its guidance distinguishes recorded samples, inferred visits, and incomplete
page-local place searches. HTTP response headers are not projected, so callers
must paginate explicitly and cannot infer exhaustion from a filtered page.
`maxResponseBytes` is 1–1,048,576. `result` contains a JSON Pointer `pointer`,
`maxBytes` (1–16,384), and optional `evidence: {pointer, equals}` for a terminal
write result. Empty pointer selects the whole JSON response; `equals` is a
non-null scalar. No scripts, filters, inferred success from prose, or polling
expressions are supported. HTTP execution is implemented by the declarative
backend; the catalog Caffeine and Messages packages use intents.

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
`query` and `path`; it is the single-placeholder case of `path` (`smsto:{number}`)
kept for existing packages. The interpreter percent-encodes the entire value and appends it to the
fixed scheme; the model never supplies a parsed URI, scheme, component, or flags.
The existing forbidden-scheme rules still apply. The catalog
[Messages package](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/messages.json) references the named
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

The catalog [Caffeine package](https://github.com/colonelpanic8/eva-extensions/blob/main/packages/caffeine.json)
is imported through the same codec as other packages. It pins Caffeine's ToggleActivity and integer
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
bytes, echoed exactly in the callback. An execute invocation ID is
`eva-` followed by the 64 lowercase hex digits of the SHA-256 of EVA's journal
call ID. The journal claims a call ID once, so the ID is stable for one invocation
and never names two; it is not a credential. Providers may key persistent
idempotency on it (see [durable writes](#9-durable-writes-receipt-states-and-locked-devices))
but must accept any v1-valid ID. `capability` is the descriptor's
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
`busy`. Avoid starting React Native/JS merely to service native extension calls;
a provider whose only transport lives in JS may start it headless from the bound
service, without an Activity, and must still reply before the deadline.

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

EVA automatically lists extensions disabled, except the default providers below.
Enabling grants explicitly claimed
reads; each write, handoff, or unknown capability has its own persistent grant
switch, off by default. Grants key on Android user, package, component, signer,
and approved contract digest including authorization scope and `_meta`. Changed
contracts require renewed enablement. Removal discards grants; reinstall must not
inherit removed grants. Temporary outages/missing configuration do not silently
change grants.

**Default providers.** `capability/extensions/DefaultProviders.kt` pins providers
by package name and production signing certificate SHA-256:

- Mova `com.colonelpanic.mova`: `905afc87…ad22`
- Paseo assembly `sh.paseo.assembly`: `8d229a78…b8ee`
- Upstream Paseo `sh.paseo` releases: `421698bd…c647`

A matching provider is enabled with every action when it is discovered, and again
under each new contract, such as a Mova account switch or an app update. Actions the
user switched off stay off. Turning the extension off records `false` under its
instance in the portable `packages.autoEnabled` map, so it stays off on every
device that restores the configuration, until the user turns it back on. Debug
builds and same-named apps with another signer get nothing automatically. The
provider's own caller check (section 9) is unchanged.

**An app's own extension speaks for that app.** When a discovered provider has a
descriptor, a declarative package that lists the provider's package in
`androidPackages` (such as the catalog's Mova package) withholds every action whose
name the provider also offers, and the Extensions screen shows the package's
remaining actions inside the provider's row instead of as a second extension.
Grants are unchanged, so the withheld actions return if the provider is removed
or stops describing itself.

The Extensions screen can enable all actions in one step. This grants claimed
reads and every write, handoff, or unknown-effect action in the current
descriptor; individual action switches remain available for later changes.

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

**Bounded multi-action requests.** `ThreadController` admits up to 32 calls per
turn, including at most 24 reads, and executes them sequentially. Reads and
mutations may follow earlier tool results without another user message. This
applies to native and imported tools, including background continuation. Existing
grants and dispatcher authorization still apply separately to every call. Unknown
or failed mutations block further mutations in the same turn; read-only checks
remain available. No automatic retry or in-turn grant approval is introduced;
persistent idempotency is the provider's promise under section 9, not EVA's.

Dispatched operations are journaled independently of result delivery, text and
structured data alike. Removal or conversation close does not undo external work.
Historical receipts retain original attribution/outcomes. Recovery/reconnect never
repeats uncertain work.

### 9. Durable writes, receipt states, and locked devices

This section is a convention layered on the unchanged v1 ABI and JSON codec. It
adds no method, status, or reason code. It lets an installed provider act while
the phone is locked and report honestly what happened, and it is what the Mova
and Paseo providers implement.

**Why this transport.** EVA binds the provider's service with `BIND_AUTO_CREATE`.
That cold-starts the provider process without an Activity. No background-activity
launch rule or keyguard applies. While EVA holds the binding, the provider runs
with at least EVA's importance, which comes from its visible assistant session or
its foreground service. A `mova://` or `paseo://` intent is an Activity launch,
which Android may defer behind the keyguard or refuse from the background. A
content-provider `call()` binding would need a new codec, and EVA could not bind
grants to the provider's signer.

**Provider journal.** A write provider persists a record keyed by caller UID and
invocation ID. It writes the record in credential-encrypted storage *before* any
network or other side effect, bound to the capability and a digest of the
canonical arguments. Replays and conflicts:

- The same ID with the same request returns the recorded (or current) envelope and
  never re-executes.
- The same ID with a different request returns `not_executed/invalid_arguments`
  with state `request_id_conflict`.
- A record left without a terminal outcome by process death replies `unknown`, or
  continues only under the provider's own idempotency key downstream.
- Providers bound the journal (for example, 14 days) and never retry an uncertain
  submission.

EVA itself never re-executes to repair a lost reply.

**Receipt state.** Write replies carry `structuredContent` with at least
`invocationId` and `state`. A declared `outputSchema` must admit every state, so
only those two fields are required. The envelope status follows the state:

| `state` | Envelope status | Meaning |
| --- | --- | --- |
| `completed` | `completed` | Operation-specific evidence: server success body, or prompt dispatched to the agent. Not "the agent's task finished". |
| `accepted`, `submitted`, `waiting_for_host` | `handed_off` | Durably accepted by the provider or its server; the provider continues it without EVA; completion not yet observed. `pollable: true` names a status read. |
| `uncertain` | `unknown` | May have run. Never retried; the user checks. |
| `failed` | `failed` | Definite failure after submission; partial effects are explained. |
| `not_sent`, `not_started`, `rejected`, `expired`, `request_id_conflict`, `invalid_request`, `unknown_request` | `not_executed` | Provably nothing took effect: never sent, refused before dispatch, expired, or invalid; `invalid_arguments`, `deadline_exceeded`, or a null reason. |
| `needs_unlock`, `needs_authorization`, `needs_configuration`, `needs_host_update` | `not_executed` / `not_configured` | Nothing started; the message says what the user must do. |

A provider replies no later than about 1.5 seconds before the absolute deadline.
If its own work has not finished by then, it reports the current durable state
(normally `handed_off`) rather than letting EVA's wait lapse into `unknown`. A
provider that offers reconciliation exposes an ordinary `read` capability (Mova:
`invocation_status`, Paseo: `request_status`, both taking `invocationId`). That
read returns `completed` with the recorded receipt as `structuredContent`; its
`state` carries the write's progress (Mova adds `in_progress`, `interrupted`, and
`not_found`; Paseo `unknown_request`). When EVA's own wait lapses, its `unknown`
receipt carries the same `invocationId` in its data, so the model can ask. EVA
does not rewrite the original receipt from a later status read.

A provider may require its own opt-in for unattended execution. Mova's "Let EVA use
Mova" and Paseo's "Let EVA run agents" start on and can be turned off. The provider
reports `needs_authorization` while it is off; the opt-in adds to EVA's
per-action grants and never replaces them. Providers keep the caller check in
section 7. The production pin is EVA's release certificate, SHA-256
`688df17827dd9a002705baf0400c80f8f4650c6e87c3fc91d41f32be287f8b68`, taken from the
v0.26.0 release APK with `apksigner`.

**Device states.** EVA and these providers are not direct-boot aware, and none
moves credentials into device-protected storage.

| State | Expected behavior | Evidence |
| --- | --- | --- |
| Locked after first unlock, provider warm or evicted | Bind cold-starts the provider; reads and writes run; no tap | API 36 emulator: Mova reads and writes answered from a cold process with the keyguard showing (not logged in, so no server write). Paseo created agents and sent prompts to a throwaway host in 0.9–2.0 s, including headless JS start |
| Before first unlock after reboot | EVA and providers do not run; credential-encrypted storage is unavailable until first unlock ([Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)) | Platform documentation |
| Provider force-stopped or never launched | Android 15 says apps leave the stopped state only through user action ([stopped state](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)); a failed bind is `not_executed`, nothing submitted | API 36 emulator: binding a force-stopped and a never-launched Mova succeeded while locked and cleared `stopped`; the caller was an instrumented EVA process, and OEM builds may differ |
| Offline before submission | `not_executed`, or `handed_off`/`waiting_for_host` if the provider durably continues | API 36 emulator: Paseo returned `waiting_for_host` with its host down, and a status read after restart returned `completed` |
| Lost reply after submission | `unknown`; a status read may later show the outcome | Provider tests |
| Keystore key requiring an unlocked device | `not_executed/not_configured` with `needs_unlock` | Neither current provider uses such a key |

Intent-backed catalog actions remain as foreground fallbacks: they open the app
for review or when no provider service is installed.

