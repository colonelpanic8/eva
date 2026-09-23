# Device-control experiment

This standalone debug-only probe recorded the **app → Shizuku UserService →
UiAutomation path on an Android 16 emulator**. Its fixed fixture workflow is
separate from EVA's integrated device-control adapter. No root, embedded ADB client, AccessibilityService,
or MediaProjection is used in either experimental APK.

Release variants of these experimental apps are disabled. For the main app's
current device-control boundary, see [Architecture](../../docs/architecture.md);
this experiment's dated results do not establish support on other devices.

## What was proved

On 2026-09-12, an ordinary app (UID 10218) obtained a Binder connection to its
own Shizuku UserService running as shell (UID 2000). That helper instantiated
the hidden `UiAutomation`/`UiAutomationConnection` pair, using
`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, and then:

| Experiment | Observed result |
| --- | --- |
| Deny Shizuku permission | Explicit permission-denied result; no helper operation |
| Bind helper | Shell UID 2000, distinct from the client app UID |
| Observe another app | Eight hierarchy nodes and a 1080 × 1920 screenshot |
| Replace text | `héllo 日本語 🙂` appeared in the separate fixture app |
| Inject touch DOWN/UP | Fixture counter changed from 0 to 1 |
| Re-observe | UI tree and screenshot showed the changed field and counter |
| Rotate, then repeat | Text replacement and touch passed at 1920 × 1080 |
| Capture a known `FLAG_SECURE` window | Fixture content blank in screenshot; its hierarchy text still accessible |
| Force-stop client | Non-daemon helper exited; relaunch bound a new shell helper |
| Stop Shizuku | Client reported unavailable |
| Restart Shizuku | New helper bound; text/tap experiment passed again |

Recorded helper-call durations were 243 ms for observation and 732 ms for
portrait text/tap plus observation. The latter includes explicit 250 ms and
350 ms waits. These are single samples, exclude client startup and its 2-second
fixture-settle delay, and are not latency guarantees. Landscape was 948 ms on
the preceding APK revision (same control implementation; final changes concerned
lint, build formatting, annotations, and release-variant disabling).

Evidence: [environment and APK hashes](evidence/2026-09-12-api36/environment.json),
[observation](evidence/2026-09-12-api36/observe.json),
[action result](evidence/2026-09-12-api36/exercise.json),
[screenshot](evidence/2026-09-12-api36/exercise.png),
[landscape result](evidence/2026-09-12-api36/landscape.json),
[secure-window tree](evidence/2026-09-12-api36/secure.json),
[secure-window screenshot](evidence/2026-09-12-api36/secure.png),
[lifecycle results](evidence/2026-09-12-api36/lifecycle.json).
The permission denial was checked interactively before granting access; it is
not included in the unattended runner.

The system image was Google Play x86_64 Android 16/API 36, fingerprint
`google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`,
with emulator 37.1.10 and Shizuku app 13.6.0/API library 13.1.5. Builds use EVA's
Gradle wrapper, AGP 9.4.1, JDK 17 and SDK/build-tools 37. The probe and fixture
are Java/AIDL; no Kotlin app implementation is added here.

## Architecture

`ProbeActivity` requests Shizuku permission, binds `DeviceProbeService` with
`daemon(false)`, launches a separate fixture APK, and executes the helper call
on a worker thread while the fixture is foregrounded. The helper connects
UiAutomation only for the call and disconnects in `finally`.

PNG bytes travel through a `ParcelFileDescriptor` opened in the client's private
files directory. Only bounded JSON metadata travels in the Binder result.
ADB installs, starts, and collects the harness results; it does **not** perform
the text replacement or counter tap being verified. Those operations run inside
the Shizuku helper. ADB bootstraps Shizuku through its documented desktop startup
path; wireless self-pairing was not part of this experiment.

The first immediate hierarchy query returned null even though capture worked.
The helper now waits up to four seconds for a root and fails if none appears.
Input is hard-coded to the separate fixture package and its named controls.
The helper refuses input if the active root is not that package. The host runner
requires an emulator serial and checks `ro.kernel.qemu` before starting any test.

## Reproduce

Run commands from the EVA repository root through its development shell.

```sh
direnv exec . just -f experiments/device-control/justfile format
direnv exec . just -f experiments/device-control/justfile check
```

The project dev shell does not bundle an emulator or a system image. Supply an
Android emulator executable and an installed x86_64 system-image directory. On
this NixOS machine the experiment reused cached Nix SDK components, with KVM.
The launcher creates a new temporary AVD and chooses an unused port pair; it
does not modify an existing AVD. Keep it running in a terminal:

```sh
direnv exec . python3 experiments/device-control/start-emulator.py \
  --emulator "$EVA_EMULATOR" --image "$EVA_SYSTEM_IMAGE"
