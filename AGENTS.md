# Working on EVA

## Read first

1. [README: design philosophy](README.org) — the product principles, including
   declarative, composable configuration and one user repo that restores every setting.
2. [Architecture](docs/architecture.md) — runtime ownership, configuration, current
   behavior, and implementation limits.
3. [Extension protocol](docs/extension-protocol.md) when changing capabilities,
   package formats, grants, or installed-provider interoperability.

[Operations](docs/operations.md) covers builds, device verification, signing, and
release infrastructure. Keep these documents current instead of creating new
implementation logs, dated plans, or duplicate specifications. Historical plans
and superseded reports remain in Git history. `CHANGELOG.md` owns release history;
experiment READMEs and third-party notices stay beside their code.

## Product and implementation invariants

- EVA is a working app under active development. Distinguish executable behavior,
  JVM verification, device verification, and future designs; do not call it a scaffold
  or imply that an untested platform integration works everywhere.
- Complete routine authorized actions with minimal friction. Reuse existing app
  interfaces; prefer declarative integrations when they avoid app/EVA code changes.
- All new settings must participate in the user-owned configuration and restore
  model. UI controls edit that same model. Preserve readable, deterministic files,
  composition, stable identities, and existing user choices during migration.
- Never put secrets in shareable configuration, logs, source, or tool results.
  Portable settings must retain credential references and identify provisioning
  needs. Android permissions and document grants require device-local authorization.
- Providers propose tools; the capability dispatcher validates, authorizes, journals,
  and executes them. Do not bypass it from UI or model-provider code.
- Keep completion, handoff, rejection, failure, and uncertainty distinct. Never
  retry an uncertain mutation merely to repair lost result delivery.
- Conversation and task ownership outlive a voice attachment. Preserve turn IDs,
  action provenance, and recovery behavior when changing provider or audio lifecycle.
- Keep stock Android useful without Shizuku. Do not add permissions, package queries,
  services, or SDKs ahead of the executable feature that needs them.

## Code map

Paths below are relative to `app/src/main/java/com/colonelpanic/eva/`:

| Work | Start here |
| --- | --- |
| Application wiring and shared state | `EvaApplication.kt` |
| Launch modes and assistant entry | `MainActivity.kt`, `Launch.kt`, `assist/` |
| Conversation and background turn work | `conversation/ThreadController.kt`, `Thread.kt`, `TurnWorkService.kt` |
| Provider contract and implementations | `providers/ConversationProvider.kt`, `providers/openai/`, `BrokerConversationProvider.kt` |
| Voice lifecycle, routing, WebRTC | `audio/`, `audio/webrtc/` |
| Registry, dispatch, journal semantics | `capability/CapabilityRegistry.kt`, `CapabilityDispatcher.kt`, `Invocation.kt` |
| Installed-extension discovery, grants, IPC | `capability/extensions/`, `adapters/android/AndroidExtensionConnector.kt` |
| Declarative package parsing/import/execution | `adapters/declarative/` |
| Native phone operations | `adapters/android/` |
| Messaging UI, reply access, remembered numbers | `ui/settings/MessagingScreen.kt`, `data/MessagingSettings.kt`, `data/ChosenNumbers.kt` |
| Media apps as per-app extensions | `adapters/android/MediaAdapter.kt`, `adapters/android/AndroidMediaApps.kt` |
| Portable configuration, composition, restore | `data/configuration/` |
| Managed Git checkout, validation, sync | `data/configuration/ManagedGitRepository.kt`, `EvaConfigurationManager.kt` |
| Persistence, settings, secrets | `data/` |
| Stock prompt and YAML composition | `conversation/prompt/` |
| App navigation and settings | `ui/EvaApp.kt`, `ui/settings/`, `ui/prompt/` |

- Bundled extension definitions: `app/src/main/assets/*.json`.
- Installed-provider ABI: `app/src/main/aidl/com/colonelpanic/eva/extension/`.
- HTTP/declarative and AIDL wire fixtures: `docs/examples/`; JSON Schemas for the
  package, index, tool, descriptor, and result shapes: `docs/schemas/`; canonical branding sources:
  `assets/branding/`.
- JVM tests mirror production packages in `app/src/test/java/`.
- Device/live tests: `app/src/androidTest/java/`; live providers require opt-in arguments.
- Voice broker experiment and evidence: `experiments/voice-poc/`.
- Isolated Shizuku probe/fixture: `experiments/device-control/`.
- External configuration sources: `colonelpanic8/eva-instructions` and
  `colonelpanic8/eva-extensions`. Fetching listings/instructions is distinct from
  installing packages, granting actions, or publishing repository changes.

## Commands and checks

Run through `direnv exec . <command>` or `nix develop .#android --command <command>`.
Use `just --list` for the supported command surface.

```sh
direnv exec . just format       # required after Kotlin edits
direnv exec . just check        # required before handoff
# Focused development loop:
direnv exec . ./gradlew :app:testDebugUnitTest --tests 'com.colonelpanic.eva.conversation.ThreadControllerTest'
```

`just check` includes formatting, fatal Android lint, JVM tests, and debug assembly.
Do not weaken lint or add tautological tests. Add focused behavior/regression tests
for migrations, lifecycle changes, and execution semantics. Device tests are
separate; do not claim device verification from a JVM pass.

Baseline: JDK 17, SDK 37, Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20.
AGP built-in Kotlin replaces `kotlin-android`; update pins together.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, ID
`com.colonelpanic.eva.debug`; production ID: `com.colonelpanic.eva`.
Select the device explicitly when using `adb` with multiple devices.

## Signing and Git hygiene

- Inspect `git status` first. Preserve concurrent edits and unknown untracked files;
  coordinate file ownership with active agents. Never stage unrelated work.
- Never commit keystores, credentials, local SDK paths, or generated build output.
- Production signing inputs are environment-only; missing inputs must fail closed.
  Verify release signatures with `apksigner`; never publish unsigned/debug-signed
  production artifacts.
- Preserve the original signing identity for production APKs and the self-hosted
  F-Droid index. The collector must not mix APK signers.
- Tags are `vMAJOR.MINOR.PATCH`; version codes are
  `major * 1,000,000 + minor * 1,000 + patch`.
- Publishing, enabling Pages, creating remotes, and uploading secrets are operator
  actions, not normal build steps. See [Operations](docs/operations.md).
