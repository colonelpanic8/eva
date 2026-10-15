#!/usr/bin/env python3
"""Drive EVA Debug's delegated phone calls on a shared Android phone, safely.

See README.md beside this file. Every command that touches the phone holds the exclusive
lock /tmp/eva-phone.lock for its whole sequence (fcntl.flock: released when the process
exits, so it never goes stale). Nothing here reads or prints credentials.

The phone is the owner's daily phone, shared with the owner and with other agents. The rules
encoded here each come from something that went wrong: a stale "lock released" marker file
let one session reinstall EVA under another's live call (hence flock held for the whole
sequence, no marker files); a blind Back key closed EVA and exposed an unrelated app (hence
Back only while EVA's own keyboard is up); an autofill dropdown swallowed a tap meant for EVA
(hence taps resolve EVA's own elements from a fresh UI dump). Scenarios refuse to dial while
any call is live, and hang up their own call if they fail midway.
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import json
import os
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import ClassVar

HERE = Path(__file__).resolve().parent
CALL_AUDIO = HERE.parent
REPO = CALL_AUDIO.parent.parent
BUILD = CALL_AUDIO / "build"
DEMOS = BUILD / "demos"
WORK = BUILD / "phone"
TWILIO = CALL_AUDIO / "twilio"

DEFAULT_SERIAL = "adb-67091FDDJ0007B-YBkFMQ._adb-tls-connect._tcp"
PACKAGE = "com.colonelpanic.eva.debug"
ACTIVITY = "com.colonelpanic.eva.MainActivity"
DIALER = "com.google.android.dialer"
LOCK_PATH = "/tmp/eva-phone.lock"
LOCK_ENV = "EVA_PHONE_LOCK_HELD"
CAPTURE_FLAG = "files/debug-call-audio"
CAPTURE_DIR = "cache/call-audio"
LOG_TAGS = ("EvaCallMonitor:I", "EvaTrace:I", "EvaCellularCall:I", "EvaCallTaskLive:I")
TERMINAL = ("Accomplished", "NotAccomplished", "Uncertain", "Interrupted")


class PhoneError(Exception):
    pass


def run(
    command: list[str], *, check: bool = False, **options
) -> subprocess.CompletedProcess:
    """subprocess.run with the exit status checked only where asked."""
    return subprocess.run(command, check=check, **options)


def log(message: str) -> None:
    print(f"[phone {time.strftime('%H:%M:%S')}] {message}", flush=True)


# ---------------------------------------------------------------- lock


@contextlib.contextmanager
def phone_lock(wait_seconds: float = 600):
    """Holds the shared phone lock for the enclosed sequence; children inherit it via EVA_PHONE_LOCK_HELD."""
    if os.environ.get(LOCK_ENV) == "1":
        yield
        return
    handle = open(LOCK_PATH, "a+")  # noqa: SIM115 - held across the yield, closed in finally
    deadline = time.monotonic() + wait_seconds
    announced = False
    while True:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            if not announced:
                log(f"waiting for {LOCK_PATH} (another session is using the phone)")
                announced = True
            if time.monotonic() > deadline:
                handle.close()
                raise PhoneError(
                    f"{LOCK_PATH} stayed busy for {wait_seconds:.0f} s; another session is using the phone."
                )
            time.sleep(1)
    os.environ[LOCK_ENV] = "1"
    try:
        yield
    finally:
        os.environ.pop(LOCK_ENV, None)
        fcntl.flock(handle, fcntl.LOCK_UN)
        handle.close()


# ---------------------------------------------------------------- adb


class Phone:
    def __init__(self, serial: str, package: str = PACKAGE):
        self.serial = serial
        self.package = package
        self._display: str | None = None
        # Where scenario "shots" steps save screenshots; the run directory while a scenario runs.
        self.shots_dir: Path | None = None

    def adb(
        self, *args: str, check: bool = True, timeout: float = 60, binary: bool = False
    ):
        try:
            result = run(
                ["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout
            )
        except subprocess.TimeoutExpired:
            raise PhoneError(
                f"adb {' '.join(args[:2])} took longer than {timeout:.0f} s; check the wireless debugging link."
            ) from None
        if check and result.returncode != 0:
            raise PhoneError(
                f"adb {' '.join(args[:3])} failed: {result.stderr.decode(errors='replace').strip()}"
            )
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command: str, check: bool = True, timeout: float = 60) -> str:
        return self.adb("shell", command, check=check, timeout=timeout)

    def run_as(self, command: str, binary: bool = False, check: bool = True):
        return self.adb(
            "exec-out",
            f"run-as {self.package} sh -c {shlex.quote(command)}",
            binary=binary,
            check=check,
        )

    # -- state

    def connected(self) -> bool:
        result = run(
            ["adb", "-s", self.serial, "get-state"], capture_output=True, timeout=15
        )
        return result.returncode == 0 and result.stdout.strip() == b"device"

    def ensure_connected(self) -> None:
        """
        Reaches the phone by its wireless-debugging service name. adb lists a phone under that name
        only while its mDNS auto-connection holds; after a reconnect it appears as the advertised
        `ip:port` instead, so that address (resolved from the same service, so still this phone and
        never an emulator) becomes the serial for the rest of the run.
        """
        if self.connected():
            return
        if "_adb-tls-connect" in self.serial:
            services = run(
                ["adb", "mdns", "services"], capture_output=True, text=True, timeout=15
            ).stdout
            for line in services.splitlines():
                if line.startswith(self.serial.split("._adb")[0]):
                    address = line.split()[-1]
                    run(["adb", "connect", address], capture_output=True, timeout=15)
                    name, self.serial = self.serial, address
                    if not self.connected():
                        self.serial = name
                    break
        if not self.connected() and "_adb-tls-connect" in self.serial:
            # mDNS discovery comes and goes; the service name embeds the phone's hardware serial, so
            # an already connected ip:port transport with that serial is the same phone.
            hardware = self.serial.removeprefix("adb-").split("-")[0]
            listed = run(
                ["adb", "devices"], capture_output=True, text=True, timeout=15
            ).stdout
            for transport in [
                line.split()[0]
                for line in listed.splitlines()[1:]
                if line.endswith("\tdevice")
            ]:
                if transport.startswith("emulator-"):
                    continue
                try:
                    serial = run(
                        ["adb", "-s", transport, "shell", "getprop ro.serialno"],
                        capture_output=True,
                        text=True,
                        timeout=15,
                    ).stdout.strip()
                except subprocess.TimeoutExpired:
                    continue
                if serial == hardware:
                    self.serial = transport
                    break
        if not self.connected():
            raise PhoneError(
                f"{self.serial} is not connected; enable wireless debugging and check `adb mdns services`."
            )

    def call_states(self) -> list[int]:
        out = self.shell("dumpsys telephony.registry | grep mCallState")
        states = [int(m) for m in re.findall(r"mCallState=(\d+)", out)]
        if not states:
            raise PhoneError("Could not read the phone's call state.")
        return states

    def require_idle_line(self) -> None:
        states = self.call_states()
        if any(states):
            raise PhoneError(
                f"A call is live on the phone (mCallState={states}); refusing to place another."
            )

    def focus(self) -> str | None:
        out = self.shell("dumpsys window | grep -E 'mCurrentFocus'", check=False)
        match = re.search(r"u0 ([\w.]+)/([\w.$]+)", out)
        return match.group(1) if match else None

    def focus_activity(self) -> str:
        return self.shell(
            "dumpsys window | grep -E 'mCurrentFocus'", check=False
        ).strip()

    def ime_shown(self) -> bool:
        return "mInputShown=true" in self.shell(
            "dumpsys input_method | grep mInputShown", check=False
        )

    def awake(self) -> bool:
        return "mWakefulness=Awake" in self.shell(
            "dumpsys power | grep mWakefulness=", check=False
        )

    def keyguard_locked(self) -> bool:
        out = self.shell(
            "dumpsys window | grep -E 'mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen'",
            check=False,
        )
        return "true" in out

    def idle_seconds(self) -> float:
        out = self.shell("dumpsys power | grep lastUserActivityTime=", check=False)
        match = re.search(
            r"^\s*lastUserActivityTime=\d+ \((\d+) ms ago\)", out, re.MULTILINE
        )
        return int(match.group(1)) / 1000 if match else float("inf")

    def microphone_muted(self) -> bool:
        return "FromApi=true" in self.shell(
            "dumpsys audio | grep 'mic mute'", check=False
        )

    def display_id(self) -> str:
        """The active physical display, so screenshots and recordings follow the fold."""
        if self._display:
            return self._display
        out = self.shell("dumpsys display | grep -m1 mViewports", check=False)
        for viewport in re.findall(r"DisplayViewport\{[^}]*\}", out):
            if "isActive=true" in viewport:
                match = re.search(r"uniqueId='local:(\d+)'", viewport)
                if match:
                    self._display = match.group(1)
                    return self._display
        raise PhoneError("Could not find the active display.")

    # -- screen

    def screenshot(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(
            self.adb("exec-out", f"screencap -p -d {self.display_id()}", binary=True)
        )
        return path

    def nodes(self) -> list[dict]:
        self.shell(
            "rm -f /sdcard/eva-phone-ui.xml; uiautomator dump /sdcard/eva-phone-ui.xml >/dev/null",
            check=False,
        )
        xml = self.adb("exec-out", "cat /sdcard/eva-phone-ui.xml", check=False)
        self.shell("rm -f /sdcard/eva-phone-ui.xml", check=False)
        if not xml.strip().startswith("<?xml"):
            return []
        result = []
        for node in ET.fromstring(xml).iter("node"):
            bounds = [
                int(v) for v in re.findall(r"\d+", node.get("bounds", "[0,0][0,0]"))
            ]
            result.append(
                {
                    "text": node.get("text") or "",
                    "desc": node.get("content-desc") or "",
                    "class": node.get("class") or "",
                    "package": node.get("package") or "",
                    "clickable": node.get("clickable") == "true",
                    "bounds": bounds,
                }
            )
        return result

    def find(self, label: str, exact: bool = True) -> dict | None:
        for node in self.nodes():
            if node["package"] != self.package:
                continue
            for value in (node["text"], node["desc"]):
                if (value == label) if exact else (label in value):
                    x1, y1, x2, y2 = node["bounds"]
                    if x2 > x1 and y2 > y1:
                        return node
        return None

    def tap_node(self, node: dict) -> None:
        x1, y1, x2, y2 = node["bounds"]
        self.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")

    def tap(
        self, label: str, exact: bool = True, scroll: bool = True, required: bool = True
    ) -> bool:
        """Taps an EVA element by its text or description, scrolling the conversation if it is off screen."""
        self.require_eva_foreground()
        for attempt in range(5):
            node = self.find(label, exact)
            if node:
                self.tap_node(node)
                time.sleep(0.8)
                return True
            if not scroll:
                break
            if attempt == 0:
                self.hide_keyboard()
            else:
                self.swipe_up()
        if required:
            raise PhoneError(f"EVA shows no '{label}' to tap.")
        return False

    def swipe_up(self) -> None:
        self.shell("input swipe 1000 1300 1000 900 300")
        time.sleep(0.8)

    def hide_keyboard(self) -> None:
        # Back only while EVA's own keyboard is up: otherwise it would close EVA and expose other apps.
        if self.ime_shown() and self.focus() == self.package:
            self.shell("input keyevent KEYCODE_BACK")
            time.sleep(0.8)

    def type_text(self, text: str) -> None:
        if not text.isascii() or "%" in text:
            raise PhoneError(
                "adb input text supports ASCII without '%'; rephrase the message."
            )
        self.shell("input text " + shlex.quote(text.replace(" ", "%s")))
        time.sleep(0.5)

    # -- EVA

    def require_eva_foreground(self) -> None:
        if self.focus() != self.package:
            raise PhoneError(
                f"EVA Debug is not in the foreground ({self.focus_activity()}); not driving another app."
            )

    def open_eva(self) -> None:
        self.shell("cmd statusbar collapse", check=False)
        self.shell(f"am start -n {self.package}/{ACTIVITY}", check=False)
        for _ in range(10):
            time.sleep(0.5)
            if self.focus() == self.package:
                return
        raise PhoneError(
            f"EVA Debug did not come to the foreground ({self.focus_activity()})."
        )

    def new_conversation(self) -> None:
        self.hide_keyboard()
        self.tap("Open navigation menu", scroll=False)
        self.tap("New conversation", scroll=False)

    def connect_for_text(self) -> None:
        if self.find("Disconnect"):
            return
        self.tap("Connect for text")
        for _ in range(30):
            time.sleep(1)
            if self.find("Disconnect"):
                return
            error = next(
                (
                    n["text"]
                    for n in self.nodes()
                    if "rejected" in n["text"] or "failed" in n["text"].lower()
                ),
                None,
            )
            if error:
                raise PhoneError(f"EVA could not connect: {error}")
        raise PhoneError("EVA did not connect for text within 30 s.")

    def composer(self) -> dict:
        fields = [
            n
            for n in self.nodes()
            if n["package"] == self.package and n["class"] == "android.widget.EditText"
        ]
        fields = [n for n in fields if n["bounds"][2] > n["bounds"][0]]
        if not fields:
            raise PhoneError("EVA's composer is not on screen.")
        return max(fields, key=lambda n: n["bounds"][1])

    def send(self, text: str) -> None:
        self.require_eva_foreground()
        self.tap_node(self.composer())
        time.sleep(0.6)
        self.type_text(text)
        self.tap("Send", scroll=False)
        self.hide_keyboard()

    def monitor_route(self) -> str:
        """The output device of EVA's listen-in playback track, from AudioFlinger's thread table."""
        uid = re.search(
            r"appId=(\d+)",
            self.shell(f"dumpsys package {self.package} | grep -m1 appId="),
        ).group(1)
        device, routes = None, []
        for line in self.shell("dumpsys media.audio_flinger", timeout=30).splitlines():
            if line.startswith("Output thread"):
                device = None
            match = re.match(r"\s+Output devices: \S+ \((\w+)\)", line)
            if match:
                device = match.group(1)
            if re.match(rf"^\s+\d+\s+(yes|no)\s+\d+/\s+{uid}\b", line):
                routes.append(device or "unknown")
        return ", ".join(routes) or "no EVA playback track"

    def bring_eva_back(self) -> None:
        """The dialer takes the screen when a call starts; the call card lives in EVA."""
        if self.focus() == DIALER:
            self.open_eva()
            self.hide_keyboard()


# ---------------------------------------------------------------- call records


@dataclass
class CallTask:
    id: str
    number: str
    state: dict
    leg: dict
    snapshot: dict
    items: list[dict] = field(default_factory=list)
    first_record_millis: int = 0

    @property
    def state_name(self) -> str:
        return (self.state or {}).get("type", "").rsplit(".", 1)[-1]

    @property
    def leg_name(self) -> str:
        return (self.leg or {}).get("type", "").rsplit(".", 1)[-1]

    @property
    def terminal(self) -> bool:
        return self.state_name in TERMINAL

    def summary(self) -> str:
        lines = [
            f"task {self.id}",
            f"  number {self.number}",
            f"  state {self.state_name} {json.dumps({k: v for k, v in self.state.items() if k != 'type'})}",
            f"  leg {self.leg_name} {json.dumps({k: v for k, v in (self.leg or {}).items() if k != 'type'})}",
            f"  listening={self.snapshot.get('listening', False)} floor={self.snapshot.get('floor', 'AGENT')}",
        ]
        for item in self.items:
            at = time.strftime(
                "%H:%M:%S", time.localtime(item.get("createdAtMillis", 0) / 1000)
            )
            lines.append(f"  {at} {item['speaker']:<15} {item['text']}")
        return "\n".join(lines)


# EVA journals each call task as incremental CALL_TASK notices (the task plus only the transcript
# items the thread lacks); reading the database through run-as is how the tool sees exactly what
# EVA recorded, rather than what the screen happens to show.
def call_tasks(phone: Phone) -> list[CallTask]:
    """Folds EVA's incremental CALL_TASK journal records, oldest task first."""
    with tempfile.TemporaryDirectory() as tmp:
        for name in ("eva-actions.db", "eva-actions.db-journal", "eva-actions.db-wal"):
            data = phone.run_as(
                f"cat databases/{name} 2>/dev/null", binary=True, check=False
            )
            if data:
                (Path(tmp) / name).write_bytes(data)
        database = Path(tmp) / "eva-actions.db"
        if not database.exists():
            raise PhoneError(
                "Could not read EVA's database (is the debug app installed?)."
            )
        connection = sqlite3.connect(database)
        rows = connection.execute(
            "select created_at, text from items where notice_kind='CALL_TASK' order by created_at"
        ).fetchall()
        connection.close()
    tasks: dict[str, CallTask] = {}
    for created, text in rows:
        record = json.loads(text)
        snapshot = record.get("task", record)
        task = tasks.get(snapshot["id"])
        if task is None:
            task = CallTask(
                snapshot["id"], snapshot["brief"]["number"], {}, {}, {}, [], created
            )
            tasks[snapshot["id"]] = task
        known = {item["id"] for item in task.items}
        task.items += [
            item for item in snapshot.get("transcript", []) if item["id"] not in known
        ]
        task.state, task.leg, task.snapshot = (
            snapshot.get("state", {}),
            snapshot.get("legState") or {},
            snapshot,
        )
    for task in tasks.values():
        task.items.sort(key=lambda item: item.get("createdAtMillis", 0))
    return sorted(tasks.values(), key=lambda t: t.first_record_millis)


