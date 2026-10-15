"""What the emulated device plays, over the emulator's gRPC controller.

Emulator 37.1.11 crashed both times its injectAudio (microphone) RPC was called, at 16 and
48 kHz, so the far end's microphone is not driven from here.

Needs grpcio and grpcio-tools (emulator.nix's python); the controller's stubs are generated
from the running emulator's emulator_controller.proto into a temporary directory.
"""

import importlib
import os
import sys
import tempfile
import time
import wave
from pathlib import Path

RATE = 48_000


def running():
    """Discovery entries ({key: value}) of the emulators running for this user."""
    root = Path(os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}") / "avd/running"
    return [dict(line.split("=", 1) for line in ini.read_text().splitlines() if "=" in line) for ini in root.glob("pid_*.ini")]


def discovery(serial):
    """The running emulator's discovery entry for adb serial emulator-<port>."""
    port = serial.removeprefix("emulator-")
    for entry in running():
        if entry.get("port.serial") == port:
            return entry
    sys.exit(f"No running emulator {serial}; see `far_end.py status`.")


def controller(serial):
    import grpc
    from grpc_tools import protoc

    entry = discovery(serial)
    proto = Path(entry["grpc.allowlist"]).parent / "emulator_controller.proto"
    out = Path(tempfile.mkdtemp(prefix="eva-emulator-grpc-"))
    include = Path(protoc.__file__).parent / "_proto"
    if protoc.main(["protoc", f"-I{proto.parent}", f"-I{include}", f"--python_out={out}", f"--grpc_python_out={out}", str(proto)]) != 0:
        sys.exit(f"Could not compile {proto}.")
    sys.path.insert(0, str(out))
    messages = importlib.import_module("emulator_controller_pb2")
    services = importlib.import_module("emulator_controller_pb2_grpc")
    channel = grpc.insecure_channel(f"127.0.0.1:{entry['grpc.port']}")
    metadata = [("authorization", f"Bearer {entry['grpc.token']}")] if "grpc.token" in entry else []
    return messages, services.EmulatorControllerStub(channel), metadata


def record(serial, out, seconds):
    """
    Writes what the emulated device plays during the next [seconds] as 48 kHz mono 16-bit WAV.
    The emulator sends packets only while the device plays, so gaps are filled with silence
    from the packets' capture timestamps and the file spans the whole wall-clock window.
    """
    messages, stub, metadata = controller(serial)
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    fmt = messages.AudioFormat(samplingRate=RATE, channels=messages.AudioFormat.Mono, format=messages.AudioFormat.AUD_FMT_S16)
    start = time.time()
    written = 0
    with wave.open(str(out), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        try:
            for packet in stub.streamAudio(fmt, metadata=metadata, timeout=seconds):
                samples = len(packet.audio) // 2
                at = int((packet.timestamp / 1e6 - start) * RATE) - samples
                if at > written:
                    wav.writeframes(bytes(2 * (at - written)))
                    written = at
                wav.writeframes(packet.audio)
                written += samples
        except Exception as error:  # the deadline ends the stream
            if "DEADLINE_EXCEEDED" not in str(error):
                raise
        wav.writeframes(bytes(2 * max(0, int(seconds * RATE) - written)))
