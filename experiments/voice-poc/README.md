# EVA subscription voice proof of concept

A standalone browser and Node host harness, separate from the Android app.
It reuses the custom Paseo voice approach with a private Codex app-server
process. The host retains the existing subscription login. The browser owns
WebRTC audio; two explicit EVA tools read and set an in-memory counter.

This is an experimental integration, not a claim of a stable public voice API
or unlimited EVA usage. No API-key fallback is implemented.

## Start and check

Prerequisites: Node 22 or newer, npm, direnv, a working `codex` executable with
an existing ChatGPT subscription login on this host, and a WebRTC-capable
browser. Verified client: `codex-cli 0.154.0`. Existing Codex login/keyring and
`CODEX_HOME` remain under Codex's control; the harness never reads auth files.
`account/read` must report `chatgpt` before a thread can start.

From the repository root:

```sh
cd experiments/voice-poc
direnv allow .
direnv exec . npm ci
direnv exec . npm run check
direnv exec . npm start
```

The experiment's `.envrc` only adds its local binaries to PATH. It intentionally
does not load the Android SDK. Supply Node via your existing environment or
`nix shell nixpkgs#nodejs` if necessary. It has its own pinned npm lockfile.

Startup chooses a fresh random port from 40000–59999, binds `0.0.0.0`, and
fails on a collision instead of silently choosing another port. `EVA_PORT`
can pin a newly selected port. `EVA_CODEX_BIN` can select a known Codex binary.
The startup URL's fragment is a random **local broker access code**, not a
subscription credential. It is removed from the address bar, is not put in
HTTP query strings, and must be supplied again after a reload. Do not share a
code-bearing link outside your trusted devices.

Open the printed **localhost** link on the host for microphone testing.
An HTTP Tailscale address can display the preview but is **not** a microphone
secure context. This experiment does not configure global Tailscale Serve,
TLS, or a reverse proxy. A secure phone-accessible origin is a remaining step.

1. Connect with the microphone checkbox off to test typed input with generated
   silent RTP. No `getUserMedia` call occurs in this mode.
2. For voice, check **Enable microphone when connecting**, then Connect.
   Try “Set the counter to seven, then read it back using your tools.”
3. Confirm the displayed value and structured tool receipts. Speech alone
   never changes the counter.
4. Speak over EVA to assess natural interruption. **Interrupt** immediately
   mutes local playback and sends a request to stop through realtime context;
   **Resume speaker** unmutes. Provider-side cancellation is not established
   by the local mute measurement.
5. Disconnect releases capture tracks, WebRTC, timers, the control socket,
   and the private Codex process. Reconnect starts a fresh thread and counter;
   conversation history and previous requests are not replayed.

Only one browser owns a broker at once. Sessions have a 10-minute lifetime,
60-second setup deadline, 8-observed-backend-turn threshold, 200 control-message
limit, and bounded receipt/event buffers. These are experiment guardrails,
not a provider spending quota. A ninth observed turn causes shutdown but may
already have started. Disconnected control clients are detected by heartbeat.

## Routes and execution

```text
Browser microphone/speaker <----- WebRTC / UDP -----> voice provider
Browser <--- authenticated control WebSocket ---> Node broker
Node broker <--- JSON-RPC over stdio ---> private Codex app-server
Voice delegation -> Codex backend turn -> item/tool/call -> EVA dispatcher
EVA structured result -> Codex -> voice response
```

`eva_counter_get({})` returns the displayed value. `eva_counter_set({value})`
accepts only integers from -1000 to 1000. Unknown tools, extra arguments, and
invalid values are rejected. Calls carry stable provider request IDs; duplicate
IDs return the original receipt without repeating the mutation. Conflicting
reuse fails. Receipts are scoped to one connection. This is not durable
cross-restart exactly-once execution.

The host advertises only these two dynamic tools, disables every inherited MCP
server individually, and uses Paseo's restricted feature profile. It denies
command/file approvals and unknown server requests. Read-only sandboxing is
defense in depth; the dispatcher contains no filesystem, shell, or network
actions. The two tools are handled by EVA, but **the voice model delegates to
a Codex backend turn before calling them**. `clientManagedHandoffs` is not used
as a pretend direct-tool switch. The requested host model is `gpt-5.6-luna`
with low effort, matching the small routing task; Codex may choose its own
internal delegation model, which has not been independently established.

Typed input defaults to `turn/start`: an explicit backend turn using the same
dispatcher, with its executor text shown in the conversation. This consumes
Codex task allowance. Spoken readback of typed results is not guaranteed.
The optional **Realtime context** route uses `thread/realtime/appendText`:
our standalone typed trials received `session.context.appended` but no
spoken response or action within 60 seconds. It is useful for investigating
context delivery, not yet a reliable text-to-voice interaction.