# ---------------------------------------------------------------- demo recording


# Demos are recorded only from debuggable builds and only while the flag file exists, and the
# recorder removes the flag and EVA's audio files when it stops. The screen guard discards the
# video if anything but EVA or the dialer comes to the front, because the owner's other apps and
# notifications must never end up in a recording.
class DemoRecorder:
    """Records the phone screen in segments and EVA's opt-in call audio capture, then muxes them."""

    ALLOWED: ClassVar[set[str]] = {PACKAGE, DIALER}

    def __init__(self, phone: Phone, directory: Path, video: bool = True):
        self.phone = phone
        self.directory = directory
        self.video = video
        self.segments: list[tuple[str, int]] = []
        self.stop_event = threading.Event()
        self.violation: str | None = None
        self.threads: list[threading.Thread] = []

    def start(self) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        self.phone.run_as(f"rm -rf {CAPTURE_DIR}; touch {CAPTURE_FLAG}")
        if self.video:
            self.threads.append(threading.Thread(target=self._record, daemon=True))
            self.threads.append(threading.Thread(target=self._guard, daemon=True))
        for thread in self.threads:
            thread.start()

    def _record(self) -> None:
        index = 0
        while not self.stop_event.is_set():
            remote = f"/sdcard/eva-demo-{os.getpid()}-{index}.mp4"
            started = int(self.phone.shell("date +%s%3N").strip())
            self.segments.append((remote, started))
            run(
                [
                    "adb",
                    "-s",
                    self.phone.serial,
                    "shell",
                    f"screenrecord --display-id {self.phone.display_id()} --time-limit 170 {remote}",
                ],
                capture_output=True,
            )
            index += 1

    def _guard(self) -> None:
        # Recording shows only EVA and the call: anything else in front stops it and its video is discarded.
        while not self.stop_event.wait(1):
            package = self.phone.focus()
            if package and package not in self.ALLOWED:
                self.violation = f"{package} came to the foreground"
                self.stop_event.set()
                self.phone.shell("pkill -INT screenrecord", check=False)

    def stop(self) -> None:
        self.stop_event.set()
        self.phone.shell("pkill -INT screenrecord", check=False)
        for thread in self.threads:
            thread.join(timeout=15)
        time.sleep(1)
        video_parts = []
        for index, (remote, started) in enumerate(self.segments):
            local = self.directory / f"screen-{index}.mp4"
            if self.violation:
                self.phone.shell(f"rm -f {remote}", check=False)
                continue
            data = self.phone.adb("exec-out", f"cat {remote}", binary=True, check=False)
            self.phone.shell(f"rm -f {remote}", check=False)
            if data:
                local.write_bytes(data)
                video_parts.append((local, started))
        audio = self._pull_audio()
        self.phone.run_as(f"rm -rf {CAPTURE_DIR}; rm -f {CAPTURE_FLAG}", check=False)
        self.video_parts, self.audio = video_parts, audio

    def _pull_audio(self) -> dict[str, tuple[Path, int]]:
        legs = self.phone.run_as(f"ls {CAPTURE_DIR} 2>/dev/null", check=False).split()
        streams: dict[str, tuple[Path, int]] = {}
        for leg in legs[-1:]:
            for stream in ("downlink", "uplink"):
                start = self.phone.run_as(
                    f"cat {CAPTURE_DIR}/{leg}/{stream}.start 2>/dev/null", check=False
                ).strip()
                pcm = self.phone.run_as(
                    f"cat {CAPTURE_DIR}/{leg}/{stream}.pcm 2>/dev/null",
                    binary=True,
                    check=False,
                )
                if start and pcm:
                    path = self.directory / f"{stream}.pcm"
                    path.write_bytes(pcm)
                    streams[stream] = (path, int(start))
        return streams

    def mux(self, output: Path) -> Path | None:
        """Screen video with the call audio mixed in: the other party as the agent heard it, plus the agent."""
        ffmpeg = ffmpeg_command()
        if not self.video_parts and not self.audio:
            return None
        origin = min(
            [s for _, s in self.video_parts] + [s for _, s in self.audio.values()]
        )
        inputs: list[str] = []
        filters: list[str] = []
        audio_labels = []
        for name, (path, start) in sorted(self.audio.items()):
            wav = self.directory / f"{name}.wav"
            run(
                [
                    *ffmpeg,
                    "-y",
                    "-loglevel",
                    "error",
                    "-f",
                    "s16le",
                    "-ar",
                    "24000",
                    "-ac",
                    "1",
                    "-i",
                    str(path),
                    str(wav),
                ],
                check=True,
            )
            path.unlink()
            inputs += ["-i", str(wav)]
            delay = start - origin
            audio_labels.append(f"[a{len(audio_labels)}]")
            filters.append(
                f"[{len(inputs) // 2 - 1}:a]adelay={delay}|{delay}{audio_labels[-1]}"
            )
        video_index = None
        if self.video_parts:
            concat = self.directory / "screen.txt"
            concat.write_text(
                "".join(f"file '{p.name}'\n" for p, _ in self.video_parts)
            )
            video_index = len(inputs) // 2
            inputs += ["-f", "concat", "-safe", "0", "-i", str(concat)]
            video_delay = (self.video_parts[0][1] - origin) / 1000
        if audio_labels:
            filters.append(
                f"{''.join(audio_labels)}amix=inputs={len(audio_labels)}:normalize=0[mix]"
            )
        command = [*ffmpeg, "-y", "-loglevel", "error"]
        if video_index is not None and video_delay > 0:
            # Shift the screen to the audio's clock by holding its first frame.
            filters.append(
                f"[{video_index}:v]tpad=start_duration={video_delay}:start_mode=clone[v]"
            )
        elif video_index is not None:
            filters.append(f"[{video_index}:v]null[v]")
        command += inputs + ["-filter_complex", ";".join(filters)]
        if video_index is not None:
            command += [
                "-map",
                "[v]",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "28",
                "-pix_fmt",
                "yuv420p",
            ]
        if audio_labels:
            command += ["-map", "[mix]", "-c:a", "aac", "-b:a", "96k"]
        output = output.with_suffix(".mp4" if video_index is not None else ".m4a")
        command += [str(output)]
        run(command, check=True)
        return output


