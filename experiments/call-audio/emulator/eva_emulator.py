#!/usr/bin/env python3
"""EVA under test on an Android emulator: boot, install, configure the Twilio route, drive call
tasks to the Twilio hotline, collect evidence, and record demos.

Every command targets the emulator recorded by `up` (or --serial) and refuses anything that is not
an emulator, so it cannot touch a physical phone. Run with emulator.nix's python for `record` and
`demo`, which read the emulator's audio over its gRPC controller.
"""

import argparse
import datetime as dt
import json
import os
import random
import re
import shlex
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import card_test

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CALL_AUDIO = HERE.parent
START = ROOT / "experiments/device-control/start-emulator.py"
IMAGE = "sdk/system-images/android-36/google_apis_playstore/x86_64"
AVD_HOME = Path(
    os.environ.get("EVA_TEST_AVD", Path.home() / ".local/share/eva/eva-under-test")
)
STATE = AVD_HOME / "state.json"
PACKAGE = "com.colonelpanic.eva.debug"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
DEMOS = CALL_AUDIO / "build/demos"
HOTLINE = "+15592493930"
PASS_ENTRY = os.environ.get("TWILIO_VOICE_PASS_ENTRY", "twilio.com/eva-voice-sdk")
# The owner's phones and the WhatsApp far end; never driven from here.
PROTECTED = ("adb-67091FDDJ0007B", "192.168.0.160", "emulator-5556")
# Follows experiments/call-audio/twilio/scenario.json: a keypad menu, a spoken verification, hold,
# the representative's hours, and a polite exit.
GOAL = (
    "Use the twilio call route to call the Test Company hotline at +1 559-249-3930. At its menu press 1 "
    "on the keypad for billing. When asked for the account holder's name and account number, say Ivan "
    "Malison, account number 4417. Wait quietly on hold. When the representative answers, ask for the "
    "company's hours. When asked if there is anything else, say no, thank you, goodbye, and hang up. This "
    "is an owner-approved test call; make no commitments."
)
WINDOW_ENV = {
    "WAYLAND_DISPLAY": "wayland-1",
    "DISPLAY": ":0",
    "XDG_RUNTIME_DIR": "/run/user/1000",
}


def fail(message):
    sys.exit(f"eva_emulator: {message}")


def free_port():
    for port in random.sample(range(20_000, 40_000), 50):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    fail("no free port for the gRPC controller")


def serial_of(args):
    serial = getattr(args, "serial", None) or (
        json.loads(STATE.read_text())["serial"] if STATE.exists() else None
    )
    if not serial:
        fail(f"no emulator recorded in {STATE}; run `up` first or pass --serial")
    if any(serial.startswith(p) for p in PROTECTED):
        fail(
            f"{serial} is protected (the owner's phone or the WhatsApp far end); refusing"
        )
    if not serial.startswith("emulator-"):
        fail(f"{serial} is not an emulator serial; refusing")
    return serial


def adb(serial, *command, check=True, text=True, input=None):
    result = subprocess.run(
        ["adb", "-s", serial, *command],
        capture_output=True,
        text=text,
        input=input,
        check=False,
    )
    if check and result.returncode:
        fail(
            f"adb {' '.join(command[:3])} failed: {(result.stderr or result.stdout).strip()}"
        )
    return result.stdout


def require_emulator(serial):
    if adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        fail(f"{serial} is not an emulator; refusing")


def up(args):
    AVD_HOME.mkdir(parents=True, exist_ok=True)
    grpc_port = free_port()
    env = dict(os.environ, **({} if args.headless else WINDOW_ENV))
    command = [
        sys.executable,
        str(START),
        "--emulator",
        str(args.sdk / "bin/emulator"),
        "--image",
        str(args.sdk / IMAGE),
        "--avd-dir",
        str(AVD_HOME),
        "--audio",
        "--grpc-port",
        str(grpc_port),
        "--ram-mb",
        "4096",
        "--data-gb",
        "8",
    ]
    if not args.headless:
        command += ["--window", "--gpu", "host"]
    process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, text=True)
    serial = None
    for line in process.stdout:
        print(line, end="", flush=True)
        if line.startswith("Serial: "):
            serial = line.split()[1]
            STATE.write_text(
                json.dumps(
                    {"serial": serial, "grpcPort": grpc_port, "pid": process.pid}
                )
                + "\n"
            )
            break
    signal.signal(signal.SIGTERM, lambda *_: process.terminate())
    for line in process.stdout:
        print(line, end="", flush=True)
    code = process.wait()
    if (
        serial
        and STATE.exists()
        and json.loads(STATE.read_text()).get("serial") == serial
    ):
        STATE.unlink()
    sys.exit(code)


