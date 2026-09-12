# Paseo reference and attribution

This experiment adapts the restricted configuration in `src/profile.ts` and
the WebRTC handshake/lifetime approach in `src/browser.ts` from
[colonelpanic8/paseo, realtime-voice-actions](https://github.com/colonelpanic8/paseo/tree/44a5b3f21047a3bd9f7f7a45223c5f8c17e022df),
commit `44a5b3f21047a3bd9f7f7a45223c5f8c17e022df` (2026-09-10), licensed under
[Apache License 2.0](licenses/Paseo-Apache-2.0.txt). These are modified, reduced
EVA implementations, not unmodified Paseo files. The reference has no NOTICE file.

Primary inspected files at that commit:

- `packages/server/scripts/assistant-realtime-smoke.ts`
- `packages/server/src/server/agent/providers/codex-live-voice-host-profile.ts`
- `packages/server/src/server/agent/providers/codex-app-server-agent.ts`
- `packages/server/src/server/agent/providers/codex-app-server-agent.live-voice.test.ts`
- `packages/server/src/server/live-voice/live-voice-coordinator.ts`
- `packages/server/src/server/live-voice/live-voice-routing-tools.ts`
- `packages/app/src/live-voice/live-voice-session.web.ts`
- `packages/app/src/live-voice/live-voice-session.native.test.ts`

The coordinator registers a restricted MCP routing catalog, creates a hidden
host, and supplies instructions to both the voice model and backend executor.
The app makes a fully gathered WebRTC offer with audio and `oai-events` before
negotiation. Canonical transcripts come through the daemon. This experiment
substitutes two app-server dynamic tools for Paseo's MCP routing catalog; it
does not parse natural-language delegations into executable commands.

Discovery on 2026-09-12 found distinct references:

| Reference | Commit |
| --- | --- |
| Local `fork/realtime-voice-actions` tracking ref (stale) | `6912e62ee456ae7e057615a892a3576ff78585dc` |
| Remote branch, verified with `ls-remote` and separate temporary clone | `44a5b3f21047a3bd9f7f7a45223c5f8c17e022df` |
| Installed running Paseo wrapper's `PASEO_BUILD_COMMIT` | `7696aa9e3dfd03338dae14d399900d9437991a06` |
| NixOS flake's Paseo input at inspection | `3cdb78ba07d037c21941a4476f71ddcc6c157d30` |
| Assembly lock build at inspection | `d002237ad6db728d359bfb2f0eb89c4723c95721` |

The assembly lock carries the remote voice commit as a reconstruction parent
of `watch-live-voice` (`d3fec6e1d8fe7c9af06e76f3b6eb26926b7ade01`). Its
other voice pins included `live-voice-context-files` (`1e4a303c7eb1`),
`live-voice-error-detail` (`6887e88bab87`), `live-voice-quick-launch`
(`7e6db98790fe`), `live-voice-start-sound` (`298df0f03b97`), and `voice-actions`
(`6684c24bb355`). These references are not interchangeable with the running
build. No Paseo checkout, assembly, daemon, or system configuration was modified.

Codex protocol inspection also used the local OpenAI source snapshot
`6219b7c40fc9c702c0aef9964e72b492558f60e4`, then the actual installed
`codex-cli 0.154.0` executable's `app-server generate-json-schema --experimental`
output. The executable supports V3 realtime, role-bearing appendText, and
`item/tool/call`. No Codex source is vendored here.
