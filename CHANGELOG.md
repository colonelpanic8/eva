# Changelog

All notable changes to EVA will be documented here.

## [0.6.0] - 2026-09-13

### Added

- Voice contact lookups assess transcription ambiguity, try ranked spelling and name-part variants, and expose a configurable retry budget.

## [0.5.1] - 2026-09-13

### Added

- A Copy code button on the ChatGPT sign-in screen, for approving the code in a browser on this phone.

## [0.5.0] - 2026-09-13

### Added

- Voice runs on your ChatGPT subscription. A signed-in account opens the realtime call itself, so speaking to EVA no longer needs an API key or a paired workstation.

## [0.4.0] - 2026-09-13

### Added

- Sign in with your ChatGPT account instead of an API key. EVA shows a one-time code, you approve it in a browser on any device, and typed conversation is then covered by your subscription rather than billed per token. The tokens are encrypted with the Android Keystore, refreshed as they expire, and excluded from backups.

### Changed

- A ChatGPT sign-in is used first when both it and an API key are present, and the key now sits behind "Use an API key instead".
- Voice still needs an API key or a paired host. A subscription covers typed conversation only. (Superseded in 0.5.0.)

## [0.3.2] - 2026-09-13

### Fixed

- A bad key or an unusable text model is now reported when you connect, instead of appearing to connect and failing on the first message.

## [0.3.1] - 2026-09-13

### Added

- The text and voice models are selectable. The picker lists the models your own account can use and also accepts a typed name, so a new model works without an app update.

### Fixed

- A rejected OpenAI request shows the provider's own message instead of the raw response body.

## [0.3.0] - 2026-09-13

### Added

- EVA runs without a workstation. Save an OpenAI API key on the phone and voice opens its own Realtime session, with the speech model calling phone actions directly; typed turns use the Responses API. The key is encrypted with the Android Keystore and excluded from backups.

### Changed

- The paired host link is now optional and only needed for subscription-backed access through a host.

## [0.2.4] - 2026-09-12

### Added

- Voice sessions perform phone actions. A spoken request is delegated to a tool call, executed through the same dispatcher and journal as typed mode, and confirmed aloud.
- A foreground service keeps the voice session alive when an action opens another app, so the confirmation plays and the conversation can continue.

## [0.2.3] - 2026-09-12

### Fixed

- Voice works in published builds. Minification left every WebRTC class present and correctly named, yet still aborted the process inside the library's native load, so release builds are no longer minified.

## [0.2.2] - 2026-09-12

### Fixed

- Voice no longer crashes the release build. Minification stripped the WebRTC classes its native library resolves by name through JNI, which aborted the process as soon as a call started.

## [0.2.1] - 2026-09-12

### Added

- The connection status line names the model in use, so it is visible whether a session runs on the text model or the realtime speech model.

## [0.2.0] - 2026-09-12

### Added

- Model-backed conversation: EVA sends its capability catalog to a provider, which chooses structured tool calls that EVA validates and executes.
- Twelve native capabilities: map search, driving navigation, text drafts, alarms, timers, dialing, web search, opening a URL, email drafts, calendar events, launching an app, and settings screens.
- Native realtime WebRTC voice conversation with microphone permission handling and a listen-only mode.
- Selectable as the system digital assistant through the assist gesture.
- A durable action journal that replays receipts for repeated calls and reports uncertain outcomes after an interruption.

## [0.1.0] - Unreleased

### Added

- Initial native Kotlin and Jetpack Compose application shell.
- Reproducible Android development environment, validation, release, and self-hosted F-Droid workflows.