def wait_boot(args):
    serial = serial_of(args)
    subprocess.run(
        ["adb", "-s", serial, "wait-for-device"], check=True, timeout=args.timeout
    )
    deadline = time.time() + args.timeout
    while (
        adb(serial, "shell", "getprop", "sys.boot_completed", check=False).strip()
        != "1"
    ):
        if time.time() > deadline:
            fail(f"{serial} did not finish booting within {args.timeout} s")
        time.sleep(2)
    require_emulator(serial)
    adb(serial, "shell", "settings", "put", "global", "window_animation_scale", "0")
    adb(serial, "shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb(serial, "shell", "settings", "put", "global", "animator_duration_scale", "0")
    adb(serial, "shell", "svc", "power", "stayon", "true")
    # Gboard otherwise opens a stylus tutorial over the form on a field tap and swallows typed input.
    adb(serial, "shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0")
    print(f"{serial} booted")


def install(args):
    serial = serial_of(args)
    require_emulator(serial)
    if not args.apk.is_file():
        fail(f"{args.apk} is missing; build it with `just build`")
    print(adb(serial, "install", "-r", "-g", str(args.apk)).strip())
    for permission in (
        "RECORD_AUDIO",
        "POST_NOTIFICATIONS",
        "READ_CONTACTS",
        "CALL_PHONE",
    ):
        adb(
            serial,
            "shell",
            "pm",
            "grant",
            PACKAGE,
            f"android.permission.{permission}",
            check=False,
        )
    adb(
        serial,
        "shell",
        "dumpsys",
        "deviceidle",
        "whitelist",
        f"+{PACKAGE}",
        check=False,
    )
    print(f"EVA Debug installed on {serial}")


# --- Driving EVA's UI -------------------------------------------------------------------------


def screen(serial):
    """The current UI hierarchy as a list of nodes: text, description, class, whether editable, and center."""
    for _ in range(3):
        dump = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        start = dump.find("<?xml")
        end = dump.rfind("</hierarchy>")
        if start >= 0 and end > start:
            root = ET.fromstring(dump[start : end + len("</hierarchy>")])
            nodes = []

            # Compose puts a switch's description on a child of the checkable node.
            def walk(node, checked, nodes=nodes):
                if node.get("checkable") == "true":
                    checked = node.get("checked") == "true"
                x1, y1, x2, y2 = map(
                    int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]"))
                )
                nodes.append(_node(node, x1, y1, x2, y2, checked))
                for child in node.findall("node"):
                    walk(child, checked)

            for node in root.findall("node"):
                walk(node, False)
            return nodes
        time.sleep(1)
    fail("could not read the emulator's screen with uiautomator")


