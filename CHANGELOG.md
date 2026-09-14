# Changelog

All notable changes to EVA will be documented here.

## [Unreleased]

### Added

- A Screen control switch removes screen observation, tapping, and text replacement from newly opened provider sessions and rejects calls from stale sessions after it is switched off. It defaults to on so existing installations keep their current behavior.
- The OpenAI provider layer can seed replacement sessions from prior user and assistant messages, action receipts, and session notes. Responses sessions can continue an existing turn without submitting another user message, while realtime voice waits for every history item to be acknowledged before reconnecting the microphone. The thread controller does not invoke this path yet.

### Changed

- The conversation reads as threads. Each session opens with a divider naming its mode and model ("Text session · gpt-5.6-sol") and closes with "Session ended"; within a turn, the actions the model ran hang off a branch under the request, ahead of the answer, instead of appearing as unrelated cards.
- When EVA ends a voice conversation itself, the assistant panel closes with it, so a request that opened an app leaves you in that app instead of behind EVA's panel. Stopping the conversation yourself leaves the panel up, and EVA's own screen is never closed by a hang-up.

### Fixed

- The model pickers list the account's models again. The field opens holding the current model, and that text was being used as a filter, so the menu showed only the model already chosen.

## [0.15.0] - 2026-09-14

### Added

- Experimental screen control through Shizuku on Android 11 and later. After granting Shizuku access, EVA can read a bounded accessibility hierarchy, replace an observed text field, and tap an observed element through its ordinary capability dispatcher. Screen mutations use short-lived, single-use observation references and revalidate the target before input.
- Music and podcast control that is not tied to any particular app. Pause, resume, skip, stop, and "what's playing" go through Android's media session, the same one the lock screen and headset buttons use, so Spotify, YouTube Music, a podcast player, and a browser tab all work without EVA supporting any of them individually.
- "Play Black Hole Sun on Spotify" starts the app if it is not already running, through the same interface Android Auto uses to play things in arbitrary media apps, and tells you what actually started rather than only that it asked. The app decides what the words match, and an app that turns EVA away falls back to the standard play-by-name intent.
- Media volume as a spoken control, separate from the ringer, alarms, and EVA's own voice.
- A media controls setting. Turning on notification access lets EVA read what is playing, pick between apps when two are playing at once, and confirm that a pause actually took effect instead of only reporting that a button was sent. Android offers nothing narrower for this, so the setting says plainly that notifications also reach EVA, which ignores them. Without the grant EVA still sends the play, pause, and skip buttons, and says it cannot see what received them.

## [0.14.1] - 2026-09-14

### Fixed

- Contact lookup tolerates misheard spellings such as “Alex Mallison” for “Alex Malison”, searches nicknames, and distinguishes partial matches from clear choices.
- Recent direct conversations break contact-name ties and prioritize voice transcription hints. EVA remembers numbers used for texts and calls and offers the last-used number first unless another is requested.
- Different people with identical names remain separate candidates.

## [0.14.0] - 2026-09-14

### Added

- EVA can end a voice conversation itself. When you say goodbye, say that is all, or ask it to hang up, it says a brief goodbye and ends the session once that goodbye has finished playing. Typed sessions are unchanged.

## [0.13.0] - 2026-09-14

### Added

- EVA can be selected as Android's device assistant. The assistant gesture opens a Compose panel over the current app, joins or starts a voice session, keeps conversation text hidden on the lockscreen, and does not consume screen context or screenshots.
- The settings screen reports whether EVA is the active device assistant and links to Android's assistant selection screen.

### Fixed

- EVA's required system speech recognizer delegates to on-device recognition or another installed recognizer without recursing between debug and release installs, and cleans up delegates after recognition ends.
- Assistant app launches are offered only while the panel is shown, matching Android's rejection of launches from hidden sessions.

## [0.12.0] - 2026-09-14

### Added

- Direct realtime voice now works with a signed-in ChatGPT subscription on the phone, without a broker or API key. Voice actions use the same capability dispatcher and can hand off to Android apps such as Clock.

### Fixed

- Removed an invalid local rejection of subscription voice and corrected the transcription session options that could make realtime negotiation time out.

## [0.11.0] - 2026-09-13

### Changed

- EVA asks for everything it needs the moment you open it: microphone, contacts, reading and sending texts, and notifications. A voice session that keeps running while the phone is locked or another app is in front cannot put a permission dialog on screen, so anything asked for later arrived too late to help.
- Voice keeps the microphone while EVA is in the background. The ongoing notification now covers both listening and speaking, so a session survives another app taking over the screen.

### Removed

- The listen-only option, where EVA spoke but never heard you. Voice needs the microphone; when it is declined, EVA offers to ask again or open system settings instead of connecting a half-working session.

## [0.10.0] - 2026-09-13

### Added

- Headset voice buttons start a voice session. A long-press or Bluetooth voice-recognition request opens EVA straight into voice instead of parking on the connect screen, and a locked phone shows transport controls over the keyguard.
- Voice sessions stay on the headset that started them. Requests made through headphones now answer and listen on the headphones instead of forcing the phone's speaker and microphone.

### Changed

- Typed conversations default to `gpt-5.6-sol`, verified live against the account's own model list, instead of a model name the account does not carry.
- The reasoning effort choices match what the account's models actually accept (`low` through `ultra`), and the model picker lists the account's real text and voice models again instead of coming back empty.

### Fixed

- The subscription model list request now clears the backend's client-version floor, so connect-time validation checks the chosen model against what the account can actually use.

## [0.9.0] - 2026-09-13

### Added

- The conversations already on your phone are now targets. EVA can list them, say who is in each one, and read a thread's recent messages back to you, so "what did the group say?" and "reply to that thread" both work.
- Group texts. Sending to several people at once, or to a group conversation, goes out as one MMS so everyone stays in a single thread instead of receiving separate one-to-one texts. When a carrier has group messaging switched off, EVA says so and offers a draft rather than quietly texting people one by one. Group messages EVA sends itself travel as MMS, because Android gives no third-party app a way to send RCS; opening a draft instead hands the group to your messaging app, which may send it over RCS.
- Reading conversations asks for the text-message permission the first time; declining leaves texting by name and number exactly as it was.
- Chats that have moved to RCS are only partly visible: EVA can read their older text-message history but not anything sent over RCS, and it says so when reading a conversation instead of presenting a stale thread as the whole story.
- A reasoning effort setting for typed conversations, next to the model picker.

### Changed

- Typed conversations default to `gpt-5.6`.

## [0.8.0] - 2026-09-13

### Added

- EVA can send a text message itself, so a spoken request finishes without opening a messaging app. Drafting is still available when you want to read the text first.

### Changed

- Contact lookups return one ranked best match, merge the same person duplicated across accounts, and ask you to choose only when two contacts fit equally well. EVA can also search first or last names specifically when a full name is stored differently than it was spoken.

## [0.7.0] - 2026-09-13

### Changed

- Spoken captions are noticeably more accurate. Voice sessions now transcribe with `gpt-transcribe`, tell it what it is listening to, pin the language, and trade caption latency for word accuracy. The speech model always heard the audio itself, so this changes what you read, not what EVA understands. The new model also costs less per minute than the one it replaces.
- Contact names you have already granted EVA access to are sent as transcription keywords, so spoken names are captioned with the spelling your phone knows. Names are read only when contacts access was already granted, never for typed sessions, and are limited to the 200 most-contacted.

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