def ffmpeg_command() -> list[str]:
    if shutil.which("ffmpeg"):
        return ["ffmpeg"]
    return ["nix", "run", "nixpkgs#ffmpeg", "--"]


# ---------------------------------------------------------------- scenarios


@dataclass
class Step:
    """An owner action on the call card, run once the call has been connected for `at` seconds."""

    at: float
    action: str  # listen, stop-listening, whisper, route, shots, hang-up
    text: str = ""


@dataclass
class Scenario:
    name: str
    description: str
    prompt: str
    steps: list[Step] = field(default_factory=list)
    minutes: float = 6
    twilio: bool = False
    twilio_scenario: str | None = None
    expect: str = "Accomplished"


NIST = "3034997111"
HOTLINE = "+15592493930"
HOTLINE_GOAL = (
    "Start a phone call task to +15592493930, a test company hotline that I own for testing. Goal: find out the "
    "company business hours from a representative. When the phone menu offers billing, press 1 on the keypad using "
    "the dtmf tool, not speech. When asked for the account holder name and account number, say: Ivan Malison, "
    "account number 4417. Stay silent through hold music. When the representative answers, ask for their business "
    "hours. If asked whether there is anything else, say no thank you and goodbye. Then finish with the hours as "
    "evidence and hang up."
)