def _node(node, x1, y1, x2, y2, checked):
    return {
        "text": node.get("text", ""),
        "desc": node.get("content-desc", ""),
        "class": node.get("class", ""),
        "editable": node.get("class", "").endswith("EditText"),
        "scrollable": node.get("scrollable") == "true",
        "checked": checked,
        "center": ((x1 + x2) // 2, (y1 + y2) // 2),
        "bounds": (x1, y1, x2, y2),
    }


def find(serial, pattern, timeout=10, editable=None):
    """The first node whose text or description matches [pattern], waiting up to [timeout] s."""
    regex = re.compile(pattern, re.IGNORECASE)
    deadline = time.time() + timeout
    while True:
        for node in screen(serial):
            if (editable is None or node["editable"] == editable) and (
                regex.search(node["text"]) or regex.search(node["desc"])
            ):
                return node
        if time.time() > deadline:
            return None
        time.sleep(0.5)


def tap(serial, node):
    x, y = node["center"]
    adb(serial, "shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)


def tap_text(serial, pattern, timeout=10, scroll=True):
    deadline = time.time() + timeout
    while True:
        node = find(serial, pattern, timeout=1)
        if node:
            return tap(serial, node)
        if time.time() > deadline:
            fail(f"nothing on screen matches {pattern!r}")
        if scroll:
            swipe_up(serial)


def swipe_up(serial):
    adb(serial, "shell", "input", "swipe", "540", "1500", "540", "700", "300")
    time.sleep(0.5)


def swipe_down(serial):
    adb(serial, "shell", "input", "swipe", "540", "600", "540", "1600", "300")
    time.sleep(1)


def type_text(serial, value):
    """Types [value] into the focused field. `input text` needs spaces as %s and shell metacharacters quoted."""
    escaped = value.replace("%", "\\%").replace(" ", "%s")
    adb(serial, "shell", "input", "text", shlex.quote(escaped))


def type_secret(serial, value):
    """Types a secret without it appearing in any host command line: it reaches the device's shell on stdin."""
    if not re.fullmatch(r"[A-Za-z0-9]+", value):
        fail(
            "the secret has characters `input text` cannot type; enter it in EVA by hand"
        )
    adb(serial, "shell", 'read -r v; input text "$v"', input=value + "\n")


def field(serial, label):
    """The text field labelled [label]: the editable node around the label's text."""
    for node in screen(serial):
        if re.fullmatch(label, node["text"]) or re.fullmatch(label, node["desc"]):
            x, y = node["center"]
            for candidate in screen(serial):
                x1, y1, x2, y2 = candidate["bounds"]
                if candidate["editable"] and x1 <= x <= x2 and y1 <= y <= y2:
                    return candidate
    return None


def keyboard_shown(serial):
    return "mInputShown=true" in adb(
        serial, "shell", "dumpsys", "input_method", check=False
    )


def fill(serial, label, value, secret=False):
    """Replaces the text of the field labelled [label] with [value], checking the result unless it is masked."""
    target = field(serial, label)
    for _ in range(12):
        if target is not None:
            break
        swipe_up(serial)
        target = field(serial, label)
    if target is None:
        fail(f"no field labelled {label!r}")
    tap(serial, target)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb(
        serial,
        "shell",
        "input",
        "keyevent",
        *["KEYCODE_DEL"] * (len(target["text"]) + 4),
    )
    (type_secret if secret else type_text)(serial, value)
    if keyboard_shown(serial):
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)
    if not secret:
        typed = field(serial, label)
        if typed is None or typed["text"] != value:
            fail(f"the {label!r} field reads {typed and typed['text']!r} after typing")


def launch(serial):
    adb(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{PACKAGE}/com.colonelpanic.eva.MainActivity",
    )
    time.sleep(2)


def open_settings(serial):
    launch(serial)
    tap_text(serial, r"^Open navigation menu$", scroll=False)
    tap_text(serial, r"^Settings$", scroll=False)
    # Settings keeps its scroll position; searches scroll down, so start from the top.
    adb(
        serial, "shell", "for i in $(seq 12); do input swipe 540 600 540 1700 120; done"
    )


def read_pass(entry):
    """The voice-SDK pass entry `twilio/voice-sdk --apply` writes: the API key secret on line one, then fields."""
    result = subprocess.run(
        ["pass", "show", entry], capture_output=True, text=True, check=False
    )
    if result.returncode:
        fail(
            f"cannot read pass entry {entry}; create it with experiments/call-audio/twilio/voice-sdk --apply"
        )
    lines = result.stdout.splitlines()
    values = {"apikeysecret": lines[0].strip() if lines else ""}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        values[re.sub(r"[^a-z]", "", key.lower())] = value.strip()
    missing = [
        k
        for k in ("apikeysecret", "accountsid", "apikeysid", "twimlappsid", "callerid")
        if not values.get(k)
    ]
    if missing:
        fail(
            f"pass entry {entry} lacks {', '.join(missing)}; rerun experiments/call-audio/twilio/voice-sdk --apply"
        )
    return values


# Well-formed but unusable: the route is configured and Twilio refuses the token, so nothing is dialed.
DUMMY = {
    "accountsid": "AC" + "0" * 32,
    "twimlappsid": "AP" + "0" * 32,
    "callerid": "+15550100000",
    "apikeysid": "SK" + "0" * 32,
    "apikeysecret": "notarealsecret",
}


def configure_twilio(args):
    """Enters the Twilio route in EVA's settings, as the owner would, from the voice-SDK pass entry."""
    serial = serial_of(args)
    require_emulator(serial)
    values = DUMMY if args.dummy else read_pass(args.pass_entry)
    open_settings(serial)
    # The row toggles and remembers whether it is expanded: expand it only when it is collapsed.
    if (
        find(serial, r"^Expand Twilio calls$", timeout=1) is None
        and find(serial, r"^Collapse Twilio calls$", timeout=1) is None
    ):
        tap_text(serial, r"^Twilio calls$", timeout=60)
    expand = find(serial, r"^Expand Twilio calls$", timeout=2)
    if expand is not None:
        tap(serial, expand)
    fill(serial, "Account SID", values["accountsid"])
    fill(serial, "TwiML app SID", values["twimlappsid"])
    fill(serial, "Caller ID", values["callerid"])
    fill(serial, "(New )?API key SID", values["apikeysid"])
    fill(serial, "API key secret", values["apikeysecret"], secret=True)
    tap_text(serial, r"^Save$")
    if find(serial, r"^New API key SID$|API key saved", timeout=10) is None:
        fail(
            "EVA did not report the Twilio API key as saved; check the settings screen"
        )
    print(
        "Twilio calls configured in EVA Debug (the API key went to its encrypted secret store)"
    )


def signin(args):
    """Starts EVA's ChatGPT device-code sign-in and waits for the owner to approve the code."""
    serial = serial_of(args)
    require_emulator(serial)
    open_settings(serial)
    tap_text(serial, r"^Sign in with ChatGPT$", timeout=30)
    code = find(serial, r"^[A-Z0-9]{4,}-[A-Z0-9]{4,}$", timeout=30)
    url = find(serial, r"^https://\S+ · the code expires", timeout=2)
    if url is None:
        adb(serial, "shell", "input", "swipe", "540", "1500", "540", "1100", "300")
        url = find(serial, r"^https://\S+ · the code expires", timeout=5)
    if code is None:
        fail(
            "EVA did not show a sign-in code; check Settings > Account on the emulator"
        )
    print(
        f"Owner: open {url['text'].split(' ')[0] if url else 'the sign-in page'} and enter code {code['text']} (expires in 15 minutes)"
    )
    deadline = time.time() + args.timeout
    while b'name="chatgpt.tokens"' not in (
        read_private(serial, PACKAGE, "shared_prefs/eva.secrets.xml") or b""
    ):
        if time.time() > deadline:
            fail("the code was not approved in time; run signin again for a new code")
        time.sleep(5)
    print("EVA Debug is signed in")


def call_test(args):
    """Starts one Twilio-route call task through EVA's own dispatcher (CallTaskLiveTest), without the main agent."""
    serial = serial_of(args)
    require_emulator(serial)
    test_apk = (
        ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    )
    if not test_apk.is_file():
        fail(
            f"{test_apk} is missing; build it with ./gradlew :app:assembleDebugAndroidTest"
        )
    adb(serial, "install", "-r", str(test_apk))
    adb(serial, "logcat", "-c", check=False)
    command = ["shell", "am", "instrument", "-w"]
    for key, value in {
        "evaCallTaskNumber": args.number,
        "evaCallTaskGoal": args.goal,
        "evaCallTaskProvider": "twilio",
        "evaCallTaskMinutes": str(args.minutes),
        "class": "com.colonelpanic.eva.call.CallTaskLiveTest",
    }.items():
        command += ["-e", key, shlex.quote(value)]
    command.append(f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
    print(adb(serial, *command, check=False).strip())
    print(adb(serial, "logcat", "-d", "-s", "EvaCallTaskLive:I", check=False).strip())


def ask(serial, text):
    """
    Sends [text] to EVA in a new conversation, as the owner would type it. Typed chat needs a model
    connection first ("Connect for text"); the composer says so until it is up.
    """
    launch(serial)
    tap_text(serial, r"^Open navigation menu$", scroll=False)
    tap_text(serial, r"^Conversation$", scroll=False)
    # Main keeps past threads in the archive; archiving preserves history and opens a fresh thread.
    tap_text(serial, r"^Conversation options$", scroll=False)
    tap_text(serial, r"^Archive conversation$", scroll=False)
    # With no existing thread the action is disabled, so dismiss its still-open menu.
    if find(serial, r"^Archive conversation$", timeout=1) is not None:
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    connect = find(serial, r"^Connect for text$", timeout=3)
    if connect is not None:
        tap(serial, connect)
        deadline = time.time() + 60
        while find(serial, r"^Connect to start a conversation\.$", timeout=1):
            if time.time() > deadline:
                fail("EVA did not connect for text within 60 s; is it signed in?")
            time.sleep(1)
    composer = find(serial, r"^Request to EVA$", timeout=10)
    if composer is None:
        fail("EVA's composer is not on screen")
    tap(serial, composer)
    type_text(serial, text)
    if keyboard_shown(serial):
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    send = find(serial, r"^Send$", timeout=5)
    if send is None:
        fail("no Send button next to the composer")
    tap(serial, send)


def call(args):
    serial = serial_of(args)
    require_emulator(serial)
    ask(serial, args.goal)
    print(f"asked EVA on {serial}: {args.goal}")


# --- Evidence ----------------------------------------------------------------------------------


def pull_databases(serial, out):
    """Copies EVA Debug's databases (run-as works for the debuggable build) into [out]."""
    out.mkdir(parents=True, exist_ok=True)
    names = adb(
        serial, "shell", "run-as", PACKAGE, "ls", "databases", check=False
    ).split()
    for name in names:
        data = subprocess.run(
            [
                "adb",
                "-s",
                serial,
                "exec-out",
                "run-as",
                PACKAGE,
                "cat",
                f"databases/{name}",
            ],
            capture_output=True,
            check=False,
        ).stdout
        (out / name).write_bytes(data)
    return [out / n for n in names if n.endswith(".db")]


def call_records(databases):
    """Every CALL_TASK record EVA journaled, newest last, from whichever database holds threads."""
    records = []
    for path in databases:
        try:
            with sqlite3.connect(path) as db:
                for (table,) in db.execute(
                    "select name from sqlite_master where type = 'table'"
                ):
                    columns = [c[1] for c in db.execute(f"pragma table_info({table})")]
                    for column in columns:
                        for (value,) in db.execute(
                            f"select {column} from {table} where typeof({column}) = 'text' and {column} like '%legState%'"
                        ):
                            records.append(value)
        except sqlite3.DatabaseError:
            continue
    return records


def collect(args):
    serial = serial_of(args)
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    records = call_records(pull_databases(serial, out / "databases"))
    (out / "call-records.jsonl").write_text(
        "".join(r.replace("\n", " ") + "\n" for r in records)
    )
    log = adb(serial, "logcat", "-d", "-v", "time", check=False)
    (out / "logcat.txt").write_text(
        "\n".join(
            l for l in log.splitlines() if re.search(r"Eva|twilio|Twilio|CallTask", l)
        )
        + "\n"
    )
    (out / "final.png").write_bytes(
        subprocess.run(
            ["adb", "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            check=False,
        ).stdout
    )
    print(
        f"collected {len(records)} call-task records, filtered logcat and a screenshot in {out}"
    )
    return records


def latest_task(records):
    """The newest call task EVA journaled. Each CALL_TASK record holds it under `task`."""
    for record in reversed(records):
        try:
            task = json.loads(record).get("task")
        except (json.JSONDecodeError, AttributeError):
            continue
        if isinstance(task, dict) and "state" in task:
            return task
    return None


def outcome(task):
    """A task's state as one readable line, such as `Accomplished: Open nine to five`."""
    if not task:
        return None
    state = task.get("state", {})
    kind = state.get("type", "").rsplit(".", 1)[-1]
    detail = state.get("summary") or state.get("reason") or ""
    return f"{kind}: {detail}" if detail else kind


def verify(out, call_sid=None):
    """Runs the hotline's `verify` for the latest (or given) call, keeping its report beside the demo."""
    command = [str(CALL_AUDIO / "twilio/verify"), *([call_sid] if call_sid else [])]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    (out / "twilio-verify.txt").write_text(result.stdout + result.stderr)
    return result.returncode == 0, result.stdout


# --- Recording ---------------------------------------------------------------------------------


class Recording:
    """
    The emulator's screen (scrcpy, video only, so the device keeps playing its audio) and what its
    speaker plays (the emulator's gRPC controller), over the same [seconds].
    """

    def __init__(self, serial, out, seconds):
        self.serial, self.out, self.seconds = serial, out, seconds
        self.video = out / "screen.mkv"
        self.audio = out / "device-audio.wav"

    def __enter__(self):
        self.screen = subprocess.Popen(
            [
                "nix",
                "run",
                "nixpkgs#scrcpy",
                "--",
                "-s",
                self.serial,
                "--no-window",
                "--no-playback",
                "--no-audio",
                f"--record={self.video}",
                f"--time-limit={int(self.seconds)}",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        sys.path.insert(0, str(CALL_AUDIO / "whatsapp"))
        import emulator_audio

        self.speaker = threading.Thread(
            target=emulator_audio.record,
            args=(self.serial, self.audio, self.seconds),
            daemon=True,
        )
        self.speaker.start()
        return self

    def wait(self):
        self.speaker.join()
        self.screen.wait(timeout=60)

    def __exit__(self, *_):
        if self.screen.poll() is None:
            self.screen.send_signal(signal.SIGINT)
            self.screen.wait(timeout=30)


def mux(video, audio, target):
    """
    One MP4: the screen as recorded, with the device's speaker as its sound. The emulator plays the
    call at earpiece level, so the demo's copy is loudness-normalized; the raw WAV beside it is kept
    as captured, as evidence.
    """
    command = [
        "nix",
        "run",
        "nixpkgs#ffmpeg",
        "--",
        "-y",
        "-loglevel",
        "error",
        "-i",
        str(video),
    ]
    if audio.exists():
        command += [
            "-i",
            str(audio),
            "-map",
            "0:v",
            "-map",
            "1:a",
            "-af",
            "loudnorm",
            # loudnorm resamples to 192 kHz internally and keeps it unless told otherwise.
            "-ar",
            "48000",
            "-c:a",
            "aac",
        ]
    subprocess.run([*command, "-c:v", "copy", str(target)], check=True)


def record(args):
    serial = serial_of(args)
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    with Recording(serial, out, args.seconds) as recording:
        recording.wait()
    mux(recording.video, recording.audio, out / "demo.mp4")
    print(f"recorded {out / 'demo.mp4'}")


def demo(args):
    """Records the screen and audio while EVA calls the hotline, then collects and verifies the evidence."""
    serial = serial_of(args)
    require_emulator(serial)
    stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    out = DEMOS / f"twilio-{stamp}"
    out.mkdir(parents=True, exist_ok=True)
    with Recording(serial, out, args.seconds) as recording:
        time.sleep(2)
        ask(serial, args.goal)
        # The speaker otherwise carries only the other party; listen-in adds the agent's side.
        if not args.no_listen:
            listen = find(serial, r"^Listen in$", timeout=60)
            if listen is not None:
                tap(serial, listen)
        recording.wait()
    mux(recording.video, recording.audio, out / "demo.mp4")
    records = collect(argparse.Namespace(serial=serial, out=out))
    task = latest_task(records)
    verified, report = verify(out) if not args.no_verify else (None, "")
    summary = {
        "when": stamp,
        "serial": serial,
        "goal": args.goal,
        "video": "demo.mp4",
        "provider": (task or {}).get("providerId"),
        "taskState": outcome(task),
        "legState": (task or {}).get("legState"),
        "twilioVerify": None
        if verified is None
        else ("passed" if verified else "failed"),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    index = DEMOS / "index.md"
    if not index.exists():
        index.write_text(
            "# EVA call demos\n\nGenerated by `experiments/call-audio/emulator/eva_emulator.py demo`; media is never committed.\n\n"
        )
    with index.open("a") as handle:
        handle.write(
            f"- `{out.name}/demo.mp4` ({stamp}): {summary['taskState']}; Twilio verify {summary['twilioVerify']}\n"
        )
    print(json.dumps(summary, indent=2))
    if report:
        print(report)


# --- Card-services regression ------------------------------------------------------------------

PS_PACKAGE = "app.passwordstore.colonelpanic.debug"
PS_TITLE = "Password Store (EVA)"
CARD_SCENARIO = CALL_AUDIO / "twilio/scenario-card-services.json"
CARD_FIXTURE = CALL_AUDIO / "build/card-test-fixture"
CARD_ENTRY = "cards/test-card-services"
STORE_UID = "EVA Test Store <eva-test@example.invalid>"
MEMORY_NOTE = "Cardholder"
WORDING = ROOT / "eva-core/src/main/resources/eva-wording.yaml"
# Deliberately underspecified, like the incident: no name, card number or ZIP.
CARD_REQUEST = (
    "Call my card company at +1 559-249-3930 using the twilio route and see if they'll drop "
    "the annual fee on my test card."
)
HOTLINE_SETUP = (
    "direnv exec . experiments/call-audio/twilio/setup --scenario "
    "experiments/call-audio/twilio/scenario-card-services.json --reuse-number +15592493930"
)


def card_expectations():
    """The card digits, cardholder name and ZIP the hotline scenario expects to receive."""
    gathers = json.loads(CARD_SCENARIO.read_text())["expect"]["gathers"]
    return (
        gathers["card"]["digits"],
        gathers["identity"]["phrase"],
        gathers["identity"]["spoken_digits"],
    )


def run_as(serial, package, command, check=True):
    return adb(
        serial,
        "shell",
        "run-as",
        package,
        "sh",
        "-c",
        shlex.quote(command),
        check=check,
    )


def read_private(serial, package, path):
    """A file in a debuggable app's private storage, or None when it does not exist."""
    if subprocess.run(
        ["adb", "-s", serial, "shell", "run-as", package, "test", "-f", path],
        capture_output=True,
        check=False,
    ).returncode:
        return None
    return subprocess.run(
        ["adb", "-s", serial, "exec-out", "run-as", package, "cat", path],
        capture_output=True,
        check=True,
    ).stdout


def write_private(serial, package, path, data):
    """Replaces [path] in a debuggable app's private storage with [data] from stdin, checked by reading it back."""
    command = (
        f"mkdir -p {shlex.quote(path.rsplit('/', 1)[0])} && cat > {shlex.quote(path)}"
    )
    subprocess.run(
        [
            "adb",
            "-s",
            serial,
            # exec-in returns before the device finishes writing; shell waits for it.
            "shell",
            f"run-as {package} sh -c {shlex.quote(command)}",
        ],
        input=data,
        capture_output=True,
        check=False,
    )
    if read_private(serial, package, path) != data:
        fail(f"could not write {path} in {package}'s storage with run-as")


def installed(serial, package):
    return bool(adb(serial, "shell", "pm", "path", package, check=False).strip())


def card_preconditions(serial):
    """What the call needs that only the owner can set up, each with the command that does it."""
    if not installed(serial, PACKAGE):
        fail(
            f"EVA Debug is not installed on {serial}; run `just build && just eva-emulator install`"
        )
    secrets = (
        read_private(serial, PACKAGE, "shared_prefs/eva.secrets.xml") or b""
    ).decode()
    twilio = (
        read_private(serial, PACKAGE, "shared_prefs/eva.twilio-calls.xml") or b""
    ).decode()
    problems = []
    if 'name="chatgpt.tokens"' not in secrets and 'name="openai.apiKey"' not in secrets:
        problems.append(
            "EVA Debug is not signed in: the owner runs `just eva-emulator signin` and approves the code"
        )
    if (
        'name="configuration"' not in twilio
        or 'name="provider:twilio-calls"' not in secrets
    ):
        problems.append(
            "EVA Debug has no Twilio route: the owner approves "
            "`direnv exec . experiments/call-audio/twilio/voice-sdk --apply`, then runs "
            "`just eva-emulator twilio-config`"
        )
    return problems


def card_fixture(digits, holder, zip_code):
    """
    A throwaway PGP key without a passphrase and the card entry encrypted to it, made once under the
    ignored build/ directory and reused. Its own GNUPGHOME there; never the owner's keyring or store.
    """
    fingerprint = CARD_FIXTURE / "fingerprint"
    key = CARD_FIXTURE / "secret-key.asc"
    entry = CARD_FIXTURE / "test-card-services.gpg"
    if fingerprint.exists():
        return fingerprint.read_text().strip(), key.read_bytes(), entry.read_bytes()
    if shutil.which("gpg") is None:
        fail("gpg is not on PATH; rerun inside `nix shell nixpkgs#gnupg`")
    shutil.rmtree(CARD_FIXTURE, ignore_errors=True)
    home = CARD_FIXTURE / "gnupg"
    home.mkdir(parents=True, mode=0o700)

    def gpg(*args, data=None):
        result = subprocess.run(
            ["gpg", "--homedir", str(home), "--batch", "--pinentry-mode", "loopback"]
            + ["--passphrase", "", *args],
            input=data,
            capture_output=True,
            check=False,
        )
        if result.returncode:
            fail(f"gpg {args[0]} failed: {result.stderr.decode().strip()}")
        return result.stdout

    try:
        gpg("--quick-gen-key", STORE_UID, "ed25519", "sign", "never")
        listing = gpg("--with-colons", "--list-secret-keys").decode()
        fpr = re.search(r"^fpr:+([0-9A-F]{40}):", listing, re.MULTILINE)[1]
        gpg("--quick-add-key", fpr, "cv25519", "encr", "never")
        number = " ".join(digits[i : i + 4] for i in range(0, len(digits), 4))
        plain = f"fake-password-for-eva-tests\nnumber: {number}\nzip: {zip_code}\nname: {holder}\n"
        key.write_bytes(gpg("--armor", "--export-secret-keys", fpr))
        entry.write_bytes(
            gpg(
                "--trust-model",
                "always",
                "--recipient",
                fpr,
                "--encrypt",
                data=plain.encode(),
            )
        )
    finally:
        subprocess.run(
            ["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], check=False
        )
    fingerprint.write_text(fpr + "\n")
    return fpr, key.read_bytes(), entry.read_bytes()


def seed_password_store(serial, ps_apk, fixture):
    """Installs Password Store (EVA) debug if missing and gives it the test key and card entry."""
    if not installed(serial, PS_PACKAGE):
        if ps_apk is None or not ps_apk.is_file():
            fail(
                f"{PS_PACKAGE} is not installed and --ps-apk names no APK. Build it from "
                "colonelpanic8/Android-Password-Store (eva-extension with eva-field-secrets and "
                "eva-debug-signer-pin) with `./gradlew :app:assembleDebug -PevaDebugCert=<SHA-256 of "
                "EVA Debug's signer>` and pass --ps-apk app/build/outputs/apk/debug/app-debug.apk"
            )
        print(adb(serial, "install", "-r", str(ps_apk)).strip())
    fpr, key, entry = fixture
    adb(serial, "shell", "am", "force-stop", PS_PACKAGE)
    write_private(serial, PS_PACKAGE, "files/store/.gpg-id", f"{fpr}\n".encode())
    write_private(serial, PS_PACKAGE, f"files/store/{CARD_ENTRY}.gpg", entry)
    write_private(
        serial, PS_PACKAGE, f"files/pgp_keys/keys/{fpr[-16:].lower()}.key", key
    )
    print(f"Password Store (EVA) holds {CARD_ENTRY}, encrypted to the test key {fpr}")


def seed_memory(serial, holder, zip_code):
    """Keeps EVA's memory note with the cardholder's name and ZIP, replacing an older copy by name."""
    text = f"My name is {holder}. My card's billing ZIP code is {zip_code}."
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    notes = json.loads(read_private(serial, PACKAGE, "files/memories.json") or b"[]")
    if not any(n["name"] == MEMORY_NOTE and n["text"] == text for n in notes):
        notes = [n for n in notes if n["name"] != MEMORY_NOTE] + [
            {
                "name": MEMORY_NOTE,
                "text": text,
                "updatedAtMillis": int(time.time() * 1000),
            }
        ]
        # AtomicFile restores a leftover backup over the file it guards.
        run_as(serial, PACKAGE, "rm -f files/memories.json.bak files/memories.json.new")
        write_private(
            serial,
            PACKAGE,
            "files/memories.json",
            json.dumps(
                sorted(notes, key=lambda n: n["name"]), separators=(",", ":")
            ).encode(),
        )
    print(f"EVA memory keeps {MEMORY_NOTE!r}: {text}")


def row_status(serial, title):
    """The supporting line under an Extensions row's title."""
    nodes = screen(serial)
    for index, node in enumerate(nodes):
        if node["text"] == title:
            return next((n["text"] for n in nodes[index + 1 :] if n["text"]), "")
    return None


def enable_password_store(serial):
    """Turns on Password Store (EVA) and all its actions in EVA's Extensions screen, as the owner would."""
    launch(serial)
    tap_text(serial, r"^Open navigation menu$", scroll=False)
    tap_text(serial, r"^Extensions$", scroll=False)
    switch = None
    deadline = time.time() + 60
    while switch is None:
        switch = find(serial, rf"^Use {re.escape(PS_TITLE)}$", timeout=1)
        if switch is not None:
            break
        refused = row_status(serial, PS_PACKAGE)
        if refused is not None:
            fail(
                f"EVA lists {PS_PACKAGE} without its actions: {refused!r}. If it says the caller is "
                "not authorized, rebuild Password Store with -PevaDebugCert set to EVA Debug's signer"
            )
        if time.time() > deadline:
            fail(
                f"EVA's Extensions screen shows no {PS_TITLE}; is {PS_PACKAGE} installed?"
            )
        swipe_up(serial)
    if not switch["checked"]:
        tap(serial, switch)
    expand = find(serial, rf"^Expand {re.escape(PS_TITLE)}$", timeout=2)
    if expand is not None:
        tap(serial, expand)
    turn_on = find(serial, r"^Turn on all$", timeout=2)
    if turn_on is not None:
        tap(serial, turn_on)
    # The expanded row's action list can push its title off screen.
    for _ in range(5):
        status = row_status(serial, PS_TITLE)
        if status is not None:
            break
        swipe_down(serial)
    switch = find(serial, rf"^Use {re.escape(PS_TITLE)}$", timeout=2)
    if not (
        switch
        and switch["checked"]
        and re.fullmatch(r"(\d+) of \1 actions? on", status or "")
    ):
        fail(
            f"{PS_TITLE} is not fully on in EVA's Extensions screen; it reads {status!r}"
        )
    print(f"EVA's Extensions screen: {PS_TITLE} {status}")


def gate_wording(serial):
    """Include followed and bundled refusals: missing catalog keys fall back to the APK."""
    phrases = []
    for text in (
        read_private(serial, PACKAGE, "files/eva-wording.yaml"),
        WORDING.read_bytes(),
    ):
        try:
            phrases.extend(card_test.gate_phrases((text or b"").decode()))
        except ValueError:
            continue
    if phrases:
        return list(dict.fromkeys(phrases))
    fail("no eva-wording.yaml defines an uncertain-mutation refusal")


def card_wait(serial, out, known, timeout):
    """Polls EVA's records until the new call task ends, EVA answers without one, or [timeout] passes."""
    deadline = time.time() + timeout
    last = None
    while True:
        # A rollback journal left from an earlier copy would undo this copy's newer pages.
        shutil.rmtree(out / "databases", ignore_errors=True)
        records = card_test.read_records(pull_databases(serial, out / "databases"))
        invocations = [i for i in records["invocations"] if i["call_id"] not in known]
        tasks = [
            t for t in card_test.fold_tasks(records["notices"]) if t["id"] not in known
        ]
        turns = [t for t in records["turns"] if t["id"] not in known]
        starts = [i for i in invocations if i["capability_id"] == card_test.START]
        state = card_test.state_kind(tasks[-1]) if tasks else None
        line = f"{len(invocations)} new actions; call task {state or 'not started'}"
        if line != last:
            print(line, flush=True)
            last = line
        if state in card_test.TERMINAL:
            return records, invocations, tasks
        if (
            starts
            and not tasks
            and turns
            and all(t["status"] != "OPEN" for t in turns)
            and all(i["status"] in ("NOT_EXECUTED", "FAILED") for i in starts)
        ):
            print(f"eva.call.start did not start a call: {starts[-1]['message']}")
            return records, invocations, tasks
        if not starts and turns and all(t["status"] != "OPEN" for t in turns):
            print("EVA finished its turn without starting a call")
            return records, invocations, tasks
        if time.time() > deadline:
            print(
                f"no outcome within {timeout:.0f} s; judging what EVA recorded so far"
            )
            return records, invocations, tasks
        time.sleep(15)


def hotline_evaluation(out):
    """The hotline's `verify --scenario` evaluation of its latest inbound call."""
    result = subprocess.run(
        [str(CALL_AUDIO / "twilio/verify"), "--scenario", str(CARD_SCENARIO)],
        capture_output=True,
        text=True,
        check=False,
    )
    (out / "twilio-verify.txt").write_text(result.stdout + result.stderr)
    artifacts = re.search(r"^Artifacts: (.+)$", result.stdout, re.MULTILINE)
    report = Path(artifacts[1]) / "report.json" if artifacts else None
    if report is None or not report.exists():
        return None
    return json.loads(report.read_text()).get("evaluation")


def card_run(args):
    """
    The card-services regression: seeds a throwaway card entry and EVA memory, asks EVA an
    underspecified request to call the fake card line, then judges EVA's records and the hotline's.
    """
    serial = serial_of(args)
    require_emulator(serial)
    digits, holder, zip_code = card_expectations()
    problems = card_preconditions(serial)
    if not args.seed_only and not args.hotline_ready:
        problems.append(
            f"the hotline must serve the card-services scenario: the owner runs `{HOTLINE_SETUP}`, "
            "then reruns with --hotline-ready"
        )
    if problems and not args.seed_only:
        fail("before the call:\n  - " + "\n  - ".join(problems))
    seed_password_store(serial, args.ps_apk, card_fixture(digits, holder, zip_code))
    seed_memory(serial, holder, zip_code)
    enable_password_store(serial)
    if args.seed_only:
        for problem in problems:
            print(f"Still needed for the call: {problem}")
        print("Seeded; no call placed (--seed-only)")
        return
    stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    out = CALL_AUDIO / f"build/card-test-{stamp}"
    before = card_test.read_records(pull_databases(serial, out / "databases-before"))
    known = (
        {i["call_id"] for i in before["invocations"]}
        | {t["id"] for t in card_test.fold_tasks(before["notices"])}
        | {t["id"] for t in before["turns"]}
    )
    adb(serial, "logcat", "-G", "16M", check=False)
    adb(serial, "logcat", "-c", check=False)
    ask(serial, args.request)
    print(f"asked EVA on {serial}: {args.request}")
    records, invocations, tasks = card_wait(serial, out, known, args.timeout)
    logcat = adb(serial, "logcat", "-d", "-v", "time", check=False)
    (out / "logcat.txt").write_text(logcat)
    (out / "final.png").write_bytes(
        subprocess.run(
            ["adb", "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            check=False,
        ).stdout
    )
    # Without the other party speaking, verify's latest inbound call would be an older one.
    heard = any(
        item.get("speaker") == "REMOTE" for t in tasks for item in t["transcript"]
    )
    evaluation = hotline_evaluation(out) if heard else None
    checks = card_test.assess(
        invocations,
        tasks,
        {"EVA databases": "\n".join(records["texts"]), "logcat": logcat},
        evaluation,
        gate_wording(serial),
        digits,
        holder,
        "number",
    )
    passed = all(c["passed"] for c in checks)
    lines = [
        f"{'PASS' if c['passed'] else 'FAIL'} {c['name']}: {c['detail']}"
        for c in checks
    ]
    (out / "checks.txt").write_text("\n".join(lines) + "\n")
    (out / "summary.json").write_text(
        json.dumps(
            {
                "when": stamp,
                "serial": serial,
                "request": args.request,
                "taskState": outcome(tasks[-1]) if tasks else None,
                "hotlineEvaluation": evaluation,
                "checks": checks,
                "passed": passed,
            },
            indent=2,
        )
        + "\n"
    )
    print("\n".join(lines))
    print(f"card-test {'PASSED' if passed else 'FAILED'}; evidence in {out}")
    if not passed:
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)

    command = commands.add_parser(
        "up",
        help="boot the persistent EVA-under-test emulator (runs in the foreground)",
    )
    command.add_argument(
        "--sdk",
        type=Path,
        default=ROOT / "build/eva-emulator",
        help="emulator.nix's sdk output (`just emulator-tools`)",
    )
    command.add_argument(
        "--headless",
        action="store_true",
        help="no window (swiftshader); input is unreliable this way",
    )
    command.set_defaults(run=up)

    for name, function, help_text in [
        ("wait", wait_boot, "wait for boot and turn off animations"),
        ("install", install, "install EVA Debug and grant its runtime permissions"),
        (
            "twilio-config",
            configure_twilio,
            "enter the Twilio route in EVA's settings from the voice-SDK pass entry",
        ),
        (
            "signin",
            signin,
            "start EVA's ChatGPT sign-in and wait for the owner to approve the code",
        ),
        (
            "call",
            call,
            "ask EVA to make the hotline test call through the Twilio route",
        ),
        (
            "call-test",
            call_test,
            "start one Twilio-route call task directly through EVA's dispatcher",
        ),
        (
            "collect",
            collect,
            "pull EVA's call-task records, filtered logcat and a screenshot",
        ),
        ("record", record, "record the screen and device audio for a while"),
        (
            "card-test",
            card_run,
            "seed a test card entry and memory, ask EVA to call the card line, judge the outcome",
        ),
        (
            "demo",
            demo,
            "record a full hotline call: video, device audio, records and Twilio verify",
        ),
    ]:
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--serial", help="defaults to the emulator `up` recorded")
        command.set_defaults(run=function)
        if name == "wait":
            command.add_argument("--timeout", type=float, default=600)
        if name == "install":
            command.add_argument("--apk", type=Path, default=APK)
        if name == "twilio-config":
            command.add_argument("--pass-entry", default=PASS_ENTRY)
            command.add_argument(
                "--dummy",
                action="store_true",
                help="well-formed fake values: Twilio refuses, nothing is dialed",
            )
        if name == "signin":
            command.add_argument("--timeout", type=float, default=900)
        if name == "call-test":
            command.add_argument("--number", default=HOTLINE)
            command.add_argument("--minutes", type=int, default=5)
        if name in ("call", "call-test", "demo"):
            command.add_argument("--goal", default=GOAL)
        if name in ("record", "demo"):
            command.add_argument("--seconds", type=float, default=240)
        if name == "card-test":
            command.add_argument(
                "--seed-only",
                action="store_true",
                help="seed Password Store, memory and the extension grant; place no call",
            )
            command.add_argument(
                "--hotline-ready",
                action="store_true",
                help="the owner deployed the card-services scenario to the hotline",
            )
            command.add_argument(
                "--ps-apk",
                type=Path,
                default=ROOT / "build/password-store/app-debug.apk",
                help="Password Store (EVA) debug APK to install when it is missing",
            )
            command.add_argument("--request", default=CARD_REQUEST)
            command.add_argument(
                "--timeout",
                type=float,
                default=600,
                help="seconds to wait for the call task to end",
            )
        if name == "demo":
            command.add_argument(
                "--no-verify", action="store_true", help="skip the Twilio verify step"
            )
            command.add_argument(
                "--no-listen", action="store_true", help="leave listen-in off"
            )
        if name in ("collect", "record"):
            stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
            command.add_argument(
                "--out",
                type=Path,
                default=CALL_AUDIO / f"build/eva-emulator-{name}-{stamp}",
            )

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
