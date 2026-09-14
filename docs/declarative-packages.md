# Declarative capability packages

Status: design being implemented. No declarative adapter or import UI is wired
into EVA yet. Packages, AppFunctions, and [installed extension apps](extension-protocol.md)
are three supported extension paths. Packages are the next implementation slice;
installed extension apps already have executable code and focused JVM tests.

## Package and repository identity

A package is one self-contained JSON file, without scripts or embedded secrets.
Its `formatVersion` identifies the codec, `id` is a publisher-chosen descriptive
name, and `version` is a semantic version. Each capability's `name`, `description`,
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
