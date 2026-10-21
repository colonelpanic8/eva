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

## Card-services regression

`card-test` replays the incident where EVA called a card company with no cardholder name, no
card number and no keypad credentials. It seeds a throwaway Password Store (EVA) and an EVA
memory note, asks EVA in a new conversation to "call my card company ... and see if they'll drop
the annual fee on my test card", waits for the call task to end, and judges the run from EVA's
databases, logcat and the hotline's `verify --scenario` evaluation of
[`scenario-card-services.json`](../twilio/scenario-card-services.json).

Seeding is idempotent and never touches the owner's keys or store:

- A passphrase-less PGP key (`EVA Test Store <eva-test@example.invalid>`) is generated once with
  its own `GNUPGHOME` under `experiments/call-audio/build/card-test-fixture/` and reused. The entry
  `cards/test-card-services` holds the fake card `number: 4111 1111 1111 1111`, `zip: 90210` and
  `name: Casey Sample`, the values the scenario's `expect` block names.
- The key and entry are written into the debug build of Password Store (EVA)
  (`app.passwordstore.colonelpanic.debug`) with `run-as`. Its EVA extension decrypts with a
  passphrase-less key and needs no app setup.
- EVA Debug keeps a memory note `Cardholder` with the name and billing ZIP.
- EVA's Extensions screen turns Password Store (EVA) and all of its actions on.

The Password Store APK is a debug build of
[colonelpanic8/Android-Password-Store](https://github.com/colonelpanic8/Android-Password-Store)
`eva-extension` with
[fields as secrets](https://github.com/colonelpanic8/Android-Password-Store/pull/1) and
[a build-time EVA Debug signer](https://github.com/colonelpanic8/Android-Password-Store/pull/2)
merged in, built with `./gradlew :app:assembleDebug -PevaDebugCert=<SHA-256 of EVA Debug's
signing certificate>` and copied to `build/password-store/app-debug.apk` (or passed as `--ps-apk`).
Without the matching signer, EVA lists the extension as an unauthorized caller.

The checks, each PASS or FAIL in `checks.txt` and `summary.json` under
`experiments/call-audio/build/card-test-<time>/`:

- `eva.memory.search` and a completed Password Store `get_credential` (requesting the `number`
  field) ran before `eva.call.start`;
- `eva.call.start` named `Casey Sample` in `onBehalfOf` and the card number's `secret:` handle in
  `credentials`, and a DTMF transcript item entered that handle;
- the card number, with or without separators, appears in no EVA database text, logcat or call
  transcript;
- no action was refused by the uncertain-mutation gate (either refusal wording);
- the call task ended Accomplished with the fee, no-waiver and Test Cash facts in its claim;
- the hotline received the digits `4111111111111111` and heard the name and ZIP.

The command exits nonzero on any failure. `card_test.py` holds these rules as pure functions over
the collected records; `test_card_test.py` (run by `../twilio/check`) covers them.

Run it from the repository root:

```sh
just build && just eva-emulator install          # an EVA Debug build with the call-prep changes
just eva-emulator card-test --seed-only          # seed and grant; places no call
just eva-emulator signin                         # owner: approve the ChatGPT device code
just eva-emulator twilio-config                  # from the existing pass entry twilio.com/eva-voice-sdk
direnv exec . experiments/call-audio/twilio/setup \
  --scenario experiments/call-audio/twilio/scenario-card-services.json \
  --reuse-number +15592493930                    # owner: deploy the card scenario
just eva-emulator card-test --hotline-ready      # one call to the hotline, then the verdict
direnv exec . experiments/call-audio/twilio/setup --reuse-number +15592493930  # owner: restore
```

Without `--hotline-ready`, `card-test` stops before typing the request and prints what is
missing, so a run never calls a hotline still serving another scenario.
