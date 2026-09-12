# External setup not performed by this scaffold

The local project is intentionally not connected to external infrastructure.
As of 2026-09-12, `colonelpanic8/eva` does not exist on GitHub. The following
steps require an operator and should happen only after reviewing the scaffold.

## GitHub repository

1. Create `colonelpanic8/eva` with `main` as its default branch.
2. Add `git@github.com:colonelpanic8/eva.git` as this checkout's `origin` and
   push the reviewed history.
3. Optionally configure branch protection and required CI checks.

The URLs already present in documentation and F-Droid metadata are the expected
post-creation URLs; they are not evidence that the repository or Pages site is live.

## Android signing identity

Create the production keystore once, before the first public build. A typical
starting command is:

```sh
keytool -genkeypair -v \
  -keystore /secure/path/eva-release.jks \
  -alias eva \
  -keyalg RSA -keysize 4096 -validity 10000
```

Record the keystore bytes, alias, store password, key password, creation notes,
and certificate fingerprints in an encrypted, backed-up password-store entry
such as `eva-android-release-keystore`. Keep at least one independent encrypted
backup. Never commit the keystore, passwords, decoded base64, or generated
credentials files.

Configure these GitHub Actions secrets from that same record:

- `ANDROID_KEYSTORE_BASE64` — base64 of the complete keystore, with no wrapping
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

After upload, test a manually dispatched release build against a non-public tag
or disposable repository before the first production tag. Record the production
certificate SHA-256 fingerprint in the encrypted entry and compare it with every
release workflow's `apksigner` output.

## GitHub Pages and F-Droid

1. In repository settings, enable Pages with **GitHub Actions** as the source.
2. Ensure Actions may create Pages deployments and the `github-pages`
   environment is available.
3. Run the F-Droid workflow after the first signed `eva.apk` release exists.
4. Verify the fingerprint in the workflow summary and rendered landing page
   against the production key before sharing the repository URL.

These steps create EVA's self-hosted binary source only. Inclusion in the
official F-Droid catalog would require a separate submission, an upstream
source-build recipe, reproducibility review, and catalog policy compliance.
