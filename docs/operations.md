# EVA operations

## Development

Run project commands through `direnv exec . <command>` or
`nix develop .#android --command <command>`. The supported baseline is JDK 17,
Android SDK 37, Gradle 9.8.0, AGP 9.4.1, and Kotlin 2.4.20. AGP supplies built-in
Kotlin; do not add `kotlin-android`. Minimum Android SDK is 23.

```sh
direnv exec . just --list
direnv exec . just format
direnv exec . just check
```

`just check` runs Kotlin formatting checks, fatal Android lint, JVM tests, and
debug assembly. For focused iteration, use a targeted Gradle test selection;
run the required aggregate check before handoff. Android instrumentation and
live-provider tests are separate, opt-in checks.

```sh
direnv exec . ./gradlew :app:testDebugUnitTest --tests 'com.colonelpanic.eva.conversation.ThreadControllerTest'
direnv exec . ./gradlew :app:assembleDebugAndroidTest
```

Debug app: `com.colonelpanic.eva.debug`; production: `com.colonelpanic.eva`.
`just install` installs `app/build/outputs/apk/debug/app-debug.apk`.
Specify `adb -s DEVICE` when multiple devices are connected. Do not replace a
user's production install merely to run a development test.

`just icons` regenerates Android, F-Droid, and repository icons from the canonical
source at `assets/branding/eva-face-profile-v8-teal-hair-blue-face.svg`.
`just fdroid-changelogs` derives fastlane changelogs from `CHANGELOG.md`.
Experiment-local commands belong in the
[voice harness](../experiments/voice-poc/README.md) and
[device-control probe](../experiments/device-control/README.md) READMEs.

## Configuration verification

The focused configuration tests cover composition, linked-file conflicts,
restoration into app stores, grant matching, and managed Git against local bare
repositories:

```sh
direnv exec . ./gradlew :app:testDebugUnitTest \
  --tests 'com.colonelpanic.eva.data.configuration.*' \
  --tests 'com.colonelpanic.eva.capability.extensions.ExtensionGrantsTest'
```

For a device check, use a dedicated debug installation and a test folder. Save
nondefault settings, verify the resulting `eva.yaml`, and restore that folder
into a fresh test installation. Check effective settings and extension identities,
then provision the reported missing dependencies and reload. Edit one setting
with an include active and verify that unrelated inherited settings still follow
the included file. Invalid YAML must preserve the previous working setup.
Do not clear a user's app data to create a fresh test state.

Robolectric checks exercise Android store integration and a real
`DocumentsProvider` through `SafConfigurationDirectory`: oversized reads, recovery
when only the backup remains, and failed final replacement preserving the old root.
These provider tests run at API 25 because the API 28 shadow does not support the
legacy provider query used by the fixture. They do not establish physical-device
rename atomicity or the behavior of every directory-sync tool.

Managed-Git JVM tests exercise initial snapshot and clone, validated fast-forward,
divergence without force, invalid-content rollback, offline checkout readability,
failed pushes retaining local commits, token redaction, and staging scope. Debug
assembly runs D8 with `desugar_jdk_libs_nio` for JGit's Java NIO surface on the
minimum-SDK build. These checks do not establish TLS/provider behavior or filesystem
semantics on a physical API-23 device.

For a managed-Git device check, use a dedicated private HTTPS repository and debug
installation. Enter a test author and device-local token, connect an empty branch,
verify the initial commit remotely, change a setting, and verify the automatic
commit/push. Advance the remote from another client and Sync a valid fast-forward;
then test divergent history and invalid YAML on a disposable branch, confirming
that active settings and the last good checkout remain unchanged. Disable managed
Git and confirm the previous SAF folder can still save/reload. Never put the test
token in the remote URL, YAML, screenshots, logs, or checked-in test arguments.

## Signing and releases

EVA production releases are signed with one long-lived Android signing identity.
Losing that key or its passwords prevents updates to existing installations;
replacing it creates a new, incompatible install lineage. Back up the keystore
and its metadata before the first public release, then keep the same key for the
APK and self-hosted F-Droid index.

