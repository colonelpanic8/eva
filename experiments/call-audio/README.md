# Cellular call audio experiment

`call_agent.py`, `probe.sh`, and `src/CallAudioProbe.java` bridge cellular call
audio to a Realtime agent. Local captures and transcripts go under `build/`.

## Twilio far-end verification

See [the Twilio fake hotline](twilio/README.md) for a hosted IVR, immediate durable
Gather receipts, dual-channel recordings, and `setup`, `verify`, and `e2e`
commands. Far-end evidence distinguishes intended uplink audio from what the
callee actually received.

## Driving EVA's call tasks on the phone (`phone`)

`phone/phone` (also `just phone ...`) drives EVA Debug's delegated phone calls on the
owner's shared Pixel and records demos. It needs only `adb`, Python 3 and, for demos,
`ffmpeg` (taken from `nix run nixpkgs#ffmpeg` when not on PATH). Run it from the
worktree root:

```sh
experiments/call-audio/phone/phone status              # line state, foreground app, lock
experiments/call-audio/phone/phone scenarios           # what `run`/`demo` can do
experiments/call-audio/phone/phone run nist-silent --install
experiments/call-audio/phone/phone demo twilio-hotline # same, recording a demo
experiments/call-audio/phone/phone calls -n 2          # call tasks + transcripts from EVA's journal
experiments/call-audio/phone/phone locked --record     # call with the screen off (see below)
```

Safety, by default:

- **One phone, one session.** Every command holds `/tmp/eva-phone.lock` (`flock`) for its
  whole sequence, so it waits for, and never interleaves with, another session. The lock
  is released when the process exits; there are no marker files to go stale. For a manual
  multi-step session, run `phone hold &`, wait for `ACQUIRED`, export
  `EVA_PHONE_LOCK_HELD=1` for your `phone` commands, and kill the holder when done.
- **Never a second call.** Placing anything refuses while any `mCallState` is non-zero.
- **The phone by serial.** `--serial`/`EVA_PHONE_SERIAL` default to the Pixel's wireless
  debugging service (reconnected through `adb mdns services` when needed); emulator
  serials are refused.
- **The owner's screen.** Scenarios refuse when another app is in front and the phone
  was used in the last two minutes (`--take-screen` overrides once you know it is free),
  or when it is asleep or locked. Taps go only to EVA's own elements, Back is sent only to
  hide EVA's own keyboard, and a scenario that fails mid-call hangs its call up from the
  card.
- **No secrets.** Twilio steps run the existing `twilio/setup` and `twilio/verify`, which
  read API keys from `pass` without printing them; keypad credentials are referenced by
  name only.

Scenarios (`phone scenarios`): `nist-silent` (silent through ticks, one announcement,
finish, hang up), `nist-listen` (listen-in plus two whispers; no self-echo), `nist-route`
(reports which output device plays listen-in at each step; Android offers no shell
command to switch a call to speaker and the dialer's in-call screen is not exported, so
toggle speaker by hand from the ongoing-call notification while it runs),
`twilio-hotline` (keypad menu, spoken name and account, hold, hours, finish) and
`twilio-credential` (as above, with the account number entered from the saved keypad
credential `test/account` while `twilio/scenario-keypad-account.json` is deployed; the
default scenario is redeployed afterwards, even on failure). Each run prints the call
task, its transcript and, for Twilio, `verify`'s Gather evidence, and exits non-zero
unless the task ended as expected. Twilio scenarios place a real call: keep to the
hotline budget in `twilio/README.md`.

### Demos

`phone demo SCENARIO` records while it runs: the active display (the inner or outer
screen of the foldable) with `screenrecord` in 170 s segments, and the call audio from
EVA itself. Debuggable builds save a call's audio only while `files/debug-call-audio`
exists in the app's data; the recorder creates it, collects
`cache/call-audio/<leg>/{downlink,uplink}.pcm` (24 kHz PCM16: the other party as the
agent heard it, after listen-in echo removal, and what EVA sent), and deletes both
afterwards. Production builds never record. The pieces are aligned on the phone's clock
and muxed into `demo.mp4`.

Demos live in the ignored `build/demos/<time>-<scenario>/` (video, `transcript.txt`,
`task.json`, `twilio-verify.txt`, `final.png`, and `logcat.txt` with EVA's call tags, which
`run` also keeps under `build/phone/`), indexed in `build/demos/index.md` and
`index.json`. If anything other than EVA or the dialer comes to the front while
recording, the recording stops and its video is discarded, and the index says so.

### Locked phone

`phone locked` turns the screen off and places a call task through the
`CallTaskLiveTest` instrumentation (no UI). Build its APK first with
`./gradlew :app:assembleDebugAndroidTest`. It refuses if another app was used in the last
two minutes, wakes the screen afterwards, and reports whether the screen stayed off.

Lint the tool like the Twilio scripts: `ruff check`, `ruff format --check` and
`shellcheck` on `phone/` (`nix shell nixpkgs#ruff nixpkgs#shellcheck` if missing).

### Pending device check

Live captions and the scoped listen-in gate are JVM-tested but not yet device-verified (the
phone's wireless debugging link failed before the check could run). With EVA Debug built
from this branch, run one NIST call (no Twilio budget):

```sh
just phone run nist-listen --install
```

Then check, in the printed run directory under `build/phone/`:

- `logcat.txt` has `Listen-in off: the agent hears all capture, ungated` and no
  `Monitor echo` lines before the card's Listen in step, then `Listen-in on: ...` and
  `Monitor echo (gate engaged ...)` lines after it.
- The transcript has the agent's two utterances and no REMOTE line echoing them.
- `caption-listen-off-*.png` and `caption-listen-on-*.png` show the agent's words on the call
  card while it speaks (italic, ending in "…"), replaced by the final line afterwards.