SCENARIOS = {
    s.name: s
    for s in [
        Scenario(
            "nist-silent",
            "NIST time line: the agent stays silent through the ticks, captures one announcement, finishes and hangs up.",
            f"Start a phone call task to {NIST}. It is the NIST time recording, which cannot hear you. Goal: listen until "
            "you hear one full spoken time announcement, then finish with the announced time as evidence and hang up. "
            "The agent must not speak at all during the call.",
            minutes=3,
        ),
        Scenario(
            "nist-listen",
            "NIST time line: the agent speaks on a whisper with listen-in off (ungated), then on (gated, no self-echo); "
            "screenshots catch its live captions.",
            f"Start a call task to {NIST}, the NIST time recording that cannot hear you. Goal: stay silent and keep "
            "listening until the owner ends the call from the call card. Do not hang up or finish on your own. Speak "
            "only if the owner whispers an instruction to say something.",
            steps=[
                # Listen-in is off by default: this utterance must pass through ungated.
                Step(
                    10,
                    "whisper",
                    "Please read this aloud once, then stay silent: One, two, three, four, five, six, "
                    "seven, eight, nine, ten. The weather today is sunny with a light breeze from the west.",
                ),
                Step(10, "shots", "caption-listen-off"),
                Step(40, "listen"),
                Step(
                    42,
                    "whisper",
                    "Now say once, slowly: Thank you for your help, have a nice evening. Then stay silent.",
                ),
                Step(42, "shots", "caption-listen-on"),
                Step(70, "hang-up"),
            ],
            minutes=3,
            expect="Interrupted",
        ),
        Scenario(
            "nist-route",
            "NIST time line: reports listen-in's output device after each whisper; switch to speaker by hand to test routing.",
            f"Start a call task to {NIST}, the NIST time recording that cannot hear you. Goal: stay silent and keep "
            "listening until the owner ends the call from the call card. Do not hang up or finish on your own. Speak "
            "only if the owner whispers an instruction to say something.",
            steps=[
                Step(
                    10,
                    "whisper",
                    "Say once: this is the first route test. Then stay silent.",
                ),
                Step(14, "route", "earpiece unless switched"),
                Step(
                    35,
                    "whisper",
                    "Say once: this is the second route test. Then stay silent.",
                ),
                Step(
                    39,
                    "route",
                    "switch the call to speaker from its notification before this",
                ),
                Step(
                    60,
                    "whisper",
                    "Say once: this is the third route test. Then stay silent.",
                ),
                Step(64, "route", "switch back before this"),
                Step(70, "hang-up"),
            ],
            minutes=3,
            expect="Interrupted",
        ),
        Scenario(
            "twilio-hotline",
            "Twilio test hotline: keypad menu, spoken name and account, silent hold, asks the hours, finishes.",
            HOTLINE_GOAL,
            minutes=5,
            twilio=True,
        ),
        Scenario(
            "twilio-credential",
            "Twilio test hotline with the account number entered from the saved keypad credential test/account.",
            "Start a phone call task to +15592493930, a test company hotline that I own for testing, and allow it the "
            "call keypad credential test/account. Goal: find out the company business hours from a representative. "
            "When the phone menu offers billing, press 1 on the keypad using the dtmf tool. When asked for the account "
            "holder name and account number, enter the account number on the keypad with the dtmf tool as the saved "
            "credential test/account followed by #, without saying it; do not say the name. Stay silent through hold "
            "music. When the representative answers, ask for their business hours. If asked whether there is anything "
            "else, say no thank you and goodbye. Then finish with the hours as evidence and hang up.",
            minutes=5,
            twilio=True,
            twilio_scenario=str(TWILIO / "scenario-keypad-account.json"),
        ),
    ]
}


