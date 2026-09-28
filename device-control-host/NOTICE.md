The eval YAML fixtures come from Ivan Malison's voice-device-agent (Apache-2.0),
`evals/cases/{settings,chrome_read,media}`, baseline
`bb45f1274e4e5568ecf70d0e52156956feab3923`.
The host independently ports the semantics in `evals/case.py`, `evals/device.py`,
and `evals/checkers.py`; it does not embed or invoke their Python implementation.

`src/test/resources/observation.json` is the core protocol v1 synthetic example.
`launcher-recorded.json` was captured through Portal 0.7.25 on the dedicated
`vda-tablet-host` API 37 emulator on 2026-09-28. `media-session.txt` is a synthetic
multi-session dumpsys fixture exercising priority selection and metadata parsing.