### Versioning

Tags and release versions use `vMAJOR.MINOR.PATCH`. Android version codes use:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Each minor and patch component must be at most 999. Check a value with
`just version-code 0.1.0`. Every published version must sort above the previous
one.

### Local signed build

Enter the Nix shell, export the four signing inputs from a secure source, and
run the release recipe:

```sh
export ANDROID_KEYSTORE_FILE=/secure/path/eva-release.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=...
export ANDROID_KEY_PASSWORD=...
just release 0.1.0
```

The script runs formatting checks, release lint, JVM tests, the optimized APK
build, and `apksigner verify`. Its output is `dist/eva.apk`. It refuses to
build when any signing value is absent, and Gradle rejects partially configured
signing even outside the script.

### GitHub release

With the signing secrets and Pages configured as described below:

1. Update `CHANGELOG.md` and run `just fdroid-changelogs`.
2. Run `just check` and, ideally, a local signed build.
3. Commit the release state and create a `vMAJOR.MINOR.PATCH` tag.
4. Push the tag. `.github/workflows/release.yml` builds from the tag and uploads
   `eva.apk`; it fails if any signing secret is missing or invalid.
5. Confirm the APK certificate and install/upgrade behavior before promoting
   the release widely.
6. The successful release workflow triggers the Pages/F-Droid workflow.

Do not delete or regenerate the signing key during ordinary version bumps.

### Infrastructure prerequisites

Use the original production signing identity. Store the keystore and its metadata
in encrypted backed-up storage. Never regenerate an existing release key during
setup or a version bump. GitHub Actions requires:

- `ANDROID_KEYSTORE_BASE64`: the complete original keystore, base64-encoded;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`.

Configure GitHub Pages to deploy from Actions with the `github-pages` environment.
Creating repositories, publishing releases, changing Pages, and uploading secrets
are operator actions, not implicit build steps. Compare the release/index
certificate fingerprint with the recorded production identity before distribution.

## Self-hosted F-Droid

The configured repository address is
`https://colonelpanic8.github.io/eva/fdroid/repo`, with a landing page at
`https://colonelpanic8.github.io/eva/`. Configuration alone is not evidence that
a deployment is live. This is an independent binary repository, not inclusion
in F-Droid's official catalog.

### Publication model

`.github/workflows/fdroid-repo.yml` runs after a successful **Release** workflow.
It installs `fdroidserver`, downloads `eva.apk` from recent non-draft GitHub
releases, copies the APK bytes without re-signing, builds a signed repository
index, and deploys the generated site to Pages.

The collector verifies every APK signature and processes releases newest first.
If it encounters a different signing certificate, it stops at that boundary so
the index contains one coherent Android upgrade history. `FDROID_RELEASE_COUNT`
defaults to four; older builds remain GitHub release assets.

The same production keystore signs release APKs and the repository index. The
index keystore is decoded only into runner-temporary/build storage and removed
before the Pages artifact is assembled.

### Local repository build

Install `fdroidserver` into a temporary environment, enter EVA's Android shell,
and use a signed APK plus either a production or throwaway index key:

```sh
python3 -m venv /tmp/eva-fdroid-venv
/tmp/eva-fdroid-venv/bin/pip install fdroidserver
export PATH="/tmp/eva-fdroid-venv/bin:$PATH"

FDROID_APK_FILE=dist/eva.apk \
FDROID_KEYSTORE_FILE=/secure/path/eva-release.jks \
FDROID_KEY_ALIAS=eva \
FDROID_KEYSTORE_PASSWORD=... \
FDROID_KEY_PASSWORD=... \
nix develop .#android --command just fdroid-repo
```

Output is written to `target/fdroid`. A throwaway key is suitable only for a
local repository test; published index continuity requires the production key.

### Installation

After publication, add `https://colonelpanic8.github.io/eva/fdroid/repo` as an
additional package source in the F-Droid client, verify the displayed fingerprint,
refresh repositories, and install EVA. A direct release install uses:

```sh
adb install -r eva.apk
```

Because both channels serve the exact same signed APK, they can upgrade one
another. Debug builds use `com.colonelpanic.eva.debug` and are separate.

