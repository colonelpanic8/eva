Protocol Kotlin mirrors and JSON examples are adapted from Ivan Malison's
voice-device-agent (Apache-2.0), protocol v1 revision 1. Source paths:
`android/companion/app/src/main/java/com/voicedeviceagent/companion/proto/`
and `protocol/v1/examples/`. The table renderer follows
`src/voice_device_agent/bridge/observation.py`.

Wire-only gate/lease/link shapes remain for fixture compatibility. EVA does not
implement an approval gate, risk classifier, evidence validator or injection
filter. The table omits the prototype's policy instruction line.

Port baseline: `22e38e1ab688c647118ea7b67e7b5812d7abe76c`.
