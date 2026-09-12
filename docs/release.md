# Release process

EVA production releases are signed with one long-lived Android signing identity.
Losing that key or its passwords prevents updates to existing installations;
replacing it creates a new, incompatible install lineage. Back up the keystore
and its metadata before the first public release, then keep the same key for the
APK and self-hosted F-Droid index.

## Versioning

Tags and release versions use `vMAJOR.MINOR.PATCH`. Android version codes use:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Each minor and patch component must be at most 999. Check a value with
`just version-code 0.1.0`. Every published version must sort above the previous
one.

## Local signed build

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

## GitHub release

After completing [external setup](external-setup.md):

1. Update `CHANGELOG.md` and run `just fdroid-changelogs`.
2. Run `just check` and, ideally, a local signed build.
3. Commit the release state and create a `vMAJOR.MINOR.PATCH` tag.
4. Push the tag. `.github/workflows/release.yml` builds from the tag and uploads
   `eva.apk`; it fails if any signing secret is missing or invalid.
5. Confirm the APK certificate and install/upgrade behavior before promoting
   the release widely.
6. The successful release workflow triggers the Pages/F-Droid workflow.

Do not delete or regenerate the signing key during ordinary version bumps.
