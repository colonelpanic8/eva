#!/usr/bin/env python3
"""A WhatsApp far end on an Android emulator: boot, install, answer, record what it hears.

The device has a speaker and microphone but no host audio backend, so nothing reaches the
desktop's audio; what it plays is read through the emulator's token-protected gRPC
controller (localhost only). Commands that use gRPC need emulator.nix's python.
Every command that touches a device first checks that it is an emulator.
"""

import argparse
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import emulator_audio  # noqa: E402  (grpc is imported only when audio is used)

AVD_HOME = Path.home() / ".local/share/eva/whatsapp-far-end"
START = HERE.parents[1] / "device-control/start-emulator.py"
IMAGE = "sdk/system-images/android-36/google_apis_playstore/x86_64"
# Observed on the official APK from whatsapp.com (2.26.40.77, 2026-10-14): the original
# signer, and the WhatsApp LLC certificate it rotated to for Android 13 and later.
WHATSAPP_SIGNERS = {
    "3987d043d10aefaf5a8710b3671418fe57e0e19b653c9df82558feb5ffce5d44",
    "fb920d381bee1b2093f27dc8f13d994da629dc91887d0529b35c9a2dc4f4a6c2",
}


def running_far_end():
    """The discovery entry of an emulator already running the far-end AVD, if any."""
    for entry in emulator_audio.running():
        if entry.get("avd.dir", "").startswith(str(AVD_HOME)):
            return entry
    return None


def free_port():
    for port in random.sample(range(20_000, 40_000), 50):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    sys.exit("No free port for the gRPC controller.")


def up(args):
    running = running_far_end()
    if running:
        sys.exit(f"The far end is already running as emulator-{running['port.serial']}; use that serial.")
    AVD_HOME.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, str(START), "--emulator", str(args.sdk / "bin/emulator"), "--image", str(args.sdk / IMAGE),
               "--avd-dir", str(AVD_HOME), "--audio", "--grpc-port", str(args.grpc_port or free_port()),
               "--gpu", args.gpu, "--ram-mb", "4096", "--data-gb", "8"]
    if args.port:
        command += ["--port", str(args.port)]
    if args.window:
        command.append("--window")
    os.execv(sys.executable, command)


def adb(serial, *command, check=True):
    return subprocess.run(["adb", "-s", serial, *command], check=check, capture_output=True, text=True).stdout


def require_emulator(serial):
    if adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        sys.exit(f"{serial} is not an emulator; refusing.")


def status(args):
    entry = running_far_end()
    if not entry:
        sys.exit("The far end is not running; start it with `up`.")
    serial = f"emulator-{entry['port.serial']}"
    print(f"serial {serial}, gRPC 127.0.0.1:{entry['grpc.port']}, AVD {entry['avd.dir']}")
    installed = adb(serial, "shell", "pm", "list", "packages", "com.whatsapp").strip()
    print(f"WhatsApp installed: {'yes' if installed else 'no'}")
    front = re.search(r"topResumedActivity=\S+ \S+ (\S+)", adb(serial, "shell", "dumpsys", "activity", "activities"))
    print("in front:", front.group(1) if front else "unknown")


def signers(apk):
    apksigner = shutil.which("apksigner") or next(
        iter(sorted(Path(os.environ.get("ANDROID_HOME", "/nonexistent")).glob("build-tools/*/apksigner"))), None
    )
    if not apksigner:
        sys.exit("apksigner not found; run inside the repository's dev shell (direnv exec . …).")
    out = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True).stdout
    return set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out))


def verify_apk(args):
    found = signers(args.apk)
    if not found or not found <= WHATSAPP_SIGNERS:
        sys.exit(f"{args.apk} is not signed by WhatsApp's known certificates: {sorted(found)}")
    print(f"{args.apk}: signed by WhatsApp ({len(found)} known certificate(s)).")


def install(args):
    verify_apk(args)
    require_emulator(args.serial)
    print(adb(args.serial, "install", "-r", str(args.apk)).strip())