### Official catalog distinction

This machinery does not submit EVA to, or build EVA inside, F-Droid's official
catalog. Official inclusion is a separate future effort involving a source-build
recipe and policy/reproducibility review. The checked-in fastlane metadata is
reusable input, not evidence of acceptance.

## Device verification

Do not infer device support from Robolectric or synthetic audio. Record the build,
device/OS, exact operation, and observed outcome when testing. Keep reusable raw
evidence with the relevant experiment; update this compact summary rather than
adding a dated Markdown report for each run.

Recorded checks from 2026-09-14:

| Surface | Evidence | Limit |
| --- | --- | --- |
| Catalog Caffeine and Messages, Pixel 11 Pro Fold API 37, build `0f53e78` | Typed enable/disable produced handoff receipts and independent Caffeine notification changes; Messages opened the correct unsent draft; grants survived restart/replacement | Does not prove SMS sending, remote HTTP execution, or AIDL provider behavior |
| Extension browsing and file preview, subsequent combined build | Repository listings loaded, Caffeine matched, a Downloads JSON opened in preview | Preview is not proof of import persistence followed by execution |
| Direct subscription voice, Pixel | Synthetic speech, expected transcripts, decoded output audio; timer handoff and connection survival | Does not measure acoustic quality, echo, Bluetooth, or natural barge-in |
| Assistant role, Pixel API 37, signed `0.13.0` candidate | System assist event opened overlay, voice connected, panel/scrim behavior checked | Keyguard, activity handoff, and delegated recognition still need device verification |
| Shizuku device control, Android 16/API 36 emulator | Observation, Unicode replacement, tap, post-action observation, service restart | Does not establish Android 17 physical-device compatibility |
| Installed-extension transport, API 36 `google_apis` emulator, 2026-09-23, EVA `locked-extension-eva` debug re-signed with Mova's debug key, Mova `locked-extension-mova` debug | From EVA's UID, `InstalledExtensionDeviceTest` described Mova and executed `find_todos` and `create_todo` (keyguard showing, PIN set, no Mova process), and did the same with Mova force-stopped and never launched. Each bind took about 0.5 s; replies decoded as `not_executed/not_configured` because Mova was not logged in | No server write, no EVA voice or assistant session; before-first-unlock was not rebooted into |
| Phone calls, API 36 `google_apis` emulator, 2026-09-23, EVA `phone-calls` debug | `PhoneCallDeviceTest` from EVA's process with the keyguard showing: `placeCall` produced Telecom call `TC@1` (`CONNECTING`, outgoing, non-emergency) on the SIM phone account; the test call was then ended | Emulator modem; no physical device, real network, or voice session |
| Mova 7.2.1 release candidate, API 35 emulator (Mova agent), org-agenda-api container at production's revision | Keyguard showing and Mova force-stopped before each call: from EVA's UID, a cold describe took about 560 ms, and find, create, update, complete, and delete completed and were confirmed in the org file. Offline create returned `not_sent`; shell callers were refused | Emulator and test server; no physical device |
| Same emulator, Paseo `locked-extension-paseo` release-variant APK signed with the shared React Native debug key, a throwaway daemon with the mock provider | Keyguard showing, Paseo killed or force-stopped: `create_agent` (local and worktree) and `send_prompt` returned `completed` in 0.9–2.0 s including headless React Native cold start. The daemon recorded one agent per marker with the marker as its first user message. With Paseo's toggle off, `needs_authorization`. With the host offline, `handed_off`/`waiting_for_host` after 23.5 s; one `request_status` after restart returned `completed`. Unknown IDs return `unknown_request`. Cold, force-stopped `/messages` reads from EVA's process returned rows in about 1.5 s. A production-configured Paseo refused EVA debug (`unauthorized_caller`, provider `SecurityException`) | Mock provider, emulator, instrumented caller; no physical device or real model provider |

A repeatable extension check starts with an installed target app and enabled EVA
extension/action grants. Invoke each operation separately, inspect its attributed
receipt, and independently inspect the target state. Verify missing-handler and
revoked-grant behavior without inventing success. For Messages compose, leave the
draft unsent. HTTP fixtures need a compatible configured server.

