#!/usr/bin/env python3
"""Create a fresh headless AVD without changing existing AVDs or system images."""

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
    args = parser.parse_args()
    image = args.image.resolve()
    if not (image / "system.img").is_file():
        parser.error("--image must contain system.img")
    ports = list(range(5554, 5586, 2))
    random.shuffle(ports)
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
        parser.error("No free emulator port pair; existing emulators were left alone")
    root = Path(tempfile.mkdtemp(prefix="eva-device-emulator-"))
    avds = root / "avd"
    avd = avds / "eva-device-poc.avd"
    avd.mkdir(parents=True)
    (avds / "eva-device-poc.ini").write_text(f"avd.ini.encoding=UTF-8\npath={avd}\n")
    (avd / "config.ini").write_text(f"""avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size=4G
image.sysdir.1={image}/
""")
    print(f"Serial: emulator-{port}\nAVD data: {root}", flush=True)
    environment = dict(os.environ, ANDROID_AVD_HOME=str(avds))
    raise SystemExit(subprocess.call([
        str(args.emulator.resolve()), "-avd", "eva-device-poc", "-no-window",
        "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader", "-port", str(port),
    ], env=environment))


if __name__ == "__main__":
    main()
