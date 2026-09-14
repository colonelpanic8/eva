# EVA installed-app extension protocol v1

Status: frozen v1 contract. EVA has an implementation with focused JVM tests for
discovery, grants, and execution. Device verification against a real installed
provider has not happened yet; see [implementation status](implementation.md).
The AIDL and JSON contract below is unchanged.

An independently installed Android app advertises actions without registration
or app-specific code in EVA. EVA discovers it automatically. The user must enable
it in EVA settings before execution. The provider owns credentials, network
access, validation, and execution; EVA owns grants, dispatch, journaling, and
attributed receipts.

## 1. Discovery and copied AIDL

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
    void describe(String requestId, long deadlineElapsedRealtimeMillis,
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
local name, not its EVA-qualified ID. Each request receives at most one terminal
callback. There is no streaming, progress, accepted-job response, cancellation
method, or callback-initiated execution. EVA ignores duplicate, unknown-ID,
wrong-provider, and already-expired callbacks.

Authenticate and capture identity in each Binder entry point before scheduling
bounded background work. Binder methods, callbacks, service creation, and
`onBind` must return promptly. Do not perform network work or wait for execution
on Binder/main threads. Bound queues and concurrency; reject excess work as
`busy`. Avoid starting React Native/JS merely to service native extension calls.

## 2. Encoding and bounds

Size accounting uses UTF-8 bytes, even though AIDL transports Java strings.
Reject duplicate object keys, invalid Unicode, nonfinite numbers, unknown fields,
wrong types, and omitted required fields. Object key order is insignificant;
array order is significant. Every field in the envelope/descriptor examples is
required, including explicit nulls. Schema keywords have their own rules below.

| Payload/value | Maximum |
| --- | --- |
| Entire describe callback JSON, including envelope | 65,536 UTF-8 bytes |
| Entire arguments JSON | 16,384 UTF-8 bytes |
| Entire execute callback JSON, including envelope | 16,384 UTF-8 bytes |
| Capabilities in a descriptor | 64 |
| Capability name | 64 ASCII bytes, `[a-z][a-z0-9_]{0,63}` |
| Revision | 128 ASCII bytes, `[A-Za-z0-9._:-]+` |
| Title | 120 Unicode code points |
| Description or schema description | 2,000 Unicode code points |
| Callback message | 4,000 Unicode code points, also subject to byte limits |

Titles/descriptions are nonempty; messages may be empty. Both sides enforce
limits. Truncate human text at Unicode boundaries and set `truncated: true`.
Never truncate JSON, IDs, item references, or schemas. Reject an oversized
catalog rather than installing a partial descriptor. Limits are below Binder's
shared transaction-buffer limit, but cannot guarantee delivery under pressure.
Diagnostics must not log credentials or raw arguments/results.

## 3. Describe callback

`describe` reads local metadata only: no network request, operation execution,
credential disclosure, or UI launch. Missing credentials do not prevent catalog
description. Changing template/view choices belong in read operations rather
than a network-dependent catalog.

Successful response (one example capability; 1–64 unique names are allowed):

```json
{
  "protocolVersion": 1,
  "status": "completed",
  "reasonCode": null,
  "message": "",
  "truncated": false,
  "descriptor": {
    "protocolVersion": 1,
    "descriptorRevision": "catalog-1.account-1",
    "authorizationScopeRevision": "account-1",
    "title": "Example agenda",
    "schemaVersion": "flat-scalar-v1",
    "capabilities": [{
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
      "effects": "read",
      "execution": {
        "requiresForeground": false,
        "maxDurationMillis": 30000,
        "cancellation": "none",
        "idempotency": "none"
      },
      "result": {"mediaType": "text/plain", "maxBytes": 16384}
    }]
  }
}
```

`effects` is exactly `read`, `write`, or `unknown`. An unclassifiable operation
uses `unknown`; omitted effects are malformed, never implicitly read-only.
Effects cover external behavior: writing a remote agenda is a write even if it
changes nothing on the phone. Effects are provider claims, not execution grants.

`maxDurationMillis` is an integer 1–60,000. `requiresForeground` means the provider
requires its own visible UI; EVA v1 admits only false and does not launch it or
grant background-activity privileges. `cancellation` and `idempotency` must be
`none`, reserving explicit seams without promising cancellation/exactly-once
execution. `mediaType` is `text/plain`. `maxBytes` is an integer 1–16,384 bounding
the entire execute callback envelope, not only the message. V1 has no binary
payloads, URI grants, structured output data, or output schemas.

Failure has the same six outer fields, `descriptor: null`, status `not_executed`
or `failed`, an applicable reason code (or null for an uncategorized internal
failure), and a bounded explanation. `truncated` applies only to the explanation.
Success requires a descriptor, null reason, and `truncated: false`.

No authoritative package ID comes from the descriptor. EVA derives user, package,
component, and signer from Android and exposes `extension.<package>.<name>`.
Debug packages have distinct identities.

## 4. Flat scalar input schema

The root is a closed object with required `type: "object"`, `properties`,
`required`, and `additionalProperties: false`; `description` is optional.
There are 0–64 properties with names `[A-Za-z][A-Za-z0-9_]{0,63}`. Required names
are unique and present in properties. Every property has a scalar `type`:

| Type | Additional optional keywords |
| --- | --- |
| `string` | `minLength`, `maxLength` |
| `integer`, `number` | `minimum`, `maximum` |
| `boolean` | none |

Every scalar also permits `description` and `enum`. Enums have 1–64 distinct
values of the declared type satisfying its constraints. String lengths count
Unicode code points and are integers 0–65,536. Numeric bounds are finite and
lower bounds do not exceed upper bounds. Integers and integer bounds lie within
-9,007,199,254,740,991 through 9,007,199,254,740,991; numbers are finite IEEE-754
doubles. No arrays, nested objects, nulls, unions, references, patterns, formats,
defaults, or other keywords. Optional means omitted, not null.

Arguments are an object of actual JSON scalars: numbers/booleans are not quoted
strings. EVA restores scalar types before IPC. Providers independently validate
arguments and operation semantics (a ten-character date still needs calendar
validation). There is no comma-separated-list convention.

## 5. Execute callback, statuses, and reasons

Execute responses have exactly five fields, with no descriptor:

```json
{
  "protocolVersion": 1,
  "status": "completed",
  "reasonCode": null,
  "message": "Created the requested entry.",
  "truncated": false
}
```

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
| `not_configured` | Required account/configuration absent; `not_executed`. |
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

## 6. Revisions and deadlines

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
reboot. EVA allows at most five seconds for describe and the advertised duration
(never over 60 seconds) for execute, starting at submission. Reject expired
requests before work; cap excessively distant deadlines to the provider's own
ceiling. Queueing and every network hop share the same remaining budget.

Expiration is not cancellation evidence. EVA may stop waiting/unbind without
undoing work. Binder death, oversized/malformed replies, or timeout after execute
submission mean unknown unless there is positive evidence nothing started. V1
has no cancellation/reconciliation API. Late callbacks never trigger a new model
response or repeat execution.

## 7. Identity, grants, and untrusted text

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
reads; each write/unknown capability has its own persistent grant switch, off by
default. Grants key on Android user, package, component, signer, and approved
contract digest including authorization scope. Changed contracts require renewed
enablement. Removal discards grants; reinstall must not inherit removed grants.
Temporary outages/missing configuration do not silently change grants.

This trusts the user's provider choice for claimed reads; it cannot prove an app
harmless. Reads may disclose private data to EVA's configured model; settings
must explain this. Unknown effects never get read grants. EVA rechecks grants
and registry revision immediately before durably committing dispatch.

All provider titles, descriptions, schema descriptions, references, and results
are untrusted data. They cannot change instructions, grants, outcomes, model
settings, or execution destinations. EVA separates its receipt envelope from
quoted external content in live results and restored history. No model-controlled
shell, arbitrary IPC component, callback execution, or credential forwarding is
part of the contract.

## 8. EVA v1 lifecycle and conversation guarantees

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

**One action per request, including reads.** Current
`ProviderSessionController.dispatch()` claims the first call regardless of
`CapabilityDefinition.readOnly`. A different call in the request is refused.
The comment saying a turn may run several reads does not describe implemented
behavior. Search then complete requires separate user requests in v1. Mutations
proposed after a tool result in the same request are refused. No in-turn/spoken
confirmation, pending approval tokens, or autonomous mutation grants in this pass.

Dispatched operations are journaled independently of result delivery. Removal or
conversation close does not undo external work. Historical receipts retain
original attribution/outcomes. Recovery/reconnect never repeats uncertain work.
