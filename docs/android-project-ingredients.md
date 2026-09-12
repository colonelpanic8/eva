# Reusable Android project ingredients

This is the short checklist for Ivan's native Android scaffolds. The local
reference repositories remain read-only examples:

- Native Compose pins and module layout:
  `/home/imalison/Projects/tile-wear/gradle/libs.versions.toml`,
  `/home/imalison/Projects/tile-wear/mobile/build.gradle.kts`
- Native CI and env-only signing:
  `/home/imalison/Projects/tile-wear/.github/workflows/{ci,release}.yml`
- Nix Android SDK composition and immutable `aapt2` override:
  `/home/imalison/Projects/mova/flake.nix`
- Direnv convention: `/home/imalison/Projects/mova/.envrc` and
  `/home/imalison/Projects/keepbook/.envrc`
- Release-derived self-hosted F-Droid publication:
  `/home/imalison/Projects/mova/docs/fdroid.md`,
  `/home/imalison/Projects/keepbook/scripts/fdroid/build-repo.sh`

## Shared ingredients

- Pin Gradle wrapper, AGP, Kotlin, Compose BOM, JDK, compile SDK, and target SDK.
- On AGP 9+, prefer built-in Kotlin instead of applying `kotlin-android`; pin the
  matching Compose compiler plugin explicitly when overriding AGP's compiler.
- Keep the application ID, namespace, minimum SDK, version name, and monotonic
  version-code formula explicit.
- Check in wrapper files and validate the wrapper in CI.
- Provide a Nix Android shell, `flake.lock`, `.envrc`, and small `just` recipes;
  run local project commands through direnv/Nix.
- Make one focused CI command cover formatting, Android lint, unit tests, and a
  debug APK; retain the APK as a short-lived artifact.
- Read release signing only from environment variables. Permit unsigned local
  release experiments if useful, but make publication fail closed and verify
  the final APK certificate.
- Store no secrets. Document keystore backup, certificate fingerprint, GitHub
  secret names, and the permanence of Android signing identity.
- Derive versions from semantic release tags with a deterministic Android code.
- Keep store text and per-version changelogs in fastlane-compatible metadata.
- For a self-hosted F-Droid source, index unchanged release APKs, sign the index,
  remove the build-time key before upload, stop collection at signer changes,
  and bound retained releases.
- State clearly that self-hosting is not official F-Droid catalog inclusion.
- Keep generated output (`build/`, `.gradle/`, `dist/`, `target/`, local SDK
  config, keystores) ignored.

## Framework-specific ingredients

- Native Compose uses Gradle modules, the Kotlin Compose compiler plugin,
  Android lint, and a Compose BOM directly; EVA and `tile-wear` follow this path.
- React Native/Expo adds Node/Yarn pins, native generation, JavaScript lint/type
  checks, architecture/ABI flags, and framework-specific release fixes; see
  `/home/imalison/Projects/mova/android/` and its workflows.
- Dioxus adds Rust targets, `dioxus-cli`, generated Android projects, NDK/CMake,
  and post-generation patch/sign steps; see
  `/home/imalison/Projects/keepbook/flake.nix` and its release workflow.
- Include emulators, system images, NDKs, CMake, Google APIs, or proprietary
  provider libraries only when an implemented feature or test actually needs them.