Installed-service providers are checked from EVA's own UID with
`InstalledExtensionDeviceTest`. A debug provider accepts EVA debug only when both
share a signer, so re-sign EVA's debug and test APKs with the provider's debug
key when they differ. Use a disposable emulator or device: Mova's debug and
release builds share one application ID. The test prints the lock state, bind
time, status, reason, and receipt `state`. It runs writes only with
`evaExtensionAllowWrite=true`. Quote the JSON arguments inside the device shell
string:

```sh
adb -s "$EVA_TEST_DEVICE" shell locksettings set-pin 1234
adb -s "$EVA_TEST_DEVICE" shell input keyevent KEYCODE_SLEEP
adb -s "$EVA_TEST_DEVICE" shell am kill com.colonelpanic.mova
adb -s "$EVA_TEST_DEVICE" shell "am instrument -w \
  -e class com.colonelpanic.eva.capability.extensions.InstalledExtensionDeviceTest \
  -e evaExtensionPackage com.colonelpanic.mova -e evaExtensionCapability find_todos \
  -e evaExtensionArguments '{\"q\":\"test\"}' \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner"
adb -s "$EVA_TEST_DEVICE" logcat -d -s EvaExtensionDevice:I
```

### Device-control parity

The backend acceptance test is
`com.colonelpanic.eva.devicecontrol.PortalBackendDeviceTest`. It runs from EVA's
Android process against unmodified Portal 0.7.25 on the same emulator at
`127.0.0.1:8080`; no adb-forward transport is used by the Kotlin backend.
`PortalFixtureActivity` exists only in the instrumentation APK and provides
repeatable controls, editable/password fields, scroll content and a URL handler.
The test is opt-in and skips non-emulators. Check for assumption skips as well as
failures; an `OK` instrumentation summary alone is insufficient.

Use only the dedicated AVD `eva-device-slice1`, port **5592**. Do not use
emulator-5554/5580/5586 or a physical phone. The prototype's `scripts/emulator.sh`
can create/start it with `--name eva-device-slice1 --port 5592` inside that
repository's Android Nix shell; set `VDA_LOCAL_DIR` to a separate directory outside
the prototype. Copy its pinned Portal APK into that directory's `portal/`, then
run `scripts/portal.sh setup emulator-5592` with the same environment. Neither
repository needs to be modified for setup.

```sh
direnv exec . ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5592 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5592 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# EVA_PORTAL_TOKEN_FILE is the path printed by Portal setup, never the token itself.
adb -s emulator-5592 push "$EVA_PORTAL_TOKEN_FILE" /data/local/tmp/eva-portal-token
adb -s emulator-5592 shell am instrument -w -r \
  -e class com.colonelpanic.eva.devicecontrol.PortalBackendDeviceTest \
  -e evaPortalParity true \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5592 shell rm /data/local/tmp/eva-portal-token
```

The token is temporary test provisioning. It is never an instrumentation argument
or portable configuration value. Production token provisioning and backend
selection must be added to EVA's configuration/credential-reference model when
the device-task capability is wired.

The dedicated AVD has passed this backend test with all 13 required action kinds
(27.414 seconds, zero failures or skips; optional lock excluded). Median standalone
observation time was 26 ms across 34 reads. Selected action measurements below are
milliseconds; repeated actions use medians. There was no model call in this test.

| Action | Target recheck | HTTP | Settle |
| --- | ---: | ---: | ---: |
| launch_app | 0 | 29 | 2586 |
| activate_element | 36 | 12 | 831 |
| set_text | 36 | 144 | 1056 |
| scroll | 19 | 13 | 1563 |
| screenshot | 0 | 433 | 0 |
| open_url | 0 | 15 | 1173 |

An initial fixture failure exposed Android's `setSingleLine` resetting password
input flags; the fixture now sets password type last, and backend tests cover a
field becoming password-marked after focus. A later fixture failure assumed Back
only dismissed a keyboard; Portal's IME was already hidden, so Back correctly
exited the Activity. The test relaunches before scrolling. Neither failure was a
task-eval result; the four worker evals below have not run through EVA yet.

