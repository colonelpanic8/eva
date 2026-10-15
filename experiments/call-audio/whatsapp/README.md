# WhatsApp far end on an emulator

A WhatsApp account on an Android emulator on the development machine answers EVA's
WhatsApp calls, so a test can check what the far end actually heard, like the
[Twilio hotline](../twilio/README.md) does for cellular calls. The emulated phone has a
speaker and microphone but no host audio backend, so nothing reaches the desktop's
speakers or microphone. What it plays is recorded through the emulator's gRPC controller
(`streamAudio`), which listens on 127.0.0.1 and requires the token in the emulator's
discovery file (`$XDG_RUNTIME_DIR/avd/running/pid_*.ini`).

## Rules

- Registering WhatsApp on the emulator is the owner's action: choosing the number,
  accepting WhatsApp's terms and entering the verification code. Never do it for them.
  A number can be registered on only one phone, so the emulator needs a number that is
  not the owner's own WhatsApp. WhatsApp blocked the Twilio number (a VoIP number) at
  `CustomRegistrationBlockActivity`.
- Call only the far end's own number, never a real person, unless the owner says so.
- The owner may be using the far end's window (desktop workspace 1). Check `just status`
  before anything that changes its screen or makes it play sound (`test-sound`), and do
  not do either while they are registering or testing on it.
- The phone is shared: hold `/tmp/eva-phone.lock` for every phone sequence, check that
  no call is active (`phone-checks`), and do not open WhatsApp chats or contacts.
- Demos and recordings stay under `experiments/call-audio/build/` (gitignored). Never
  commit or upload media.

## Tools

Run recipes from this directory (`just --list`). Paths below are from the repository root.

| Recipe | What it does |
| --- | --- |
| `tools` | Builds `build/whatsapp-far-end-{sdk,python,tools}` from the flake's pinned nixpkgs ([`emulator.nix`](emulator.nix)): emulator 37.1.11 with the Android 36 Google Play x86_64 image as an SDK root, Python with gRPC, scrcpy and ffmpeg |
| `up` | Boots the far end headless with the software GPU; foreground |
| `up-window [port]` | Boots it in a window with the host GPU as `emulator-5556`; the window is usable for typing, unlike scrcpy over the software GPU. Needs the desktop session (`DISPLAY`/`WAYLAND_DISPLAY`) |
| `status` | Serial, gRPC port, whether WhatsApp is installed, the app in front |
| `fetch-apk` | Downloads the official APK from whatsapp.com into `experiments/call-audio/build/` and verifies it |
| `verify-apk APK` | Requires WhatsApp's signing certificates (pinned in `far_end.py`) |
| `install SERIAL APK` | Verifies, then installs on the emulator (refuses a non-emulator) |
| `test-sound SERIAL` | Posts a notification and requires its sound in a recording: checks the recording path |
| `answer SERIAL` | Waits for a ringing call and answers it (call key, then an on-screen Answer button) |
| `record SERIAL SECONDS WAV` | Records what the far end hears as 48 kHz mono WAV |
| `levels WAV`, `dtmf WAV` | Loudness over time; keypad keys heard, in order ([`audio_check.py`](audio_check.py), standard library only) |
| `phone-checks [SERIAL]` | Read-only phone checks under the phone lock ([`phone_checks.sh`](phone_checks.sh)): call state, WhatsApp version, voice-call row handler, WhatsApp's Telecom account, live WhatsApp calls, row count, EVA's permissions and call-control app op |
| `demo TITLE SECONDS --screen S[:DISPLAY]… [--far-end S] [--notes …]` | Records a demo, below |

`far_end.py up` refuses to boot a second copy of the AVD and prints the running serial.
The AVD lives in `~/.local/share/eva/whatsapp-far-end`, so WhatsApp's install and
registration survive restarts; [`start-emulator.py`](../../device-control/start-emulator.py)
does the booting (`--avd-dir`, `--audio`, `--grpc-port`, `--gpu`, `--port`, `--window`).

## A call test

1. `just tools`, then `just up-window` (or `up`), and `just status`.
2. Once WhatsApp is registered: on the phone, save the far end's number as a contact and
   wait until WhatsApp shows a voice-call action for it (EVA dials through that row).
3. `just answer emulator-5556 &` and `just record emulator-5556 120 ../build/far-end.wav &`,
   then start the call from EVA with `provider` set to `whatsapp`.
4. `just levels ../build/far-end.wav` shows when the agent spoke; listen to the WAV.

## Demos

`demo.py` mirrors each `--screen` read-only with scrcpy (no input, no device audio),
records the far end's audio over gRPC, aligns them by their measured start times
(scrcpy runs line-buffered so its "Recording started" time is accurate), and muxes the
screens side by side with the far-end audio into `demo.mp4` with ffmpeg. Each run gets
`experiments/call-audio/build/demos/<time>-<title>/` with the raw parts, `meta.json`
(commit, devices, offsets) and a line in `demos/index.md`.

```sh
just demo "WhatsApp call: agent books a table" 120 \
  --screen adb-67091FDDJ0007B-YBkFMQ._adb-tls-connect._tcp:0 --screen emulator-5556 \
  --far-end emulator-5556 --notes "commit and scenario"
```

The phone's screen can show private content: record it only for a planned demo, holding
the phone lock, and check the result before sharing it anywhere. The Pixel 11 Pro Fold has
two displays; give the one in use as `:display-id` (`adb shell dumpsys display | grep
mDisplayId`). The phone's own call audio cannot be captured, so the soundtrack is what
the far end heard.

## Findings

- Recording: dialpad taps on the emulator arrived at the wall-clock times they were made,
  and `dtmf` decoded them (697 + 1209 Hz for 1, and so on). It works in both GPU modes.
- A demo of the emulator screen with a notification sound muxed with the screen 0.25 s
  after the audio start, with the sound 0.5 s after it was posted.
- The emulator's PulseAudio backend fails to connect on this machine (`Failed to
  initialize PA context`), and this build has no ALSA backend, hence gRPC.
- `injectAudio` (the far end's microphone) crashed emulator 37.1.11 both times it was
  called, at 16 and 48 kHz, so the far end cannot speak a scripted prompt; it sends silence.
- The headless emulator's own window (`-no-window` off with the software GPU) fails without
  the desktop session's display variables; `up-window` sets them.
- Not yet run with WhatsApp: `answer`, and its parsing of `dumpsys telecom`, have not met a
  WhatsApp call. Whether WhatsApp voice calls work on this AVD is unverified.
