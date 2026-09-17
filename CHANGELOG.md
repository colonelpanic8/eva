# Changelog

All notable changes to EVA will be documented here.

## [Unreleased]

### Changed

- Alarms and timers use the Clock catalog extension instead of built-in tools. EVA adopts it once per configuration through the existing shipped-default mechanism; removal and disablement remain portable. Clock actions request the clock UI and report handoff rather than verified creation.

## [0.21.0] - 2026-09-16

### Added

- Tool schemas accept a bounded string map, and an intent binding can spread such an argument into extra query parameters; content projections can declare a `json` column that decodes a provider's embedded document into data. The Mova package uses both so a capture template's own prompts are readable and fillable.

## [0.20.0] - 2026-09-16

### Added

- Web search, opening a web page, email drafts, calendar events, and settings screens are catalog extensions (Web, Email, Calendar, Settings), installed and approved as shipped defaults like Google Maps.
- Declarative intent bindings can choose the action from a closed publisher map, take a whole URI from one argument restricted to declared schemes, and target a fixed `content:` insert.

### Removed

- The built-in web search, open page, email, calendar, and settings tools; the four default extensions replace them with the same behaviour.

## [0.19.0] - 2026-09-16

### Added

- Google Maps is a catalog extension: map search through `geo:` and turn-by-turn navigation through `google.navigation:` with a travel mode (driving, bicycling, walking, two-wheeler). EVA installs and approves it automatically the first time a configuration runs; `packages.appliedDefaults` in `eva.yaml` records that, so removing it stays removed everywhere.
- Declarative intent bindings can place `{name}` placeholders in the URI after its scheme, and enum string slots can map the model's readable values onto the target's codes.

### Changed

- The extension catalog is a Git repository, cloned read-only under app storage and fast-forwarded on refresh; every `packages/*.json` in it is listed. Repository indexes and their digests are gone, and index-era sources are read as their repository.

### Removed

- The built-in map search and driving navigation tools; the Google Maps extension replaces them.

## [0.18.0] - 2026-09-14

### Added

- Device assistant settings can choose whether external assist gestures, headset commands, and voice intents end after one completed request or stay open for follow-up requests. The choice is portable in `eva.yaml`; the in-app **Ask once** action remains one-and-done.

### Changed

- Refreshing the extension catalog is now a single action beside the available extensions. EVA no longer asks for an extension index URL in general settings and continues to use the configured catalog source internally.

## [0.17.1] - 2026-09-14

### Fixed

- Assistant contact lookup, SMS reads/sends, and Shizuku device-setting actions reuse existing permissions without requiring EVA’s main activity. Missing grants still require setup in EVA.
- Android’s lock-screen assistant launch callback now opens EVA’s secure hands-free screen and starts or joins voice with the conversation hidden.

## [0.17.0] - 2026-09-14

### Changed

- Declarative extension packages now come from the configured package catalog instead of being bundled into the app. Portable configuration format 3 removes bundled-package instance identities; older configurations preserve their choices and identify packages that need to be reinstalled from the catalog.

### Fixed

- The installable Mova fixture pins each `mova://` action to its intended exported activity, avoiding Android's same-app activity chooser.
- Voice instructions now require EVA to call `eva.session.end` in the same response as its closing line, because saying goodbye by itself does not close the call. Untouched stock prompt files are upgraded automatically while custom wording is preserved. Connected calls continue to play the falling end-of-call cue exactly once on normal hang-up and connection-failure teardown paths.

## [0.16.0] - 2026-09-14

### Added

- Declarative packages can mix bounded Android content-provider reads with intent handoffs. Content URIs accept typed, encoded query and path slots; reads return whole projected rows as text and structured data with honest truncation and failure outcomes.
- Extension settings expose Mova's Android read-permission request and provider availability. Mova and Paseo content authorities are visible without an AIDL service; portable configuration retains the device authorization requirements for restore.
- JVM fixtures use the installable mixed Mova and Paseo packages: template/todo/agenda reads follow Mova 7.0.1's contract, and Paseo catalog reads retain the host ID for subsequent intent actions.

- Reasoning effort is now chosen separately for the text model and the voice model. The two legs
  accept different levels -- `max` only on text, `minimal` only on voice -- so one shared choice
  could not be right for both, and the speech leg now sends an effort at all instead of silently
  leaving it to the server. Existing configuration files keep working; a file written before this
  change takes the built-in voice default.

- A short rising chime plays when a voice call goes live and a falling one when it ends, so a call
  started by the assistant gesture, or one that hangs up on its own, is obvious without looking at
  the screen. The tones follow the phone's alert volume.

