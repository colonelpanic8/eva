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

## Collecting diagnostics

A production build is not debuggable, so its journal cannot be pulled over adb.
Collect evidence from the phone itself:

- **One conversation**: open it and choose ⋮ → **Share diagnostics**, or use
  **Share diagnostics** on its row in Running work, or Settings → Diagnostics →
  **Share diagnostics for current thread**. The share sheet receives one JSON file
  (`eva-thread-<id>-<time>.json`) and a short summary as text. Save it to Drive or
  send it to yourself; attach it to the bug report.
- **Recent logs only**: Settings → Diagnostics → **Export recent logs** shares the
  trace ring without any conversation content.
- **Live Logcat**: every trace event is also logged at INFO under one tag, without
  message text, arguments, or credentials:

  ```sh
  adb -s "$EVA_TEST_DEVICE" logcat -s EvaTrace
  ```

Turn on Settings → Diagnostics → **Verbose logging** before reproducing a speech
problem: it adds speech start, transcript and reply lengths, and assistant-audio
events. It is part of the portable configuration (`diagnostics.verboseLogging`) and
defaults to off. The export's `summary`, `bounds`, and `redaction` fields say what was
included, what a bound omitted, and what was redacted; see
[diagnostics](architecture.md#diagnostics).

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
| Shizuku device-task backend, dedicated API 37 `sdk_gphone64_x86_64` emulator (port 5612), Shizuku 13.6.0 started over adb, 2026-09-28 | `everyRequiredActionRunsThroughShizuku` passed all 13 required action kinds against `PortalFixtureActivity`; access was granted through the new Screen control button | Emulator only; no model-driven task, physical phone or Wireless-debugging start |
| Shizuku helper idle release, Pixel 11 Pro Fold API 37, Shizuku 13.6.0, EVA debug, 2026-10-01 | `helperReadsTheScreenAgainAfterItsIdleRelease` read the screen, waited past the 20 s idle release and read it again. Before the fix the second read failed: the idle disconnect ran as shell while the connection belonged to EVA's UID, so its UiAutomation stayed registered and every later connection was refused | Screen reads only; no model-driven task or input on the phone |
| Screen control status and backend fallback, Pixel 11 Pro Fold API 37, signed 0.48.2 candidate, Portal 0.7.25, Shizuku 13.6.0, 2026-10-01 | The chip showed Portal and Shizuku ready. After Portal's accessibility service was turned off, it flagged Portal within one refresh and opened Screen control with the reason. With only `[portal]` usable, a task returned `Not run` with that reason. With `[portal, shizuku]` and Portal off, a typed "open the Clock app" task finished through the Shizuku helper. No phase label stayed after tasks ended | Fallback after the Portal probe passes but the first read fails is covered only by JVM tests |
| Screen control repair, Pixel 11 Pro Fold API 37, signed 0.59.0 candidate, Portal 0.7.25, Shizuku 13.6.0, 2026-10-05 | With Portal's accessibility service removed from settings, one tap on Portal re-enabled it through the Shizuku helper and reported `Fixed` after a screen read. After the Shizuku server was restarted with EVA in front, the chip said Shizuku was not connected, and the server re-sent its binder only after EVA came back to the front. Tapping Shizuku opened Shizuku, and Back reconnected it. A typed "Open the Clock app" task then opened Clock | The no-Shizuku path, which opens Accessibility settings, and the helper restart for a connected but failing helper are covered only by JVM tests |
| Portal restore after force stop, Pixel 11 Pro Fold API 37, signed 0.59.1 candidate, Portal 0.7.25, Shizuku 13.6.0, 2026-10-05 | `dumpsys activity exit-info` showed Play Store force-stopping sideloaded Portal around 23:29 every few days. `am force-stop com.mobilerun.portal` cleared `enabled_accessibility_services` the same way. With the service cleared, opening EVA restored it within about 2 s, with no tap. A later force stop while EVA was in front was undone within 10 s | Restore at device-task admission while EVA is in the background is covered only by the shared code path and JVM tests |
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

### Background work and task manager

These checks require an explicitly selected device; JVM/Robolectric coverage is
not evidence of Android foreground eligibility or OEM background behavior.

- Start a delegated research/device task while the assistant window is visible.
  End the call, hide the assistant, and let it work for more than three minutes.
  Verify findings, action receipts, and the final result survive. Repeat from the
  Activity and with several concurrent tasks across threads.
- Have a browser take audio focus during work. Verify voice releases audio while
  task execution and its notification continue, with no foreground-service gap.
- Repeat with assistant shown/hidden and with the phone locked. Verify operations
  that can run locked complete; UI/device actions that need unlock report that
  limitation without claiming success or replaying an uncertain action.
- Check Running work from the drawer, Settings → Background work (including the
  active count), and the notification, including tapping while EVA is already
  open. Open thread must show the owning conversation.
- Change the stall period in Settings and round-trip the portable configuration.
  Leave a test provider/device task without progress: **looks stuck** must appear
  in the list and notification without stopping the task, then clear on progress.
- Stop a task, Stop all from both surfaces, and Force stop a device task while an
  action is in flight. Verify the force-stop notice and `INTERRUPTED` status, lease
  retention during input unwinding (Releasing device…), truthful final receipts,
  and no duplicate action. Queue a successor and verify it cannot inject input
  until the first backend returns or ten seconds elapse. After expiry it must
  observe the uncertain screen afresh. Simulate a stuck backend: receipt waiting
  ends within ten seconds, records UNKNOWN and stops coverage; a second Force stop
  skips that wait. A late backend result must not unlock a successor's lease.
  Force-stopping an already answered turn must preserve ANSWERED.
- Exercise Android rejecting specialUse promotion in a controlled test build:
  shortService fallback must visibly state its three-minute limitation. Refusing
  both types must interrupt only uncovered work and retain partial findings.
  Let fallback expire while EVA is hidden: it must stop promptly if specialUse is
  still refused, without attempting shortService renewal. Open EVA during fallback
  and verify upgrade to specialUse. Repeat with notification permission denied.
- Start a voice session visibly, lock the phone, then speak several utterances.
  Work coverage must remain through pauses without a service start per utterance;
  voice-covered turns must not receive restriction notices or repeated alerts.
  End the call during delegation and verify continuing work remains covered.
- While a device task needs input or waits for the lease, exceed the stall period:
  neither must show looks stuck. Rotate/recreate EVA after opening a thread from
  Running work: it must not jump back to Running work.
- Check that the work notification disappears after the voice session ends and
  the last task and its journal writes finish, and that restarting after process death reports interruption
  without replaying any action.

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
or portable configuration value. Production backend preference is the ordered `capabilities.deviceTask.backends` list;
Portal tokens are provisioned in Screen control settings and kept in SecretStore.
Portable configuration contains only the `device/portal` credential reference,
local port and model/worker tuning. Restore reports missing local credentials.

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

The fixture sets password type after `setSingleLine`, which otherwise resets
password flags. Back may exit the Activity when Portal's IME is already hidden;
the test relaunches before scrolling.

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
  restore. The separate `DeviceTaskEvalTest` exercises native task admission and terminal journaling.
- End-to-end: run `settings.wifi_scanning_off.baseline`,
  `settings.ble_scanning_off.deep`, `chrome_read.closing_time.baseline`, and
  `chrome_read.pool_hours.scrolling` through EVA's device task. Serve the
  prototype's `evals/fixtures/web/` with `python -m http.server` bound to
  `0.0.0.0` on a fresh random high port; the emulator uses `10.0.2.2:<port>`.
  Compare any failing case with `vda eval run ... --driver worker` on the same
  emulator, with the same initial state. Record observation/model/action timing
  separately. These task evals use the admitted EVA task capability on emulator-5592; see results below.
- Shizuku: the same backend test, run as `everyRequiredActionRunsThroughShizuku` with
  `-e evaShizukuParity true` and EVA already allowed in Shizuku (Screen control
  settings, **Allow Shizuku access**). No Portal token is needed.

Task eval results on emulator-5592 (Android 17/API 37, `sdk_gphone64_x86_64`),
unmodified Portal 0.7.25, `gpt-6-sol`, low
reasoning effort, append context (six screens): all four cases passed. Settings
checks independently read `0`; answer checks matched 9 p.m. and 4:30 p.m.
Every run stayed within its original case step/time limits. No failed case
required a Python-prototype comparison.

| Case | Steps | Wall seconds | Observation ms | Model ms | Action ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| settings.wifi_scanning_off.baseline | 23 | 124.364 | 2330 | 75143 | 46463 |
| settings.ble_scanning_off.deep | 14 | 61.184 | 54 | 39802 | 20994 |
| chrome_read.closing_time.baseline | 4 | 10.415 | 51 | 8661 | 1416 |
| chrome_read.pool_hours.scrolling | 6 | 31.291 | 146 | 23547 | 7327 |

Observation is standalone capture (including explicit `observe` turns); action
includes target recheck, HTTP and post-action settling/capture. Model is inference
wall time. Table sums exclude setup and bookkeeping overhead. Zero observation
time means that turn reused the previous action’s returned screen.


`settings.wifi_scanning_off.baseline`

| Step | Kind | Result | Observation ms | Model ms | Action ms |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | launch_app | ok | 96 | 3962 | 4163 |
| 2 | activate_element | ok | 0 | 2020 | 5289 |
| 3 | set_text | ok | 0 | 2035 | 1852 |
| 4 | observe | ok | 779 | 2315 | 0 |
| 5 | ime_action | ok | 0 | 12645 | 2095 |
| 6 | back | ok | 0 | 2146 | 3128 |
| 7 | activate_element | ok | 0 | 3535 | 4482 |
| 8 | observe | ok | 76 | 5828 | 0 |
| 9 | activate_element | ok | 0 | 2964 | 5052 |
| 10 | observe | ok | 1379 | 1882 | 0 |
| 11 | activate_element | ok | 0 | 4497 | 3251 |
| 12 | back | ok | 0 | 2556 | 1934 |
| 13 | activate_element | ok | 0 | 2026 | 2058 |
| 14 | back | ok | 0 | 2709 | 1693 |
| 15 | back | ok | 0 | 1527 | 2215 |
| 16 | back | ok | 0 | 2184 | 1226 |
| 17 | scroll | ok | 0 | 2371 | 1475 |
| 18 | scroll | ok | 0 | 5337 | 1407 |
| 19 | activate_element | ok | 0 | 2004 | 1585 |
| 20 | activate_element | ok | 0 | 4513 | 1508 |
| 21 | activate_element | ok | 0 | 1909 | 1432 |
| 22 | activate_element | ok | 0 | 2088 | 618 |
| 23 | finish | completed | 0 | 2090 | 0 |

`settings.ble_scanning_off.deep`

| Step | Kind | Result | Observation ms | Model ms | Action ms |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | back | ok | 54 | 2406 | 1636 |
| 2 | launch_app | ok | 0 | 6508 | 1428 |
| 3 | activate_element | ok | 0 | 2358 | 1650 |
| 4 | activate_element | ok | 0 | 3025 | 1478 |
| 5 | activate_element | ok | 0 | 2074 | 1434 |
| 6 | launch_app | ok | 0 | 1687 | 448 |
| 7 | back | ok | 0 | 2512 | 957 |
| 8 | back | ok | 0 | 2147 | 1071 |
| 9 | back | ok | 0 | 1680 | 1405 |
| 10 | activate_element | ok | 0 | 2085 | 1954 |
| 11 | set_text | ok | 0 | 2480 | 2648 |
| 12 | activate_element | ok | 0 | 5387 | 1270 |
| 13 | activate_element | ok | 0 | 2766 | 3615 |
| 14 | finish | completed | 0 | 2687 | 0 |

`chrome_read.closing_time.baseline`

| Step | Kind | Result | Observation ms | Model ms | Action ms |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | activate_element | ok | 51 | 2228 | 973 |
| 2 | activate_element | NotActionable | 0 | 1693 | 19 |
| 3 | screenshot | ok | 0 | 2075 | 424 |
| 4 | finish | completed | 0 | 2665 | 0 |

`chrome_read.pool_hours.scrolling`

| Step | Kind | Result | Observation ms | Model ms | Action ms |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | scroll | ok | 146 | 3674 | 1665 |
| 2 | scroll | ok | 0 | 10036 | 1468 |
| 3 | scroll | ok | 0 | 2399 | 1597 |
| 4 | scroll | ok | 0 | 2343 | 1513 |
| 5 | scroll | ok | 0 | 2110 | 1084 |
| 6 | finish | completed | 0 | 2985 | 0 |


Interruption probes also passed on emulator-5592. Inference stop latched in
367 µs and returned NOT_EXECUTED with zero effects (0.465 s total test wall time).
A stop 100 ms into the launch action latched in 343 µs; the lease remained held
until the issued launch drained. The terminal receipt was FAILED/CANCELLED with
one known partial effect, not a false “nothing happened.” That step measured
121 ms observation, 2456 ms model, and 2655 ms action including settling; total
test wall time was 5.587 s. These are single-run measurements, not latency bounds.
The local JVM HTTP/SSE cancellation test independently verifies `Call.cancel()`
while the response body remains open.

Task configuration is a nested portable value, for example:

```yaml
capabilities:
  deviceTask:
    backends: [portal, shizuku]
    portalPort: 8080
    credential: device/portal
    model: gpt-6-sol
    reasoningEffort: low
    maxSteps: 30
    maxMillis: 300000
    modelTimeoutMillis: 120000
    maxScreens: 6
    historyLines: 30
    maxRefusals: 4
    maxScreenshots: 3
```

The live task test is opt-in (`evaDeviceEval=true`) and emulator-only. It takes
`goal`, `case`, optional `answerContains` and `interruptAt` (THINKING or ACTING),
and `credentialPort`. The latter is an ephemeral emulator loopback port reversed
to a host test credential endpoint; credentials are supplied at runtime and saved
only to EVA's encrypted store. Do not put tokens in instrumentation arguments,
logs or portable files. Wait for configuration/catalog readiness before admission.
The test executes the application-owned coordinator through `CapabilityDispatcher`
and SQLite journal, without a second outer conversational model. It verifies the
worker/capability path; it is not a text-UI or acoustic voice test. Controller
correlation, correction, stop and provider-cancelled races have focused JVM tests.

Catalog mirror required before publishing this feature: copy the shipped
`eva-core/src/main/resources/eva-wording.yaml` byte-for-byte to
`colonelpanic8/eva-instructions/eva-wording.yaml`. Added tool keys are
`eva.device.task` and `device-worker.{observe,launch_app,activate_element,set_text,
scroll,back,home,tap_point,swipe,long_press,screenshot,ime_action,open_url,
open_notifications,ask_user,finish}`. Added message keys are
`device-worker.{system,task,screen,one_call,invalid_call,scroll_reversal,scroll_end,
scrolled,screenshot_limit,result,text_result}`. No other catalog files changed;
no external catalog repository was modified or pushed in this worktree.

After this slice, extract the OpenAI client for the JVM host runner and eval CLI,
then add a native accessibility backend with the same action/eval checks. Compare
a vision-first `TaskAgent` against the text worker using identical initial states,
budgets and timing capture; no vision-first implementation is included here.

### Worker efficiency and model-input comparison

Completion-only acceptance above did not establish worker efficiency parity. The
same-device audit used emulator-5592, Portal 0.7.25 and prototype `bb45f12`, with
`gpt-6-sol`, low effort and append context capped at six screens. All device work
was serial. Prototype runs used its per-serial lease; EVA ran its admitted task
capability through the production agent factory and journal. No other serial was
used. Prototype source was unchanged; an external Python import hook recorded
requests without changing their contents.

`vda eval prepare --serial emulator-5592` completed. This prototype CLI accepts
`--filter id:settings.wifi_scanning_off.baseline`, not `id=...`; the rejected
filter attempt ran no worker. Batches were `parity-wifi`, `parity-wifi-traced`,
`parity-ble`, `parity-closing`, and `parity-pool`.

Each table cell is **steps / wall seconds / model ms / action ms**. Python wall
time is its task timer; EVA wall time is instrumentation wall time, including
local setup (the same convention as the historical measurements). Action time
includes HTTP, target recheck and settling/post-capture; explicit observe work is
excluded. Python's initial observation is outside its per-step device timer.
The linked timing artifact retains EVA standalone observation times separately.

| Case | Python same device | EVA historical | EVA after 1 | EVA after 2 |
| --- | --- | --- | --- | --- |
| settings.wifi_scanning_off.baseline | 5 / 18.781 / 13023 / 5700 | 23 / 124.364 / 75143 / 46463 | 4 / 16.115 / 10424 / 4781 | 4 / 18.713 / 13530 / 4884 |
| settings.ble_scanning_off.deep | 7 / 21.764 / 14000 / 7732 | 14 / 61.184 / 39802 / 20994 | 6 / 20.044 / 13558 / 6104 | 5 / 18.096 / 12286 / 5514 |
| chrome_read.closing_time.baseline | 2 / 5.271 / 4973 / 273 | 4 / 10.415 / 8661 / 1416 | 2 / 8.843 / 7805 / 748 | 2 / 7.752 / 7180 / 324 |
| chrome_read.pool_hours.scrolling | 7 / 22.928 / 15403 / 7471 | 6 / 31.291 / 23547 / 7327 | 6 / 24.139 / 16482 / 7297 | 7 / 26.659 / 17364 / 8894 |

Settings values were independently checked at `0`; Chrome answers were 9 p.m.
and 4:30 p.m. All eight after-runs returned the expected result. The last pool run
used five scrolls, screenshot, finish. The first pool run read the answer in a
full-paragraph scroll preview, so its six steps are not comparable to Python's
bounded preview behavior. The final preview bound and read-back/screenshot notice
alignment were verified with JVM tests after the 16-task budget was exhausted;
there was no ninth after-run on those final feedback changes.

There were exactly **16 live worker tasks**: five Python baselines (including a
second Wi-Fi run for tracing), two fresh EVA-before Settings runs, one failed
WebSocket integration trial, and eight after-runs. The failed trial delivered no
action and ended after two no-call turns: the adapter initially read only
`response.completed.output`, but this endpoint emitted calls in
`response.output_item.done`. The collector now handles those items, with a
regression test. The matching Python Wi-Fi baseline passed; the difference was
transport decoding, not navigation or a device/backend refusal.

The historical Settings slowdown was not reproduced consistently before fixing
code. Fresh EVA-before was Wi-Fi **4 / 14.668 / 9703 / 4443** and
Bluetooth **5 / 17.494 / 12041 / 5149** (same tuple units). Python's traced
Wi-Fi repeat was **4 / 21.290 / 16808 / 4455**. Force-stopping Settings
and its search app preserves recent search controls; later runs exposed scanning
switches directly in search. This state and model variation confound a causal
claim that the code changes alone removed 19 steps. Step counts now compare
favorably with these same-device baselines, but wall-time parity is not established
for every case: both Chrome cases remained slower, predominantly in inference.
Host load was not controlled; Gradle checks overlapped some after-runs, so these
wall-time samples are not an isolated latency benchmark.

#### Model-visible differences and fixes

The [compressed request archive](../experiments/device-control/evidence/worker-model-parity.json.gz)
contains the first three actual requests of each Settings case for Python,
EVA-before, and both after rounds, including instructions, complete tool schemas,
rendered tables and preceding call/result messages. It also contains unified row
diffs. Read it with `gzip -dc .../worker-model-parity.json.gz | jq .requests`.
[Per-step timing data](../experiments/device-control/evidence/worker-parity-timings.json)
includes every run and the failed trial. These artifacts contain synthetic emulator
screen data, not authorization headers or account credentials.

- **Calls/results:** Python `worker/loop.py:713-819` sends native tool calls and
  correlated results, then the fresh table as a user message in the same next
  request. It does not place the full table inside the tool-result string. EVA
  already supplied that fresh table, but encoded calls as assistant prose and
  status as a screen prefix. It now preserves native call IDs, result items and
  provider output (including encrypted reasoning), and retains the last exchange
  when rebuilding bounded context. Extra calls receive explicit non-execution
  results. Non-tool replies retain their output and the one-call reminder.
- **Tools/wording:** all primitive names and parameter schemas already matched.
  The deliberate differences remain: no `propose_commit`, no finish evidence
  parameter, and no approval/evidence/injection-policy instructions. The non-policy
  system wording, IME preference for explicit buttons, finish descriptions,
  read-back/scroll/screenshot notices now follow the prototype. A catalog sentence
  clarifies reading truncated text instead of inferring the hidden portion.
- **Tables:** row ordering, role/flag letters, indentation, bounds, 200-element
  cap, 80-codepoint text truncation and JSON quoting matched. Wi-Fi steps 2–3 and
  Bluetooth steps 1 and 3 were byte-identical row-for-row. Wi-Fi step 1 differed
  in launcher dock bounds during settling; Bluetooth step 2 differed only in the
  clock text. The prototype's static untrusted-content header is now injected
  from the wording catalog. Its additional injection-policy wrapper is intentionally
  not ported; there is no classifier or enforcement gate.
- **Scroll feedback:** the port emitted whole labels and omitted resource-only
  or state-only rows. Python `worker/loop.py:1018-1033` quotes up to three labels,
  each bounded to 30 codepoints, with an omitted-label marker. That rendering and
  row inclusion now match, with a focused fake-backend test. Scroll feedback stays
  in the correlated result; the full current table follows it.
- **Context/model:** both use `gpt-6-sol`, low effort, one action per turn and
  append context. EVA now uses `tool_choice=auto` like Python and reuses one
  subscription WebSocket per task with stable `session-id`/`thread-id` and cache
  key. It sends the full append prefix; Python can send deltas using
  `previous_response_id`. The logical context is equivalent. API-key calls retain
  cancellable HTTP. Socket interruption cancels the underlying connection, late
  frames cannot satisfy a replacement call, and task completion closes the socket.
- **Launch aliases:** portable `capabilities.deviceTask.launchAliases` defaults
  to `com.android.settings: [com.google.android.settings.intelligence]`. Both
  worker result handling and Portal launch settling recognize the alias. An
  alias launch no longer waits six seconds for the wrong package or reports
  `app_not_found`; the fake transport test verifies settlement below one second.

#### Action latency

Python `bridge/portal/adapter.py:96-121,588-622` and EVA use the same policy:
100 ms poll, 100 ms gesture margin, 300 ms quiet window, 1500 ms no-change grace,
4000 ms normal budget, 6000 ms launch/read budgets. Their signatures both compare
package, activity, keyboard/content availability and complete element values.
No timing constant was reduced. The alias-aware foreground condition was the
behavioral correction. Screenshot telemetry now charges the post-capture tree
read to settling, matching Python.

Across the eight after-runs, Settings activation settling was 566–1969 ms; all
those samples settled successfully. The historical 4–5 second activation costs
did not recur on these routes. The old aggregate measurements cannot distinguish
an unstable/no-change tree from HTTP or host-load delay, so they do not justify
claiming a different settle constant caused the slowdown. Live model-token usage
shows 70,912 cached input tokens out of 159,402 (44.5%) across the eight after-runs;
detailed per-step request and action timings remain in the
artifacts above. The earlier on-device stop measurements predate this WebSocket
change; its cancellation/reuse/late-frame behavior has JVM coverage, not a new
on-device interruption measurement in this capped audit.

The catalog mirror is still exactly `eva-core/src/main/resources/eva-wording.yaml`
→ `colonelpanic8/eva-instructions/eva-wording.yaml`, byte-for-byte. This audit
changes tool keys `device-worker.ime_action` and `device-worker.finish`; updates
message keys `device-worker.{system,task,one_call,scroll_reversal,scroll_end,
scrolled,screenshot_limit,result,text_result}`; and adds
`device-worker.{revisions,history,screen_content,extra_call,text_verified,screenshot_attached}`.
No external catalog was modified or pushed.

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

To link an account through a messaging bridge (for example WhatsApp on the
[multidevice bridge](https://github.com/colonelpanic8/google-messages-multidevice-bridge)
served over Tailscale Serve):

1. Run one bridge instance per network with an HTTPS origin the phone can reach
   and a bearer token; pair the bridge with the account in its own web client.
2. Under **Messaging → Messaging services**, add a service: the name as it is
   spoken (`whatsapp`), a label, the HTTPS origin only, and the token. The name,
   label, and origin are saved to `eva.yaml` with a
   `messaging/<name>/bearer` credential reference; the token stays on the phone.
3. Select **Test connection**: it shows the bridge's `/v1/status` state,
   transport, and phone responsiveness. `authentication_required` means the
   bridge itself needs re-pairing.
4. In a typed conversation, ask “Which WhatsApp chats did I get recently?”,
   then read one or send a short message to a known number. Check the linked
   app to confirm the message and its later delivery status.
5. On another device, restore the same configuration and enter the token when
   the setup list asks to provision `messaging/<name>/bearer`.

The bridge path has JVM tests against a scripted bridge (search, history,
outbox outcome mapping, chat creation, idempotency key stability, and the
configuration round trip). It has not been verified against a live bridge or on
a device, and no automated test sends a real message.

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