Acceptance for the complete slice:

- Protocol: every copied `protocol/v1/examples/*.json` round-trips; the compact
  observation table matches the Python renderer (without its policy instruction).
- Backend: launch_app, activate_element, set_text (replace and append, Unicode,
  verified read-back and password redaction), scroll, back, home, tap_point,
  swipe, long_press, screenshot, ime_action, open_url and open_notifications
  execute against same-phone Portal. Lock is optional and excluded from the
  instrumentation test. Retain separate recheck/HTTP/settle timings.
- Worker: ask-first ambiguity, one action per turn, scroll progress and reversal
  rules, loop/refusal limits, bounded append-mode context/cache prefix, and
  revision/cancellation get scripted-model JVM tests. No approval, risk,
  evidence-validation or injection subsystem is part of this slice.
- Integration: one capability waits for a terminal receipt; progress and
  corrections retain the owning thread/turn. Stop reaches inference and input
  immediately; issued input settles before the device lease is released. Existing
  UI tools and launches cannot interleave. Assistant-panel Stop stops work.
  Backend/model tuning and token references survive configuration composition and
  restore. No capability integration is implemented by the backend test alone.
- End-to-end: run `settings.wifi_scanning_off.baseline`,
  `settings.ble_scanning_off.deep`, `chrome_read.closing_time.baseline`, and
  `chrome_read.pool_hours.scrolling` through EVA's device task. Serve the
  prototype's `evals/fixtures/web/` with `python -m http.server` bound to
  `0.0.0.0` on a fresh random high port; the emulator uses `10.0.2.2:<port>`.
  Compare any failing case with `vda eval run ... --driver worker` on the same
  emulator, with the same initial state. Record observation/model/action timing
  separately. These task evals remain pending until worker/capability wiring.
- Shizuku: adapt existing observe/tap/set_text to `DeviceBackend`; every other
  unsupported action must return the protocol's `unsupported`, never succeed as
  a no-op. Its adapter remains separate from Portal parity acceptance.

### Messaging setup and verification

Ivan reporteda verified real SMS send on 2026-09-14. That verifies direct
sending; it is distinct from the Messages JSON extension's previously verified
unsent draft. Group MMS and the new notification reply path do not inherit that
verification. Notification replies have focused JVM/Robolectric tests but have
not yet been verified against WhatsApp, Telegram, or another real messaging app.

Open **Messaging** from the navigation drawer. **Phone permissions** shows
contacts, SMS history, and SMS-send access, including permissions denied after
setup. **Contact lookup** controls the retry budget for resolving spoken contact
names. **Remembered numbers → Forget** clears saved number preferences; this
change also updates a linked `eva.yaml`.

To use another app's notification replies:

1. Enable **Read messaging notifications**. This explicitly permits EVA to use
   message excerpts with the configured model; media notification access alone
   does not opt into message collection.
2. Grant Android notification access using **Change**.
3. Receive a messaging notification, leave it active, and select **Refresh
   messaging apps** (returning to EVA also refreshes).
4. Enable **Allow replies** for the app you intend to use.
5. In a typed conversation, ask “What recent WhatsApp conversations can you see?”
   or “Show recent conversations from my messaging apps.”
6. Identify the intended conversation, then ask for a reply with exact text.
   Check the destination app to verify the result.

The user must authorize the particular real message sent during a device test.
Automated tests use fake reply callbacks and construct Android intents without
sending messages.

