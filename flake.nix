{
  description = "EVA native Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem ["x86_64-linux"] (system: let
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "37.0.0";
      # Latest version available in the pinned nixpkgs Android package set.
      cmdLineToolsVersion = "22.0";
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        cmdLineToolsVersion = cmdLineToolsVersion;
        toolsVersion = "26.1.1";
        platformToolsVersion = "37.0.1";
        buildToolsVersions = [buildToolsVersion];
        platformVersions = ["37"];
        includeEmulator = false;
        includeSources = false;
        includeSystemImages = false;
        includeNDK = false;
        useGoogleAPIs = false;
        useGoogleTVAddOns = false;
      };
      androidHome = "${androidComposition.androidsdk}/libexec/android-sdk";
      aapt2 = "${androidHome}/build-tools/${buildToolsVersion}/aapt2";
      androidShell = pkgs.mkShell {
        packages = with pkgs; [
          androidComposition.androidsdk
          gh
          git
          jdk17
          just
          python3
        ];
        ANDROID_HOME = androidHome;
        ANDROID_SDK_ROOT = androidHome;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
        JAVA_HOME = pkgs.jdk17.home;
        LC_ALL = "en_US.UTF-8";
        LANG = "en_US.UTF-8";
        shellHook = ''
          export PATH=${androidHome}/platform-tools:${androidHome}/cmdline-tools/${cmdLineToolsVersion}/bin:$PATH
          echo "EVA Android dev shell"
          echo "  Java: $(java -version 2>&1 | head -1)"
          echo "  SDK:  $ANDROID_HOME"
          echo "  Run:  just check"
        '';
      };
    in {
      devShells = {
        android = androidShell;
        default = androidShell;
      };
    });
}
