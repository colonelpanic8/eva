{
  lib,
  stdenv,
  gradle_9,
  jdk17,
  makeWrapper,
  makeDesktopItem,
  copyDesktopItems,
  xdg-utils,
  desktopLibraryPath,
  version,
}: let
  gradle = gradle_9.override {java = jdk17;};
  fs = lib.fileset;
in
  stdenv.mkDerivation (finalAttrs: {
    pname = "eva-desktop";
    inherit version;

    src = fs.toSource {
      root = ../.;
      fileset = fs.unions [
        ../build.gradle.kts
        ../settings.gradle.kts
        ../gradle.properties
        ../gradle/libs.versions.toml
        ../assets/branding
        ../device-control-core
        ../device-control-host
        ../device-control-portal
        ../eva-core
        ../eva-desktop
        ../keyword-core
      ];
    };

    nativeBuildInputs = [
      gradle
      makeWrapper
      copyDesktopItems
    ];

    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./eva-desktop-deps.json;
    };

    gradleFlags = [
      "-Peva.desktopOnly=true"
      "-Dfile.encoding=utf-8"
    ];
    gradleBuildTask = ":eva-desktop:installDist";
    gradleUpdateTask = finalAttrs.gradleBuildTask;

    desktopItems = [
      (makeDesktopItem {
        name = "eva";
        desktopName = "EVA";
        genericName = "Assistant";
        comment = "Talk to EVA";
        exec = "eva-desktop tray";
        icon = "eva";
        categories = ["Utility"];
        startupWMClass = "com-colonelpanic-eva-desktop-MainKt";
        actions.summon = {
          name = "Show the running EVA";
          exec = "eva-desktop summon";
        };
      })
    ];

    installPhase = ''
      runHook preInstall

      mkdir -p $out/share/eva-desktop
      cp -r eva-desktop/build/install/eva-desktop/lib $out/share/eva-desktop/
      install -Dm755 eva-desktop/build/install/eva-desktop/bin/eva-desktop $out/share/eva-desktop/bin/eva-desktop
      makeWrapper $out/share/eva-desktop/bin/eva-desktop $out/bin/eva-desktop \
        --set JAVA_HOME ${jdk17.home} \
        --prefix LD_LIBRARY_PATH : ${desktopLibraryPath} \
        --prefix PATH : ${lib.makeBinPath [xdg-utils]}
      install -Dm644 assets/branding/eva-face-profile-v8-teal-hair-blue-face.svg \
        $out/share/icons/hicolor/scalable/apps/eva.svg

      runHook postInstall
    '';

    meta = {
      description = "EVA, a text assistant for the desktop with a tray icon";
      homepage = "https://github.com/colonelpanic8/eva";
      license = lib.licenses.asl20;
      mainProgram = "eva-desktop";
      platforms = ["x86_64-linux"];
    };
  })
