# Keyword core attribution and model provenance

## Ported source

The Kotlin sources and corresponding JVM tests are adapted from Ivan Malison's
[voice-device-agent](https://github.com/colonelpanic8/voice-device-agent),
`android/companion/app/src/{main,test}/java/com/voicedeviceagent/companion/`,
revision `bb45f1274e4e5568ecf70d0e52156956feab3923`, under Apache-2.0 (see EVA's
[LICENSE](../LICENSE)). Packages use EVA’s `com.colonelpanic.eva` namespace. EVA replaces the
companion transport and execution dependencies with local ports, separates the
ONNX implementation, and adjusts state publication and spotter lifecycle handling.

## openWakeWord models (not bundled here)

This module ships no model binaries or recorded audio. The following provenance
applies when a personal/pilot host supplies the companion's model set.

Downloaded unmodified from the openWakeWord
[v0.5.1 release](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1)
(the model set that openWakeWord v0.6.0 still uses).

| File | Role | SHA-256 |
| --- | --- | --- |
| `melspectrogram.onnx` | ONNX export of a fixed torchaudio mel spectrogram | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
| `embedding_model.onnx` | openWakeWord's re-implementation of Google's `speech_embedding` backbone | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |
| `hey_jarvis_v0.1.onnx` | "hey jarvis" classifier (dev wake phrase) | `94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb` |
| `hey_mycroft_v0.1.onnx` | "hey mycroft" classifier (dev stand-in for the stop phrase) | `c2a311e8fa1338de89c31b3b46dc4dffd4af2f9a8d6ddead48893c2d301b1f18` |
| `hey_rhasspy_v0.1.onnx` | "hey rhasspy" classifier (dev stand-in for mute/unmute) | `5a9b3ed3be2910e35780e097905aa9f35a9c10038df47914cf2b3ec4d670f6ea` |

**License: CC BY-NC-SA 4.0.** openWakeWord's code is Apache-2.0, but its README
states that all of its pre-trained models are licensed under
[Creative Commons Attribution-NonCommercial-ShareAlike 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/),
because their training data includes sets with unknown or restrictive licenses.
The embedding backbone derives from Google's
[`speech_embedding`](https://tfhub.dev/google/speech_embedding/1) (Apache-2.0),
but openWakeWord ships its re-implementation under the same model license, so
this module treats all five files as CC BY-NC-SA 4.0. Consequences:

- Intended for this personal, non-commercial research pilot, with attribution (this file).
- Not usable in a commercial build. That needs models trained on permissively
  licensed data (openWakeWord's training pipeline can do this), or Porcupine
  under a Picovoice license.
- Models trained by adapting these files (for example a custom stop phrase on
  top of the bundled backbone) inherit ShareAlike.

No stop-phrase model exists in the openWakeWord release or in the community
collection ([home-assistant-wakewords-collection](https://github.com/fwartner/home-assistant-wakewords-collection),
checked 2026-09-28); real stop/mute phrases require separately trained models.

The streaming feature pipeline in `keyword/OpenWakeWordPipeline.kt` is a
Kotlin port of the algorithm in openWakeWord's `openwakeword/utils.py` and
`openwakeword/model.py` (v0.6.0, Apache-2.0, © David Scripka).

## ONNX Runtime

`com.microsoft.onnxruntime:onnxruntime-android` (app) and
`com.microsoft.onnxruntime:onnxruntime` (desktop evaluation tool), version 1.30.0 in
`keyword-core/build.gradle.kts`. MIT License, © Microsoft Corporation.
