#!/usr/bin/env python3
"""Create a fresh headless AVD without changing existing AVDs or system images.

With --avd-dir the AVD lives there and is reused on later runs, so app state such as a
registered account survives. --audio gives the device a speaker and microphone without any
host audio backend; with --grpc-port their audio is reachable through the emulator's
token-protected gRPC controller (streamAudio, injectAudio).
"""

import argparse
from pathlib import Path
import os
import random
import socket
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True, help="x86_64 system-image directory")
    parser.add_argument("--avd-dir", type=Path, help="persistent AVD home; created on first use, then reused")
    parser.add_argument("--window", action="store_true", help="show the emulator window")
    parser.add_argument("--gpu", default="swiftshader", help="emulator -gpu mode; a window needs host for usable input")
    parser.add_argument("--audio", action="store_true", help="give the device audio, reachable only through gRPC")
    parser.add_argument("--grpc-port", type=int, help="start the token-protected gRPC controller on this port")
    parser.add_argument("--port", type=int, help="console port (even, 5554-5584); default: a free one")
    parser.add_argument("--ram-mb", type=int, default=2048)
    parser.add_argument("--data-gb", type=int, default=4)
    args = parser.parse_args()
    audio = args.audio
    image = args.image.resolve()
    if not (image / "system.img").is_file():
        parser.error("--image must contain system.img")
    ports = [args.port] if args.port else random.sample(range(5554, 5586, 2), 16)
    port = None
    for candidate in ports:
        sockets = [socket.socket(), socket.socket()]
        try:
            for offset, sock in enumerate(sockets):
                sock.bind(("127.0.0.1", candidate + offset))
            port = candidate
            break
        except OSError:
            continue
        finally:
            for sock in sockets:
                sock.close()
    if port is None:
        parser.error(f"Port {args.port} is in use" if args.port else "No free emulator port pair; existing emulators were left alone")
    root = args.avd_dir.resolve() if args.avd_dir else Path(tempfile.mkdtemp(prefix="eva-device-emulator-"))
    avds = root / "avd"
    avd = avds / "eva-device-poc.avd"
    avd.mkdir(parents=True, exist_ok=True)
    (avds / "eva-device-poc.ini").write_text(f"avd.ini.encoding=UTF-8\npath={avd}\n")
    audio_flag = "yes" if audio else "no"
    (avd / "config.ini").write_text(f"""avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize={args.ram_mb}
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.audioInput={audio_flag}
hw.audioOutput={audio_flag}
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size={args.data_gb}G
image.sysdir.1={image}/
PlayStore.enabled={"yes" if "playstore" in str(image) else "no"}
""")
    print(f"Serial: emulator-{port}\nAVD data: {root}", flush=True)
    environment = dict(os.environ, ANDROID_AVD_HOME=str(avds))
    command = [str(args.emulator.resolve()), "-avd", "eva-device-poc", "-no-snapshot", "-no-boot-anim", "-gpu", args.gpu, "-port", str(port)]
    if args.window:
        if not (os.environ.get("WAYLAND_DISPLAY") or os.environ.get("DISPLAY")):
            parser.error("--window needs DISPLAY or WAYLAND_DISPLAY (for example DISPLAY=:0 WAYLAND_DISPLAY=wayland-1)")
    else:
        command.append("-no-window")
    if audio:
        environment["QEMU_AUDIO_DRV"] = "none"
    else:
        command.append("-no-audio")
    if args.grpc_port:
        command += ["-grpc", str(args.grpc_port), "-grpc-use-token"]
    raise SystemExit(subprocess.call(command, env=environment))


if __name__ == "__main__":
    main()
