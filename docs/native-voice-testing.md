# Native voice verification

The opt-in `NativeVoiceLiveTest` exercises the Android WebRTC capture path,
the subscription-backed provider, transcripts, and decoded playback. It replaces
every capture buffer with silence or generated speech before WebRTC sends it.
It does not send ambient microphone samples. No injection hook is shipped in
the production app.

The test asks “What is thirty seven plus fifty eight?” and requires a user
transcript containing 37, an assistant transcript containing 95, the entire
fixture sent, and nonzero decoded output PCM. It runs with an empty tool catalog.
Normal instrumentation skips this test unless both explicit arguments are set.

## Run on a dedicated emulator

Start a fresh broker using the instructions in
[implementation.md](implementation.md#development-connection), or use an idle
broker owned by the current test session. It must have an existing host ChatGPT
login. Set the following variables in your shell; do not save a broker code in
source control. Select the emulator explicitly because the test launches EVA
and grants microphone permission to the debug application.

```sh
EVA_TEST_DEVICE=emulator-5576
EVA_TEST_PORT=PORT_FROM_BROKER
EVA_TEST_LINK='http://localhost:PORT_FROM_BROKER/#CODE_FROM_BROKER'

direnv exec . espeak -s 145 -w /tmp/eva-native-speech.wav \
  'Say EVA voice verified. What is thirty seven plus fifty eight?'
direnv exec . ffmpeg -v error -y -i /tmp/eva-native-speech.wav \
  -ar 48000 -ac 1 -f s16le /tmp/eva-native-speech.pcm
direnv exec . ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
direnv exec . adb -s "$EVA_TEST_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
direnv exec . adb -s "$EVA_TEST_DEVICE" install -r \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
direnv exec . adb -s "$EVA_TEST_DEVICE" reverse --no-rebind \
  "tcp:$EVA_TEST_PORT" "tcp:$EVA_TEST_PORT"
direnv exec . adb -s "$EVA_TEST_DEVICE" push \
  /tmp/eva-native-speech.pcm /data/local/tmp/eva-native-speech.pcm
direnv exec . adb -s "$EVA_TEST_DEVICE" shell am instrument -w \
  -e class com.colonelpanic.eva.providers.NativeVoiceLiveTest \
  -e evaSpeechPcmPath /data/local/tmp/eva-native-speech.pcm \
  -e evaBrokerLink "$EVA_TEST_LINK" \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`espeak` and `ffmpeg` generate a 48 kHz mono PCM16 fixture. If unavailable on
PATH, obtain them temporarily through Nix. The fixture must be 1–20 seconds and
its device path must match `/data/local/tmp/eva-[a-z0-9-]+.pcm`.
Skip the reverse command if this session already owns that exact forwarding.
The test prints bounded evidence and sample counts; it does not print transcripts
or credentials. It closes its provider and media resources on success or failure.

## What passed

On 2026-09-12 the test passed on the Android 16/API 36 Google Play x86_64
emulator with host audio disabled. It transmitted 467,332 synthetic PCM bytes,
received both expected transcripts, and observed 281,734 nonzero decoded PCM
bytes. See [the evidence record](../experiments/voice-poc/evidence/2026-09-12-native-speech.json).

This verifies native synthetic speech input and returned audio. It does not
verify a physical microphone, audible speaker output, echo cancellation,
Bluetooth routing, acoustic interruption, or voice-driven phone actions.

The integrated build also passed 57 JVM tests, formatting, fatal Android lint,
and APK assembly. Four focused emulator tests passed for native offer creation,
invalid-answer cleanup, and permission-state retention through Activity
recreation. Permission results in the retention test are simulated; it does
not exercise the operating system's permission dialog. The broker check passed
all 17 tests. Fable reviewed the controller teardown and live test; the root
review of Fable's UI changes led to retaining permission state across rotation.

## Voice-action correlation gate

A separate browser counter probe against Codex 0.154.0 observed a realtime
handoff followed by a root-thread backend turn and correlated tool calls. The
user's finalized transcript arrived **after** the handoff and backend turn
started. Transcript arrival therefore cannot safely define a new input or
invalidate the prior input: it may finalize the same utterance.

The media data channel supplies a user turn ID and a delegation pointing to it.
In this observation, the delegation's `id` matched the stdio handoff's `item_id`
and `handoff_id`; the delegation's own `handoff_id` was a different value.
Backend turn events contained no handoff ID. The
[sanitized event record](../experiments/voice-poc/evidence/2026-09-12-voice-correlation.json)
preserves these relationships without account IDs or transcripts.

Production voice tools remain disabled. Before enabling them, establish an
utterance identity and interruption signal that survives event ordering across
the media and control streams, then test overlapping speech, late transcripts,
duplicate handoffs, and calls arriving after invalidation. Binding the next
backend turn by arrival order alone is not sufficient evidence of that contract.
