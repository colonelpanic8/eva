# EVA development conventions

## Scope and product claims

- EVA is currently a scaffold. Do not describe voice, model, Android capability,
  Paseo, Shizuku, or AppFunctions integration as working until it has executable
  code and focused verification.
- Keep stock Android APIs useful without privileged extensions. Treat Shizuku
  and AppFunctions as optional experiments until device-tested.
- Do not add Android permissions, package visibility declarations, services, or
  provider SDKs ahead of an implemented feature that needs them.
- `docs/design.md` is a proposed outline maintained separately. Preserve it and
  do not silently convert proposals into current-feature documentation.

## Development environment

- Run project commands through `direnv exec . <command>` or
  `nix develop .#android --command <command>`.
- JDK 17, Android SDK 37, Gradle 9.7.1, AGP 9.4.0, and Kotlin 2.4.20 are the
  supported baseline. AGP built-in Kotlin replaces `kotlin-android`. Update pins
  together and document compatibility choices.
- Use `just --list` for the supported command surface.

## Verification

- Run `just format` after Kotlin edits and `just check` before handoff.
- Keep Android lint warnings fatal. Add focused tests for behavior, not
  structural or tautological tests.
- Verify release APK signatures with `apksigner`; never publish an unsigned or
  debug-signed production artifact.

## Signing and releases

- Signing material is environment-only and must never enter source files,
  Gradle properties, logs, or build artifacts.
- Production workflows must fail closed when any signing input is absent.
- Preserve the original Android signing identity for every production release
  and self-hosted F-Droid index. The F-Droid collector must not mix APK signers.
- Semantic tags are `vMAJOR.MINOR.PATCH`; version codes use
  `major * 1,000,000 + minor * 1,000 + patch`.
- Publishing, enabling Pages, creating remotes, and uploading secrets are
  operator actions, not normal build steps.

## Git hygiene

- Preserve concurrent edits and unrelated files. Inspect `git status` before
  changing or staging anything.
- Never commit keystores, credentials, local SDK paths, or generated build output.
- Prefer focused commits in which the app, checks, and documentation agree.
