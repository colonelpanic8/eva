#!/usr/bin/env python3
"""Run the standalone probe on an explicitly selected emulator; never a USB phone."""

import argparse
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--operation", choices=["ping", "observe", "exercise", "secure"], default="observe")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "artifacts")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This experiment only accepts emulator serials")
    adb = ["adb", "-s", args.serial]
    package = "com.colonelpanic.eva.deviceprobe"

    def command(*parts):
        return subprocess.check_output(adb + list(parts), timeout=15)

    if command("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        parser.error("Target did not identify itself as an emulator")
    command("shell", "run-as", package, "rm", "-f", "files/result.json")
    command("shell", "am", "start", "-n", package + "/.ProbeActivity", "--es", "operation", args.operation)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        time.sleep(0.5)
        try:
            result = json.loads(command("exec-out", "run-as", package, "cat", "files/result.json"))
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            continue
        if result.get("pending"):
            continue
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / (args.operation + ".json")).write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        if result.get("imageWidth"):
            png = command("exec-out", "run-as", package, "cat", "files/capture.png")
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("Capture did not contain a PNG")
            (args.output / (args.operation + ".png")).write_bytes(png)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        if not result.get("ok"):
            raise SystemExit(1)
        return
    raise SystemExit("Probe did not finish within 30 seconds; check permission UI and logcat")


if __name__ == "__main__":
    main()
