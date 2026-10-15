# EVA under test on an emulator

EVA's Twilio call route carries the call inside EVA (the Twilio Voice SDK hands EVA the
call's audio), so call tasks and the call agent can run on an Android emulator with no
Shizuku and no physical phone. This directory boots a persistent emulator for EVA Debug,
configures the route, places call tasks to the [Twilio fake hotline](../twilio/README.md),
collects EVA's own records next to Twilio's `verify` evidence, and records demos.

`eva_emulator.py` refuses any serial that is not an emulator, and refuses the owner's phones
and the WhatsApp far end by name. Every command uses the serial `up` recorded in
`~/.local/share/eva/eva-under-test/state.json` unless `--serial` is given.

## Once

```sh
just emulator-tools            # emulator 37.1.11, Android 36 Play image, gRPC Python (Nix)
just eva-emulator up &         # windowed, -gpu host; keeps running; prints the serial
just eva-emulator wait         # boot, animations off, stay awake
just build && just eva-emulator install    # EVA Debug, runtime permissions granted
```

`up` runs the shared [`start-emulator.py`](../../device-control/start-emulator.py) with a
window and `-gpu host` (headless swiftshader made input unusable), its own AVD under
`~/.local/share/eva/eva-under-test` (so sign-in and settings survive restarts), and audio
reachable only through the emulator's token-protected gRPC controller. Nothing reaches the
desktop's speakers or microphone. The window needs a Wayland/X session; `up` sets
`WAYLAND_DISPLAY=wayland-1 DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/1000` (`--headless` skips it).

Two steps need the owner:

1. **Model sign-in.** `just eva-emulator signin` starts EVA's ChatGPT device-code sign-in and
   prints the page and code; the owner approves it in a browser within 15 minutes, and the
   command returns once EVA shows the account.
2. **Twilio route.** The account resources come from `../twilio/voice-sdk --apply`, which the
   owner must approve (it creates an API key, a Function and a TwiML app; run it without
   `--apply` to see the plan). It writes the pass entry `twilio.com/eva-voice-sdk`, and
   `just eva-emulator twilio-config` types those values into EVA's **Settings > Phone call
   agent > Twilio calls**, as the owner would. The API key secret reaches the emulator's shell
   on stdin, never a host command line, and EVA keeps it in its encrypted secret store.

`twilio-config --dummy` enters well-formed fake values instead. Twilio then refuses the access
token, so `call-test` exercises the SDK, the custom audio device and the refusal path with
nothing dialed and no account involved.

## Calls and evidence

| Command | What it does |
| --- | --- |
| `call-test` | One Twilio-route call task through EVA's dispatcher (`CallTaskLiveTest`, needs `./gradlew :app:assembleDebugAndroidTest`); no main agent |
| `call` | Opens a new conversation, connects for text and types the hotline request, so the main agent starts the call task |
| `collect` | EVA's call-task records from its databases (`run-as`), filtered logcat, a screenshot |
| `record --seconds N` | Screen (scrcpy) and what the emulator's speaker plays (gRPC), muxed to `demo.mp4` |
| `demo` | `record` around `call` with listen-in turned on (`--no-listen` leaves it off), then `collect` and the hotline's `verify` |

`demo` writes `experiments/call-audio/build/demos/twilio-<time>/`: `demo.mp4`, the raw screen
and speaker tracks, `call-records.jsonl`, `logcat.txt`, `final.png`, `twilio-verify.txt` and
`summary.json` (route, outcome, leg end), and appends a line to `build/demos/index.md`. The MP4's
audio is loudness-normalized; `device-audio.wav` is the speaker as captured. `build/` is ignored; media is
never committed. Twilio's dual-channel recording of what the hotline heard lands in
`build/twilio-<CallSid>/` from `verify`.

The speaker track is what EVA plays on the emulator: the other party throughout (on an in-app
call the owner hears them only through EVA), plus the call agent's voice while listen-in is
on. The default goal follows the hotline scenario: billing, name and account 4417, hold, ask
for the hours, decline further help.