# Two minutes of no input is the heuristic for "the owner is not using the phone right now"; the
# tool's own taps count as input too, so a scenario right after another passes as expected.
def preflight(phone: Phone, take_screen: bool) -> None:
    phone.ensure_connected()
    phone.require_idle_line()
    if not phone.awake():
        raise PhoneError(
            "The screen is off; wake it (or use the locked scenario) before driving EVA."
        )
    if phone.keyguard_locked():
        raise PhoneError("The phone is locked; unlock it before driving EVA.")
    package = phone.focus()
    if package not in (PACKAGE, None) and not take_screen:
        idle = phone.idle_seconds()
        if idle < 120:
            raise PhoneError(
                f"{package} is in the foreground and the phone was used {idle:.0f} s ago; the owner may be using it. "
                "Retry later, or pass --take-screen once you know it is free."
            )
        log(
            f"{package} is in front but the phone has been idle {idle:.0f} s; opening EVA over it"
        )


def twilio(*args: str) -> str:
    result = run(
        ["direnv", "exec", str(REPO), str(TWILIO / args[0]), *args[1:]],
        capture_output=True,
        text=True,
        cwd=REPO,
    )
    output = "\n".join(
        line
        for line in (result.stdout + result.stderr).splitlines()
        if not line.startswith("direnv:")
    )
    if result.returncode != 0:
        raise PhoneError(f"twilio {args[0]} failed:\n{output}")
    return output


