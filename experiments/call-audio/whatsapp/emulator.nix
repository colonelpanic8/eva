# The WhatsApp far end's tools from the flake's pinned nixpkgs: emulator 37.1.11 with the
# Android 36 Google Play x86_64 image as an SDK root it accepts (sdk), a Python with gRPC for
# the emulator's audio controller (python), and scrcpy and ffmpeg for demos (tools).
{ repo }:
let
  flake = builtins.getFlake repo;
  pkgs = import flake.inputs.nixpkgs.outPath {
    system = "x86_64-linux";
    config = { allowUnfree = true; android_sdk.accept_license = true; };
  };
  composition = pkgs.androidenv.composeAndroidPackages {
    includeEmulator = true;
    includeSystemImages = true;
    platformVersions = [ "36" ];
    systemImageTypes = [ "google_apis_playstore" ];
    abiVersions = [ "x86_64" ];
  };
  image = builtins.head composition.system-images;
in
{
  sdk = pkgs.runCommand "eva-whatsapp-far-end-sdk" { nativeBuildInputs = [ pkgs.makeWrapper ]; } ''
    mkdir -p $out/bin $out/sdk/platforms $out/sdk/platform-tools
    ln -s ${composition.emulator}/libexec/android-sdk/emulator $out/sdk/emulator
    ln -s ${image}/libexec/android-sdk/system-images $out/sdk/system-images
    makeWrapper ${composition.emulator}/bin/emulator $out/bin/emulator \
      --set ANDROID_SDK_ROOT $out/sdk --set ANDROID_HOME $out/sdk
  '';
  python = pkgs.python3.withPackages (p: [ p.grpcio p.grpcio-tools ]);
  tools = pkgs.buildEnv {
    name = "eva-whatsapp-far-end-tools";
    paths = [ pkgs.scrcpy pkgs.ffmpeg ];
  };
}
