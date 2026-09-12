# Self-hosted F-Droid distribution

EVA is prepared to publish a self-hosted F-Droid-compatible binary repository
through GitHub Pages:

- Landing page: <https://colonelpanic8.github.io/eva/>
- Repository address: `https://colonelpanic8.github.io/eva/fdroid/repo`

Neither URL is live until the external GitHub repository, signing secrets,
Pages configuration, and first release are created. See
[external-setup.md](external-setup.md).

## Publication model

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

## Local repository build

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

## Installation

After publication, add `https://colonelpanic8.github.io/eva/fdroid/repo` as an
additional package source in the F-Droid client, verify the displayed fingerprint,
refresh repositories, and install EVA. A direct release install uses:

```sh
adb install -r eva.apk
```

Because both channels serve the exact same signed APK, they can upgrade one
another. Debug builds use `com.colonelpanic.eva.debug` and are separate.

## Official catalog distinction

This machinery does not submit EVA to, or build EVA inside, F-Droid's official
catalog. Official inclusion is a separate future effort involving a source-build
recipe and policy/reproducibility review. The checked-in fastlane metadata is
reusable input, not evidence of acceptance.