def card_action(phone: Phone, step: Step) -> str | None:
    """Runs one owner step; returns a note for the report when the step measures something."""
    if step.action == "route":
        return f"Listen-in playback route at {step.at:.0f} s: {phone.monitor_route()} ({step.text})"
    if step.action == "shots":
        # A burst, because the agent starts talking a moment after a whisper and speaks for seconds.
        directory = phone.shots_dir or WORK
        for index in range(6):
            phone.screenshot(directory / f"{step.text}-{index}.png")
            time.sleep(1.5)
        return f"Screenshots {step.text}-0..5.png"
    phone.bring_eva_back()
    phone.hide_keyboard()
    if step.action == "listen":
        if not phone.find("Stop listening"):
            phone.tap("Listen in")
    elif step.action == "stop-listening":
        if phone.find("Stop listening"):
            phone.tap("Stop listening")
    elif step.action == "whisper":
        if not phone.find("Whispering to the call agent", exact=False):
            phone.tap("Whisper")
        phone.send(step.text)
    elif step.action == "hang-up":
        phone.tap("Hang up")
    else:
        raise PhoneError(f"Unknown step {step.action}")
    return None


def run_scenario(
    phone: Phone,
    scenario: Scenario,
    record: bool,
    install: bool,
    take_screen: bool,
    name: str | None,
) -> int:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    directory = (
        DEMOS / f"{stamp}-{name or scenario.name}"
        if record
        else WORK / f"{stamp}-{scenario.name}"
    )
    directory.mkdir(parents=True, exist_ok=True)
    phone.shots_dir = directory
    with phone_lock():
        preflight(phone, take_screen)
        if install:
            apk = REPO / "app/build/outputs/apk/debug/app-debug.apk"
            log(f"installing {apk.relative_to(REPO)}")
            phone.adb("install", "-r", str(apk), timeout=900)
        deployed = False
        recorder = None
        task = None
        notes: list[str] = []
        try:
            if scenario.twilio_scenario:
                log(f"deploying hotline scenario {Path(scenario.twilio_scenario).name}")
                twilio("setup", "--scenario", scenario.twilio_scenario)
                deployed = True
            phone.open_eva()
            phone.new_conversation()
            phone.connect_for_text()
            before = {t.id for t in call_tasks(phone)}
            # Only from now on, and without clearing the shared device's buffer.
            logcat = subprocess.Popen(
                [
                    "adb",
                    "-s",
                    phone.serial,
                    "logcat",
                    "-v",
                    "time",
                    "-T",
                    "1",
                    *LOG_TAGS,
                    "*:S",
                ],
                stdout=(directory / "logcat.txt").open("w"),
                stderr=subprocess.DEVNULL,
            )
            if record:
                recorder = DemoRecorder(phone, directory)
                recorder.start()
                time.sleep(1.5)
            log(f"asking EVA: {scenario.prompt[:100]}...")
            phone.send(scenario.prompt)
            task, connected_at, done_steps = None, None, 0
            deadline = time.monotonic() + scenario.minutes * 60
            mic_samples: list[bool] = []
            while time.monotonic() < deadline:
                time.sleep(3)
                if recorder and recorder.violation:
                    notes.append(
                        f"Recording stopped and discarded: {recorder.violation}."
                    )
                    recorder = None
                tasks = [t for t in call_tasks(phone) if t.id not in before]
                if not tasks:
                    continue
                task = tasks[-1]
                if task.leg_name == "Connected":
                    if connected_at is None:
                        connected_at = time.monotonic()
                        log(
                            f"connected; listening={task.snapshot.get('listening', False)}"
                        )
                    phone.bring_eva_back()
                    mic_samples.append(phone.microphone_muted())
                    while (
                        done_steps < len(scenario.steps)
                        and time.monotonic() - connected_at
                        >= scenario.steps[done_steps].at
                    ):
                        step = scenario.steps[done_steps]
                        log(f"card: {step.action} {step.text[:60]}")
                        note = card_action(phone, step)
                        if note:
                            log(note)
                            notes.append(note)
                        done_steps += 1
                if task.terminal:
                    break
            else:
                notes.append(
                    f"No terminal state within {scenario.minutes} minutes; check the phone."
                )
            time.sleep(4)
            if task:
                task = next((t for t in call_tasks(phone) if t.id == task.id), task)
            if mic_samples:
                notes.append(
                    f"Owner microphone muted in {sum(mic_samples)} of {len(mic_samples)} samples while connected."
                )
            if record and phone.focus() == PACKAGE:
                phone.hide_keyboard()
                phone.screenshot(directory / "final.png")
        except BaseException:
            # Never leave a call this run placed running unattended.
            live = (
                [t for t in call_tasks(phone) if t.id not in before and not t.terminal]
                if "before" in locals()
                else []
            )
            if live:
                log("scenario failed with its call live; hanging up from the call card")
                with contextlib.suppress(Exception):
                    phone.open_eva()
                    card_action(phone, Step(0, "hang-up"))
            raise
        finally:
            if "logcat" in locals():
                logcat.terminate()
            if recorder:
                recorder.stop()
            if deployed:
                log("restoring the default hotline scenario")
                try:
                    twilio("setup")
                except PhoneError as error:
                    notes.append(f"RESTORE FAILED, run `twilio/setup` by hand: {error}")
        verify = None
        if scenario.twilio:
            time.sleep(5)
            verify = twilio("verify")
            (directory / "twilio-verify.txt").write_text(verify)
        output = recorder.mux(directory / "demo") if recorder else None
        summary = (
            task.summary()
            if task
            else "No call task was created; see EVA's reply on the phone."
        )
        (directory / "transcript.txt").write_text(summary + "\n")
        if task:
            (directory / "task.json").write_text(json.dumps(task.snapshot, indent=2))
        print(summary)
        if verify:
            print(verify)
        for note in notes:
            print("note:", note)
        passed = bool(task) and task.state_name == scenario.expect
        if record:
            index_demo(directory, scenario, task, output, verify, notes, passed)
        print(
            f"{'PASS' if passed else 'FAIL'}: {scenario.name} ended {task.state_name if task else 'without a task'} (expected {scenario.expect}); files in {directory}"
        )
        return 0 if passed else 1