```

Use the printed serial for **every** ADB command. Wait for
`adb -s "$EVA_SERIAL" shell getprop sys.boot_completed` to return `1`.
Download the official [Shizuku 13.6.0 APK](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0),
then install it and open its manager on that emulator. In Shizuku choose
**Start by connecting to a computer → View command**, then execute the displayed
command with `adb -s "$EVA_SERIAL" shell …` rather than unqualified `adb shell`.
Version 13.6.0 displays a path ending in `lib/x86_64/libshizuku.so`; the older
`/sdcard/Android/data/…/start.sh` command did not exist on this installation.

```sh
direnv exec . adb -s "$EVA_SERIAL" install -r "$EVA_SHIZUKU_APK"
direnv exec . adb -s "$EVA_SERIAL" shell am start \
  -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity
# Start Shizuku using its displayed command, as described above.
direnv exec . adb -s "$EVA_SERIAL" install -r \
  experiments/device-control/probe/build/outputs/apk/debug/probe-debug.apk
direnv exec . adb -s "$EVA_SERIAL" install -r \
  experiments/device-control/fixture/build/outputs/apk/debug/fixture-debug.apk
direnv exec . adb -s "$EVA_SERIAL" shell am start \
  -n com.colonelpanic.eva.deviceprobe/.ProbeActivity --es operation ping
```

Grant the probe access in the emulator's Shizuku dialog. It should show a helper
identity with UID 2000. Then:

```sh
direnv exec . python3 experiments/device-control/run-probe.py --serial "$EVA_SERIAL" --operation ping
direnv exec . python3 experiments/device-control/run-probe.py --serial "$EVA_SERIAL" --operation observe
direnv exec . python3 experiments/device-control/run-probe.py --serial "$EVA_SERIAL" --operation exercise
direnv exec . python3 experiments/device-control/run-probe.py --serial "$EVA_SERIAL" --operation secure
direnv exec . python3 experiments/device-control/check-lifecycle.py --serial "$EVA_SERIAL"
```

`run-probe.py` fails on an unsuccessful result or a 30-second timeout. It saves
JSON and PNG files under ignored `artifacts/`, or the supplied `--output` path.
`secure` verifies that observation returned; compare its PNG with the ordinary
fixture capture to assess blanking. It does not infer protected content from
pixel colors. `check-lifecycle.py` deliberately stops the client and Shizuku on
the selected emulator, then restarts them. Do not use it on a shared emulator.

For landscape testing, set `accelerometer_rotation` to `0` and `user_rotation`
to `1` using `adb shell settings put system`, then repeat `exercise`. Restore
`user_rotation` to `0` afterwards. This tests rotation **before** the call, not
rotation racing an injection. To stop an instance you created, use
`adb -s "$EVA_SERIAL" emu kill`. Its temporary AVD directory remains for inspection.

## Scope of the recorded evidence

- Android 17/API 37, a physical phone, fold transitions, secondary displays,
  and other OEMs are unverified. The hidden constructor assumes display 0.
- This is a fixed fixture workflow, not a model loop, general-purpose tool API,
  production authorization design, or EVA integration.
- No foreground service is used. A short call while another activity is in
  front worked; long sessions, freezer behavior, and memory pressure are untested.
- No cancellation, stale-observation guards, user takeover, lock-screen policy,
  gesture sequences, keyboard insertion, clipboard fallback, or durable invocation
  journal is implemented. Never automatically retry a failed mutating probe.
- `ACTION_SET_TEXT` replaces the whole field; it does not establish Unicode
  insertion at an arbitrary caret. The verified touch uses the default display.
- Secure screenshots do not imply a protected hierarchy. The fixture establishes
  that distinction directly. Real screen data must not enter model transport
  until disclosure rules are implemented.
- Screenshot and hierarchy are sequential, not atomic. The fixed waits are
  sufficient for the fixture, not a synchronization protocol for arbitrary apps.
- UiAutomation ownership contention and coexistence with an enabled accessibility
  service still need focused tests. No binary fallback was needed in this run.
- APKs, raw run output and AVD data are experimental. The exported activity and
  local result files are test harness interfaces, not production entry points.

Use this fixture to investigate platform compatibility independently of model
behavior. The main app has separate bounded operations and freshness checks;
changes there require their own verification.

## Validation and sources

The standalone `just format` and `just check` passed (Kotlin build-script
formatting, fatal Android lint, Java/AIDL compilation, and both debug APKs).
The repository-root `direnv exec . just check` also passed; most existing app
tasks were up to date. Behavioral verification is the emulator execution above,
not mocked structural tests.

- [Shizuku UserService API and sample](https://github.com/RikkaApps/Shizuku-API)
- [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
- [AOSP UiAutomation implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/UiAutomation.java)
- [Android emulator command line](https://developer.android.com/studio/run/emulator-commandline)

No third-party source is vendored. Shizuku and AndroidX are Gradle dependencies;
the Shizuku manager APK is downloaded separately from its official release.