See [Architecture](architecture.md#messaging) for tool parameters, grant identity,
receipt semantics, and notification lifetime limits.

### Native voice tests

`NativeVoiceLiveTest` replaces microphone buffers with synthetic speech/silence
before transmission. It requires explicit instrumentation arguments, verifies an
arithmetic transcript and nonzero decoded output PCM, and uses no tools. The
injection hook is not shipped in production. Tests that invoke a timer have real
side effects and should run on a dedicated test device.

### Direct subscription voice on a signed-in phone

Sign in to ChatGPT in the debug app, build and install the debug application and
test APK, and push the synthetic arithmetic fixture as shown below. Run the
same native test with `evaSubscriptionVoice` instead of `evaBrokerLink`:

```sh
direnv exec . adb -s "$EVA_TEST_DEVICE" shell am instrument -w \
  -e class com.colonelpanic.eva.providers.NativeVoiceLiveTest \
  -e evaSubscriptionVoice true \
  -e evaSpeechPcmPath /data/local/tmp/eva-native-speech.pcm \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner
```

This uses the phone's encrypted account store and production Realtime provider;
no broker, API key, or exported subscription token is needed. Do not supply both
route arguments. On 2026-09-14 this passed on a Pixel 11 Pro Fold: 467,332
synthetic input bytes, the expected question and arithmetic answer, and 30,969
nonzero decoded output bytes. The direct subscription action test also passed:
timer handoff, spoken confirmation, and a connection that survived Clock taking
the foreground. See the [evidence record](../experiments/voice-poc/evidence/2026-09-14-direct-subscription-voice.json).
It verifies synthesized speech, not acoustic quality.

### Run on a dedicated emulator

Start a fresh broker using the [voice harness instructions](../experiments/voice-poc/README.md),
or use an idle broker owned by the current test session. It must have an existing host ChatGPT
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


### Voice action test

`NativeVoiceActionLiveTest` drives the production controller with synthetic
speech that asks for a timer, and asserts the delegated tool call, the Clock
handoff, the journaled utterance, the spoken confirmation, and that the session
is still connected five seconds after Clock takes the foreground.

```sh
espeak -w /tmp/q.wav -s 140 "Set a timer for three minutes."
ffmpeg -y -i /tmp/q.wav -af 'adelay=800:all=1,apad=pad_dur=1.5' -ar 48000 -ac 1 -f s16le /tmp/eva-voice-timer.pcm
adb -s DEVICE push /tmp/eva-voice-timer.pcm /data/local/tmp/eva-voice-timer.pcm
adb -s DEVICE shell am instrument -w \
  -e class com.colonelpanic.eva.providers.NativeVoiceActionLiveTest \
  -e evaSpeechPcmPath /data/local/tmp/eva-voice-timer.pcm \
  -e evaBrokerLink "$EVA_BROKER_LINK" \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The test waits for journal recovery before connecting; `connectVoice` is a
no-op while history is loading, which is why the UI disables the button then.

### Direct OpenAI voice action test

`OpenAiVoiceActionLiveTest` is the workstation-free variant: no broker, the
phone opens its own Realtime session. Use `-e evaSubscriptionVoice true` with a
saved ChatGPT sign-in, or supply an API key as an instrumentation argument.
The fixture asks Clock to start a real three-minute timer.

```sh
adb -s DEVICE shell am instrument -w \
  -e class com.colonelpanic.eva.providers.OpenAiVoiceActionLiveTest \
  -e evaSpeechPcmPath /data/local/tmp/eva-voice-timer.pcm \
  -e evaOpenAiKey "$OPENAI_API_KEY" \
  com.colonelpanic.eva.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## JVM device-control host

Build in the Android dev shell (the host itself targets JVM 17):

```sh
direnv exec . just device-host
# Equivalent: direnv exec . ./gradlew :device-control-host:installDist
# Commands below also run inside direnv exec .
device-control-host/build/install/eva-device/bin/eva-device observe --serial emulator-5594
device-control-host/build/install/eva-device/bin/eva-device act --serial emulator-5594 '{"kind":"home"}'
device-control-host/build/install/eva-device/bin/eva-device eval run \
  --serial emulator-5594 --agent noop --families settings,chrome_read \
  --cases /path/to/voice-device-agent/evals/cases \
  --fixture-base-url http://10.0.2.2:<fixture-port>
```

Portal must already be installed, enabled, and listening on device TCP 8080.
Each command reads its token from the content provider without printing or saving
it, creates `adb -s SERIAL forward tcp:0 tcp:8080`, and removes only its own forward
on completion. Both an emulator serial and a QEMU property are required unless
`--allow-physical SERIAL` exactly matches the selected serial. Always use an AVD
you own; never aim a test at another session's emulator.

`act` accepts protocol v1 JSON. It observes immediately before dispatch and fills
omitted `action_id`, `task_id`, `task_revision`, and `bound_observation_id` fields.
Explicit fields are preserved, including stale observation IDs (which are rejected).
Element indices in short action templates refer to that command's fresh screen;
IDs from a previous CLI process cannot bind to the new backend session. For
multi-step automation use a `TaskAgent` with its own backend instance. Actions are
not retried after failures or uncertain results.

Eval options include `--case ID`, comma-separated `--families`, and `--state-dir`
(default `.device-control/`, gitignored). Each invocation writes a unique
`runs/*.jsonl`; per-device `snapshots/*.json` retain original settings/volume across
interruption. Startup restores pending snapshots before a new batch. Teardown
attempts every step, then any pending restoration; unresolved snapshots stop the
batch. Exit codes are 0 for passing/excluded cases, 1 for failed evals/actions,
and 2 for command/configuration errors. A noop batch is expected to exit 1.
Reset errors and checker errors are distinct from failed checker verdicts.
Cases marked `not_runnable_on_emulator` are recorded without reset or task work.

Use the lab's device preparation before evaluating Chrome/media: finish Chrome's
first-run and notification prompts, serve `evals/fixtures/web/` on a fresh high
port bound to `0.0.0.0`, and use `10.0.2.2` from the emulator. The lab's emulator
Chrome flags (`--disable-fre --no-default-browser-check --no-first-run
--no-restore-state` in `/data/local/tmp/chrome-command-line`, preceded by `_`)
ensure one tab after force-stop. Media cases require the synthetic tracks and a
prepared player queue; loading their YAMLs does not provision that queue.

For a deterministic harness run, supply `--agent scripted --script /path/script.json`.
The JSON maps case IDs to protocol action templates and an optional final answer:

```json
{
  "chrome_read.closing_time.baseline": {
    "actions": [],
    "answer": "The library now closes at 9 PM on weekdays."
  }
}
```

This supplied answer tests checker plumbing; it is not evidence of model reasoning.
Script actions go through Portal and stop on the first failed/unknown result.

To enable `--agent worker`, add a JVM adapter implementing
`com.colonelpanic.eva.devicecontrol.host.WorkerAgentFactory`, register its class
in `META-INF/services/com.colonelpanic.eva.devicecontrol.host.WorkerAgentFactory`,
and include its artifact on the host runtime classpath. `create(backend, case)`
must construct slice 1's shared `TaskAgent`, inject the extracted JVM model
transport and runtime credential source, apply `maxSteps`/`timeoutMillis`, and
handle the case's followups. Neither that core factory nor a JVM OpenAI client
exists at host baseline `ed9b6e6`; `--agent worker` fails before connecting or
resetting a device until a real provider is installed. No model calls or token
refresh logic are duplicated in the host.

Unit coverage includes all twelve lab YAMLs, reset mapping with a fake ADB,
restoration after failure, independent checkers, recorded Portal observations,
media dumpsys parsing, physical-device refusal, and no retry of uncertain actions.
The opt-in device test is restricted to the dedicated host emulator:

```sh
EVA_HOST_TEST_SERIAL=emulator-5594 direnv exec . ./gradlew \
  :device-control-host:test --tests '*DeviceSmokeTest'
```

Host verification on 2026-09-28 used a new `vda-tablet-host` AVD on
`emulator-5594` (API 37, Portal 0.7.25). Compact observation, a settled Home action,
and the opt-in JVM device test passed. After Chrome first-run preparation, all
four settings and all four Chrome cases with `--agent noop` produced the expected
failed checker verdicts, with no reset/teardown errors and no pending snapshots.
The Chrome closing-time case with one scripted URL action and a supplied answer
passed. These establish host/reset/checker plumbing, not worker parity. Media
helpers/checkers were JVM-tested; media tasks were not run on this AVD. The
emulator was stopped after verification.
