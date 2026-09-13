# Changelog

All notable changes to EVA will be documented here.

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