def calls(serial):
    """The states of Telecom's calls on the emulator, from dumpsys telecom."""
    dump = adb(serial, "shell", "dumpsys", "telecom", check=False)
    return re.findall(r"\bCall TC@\S+.*?\bstate[=:]\s*(\w+)", dump) or re.findall(r"^\s+state:\s*(\w+)", dump, re.M)


def answer(args):
    """Waits for a ringing call, then answers with the call key, falling back to the on-screen Answer button."""
    require_emulator(args.serial)
    deadline = time.time() + args.timeout
    while "RINGING" not in calls(args.serial):
        if time.time() > deadline:
            sys.exit(f"No ringing call within {args.timeout} s.")
        time.sleep(0.5)
    time.sleep(args.delay)
    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_CALL")
    time.sleep(2)
    if "ACTIVE" not in calls(args.serial):
        dump = adb(args.serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        match = re.search(r'(?:text|content-desc)="(?:Answer|ANSWER|Accept)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            adb(args.serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(2)
    states = calls(args.serial)
    print(f"Telecom call states after answering: {states}")
    if "ACTIVE" not in states:
        sys.exit("The call did not become active.")


def record(args):
    require_emulator(args.serial)
    emulator_audio.record(args.serial, args.out, args.seconds)
    print(f"recorded {args.out}")


def test_sound(args):
    """Checks the recording path: records while a notification plays its sound, and requires the sound."""
    import audio_check

    require_emulator(args.serial)
    out = args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    recorder = subprocess.Popen([sys.executable, __file__, "record", "--serial", args.serial, "--out", str(out), "--seconds", "6"])
    time.sleep(2)
    adb(args.serial, "shell", "cmd", "notification", "post", "-t", "EVA far-end audio check", "eva-audio-check", "Recording test sound")
    recorder.wait()
    loud = [(at, db) for at, db in audio_check.levels(out) if db > -45]
    if not loud:
        sys.exit(f"No sound reached {out}: the recording path is broken, or notification sounds are muted on the emulator.")
    print(f"Recording path works: sound at {loud[0][0]:.1f}-{loud[-1][0]:.1f} s, peak {max(db for _, db in loud):.1f} dBFS ({out}).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    start = commands.add_parser("up", help="boot the persistent far-end emulator (runs in the foreground)")
    start.add_argument("--sdk", type=Path, required=True, help="emulator.nix's sdk output")
    start.add_argument("--window", action="store_true", help="show a window (needs DISPLAY or WAYLAND_DISPLAY)")
    start.add_argument("--gpu", default="swiftshader", help="swiftshader (headless-safe) or host (fast; use with --window)")
    start.add_argument("--port", type=int, help="fixed console port, such as 5556 (adb serial emulator-5556)")
    start.add_argument("--grpc-port", type=int, help="fixed gRPC port; default: a free one")
    start.set_defaults(run=up)
    commands.add_parser("status", help="show the running far end").set_defaults(run=status)
    check = commands.add_parser("verify-apk", help="check an APK is signed by WhatsApp")
    check.add_argument("--apk", type=Path, required=True)
    check.set_defaults(run=verify_apk)
    setup = commands.add_parser("install", help="verify, then install a WhatsApp APK on the emulator")
    setup.add_argument("--serial", required=True)
    setup.add_argument("--apk", type=Path, required=True)
    setup.set_defaults(run=install)
    pickup = commands.add_parser("answer", help="answer the next incoming call")
    pickup.add_argument("--serial", required=True)
    pickup.add_argument("--timeout", type=float, default=120)
    pickup.add_argument("--delay", type=float, default=1, help="seconds to let it ring")
    pickup.set_defaults(run=answer)
    capture = commands.add_parser("record", help="record what the far end hears")
    capture.add_argument("--serial", required=True)
    capture.add_argument("--out", type=Path, required=True)
    capture.add_argument("--seconds", type=float, required=True)
    capture.set_defaults(run=record)
    sound = commands.add_parser("test-sound", help="check the recording path with a notification sound")
    sound.add_argument("--serial", required=True)
    sound.add_argument("--out", type=Path, default=HERE.parent / "build/far-end-test-sound.wav")
    sound.set_defaults(run=test_sound)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
