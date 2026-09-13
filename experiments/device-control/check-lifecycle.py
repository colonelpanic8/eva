#!/usr/bin/env python3
"""Check client/helper death and Shizuku restart on an explicitly selected emulator."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "artifacts/lifecycle")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    package = "com.colonelpanic.eva.deviceprobe"

    def command(*parts):
        return subprocess.check_output(adb + list(parts), timeout=20).decode().strip()

    if not args.serial.startswith("emulator-") or command("shell", "getprop", "ro.kernel.qemu") != "1":
        parser.error("This test may only stop processes on an emulator")

    def probe(operation, folder, success=True):
        directory = args.output / folder
        result = subprocess.run([
            "python3", str(Path(__file__).with_name("run-probe.py")),
            "--serial", args.serial, "--operation", operation, "--output", str(directory),
        ], stdout=subprocess.DEVNULL, timeout=40)
        if result.returncode != (0 if success else 1):
            raise RuntimeError(f"Unexpected {folder} exit: {result.returncode}")
        return json.loads((directory / (operation + ".json")).read_text())

    before = probe("ping", "before-client-stop")
    old_pid = re.search(r"pid=(\d+)", before["identity"])[1]
    command("shell", "am", "force-stop", package)
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        process = subprocess.run(adb + ["shell", "ps", "-p", old_pid], capture_output=True, timeout=10)
        if process.returncode == 1:
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("Non-daemon helper survived its binding process")
    after = probe("ping", "after-client-stop")
    apk = command("shell", "pm", "path", "moe.shizuku.privileged.api").removeprefix("package:")
    abi = command("shell", "getprop", "ro.product.cpu.abi")
    if abi != "x86_64":
        raise RuntimeError("This starter path has only been verified on x86_64")
    starter = apk.rsplit("/", 1)[0] + "/lib/x86_64/libshizuku.so"
    server = command("shell", "pidof", "shizuku_server")
    if not server.isdigit():
        raise RuntimeError("Expected exactly one Shizuku server PID")
    command("shell", "kill", server)
    try:
        time.sleep(1)
        stopped = probe("ping", "shizuku-stopped", success=False)
        if not stopped.get("shizukuUnavailable"):
            raise RuntimeError(f"Wrong unavailable result: {stopped}")
    finally:
        command("shell", starter)
    time.sleep(2)
    restarted = probe("ping", "shizuku-restarted")
    exercise = probe("exercise", "exercise-after-restart")
    result = {
        "helperDiedWithClient": True,
        "before": before,
        "after": after,
        "shizukuStoppedResult": stopped,
        "restarted": restarted,
        "exerciseAfterRestartPassed": exercise["ok"],
    }
    (args.output / "lifecycle.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