- An Ask once button on the main conversation screen invokes EVA through Android's device-assistant panel, making the one-request assistant flow easy to test without using the system gesture.
- Conversations are threads that outlive a call. Hanging up no longer cancels what EVA was doing: an unfinished request carries on in the background, moving from the voice model to the text model once the call ends, and the answer arrives as a notification. Ask for something and hang up, and EVA still finishes it.
- A conversation list in the drawer. Voice and assist launches start a new conversation, and an earlier one can be reopened and continued by text or voice. A resumed session is given what was already said, including which actions ran and what they reported; a voice session waits for all of it to be acknowledged before the microphone comes back.
- A Stop control for a request in progress, which is now a different thing from hanging up.
- A request may make several read-only lookups, such as searching contacts for a misheard name, while still performing at most one action that changes something.
- A Screen control switch removes screen observation, tapping, and text replacement from newly opened provider sessions and rejects calls from stale sessions after it is switched off. It defaults to on so existing installations keep their current behavior.
- The system prompt is now a file you own. `eva-prompt.yaml` lists the components EVA's instructions are built from, each with its text, whether it applies to voice, text, or both, and an on/off switch; a component can also reword or withhold tools. EVA writes its stock prompt there on first run, reads the file at the start of every session, and reports a parse problem with its line instead of quietly using something else. Pick any file with the system picker to keep the prompt in a synced folder or a git checkout; EVA's own copy stays the fallback, reachable with `adb`.
- An Instructions screen in the drawer that switches components on and off, edits their text and scope, adds and removes them, chooses or creates the file, and resets to the stock prompt.
- Two ways a call can end, shipped as alternatives in the stock prompt. "One request" is how a phone assistant behaves: EVA answers what you asked, says a short closing line, and hangs up without asking whether there is anything else. "Open conversation" keeps the call going until you end it or ask EVA to hang up, which is what EVA did before. New installs start on "One request".
- Queueing a song so it plays after the current track. Spotify works through its Web API after connecting a user-supplied Client ID, and EVA also discovers installed players such as Jellyfin that expose searchable, editable queues through Media3. An app with no provider is answered by naming the apps that do rather than by a bare refusal.
- A separately switchable voice instruction keeps simple phone actions to a brief confirmation unless more detail is requested.
- The Instructions screen can update its catalog from the default `eva-instructions` GitHub repository or any raw HTTPS YAML source. Updates replace repository-owned text and order while preserving on/off choices for matching instruction ids; the baked-in catalog remains the offline fallback.
- Extensions can return machine-readable results. Installed-app services reply with MCP-style `content` text blocks plus `structuredContent`, and can declare an `outputSchema` that EVA enforces; declarative HTTP item projections, pointer results, and content-provider rows attach the same items as structured data beside their text. The data is journaled with the receipt, replayed into resumed conversations, and delivered to the model as quoted external data.
- Capabilities in both extension formats share one MCP tool object (`tool.name`, `title`, `description`, `inputSchema`, optional `outputSchema` and `annotations`), the same `effects` vocabulary including `external_handoff`, and the same `execution` block with `maxWaitMillis`. Tool inputs may include arrays of scalars; a declarative request body can carry one as a JSON array.
- JSON Schemas for packages, repository indexes, the shared tool object, and the installed-app describe and execute replies live under `docs/schemas/`, with decodable fixtures under `docs/examples/`.
- Installed-app `describe` now receives a request stating which protocol versions EVA accepts, and descriptors, capabilities, tools, and replies may carry a `_meta` object that EVA digests but does not interpret, so providers have a forward-compatible place for their own data.

### Changed

- Both extension formats changed shape without a compatibility path. Capabilities nest their MCP tool under `tool`, the action title lives in `tool.title`, the `cancellation`/`idempotency`/`reconciliation` fields are gone, and installed-app descriptors use `maxWaitMillis`, drop `schemaVersion` and `result.mediaType`, and reply with `content` instead of `message`. The bundled Caffeine and Messages packages were rewritten to the new shape, so their grants need renewing.
- The conversation reads as grouped turns. Each session opens with a divider naming its mode and model ("Text session · gpt-5.6-sol") and closes with "Session ended"; within a turn, the actions the model ran hang off a branch under the request, ahead of the answer, instead of appearing as unrelated cards.
- When EVA ends a voice conversation itself, the assistant panel closes with it, so a request that opened an app leaves you in that app instead of behind EVA's panel. Stopping the conversation yourself leaves the panel up, and EVA's own screen is never closed by a hang-up.

### Fixed

- Hanging up no longer kills the app. A turn that finished within milliseconds of the call ending stopped the background work service before Android had delivered its first `onStartCommand`, so `startForeground` never ran and the system killed the process with `ForegroundServiceDidNotStartInTimeException`. A stop that arrives before the latest service start is foreground is now held and applied by the service itself, including when a new background turn begins while the previous service instance is still tearing down. The voice session service shared the same shape and got the same guard.
- Voice starts again on a conversation that already has history. EVA labelled each replayed history item with a 41-character id, past the Realtime API's 32-character cap, so resuming an existing conversation by voice failed with "Invalid 'item.id': string too long." A new conversation was unaffected, because it has no history to replay.
- A request carried on after a hang-up no longer dies at the handover. EVA's own replies were resent to the text model as `input_text`, a content type the Responses API only accepts on an incoming turn, so the first request to move from voice to text came back as "OpenAI rejected the request (400): Invalid value: 'input_text'." An assistant turn is now sent as `output_text`.
- Tool results reach the model whole. Every action result was silently cut to 2,000 characters on its way to the model, so a long agenda lost most of its items and the "[Truncated]" note that would have said so. The budget now matches the 16 KiB extension result limit, and a result EVA does have to cut ends with an explicit truncation note.
- EVA starts again. The pattern matching `{{variable}}` references in the prompt file was rejected by Android's regex engine, so building the prompt aborted the app on launch. Only a device run catches this: the desktop JVM accepts the same pattern, which is why the unit tests passed.
- The model pickers list the account's models again. The field opens holding the current model, and that text was being used as a filter, so the menu showed only the model already chosen.
- "Play X on Spotify" now plays instead of stopping at Spotify's search screen. Spotify turns EVA away as a media browser client, and answers the standard play-by-name intent with a results page rather than playback. EVA now asks the app's own media session to play the words first when it has one, which needs no screen, and after falling back to the intent it asks the session the intent brought up to play them. Confirmation waits for the track to change rather than reporting whatever was already playing.

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