Custom instructions are supplied to both the voice prompt and backend executor.
The experiment overrides an inherited realtime prompt within its own thread
so it does not silently replace the UI's instructions.

## Diagnostics and live tests

The UI shows session setup, WebRTC state, RTP counts/energy, selected candidate
transport/RTT (no IPs), data-channel event types, canonical Codex transcripts,
backend turns, tool receipts, usage token counts when present, and errors.
Exports omit transcripts, SDP, endpoint addresses, and account credentials.
Provider stderr is discarded. Transcripts exist in browser memory; no audio
recording or transcript file is written by this harness. Codex/provider logging
and retention are separate; `ephemeral: true` requests a nonpersistent thread.

`inputToActionMs` measures typed submission to tool completion.
`speechTranscriptToActionMs` starts at the finalized user transcript, **not**
the physical end of speech. `dispatchMs` measures synchronous local dispatch.
First-audio energy is sampled every second, so it is a coarse observation,
not a precise speech latency benchmark. Backend-turn counts are observed
notifications, not a bill or proof that hidden internal work is absent.

Live tests are separate from `npm run check` and never run in ordinary CI.
Run only against a broker you just started, with no other session using it:

```sh
# Typed backend test; supplies generated silence and never opens a microphone.
EVA_SMOKE_URL='http://localhost:NEW_PORT/#CODE_FROM_STARTUP' \
  direnv exec . npm run smoke

# Synthetic voice test. Requires espeak and ffmpeg; these files contain
# generated test speech, not a recording. The five-second lead allows setup.
direnv exec . espeak -w /tmp/eva-counter-speech.wav -s 145 \
  'Set the E V A counter to seven using your tool. Then read the counter using your read tool and tell me its value.'
direnv exec . ffmpeg -hide_banner -loglevel error -y \
  -i /tmp/eva-counter-speech.wav \
  -af 'adelay=5000:all=1,apad=pad_dur=50' -ar 48000 /tmp/eva-counter-input.wav
EVA_SMOKE_AUDIO=/tmp/eva-counter-input.wav \
  EVA_SMOKE_URL='http://localhost:NEW_PORT/#CODE_FROM_STARTUP' \
  direnv exec . npm run smoke
```

The smoke runner uses Chrome's **fake capture device**, never an ambient
microphone. `EVA_CHROME_BIN` overrides `/run/current-system/sw/bin/google-chrome`.
The voice test requires both counter tools, a final assistant transcript
confirming 7 and the custom “EVA” instruction, nonzero inbound RTP, and local
interrupt muting. Failures produce bounded, sanitized metadata. See the recorded observations below for the scope of the evidence.

## Recorded browser evidence

The 2026-09-12 [synthetic voice run](evidence/2026-09-12-synthetic-voice.json)
and [typed backend run](evidence/2026-09-12-typed-backend.json) established the
counter loop through a private Codex app-server with host-retained ChatGPT
subscription authentication. Synthetic voice set and read 7, returned nonzero
provider audio, and satisfied the final transcript assertions. Typed input used
two explicit backend turns. The final host thread reported `gpt-5.6-luna`;
the internal delegation model was not independently identified.

| Observation | Synthetic voice | Typed backend |
| --- | --- | --- |
| Browser connect to WebRTC connected | 1,470 ms | 2,010 ms |
| Observed backend turns | 1 for both tools | 2, one per request |
| Local tool dispatch | 0.115 / 0.014 ms | 0.093 / 0.055 ms |
| Local interrupt | Browser audio element muted | Browser audio element muted |

These are individual samples, not latency distributions or cancellation proof.
Realtime text append alone did not produce a reliable spoken response/action;
voice used backend-model delegation, not direct voice-model function calls.
The experiment did not establish acoustic quality, human barge-in, Android
lifecycle, subscription entitlement for other accounts, or durable recovery of
external mutations. Metadata evidence excludes transcripts, SDP, and credentials.

At that revision, `npm run check` passed formatting, lint, strict TypeScript,
nine deterministic tests, and browser bundling. That count is historical; run
the current check for present behavior. The old narrative report and initial
Android-port plan are retained in Git history.

## Relationship to the Android app

Native voice and phone-action implementations now live in the main app; this
browser counter harness remains an isolated protocol/regression experiment.
It does not define current Android authentication, configuration, or capability
support. See [Architecture](../../docs/architecture.md) and
[Operations](../../docs/operations.md#device-verification) for those boundaries.
[THIRD_PARTY.md](THIRD_PARTY.md) retains source provenance and license attribution.