def index_demo(
    directory: Path,
    scenario: Scenario,
    task: CallTask | None,
    output: Path | None,
    verify: str | None,
    notes: list[str],
    passed: bool,
) -> None:
    call_sid = re.search(r"CallSid: (CA\w+)", verify or "")
    entry = {
        "demo": directory.name,
        "scenario": scenario.name,
        "description": scenario.description,
        "recorded": time.strftime("%Y-%m-%d %H:%M"),
        "video": output.name if output else None,
        "outcome": task.state_name if task else None,
        "leg": task.leg_name if task else None,
        "passed": passed,
        "twilioCallSid": call_sid.group(1) if call_sid else None,
        "notes": notes,
    }
    index = DEMOS / "index.json"
    entries = json.loads(index.read_text()) if index.exists() else []
    entries.append(entry)
    index.write_text(json.dumps(entries, indent=2) + "\n")
    lines = [
        "# EVA call demos",
        "",
        "Local recordings from `phone demo`; not in git. Newest last.",
        "",
    ]
    for e in entries:
        lines.append(f"## {e['demo']}")
        lines.append(f"- {e['description']}")
        lines.append(
            f"- Recorded {e['recorded']}; outcome {e['outcome']} (leg {e['leg']}); {'pass' if e['passed'] else 'FAIL'}"
        )
        if e["video"]:
            lines.append(
                f"- Video: `{e['demo']}/{e['video']}`; transcript: `{e['demo']}/transcript.txt`"
            )
        if e["twilioCallSid"]:
            lines.append(
                f"- Twilio CallSid {e['twilioCallSid']}; Gather evidence in `{e['demo']}/twilio-verify.txt`"
            )
        lines += [f"- {n}" for n in e["notes"]]
        lines.append("")
    (DEMOS / "index.md").write_text("\n".join(lines))


# ---------------------------------------------------------------- locked phone


def run_locked(
    phone: Phone, number: str, goal: str, minutes: int, record_audio: bool
) -> int:
    """Places a call task with the screen off through the CallTaskLiveTest harness (no UI)."""
    test_apk = (
        REPO / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    )
    if not test_apk.exists():
        raise PhoneError(
            f"Build {test_apk.relative_to(REPO)} first: ./gradlew :app:assembleDebugAndroidTest"
        )
    stamp = time.strftime("%Y%m%d-%H%M%S")
    directory = (DEMOS if record_audio else WORK) / f"{stamp}-locked"
    directory.mkdir(parents=True, exist_ok=True)
    with phone_lock():
        phone.ensure_connected()
        phone.require_idle_line()
        package = phone.focus()
        if (
            phone.awake()
            and package not in (PACKAGE, None)
            and phone.idle_seconds() < 120
        ):
            raise PhoneError(
                f"{package} was used {phone.idle_seconds():.0f} s ago; not locking the owner's screen."
            )
        phone.adb("install", "-r", "-t", str(test_apk), timeout=900)
        for _ in range(3):
            phone.shell("input keyevent KEYCODE_SLEEP")
            time.sleep(2)
            if not phone.awake():
                break
        else:
            raise PhoneError("The screen did not turn off.")
        recorder = DemoRecorder(phone, directory, video=False) if record_audio else None
        if recorder:
            recorder.start()
        log("screen off; running CallTaskLiveTest")
        awake_samples: list[bool] = []
        sampling = threading.Event()

        def sample_screen() -> None:
            while not sampling.wait(5):
                awake_samples.append(phone.awake())

        sampler = threading.Thread(target=sample_screen, daemon=True)
        sampler.start()
        logcat = subprocess.Popen(
            [
                "adb",
                "-s",
                phone.serial,
                "logcat",
                "-v",
                "time",
                "-T",
                "1",
                *LOG_TAGS,
                "*:S",
            ],
            stdout=(directory / "logcat.txt").open("w"),
            stderr=subprocess.DEVNULL,
        )
        instrument = phone.shell(
            "am instrument -w -e class com.colonelpanic.eva.call.CallTaskLiveTest "
            f"-e evaCallTaskNumber {shlex.quote(number)} -e evaCallTaskGoal {shlex.quote(goal)} -e evaCallTaskMinutes {minutes} "
            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner",
            check=False,
            timeout=minutes * 60 + 120,
        )
        sampling.set()
        sampler.join(timeout=10)
        logcat.terminate()
        time.sleep(1)
        log_text = (directory / "logcat.txt").read_text()
        if recorder:
            recorder.stop()
        phone.shell("input keyevent KEYCODE_WAKEUP")
        (directory / "instrument.txt").write_text(instrument)
        transcript = "\n".join(
            line for line in log_text.splitlines() if "EvaCallTaskLive" in line
        )
        (directory / "transcript.txt").write_text(transcript + "\n")
        output = recorder.mux(directory / "demo") if recorder else None
        print(
            instrument.strip().splitlines()[-1]
            if instrument.strip()
            else "(no instrumentation output)"
        )
        print(transcript)
        print(
            f"screen awake in {sum(awake_samples)} of {len(awake_samples)} samples during the call; audio: {output}"
        )
        return 0 if "OK (1 test)" in instrument else 1


