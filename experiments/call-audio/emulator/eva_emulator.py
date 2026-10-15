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
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

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
            for node in root.iter("node"):
                x1, y1, x2, y2 = map(
                    int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]"))
                )
                nodes.append(_node(node, x1, y1, x2, y2))
            return nodes
        time.sleep(1)
    fail("could not read the emulator's screen with uiautomator")


def _node(node, x1, y1, x2, y2):
    return {
        "text": node.get("text", ""),
        "desc": node.get("content-desc", ""),
        "class": node.get("class", ""),
        "editable": node.get("class", "").endswith("EditText"),
        "scrollable": node.get("scrollable") == "true",
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
    while find(serial, r"^Sign in with ChatGPT$|enter this code", timeout=1):
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
