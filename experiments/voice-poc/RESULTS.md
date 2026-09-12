# Recorded result — 2026-09-12

**The subscription-authenticated voice/action loop works in this browser
experiment.** Generated speech executed both counter tools, received nonzero
audio from the provider, and produced a final assistant transcript confirming
7 and the custom “EVA” instruction. This is synthetic capture testing on Linux,
not a claim of physical microphone, Android, or human barge-in verification.

## Reproducible evidence

- [Final synthetic voice run](evidence/2026-09-12-synthetic-voice.json)
- [Final typed backend run](evidence/2026-09-12-typed-backend.json)
- [Commands, controls, and protocol boundaries](README.md)
- [Exact Paseo source and installed provenance](THIRD_PARTY.md)

The evidence files contain metadata only: no SDP, account identity/token,
provider URLs, raw audio, or transcript text. The smoke test asserts the final
transcript in browser memory before reporting confirmation. Request IDs are
retained to correlate actual dispatch receipts. These are individual trials,
not percentile estimates or reliability statistics.

Environment: NixOS x86_64 Linux, Node 24.19.0, Codex 0.154.0, Chrome through
Playwright, existing `chatgpt` authentication with reported plan type `pro`,
V3 realtime model request `gpt-live-1-codex`. The host thread resolved to
`gpt-5.6-luna`; the internal delegation model was not independently identified.
Five inherited MCP servers were disabled in each live thread. No API key or
paid API fallback was used. Existing Paseo processes were not used for calls
or changed. Each call had a new private Codex app-server and temporary cwd.

| Observation | Final voice trial | Final typed trial |
| --- | --- | --- |
| Input | Generated WAV through Chrome fake capture | Generated silence + two typed backend requests |
| Browser Connect → WebRTC connected | 1470 ms | 2010 ms |
| Broker start → realtime started | 1112 ms | 1461 ms |
| Tools | Set 7, read 7 | Set 7, read 7 |
| Observed backend turns | 1 for both tools | 2, one per request |
| Local dispatch duration | 0.115 / 0.014 ms | 0.093 / 0.055 ms |
| Finalized user transcript → tool completion | 1240 / 1241 ms | Not applicable |
| Typed submission → tool completion | Not applicable | 1388 / 1093 ms |
| Custom instructions and confirmed value | Final voice transcript asserted | Executor text asserted |
| Local interrupt | Audio element muted; measured 0 ms at browser timer resolution | Same assertion passed |

Voice data-channel `delegation.created` arrived before `turn/started`; the
structured tool requests then arrived through Codex stdio. This proves
**EVA-controlled tools via backend-model delegation**, not direct function
calls from the voice model. No prose parsing or simulated speech-only action
was involved. The action dispatcher actually changed the in-memory state.

The final voice sample reported 878 inbound RTP packets / 37,762 bytes with
nonzero accumulated audio energy, and 942 outbound RTP packets / 49,305 bytes.
The selected candidate pair used UDP, with an observed RTT of 48 ms. Audio
flowed between the browser and the provider; the Node broker only negotiated
SDP and carried control/events. No host audio-delta notification was observed.
Data-channel event types and Codex notifications are separate evidence: a
direct audio connection does not eliminate the tool-event/model path.

## What failed or remains unverified

1. Initial standalone realtime text testing returned
   `session.context.appended` but no action/response within 60 seconds. The
   first trial also exposed an empty synthetic AudioContext graph producing
   no RTP; that harness bug was fixed. A second trial with actual silent
   frames still did not elicit a response. Typed input therefore defaults to
   an explicit backend turn. Realtime context append remains labeled
   experimental; it is not sold as a working voice input turn.
2. Natural human barge-in, accurate provider cancellation, playback truncation,
   physical microphone/speaker behavior, echo handling, Bluetooth, and Android
   lifecycle have not been tested. The interrupt button proves local muting
   only; its context request does not prove that the provider stopped generating.
3. One final voice scenario is insufficient for latency distributions,
   reliability, billing/entitlement promises, or a production provider choice.
   An earlier exploratory synthetic voice scenario also executed both tools,
   but only the final run required the custom final spoken confirmation.
4. Reconnection/session isolation is covered by deterministic fake-provider
   tests. Separate live browser connections on the same final broker also
   succeeded sequentially with fresh sessions. Network loss during an actual
   external mutation and durable reconciliation remain future work.
5. Provider/account access worked for this existing login on this date. Public
   client support, other account access, and applicable long-term allowances
   remain unresolved. Published desktop voice pricing does not establish EVA
   entitlement. Codex backend turns remain a separate usage consideration.
6. The preview is reachable over Tailscale HTTP; microphone testing requires
   desktop localhost. No global TLS or Tailscale Serve changes were made.

## Checks and handoff

`npm run check` passed formatting/lint, strict TypeScript checking, nine
focused deterministic tests, and browser bundling. Tests cover argument
validation, duplicate receipts, conflicting IDs, RPC timeout/exit behavior,
redaction, late capture cleanup, canceled ICE waits, early SDP notification,
session cleanup/reconnection, replayed receipts preserving the current display,
and API-auth refusal. `npm audit` reported zero
vulnerabilities after updating `ws` to 8.21.3. The final browser page loaded,
rendered its controls, and had no reported browser errors.

The pushed final scaffold `0c0d74f` was merged from `origin/main` into
`voice-poc` as `8ea49a8`, without conflicts. After that merge,
`direnv exec . just check` **passed**: Kotlin formatting checks, fatal Android
lint, and debug APK assembly. The Android unit-test target reported NO-SOURCE.
The first attempt before this merge had been blocked by unavailable Android
command-line tools 23.0 and an `.envrc` hash mismatch; the final scaffold fixed
both. No extra Kotlin or scaffold edits were made for the experiment.

Next: a native Android screen with the same WebRTC lifecycle and one
EVA-dispatched Android intent, followed by one read-only Paseo status action.
Use the explicit dispatcher/result protocol as the boundary; retain host-side
subscription auth. Evaluate whether the measured extra backend turn is an
acceptable interim architecture before expanding the action catalog.