# ---------------------------------------------------------------- CLI


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="phone",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--serial",
        default=os.environ.get("EVA_PHONE_SERIAL", DEFAULT_SERIAL),
        help="adb serial of the phone (never the emulator)",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="call state, foreground app, screen, lock")
    hold = commands.add_parser(
        "hold",
        help="hold the phone lock until interrupted (for multi-step manual sessions)",
    )
    hold.add_argument("--seconds", type=float, default=4 * 3600)
    lock = commands.add_parser(
        "lock", help="run a command while holding the phone lock"
    )
    lock.add_argument("argv", nargs=argparse.REMAINDER)
    shot = commands.add_parser("shot", help="screenshot the active display")
    shot.add_argument("name")
    commands.add_parser("ui", help="list EVA's on-screen elements")
    tap = commands.add_parser("tap", help="tap an EVA element by its text")
    tap.add_argument("label")
    ask = commands.add_parser(
        "ask", help="type and send a message in EVA (optionally in a new conversation)"
    )
    ask.add_argument("text")
    ask.add_argument("--new", action="store_true")
    card = commands.add_parser(
        "card",
        help="run a call card step: listen, stop-listening, whisper TEXT, hang-up, route",
    )
    card.add_argument("action")
    card.add_argument("text", nargs="?", default="")
    calls = commands.add_parser(
        "calls", help="show recent call tasks and transcripts from EVA's journal"
    )
    calls.add_argument("-n", type=int, default=1)
    for name, help_text in (
        ("run", "run a scenario"),
        ("demo", "run a scenario and record a demo"),
    ):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("scenario", choices=sorted(SCENARIOS))
        command.add_argument(
            "--install",
            action="store_true",
            help="install app/build/outputs/apk/debug/app-debug.apk first",
        )
        command.add_argument(
            "--take-screen",
            action="store_true",
            help="open EVA even if another app was used recently",
        )
        command.add_argument("--name", help="demo directory suffix")
    locked = commands.add_parser(
        "locked", help="place a call task with the screen off (CallTaskLiveTest)"
    )
    locked.add_argument("--number", default=NIST)
    locked.add_argument(
        "--goal",
        default="Listen silently to the NIST time recording until one full spoken time announcement, then finish with "
        "the announced time as evidence and hang up. Do not speak.",
    )
    locked.add_argument("--minutes", type=int, default=3)
    locked.add_argument(
        "--record", action="store_true", help="save the call audio as a demo"
    )
    commands.add_parser("scenarios", help="list scenarios")
    args = parser.parse_args()
    if args.serial.startswith("emulator-"):
        raise PhoneError(
            "This tool drives the real phone; pass its serial, not an emulator."
        )
    phone = Phone(args.serial)

    if args.command == "scenarios":
        for scenario in SCENARIOS.values():
            print(f"{scenario.name}: {scenario.description}")
        return 0
    if args.command == "status":
        phone.ensure_connected()
        busy = run(["flock", "-n", LOCK_PATH, "true"]).returncode != 0
        print(
            f"serial {phone.serial}\ncall states {phone.call_states()}\nforeground {phone.focus_activity()}"
        )
        print(
            f"awake {phone.awake()} keyguard {phone.keyguard_locked()} keyboard {phone.ime_shown()} idle {phone.idle_seconds():.0f} s"
        )
        print(
            f"display {phone.display_id()} lock {'held by another session' if busy else 'free'}"
        )
        return 0
    if args.command == "hold":
        with phone_lock():
            print("ACQUIRED", flush=True)
            time.sleep(args.seconds)
        return 0
    if args.command == "lock":
        with phone_lock():
            return run(
                args.argv[1:] if args.argv[:1] == ["--"] else args.argv
            ).returncode
    if args.command in ("run", "demo"):
        return run_scenario(
            phone,
            SCENARIOS[args.scenario],
            args.command == "demo",
            args.install,
            args.take_screen,
            args.name,
        )
    if args.command == "locked":
        return run_locked(phone, args.number, args.goal, args.minutes, args.record)
    with phone_lock():
        phone.ensure_connected()
        if args.command == "shot":
            print(phone.screenshot(WORK / f"{args.name}.png"))
        elif args.command == "ui":
            for node in phone.nodes():
                label = node["text"] or node["desc"]
                if label or node["clickable"]:
                    print(
                        f"{label[:90]!r} {node['bounds']} {'clickable' if node['clickable'] else ''} {node['package']}"
                    )
        elif args.command == "tap":
            phone.tap(args.label)
        elif args.command == "ask":
            phone.open_eva()
            if args.new:
                phone.new_conversation()
            phone.connect_for_text()
            phone.send(args.text)
        elif args.command == "card":
            note = card_action(phone, Step(0, args.action, args.text))
            if note:
                print(note)
        elif args.command == "calls":
            for task in call_tasks(phone)[-args.n :]:
                print(task.summary())
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PhoneError as error:
        print(f"phone: {error}", file=sys.stderr)
        sys.exit(2)
