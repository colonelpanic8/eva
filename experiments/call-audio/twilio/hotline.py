#!/usr/bin/env python3
"""Twilio-only fake hotline deployment, evidence collection, and device test."""

import argparse
import base64
import datetime as dt
import difflib
import email.utils
import fcntl
import io
import json
import math
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CALL_AUDIO = HERE.parent
BUILD = CALL_AUDIO / "build"
NAME = "eva-fake-hotline"
HOTLINE_NUMBER = os.environ.get("TWILIO_HOTLINE_NUMBER", "")
SERVERLESS = "https://serverless.twilio.com/v1"
UPLOAD = "https://serverless-upload.twilio.com/v1"
API = "https://api.twilio.com/2010-04-01"
SYNC = "https://sync.twilio.com/v1"
SERIAL = "adb-67091FDDJ0007B-YBkFMQ._adb-tls-connect._tcp"
AGENT_PYTHON = (
    "/nix/store/xlmg4gla00bkqa1m9gjbflfyhqv0ms47-python3-3.14.7-env/bin/python3"
)
AUTH_HOSTS = {
    "api.twilio.com",
    "serverless.twilio.com",
    "serverless-upload.twilio.com",
    "sync.twilio.com",
}


class Failure(Exception):
    pass


class ApiFailure(Failure):
    def __init__(self, status, code=None, operation=None, detail=None):
        self.status, self.code = status, code
        message = f"Twilio HTTP {status}, error code {code or 'unavailable'}"
        if operation:
            message = f"{operation}: {message}"
        if detail:
            message += f"; {detail}"
        if code == 21404:
            message += "; Twilio requires the owner to upgrade the account before provisioning a number. No number was purchased."
        super().__init__(message)


def parse_credentials(raw):
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    if len(lines) == 2 and re.fullmatch(r"AC[0-9a-fA-F]{32}", lines[0]):
        sid, token = lines
    else:
        values = {}
        for line in lines:
            if ":" not in line:
                raise Failure(
                    "Unclear pass format: use Account SID: / Auth Token: or SID then token on two lines"
                )
            key, value = line.split(":", 1)
            key = re.sub(r"[^a-z]", "", key.lower()).removeprefix("twilio")
            if key not in {"accountsid", "authtoken"} or key in values:
                raise Failure(
                    "Unclear pass format: expected exactly one Account SID and Auth Token"
                )
            values[key] = value.strip()
        sid, token = values.get("accountsid", ""), values.get("authtoken", "")
    if not re.fullmatch(r"AC[0-9a-fA-F]{32}", sid) or not re.fullmatch(
        r"[0-9a-fA-F]{32}", token
    ):
        raise Failure(
            "Unclear pass format: expected an AC Account SID and a 32-character hex Auth Token"
        )
    return sid, token


def read_credentials(entry):
    # Discard the password line and unrelated fields before Python receives stdout.
    with subprocess.Popen(
        ["pass", "show", entry], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    ) as source:
        result = subprocess.run(
            [
                "awk",
                "NR > 1 && /^[[:space:]]*(account-sid|auth-token)[[:space:]]*:/ { print }",
            ],
            stdin=source.stdout,
            capture_output=True,
            text=True,
            check=False,
        )
        source.stdout.close()
        status = source.wait()
    if status or result.returncode:
        raise Failure(f"Cannot extract API credentials from pass entry {entry}")
    return parse_credentials(result.stdout)


def authenticate_subprocess(args):
    sid, token = read_credentials(args.pass_entry)
    env = {**os.environ, "TWILIO_ACCOUNT_SID": sid, "TWILIO_AUTH_TOKEN": token}
    return subprocess.run(
        [sys.executable, str(HERE / "hotline.py"), "--authenticated", *sys.argv[1:]],
        env=env,
        check=False,
    ).returncode


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise Failure("Refused non-HTTPS recording redirect")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlsplit(newurl).hostname not in AUTH_HOSTS:
            redirected.remove_header("Authorization")
        return redirected


class Client:
    def __init__(self):
        self.sid = os.environ.pop("TWILIO_ACCOUNT_SID")
        token = os.environ.pop("TWILIO_AUTH_TOKEN")
        self.authorization = (
            "Basic " + base64.b64encode(f"{self.sid}:{token}".encode()).decode()
        )
        self.account = f"{API}/Accounts/{self.sid}"
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, url, method="GET", data=None, content_type=None, binary=False):
        if urllib.parse.urlsplit(url).hostname not in AUTH_HOSTS:
            raise Failure("Refused to send credentials to a non-Twilio API host")
        headers = {"Authorization": self.authorization}
        if data is not None and not isinstance(data, bytes):
            data = urllib.parse.urlencode(data, doseq=True).encode()
            content_type = "application/x-www-form-urlencoded"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                body = response.read()
                return body if binary else json.loads(body)
        except urllib.error.HTTPError as error:
            code = None
            detail = None
            try:
                payload = json.loads(error.read())
                value = payload.get("code")
                if isinstance(value, int):
                    code = value
                if isinstance(payload.get("message"), str):
                    detail = payload["message"]
                    credentials = base64.b64decode(
                        self.authorization.split(" ", 1)[1]
                    ).decode()
                    for secret in (
                        self.authorization,
                        credentials,
                        credentials.split(":", 1)[1],
                    ):
                        detail = detail.replace(secret, "[redacted]")
            except (ValueError, AttributeError):
                pass
            raise ApiFailure(
                error.code, code, f"{method} {urllib.parse.urlsplit(url).path}", detail
            ) from None
        except (urllib.error.URLError, TimeoutError):
            raise Failure(
                "Twilio network request failed; mutations were not retried. Rerun setup to rediscover resources."
            ) from None

    def items(self, url, key, params=None):
        if params:
            url += "?" + urllib.parse.urlencode(params)
        result = []
        while url:
            page = self.request(url)
            result.extend(page[key])
            url = page.get("next_page_uri") or page.get("meta", {}).get("next_page_url")
            if url:
                url = urllib.parse.urljoin("https://api.twilio.com", url)
        return result

    def ensure(self, url, key, field, value, data):
        matches = [item for item in self.items(url, key) if item.get(field) == value]
        if len(matches) > 1:
            raise Failure(
                f"Multiple resources named {value}; resolve the ambiguity in Twilio"
            )
        return matches[0] if matches else self.request(url, "POST", data)

    def upload(self, url, path, visibility, content, mime):
        boundary = "eva" + uuid.uuid4().hex
        parts = []
        for key, value in {"Path": path, "Visibility": visibility}.items():
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
            )
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="Content"; filename="{path[1:]}"\r\nContent-Type: {mime}\r\n\r\n'.encode()
        )
        parts.extend([content, f"\r\n--{boundary}--\r\n".encode()])
        return self.request(
            url, "POST", b"".join(parts), f"multipart/form-data; boundary={boundary}"
        )


def scenario(path):
    value = json.loads(path.read_text())
    # Use the same validator as the deployed Function, before any remote mutation.
    if not shutil.which("node"):
        raise Failure(
            "Node is needed to validate the scenario: use nix shell nixpkgs#nodejs"
        )
    check = subprocess.run(
        [
            "node",
            "-e",
            "require(process.argv[1]).validateScenario(JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8')))",
            str(HERE / "hotline.js"),
            str(path),
        ],
        capture_output=True,
        check=False,
    )
    if check.returncode:
        raise Failure(
            "Invalid scenario; run node --test experiments/call-audio/twilio/hotline.test.js for examples"
        )
    return value


def hold_music(seconds):
    rate = 8000
    notes = [261.63, 329.63, 392.0, 523.25, 392.0, 329.63, 293.66, 349.23]
    pcm = bytearray()
    for i in range(rate * seconds):
        t = i / rate
        phase = t % 0.5
        envelope = min(1, phase / 0.025, (0.5 - phase) / 0.08)
        hz = notes[int(t * 2) % len(notes)]
        value = (
            2200
            * envelope
            * (math.sin(2 * math.pi * hz * t) + 0.3 * math.sin(4 * math.pi * hz * t))
        )
        pcm.extend(struct.pack("<h", int(value)))
    result = io.BytesIO()
    with wave.open(result, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(pcm)
    return result.getvalue()


def choose_number(numbers, reuse=None):
    if reuse:
        matches = [
            n for n in numbers if n["sid"] == reuse or n["phone_number"] == reuse
        ]
    else:
        matches = [n for n in numbers if n["phone_number"] == HOTLINE_NUMBER]
        if not matches:
            matches = [
                n
                for n in numbers
                if NAME in n.get("friendly_name", "").lower()
                or n.get("friendly_name", "").lower() == "eva testing"
            ]
    if len(matches) > 1:
        raise Failure(
            "Multiple EVA numbers exist; choose --reuse-number PN...; no number was purchased"
        )
    if matches:
        number = matches[0]
        if not re.fullmatch(r"\+1\d{10}", number["phone_number"]) or not number.get(
            "capabilities", {}
        ).get("voice"):
            raise Failure(
                "Existing EVA number is not a US voice number; no number was purchased"
            )
        return number
    if numbers or reuse:
        raise Failure(
            "Account has other numbers, or requested SID was not found. Use --reuse-number PN... for an existing test number; no number was purchased"
        )
    return None


def number(client):
    selected = choose_number(
        client.items(
            f"{client.account}/IncomingPhoneNumbers.json", "incoming_phone_numbers"
        )
    )
    if not selected:
        raise Failure("No EVA test number exists; run setup first")
    return selected


def deployment(client):
    services = [
        s
        for s in client.items(f"{SERVERLESS}/Services", "services")
        if s["unique_name"] == NAME
    ]
    if len(services) != 1:
        raise Failure("EVA Serverless service missing or ambiguous; run setup")
    service = services[0]
    environments = [
        e
        for e in client.items(
            f"{SERVERLESS}/Services/{service['sid']}/Environments", "environments"
        )
        if e["unique_name"] == "test"
    ]
    if len(environments) != 1:
        raise Failure("EVA Serverless environment missing or ambiguous; run setup")
    return service, environments[0]


def setup(client, args):
    data = scenario(args.scenario)
    owned = client.items(
        f"{client.account}/IncomingPhoneNumbers.json", "incoming_phone_numbers"
    )
    selected = choose_number(owned, args.reuse_number)
    if not selected and getattr(args, "provision", False):
        owned = client.items(
            f"{client.account}/IncomingPhoneNumbers.json", "incoming_phone_numbers"
        )
        if owned:
            raise Failure(
                "Account has existing numbers; no additional number will be purchased"
            )
        candidates = client.request(
            f"{client.account}/AvailablePhoneNumbers/US/Local.json?VoiceEnabled=true&PageSize=1"
        )["available_phone_numbers"]
        if not candidates:
            raise Failure("No US local voice number available; no purchase attempted")
        # Provision exactly once; rediscover after an uncertain response instead of retrying.
        selected = client.request(
            f"{client.account}/IncomingPhoneNumbers.json",
            "POST",
            {"PhoneNumber": candidates[0]["phone_number"], "FriendlyName": NAME},
        )
        print(
            f"Provisioned Phone: {selected['phone_number']}\nNumberSid: {selected['sid']}",
            flush=True,
        )
    if not selected:
        raise Failure(
            "Provisioned hotline number not found; use --provision only when buying one number is authorized"
        )
    service = client.ensure(
        f"{SERVERLESS}/Services",
        "services",
        "unique_name",
        NAME,
        {"UniqueName": NAME, "FriendlyName": NAME, "IncludeCredentials": "true"},
    )
    base = f"{SERVERLESS}/Services/{service['sid']}"
    client.request(base, "POST", {"IncludeCredentials": "true"})
    environment = client.ensure(
        f"{base}/Environments",
        "environments",
        "unique_name",
        "test",
        {"UniqueName": "test", "DomainSuffix": "test"},
    )
    sync_service = client.ensure(
        f"{SYNC}/Services", "services", "friendly_name", NAME, {"FriendlyName": NAME}
    )
    variables_url = f"{base}/Environments/{environment['sid']}/Variables"
    variables = client.items(variables_url, "variables")
    existing_variable = next(
        (v for v in variables if v["key"] == "SYNC_SERVICE_SID"), None
    )
    if existing_variable:
        client.request(
            f"{variables_url}/{existing_variable['sid']}",
            "POST",
            {"Value": sync_service["sid"]},
        )
    else:
        client.request(
            variables_url,
            "POST",
            {"Key": "SYNC_SERVICE_SID", "Value": sync_service["sid"]},
        )
    function = client.ensure(
        f"{base}/Functions",
        "functions",
        "friendly_name",
        "hotline",
        {"FriendlyName": "hotline"},
    )
    function_version = client.upload(
        f"{UPLOAD}/Services/{service['sid']}/Functions/{function['sid']}/Versions",
        "/hotline",
        "protected",
        (HERE / "hotline.js").read_bytes(),
        "application/javascript",
    )
    asset_versions = []
    for name, content, visibility, mime in [
        ("scenario.json", args.scenario.read_bytes(), "private", "application/json"),
        ("hold.wav", hold_music(data["hold_segment_seconds"]), "public", "audio/wav"),
    ]:
        asset = client.ensure(
            f"{base}/Assets", "assets", "friendly_name", name, {"FriendlyName": name}
        )
        version = client.upload(
            f"{UPLOAD}/Services/{service['sid']}/Assets/{asset['sid']}/Versions",
            f"/{name}",
            visibility,
            content,
            mime,
        )
        asset_versions.append(version["sid"])
    build = client.request(
        f"{base}/Builds",
        "POST",
        {
            "FunctionVersions": [function_version["sid"]],
            "AssetVersions": asset_versions,
            "Runtime": "node22",
            "Dependencies": json.dumps(
                [
                    {"name": "twilio", "version": "6.2.0"},
                    {"name": "@twilio/runtime-handler", "version": "2.0.1"},
                ]
            ),
        },
    )
    print(f"BuildSid: {build['sid']}", file=sys.stderr, flush=True)
    while build["status"] == "building":
        time.sleep(2)
        build = client.request(f"{base}/Builds/{build['sid']}")
    if build["status"] != "completed":
        raise Failure(
            f"Serverless build {build['sid']} failed; inspect Twilio Build Status. Number webhook was not changed."
        )
    client.request(
        f"{base}/Environments/{environment['sid']}/Deployments",
        "POST",
        {"BuildSid": build["sid"]},
    )
    voice_url = f"https://{environment['domain_name']}/hotline"
    selected = client.request(
        f"{client.account}/IncomingPhoneNumbers/{selected['sid']}.json",
        "POST",
        {
            "FriendlyName": NAME,
            "VoiceUrl": voice_url,
            "VoiceMethod": "POST",
            "VoiceApplicationSid": "",
        },
    )
    print(
        f"Phone: {selected['phone_number']}\nNumberSid: {selected['sid']}\nServiceSid: {service['sid']}\nEnvironmentSid: {environment['sid']}\nSyncServiceSid: {sync_service['sid']}\nBuildSid: {build['sid']}"
    )
    account = client.request(f"{client.account}.json")
    if account.get("type", "").lower() == "trial":
        print(
            "Trial account: verify the Pixel caller number yourself. Trial recording may be unavailable; Gather evidence is still retained.",
            file=sys.stderr,
        )


VOICE_SDK = "eva-voice-sdk"
VOICE_SDK_FIELDS = ("account-sid", "api-key-sid", "twiml-app-sid", "caller-id")


def read_voice_entry(entry):
    """The voice-SDK pass entry as {field: value} plus whether line one holds a key secret; None if absent."""
    result = subprocess.run(
        ["pass", "show", entry], capture_output=True, text=True, check=False
    )
    if result.returncode:
        return None
    lines = result.stdout.splitlines()
    fields = {}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        if key.strip() in VOICE_SDK_FIELDS:
            fields[key.strip()] = value.strip()
    return fields, bool(lines and lines[0].strip())


def write_voice_entry(entry, secret, fields):
    """Writes the entry through pass's stdin: the secret never reaches arguments, files, or output."""
    body = secret + "\n" + "".join(f"{k}: {fields[k]}\n" for k in VOICE_SDK_FIELDS)
    result = subprocess.run(
        ["pass", "insert", "--multiline", "--force", entry],
        input=body,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode:
        raise Failure(f"pass could not save {entry}")


def voice_secret(entry):
    result = subprocess.run(
        ["pass", "show", entry], capture_output=True, text=True, check=False
    )
    return result.stdout.splitlines()[0].strip() if not result.returncode else ""


def voice_sdk(client, args):
    """
    The account resources EVA's Twilio Voice SDK route needs, each found by its eva-voice-sdk name:
    an API key (secret straight into pass), a Serverless service whose protected /dial Function
    dials the To parameter, and a TwiML app pointing at it. Without --apply nothing changes.
    """
    owned = client.items(
        f"{client.account}/IncomingPhoneNumbers.json", "incoming_phone_numbers"
    )
    verified = client.items(
        f"{client.account}/OutgoingCallerIds.json", "outgoing_caller_ids"
    )
    caller_id = args.caller_id or number(client)["phone_number"]
    if caller_id not in {n["phone_number"] for n in owned + verified}:
        raise Failure(
            f"{caller_id} is neither a number on this account nor a verified caller ID"
        )
    keys = [
        k
        for k in client.items(f"{client.account}/Keys.json", "keys")
        if k["friendly_name"] == VOICE_SDK
    ]
    if len(keys) > 1:
        raise Failure(f"Several API keys are named {VOICE_SDK}; delete the extras")
    stored = read_voice_entry(args.voice_entry)
    key_usable = bool(
        keys and stored and stored[1] and stored[0].get("api-key-sid") == keys[0]["sid"]
    )
    if keys and not key_usable:
        raise Failure(
            f"API key {keys[0]['sid']} ({VOICE_SDK}) exists but its secret is not in pass entry "
            f"{args.voice_entry}; Twilio shows a secret only once. Delete that key in the Twilio console, then rerun."
        )
    services = [
        s
        for s in client.items(f"{SERVERLESS}/Services", "services")
        if s["unique_name"] == VOICE_SDK
    ]
    apps = [
        a
        for a in client.items(f"{client.account}/Applications.json", "applications")
        if a["friendly_name"] == VOICE_SDK
    ]
    if len(apps) > 1:
        raise Failure(f"Several TwiML apps are named {VOICE_SDK}; delete the extras")
    plan = [
        f"Caller ID: {caller_id}",
        f"API key {VOICE_SDK}: "
        + (
            f"reuse {keys[0]['sid']}"
            if keys
            else "CREATE (standard key; secret into pass)"
        ),
        f"Serverless service {VOICE_SDK}: "
        + (f"reuse {services[0]['sid']}" if services else "CREATE")
        + "; environment 'voice', protected Function /dial, CALLER_ID variable, new build and deployment",
        f"TwiML app {VOICE_SDK}: "
        + (f"reuse {apps[0]['sid']}, voice URL updated" if apps else "CREATE")
        + " with voice URL https://<service domain>/dial (POST)",
        f"pass entry {args.voice_entry}: "
        + ("update fields" if stored else "CREATE")
        + f" ({', '.join(VOICE_SDK_FIELDS)}; line one the key secret)",
    ]
    print("\n".join(plan))
    if not args.apply:
        print(
            "Plan only: nothing was changed. Rerun with --apply once the owner approves these resources."
        )
        return
    # Rerun-safe order: the key (and its secret) first, then the dialer, then the app that uses it.
    if keys:
        key_sid, secret = keys[0]["sid"], voice_secret(args.voice_entry)
    else:
        created = client.request(
            f"{client.account}/Keys.json", "POST", {"FriendlyName": VOICE_SDK}
        )
        key_sid, secret = created["sid"], created["secret"]
        fields = {
            "account-sid": client.sid,
            "api-key-sid": key_sid,
            "twiml-app-sid": "",
            "caller-id": caller_id,
        }
        try:
            write_voice_entry(args.voice_entry, secret, fields)
        except Failure:
            client.request(
                f"{client.account}/Keys/{key_sid}.json", "DELETE", binary=True
            )
            raise Failure(
                f"pass could not save the new key's secret, so API key {key_sid} was deleted again"
            ) from None
        print(
            f"Created API key {key_sid}; its secret is only in pass entry {args.voice_entry}"
        )
    service = client.ensure(
        f"{SERVERLESS}/Services",
        "services",
        "unique_name",
        VOICE_SDK,
        {
            "UniqueName": VOICE_SDK,
            "FriendlyName": VOICE_SDK,
            "IncludeCredentials": "false",
        },
    )
    base = f"{SERVERLESS}/Services/{service['sid']}"
    environment = client.ensure(
        f"{base}/Environments",
        "environments",
        "unique_name",
        "voice",
        {"UniqueName": "voice", "DomainSuffix": "voice"},
    )
    variables_url = f"{base}/Environments/{environment['sid']}/Variables"
    existing = {v["key"]: v for v in client.items(variables_url, "variables")}
    for key, value in {"CALLER_ID": caller_id, "CLIENT_IDENTITY": "eva"}.items():
        if key in existing:
            client.request(
                f"{variables_url}/{existing[key]['sid']}", "POST", {"Value": value}
            )
        else:
            client.request(variables_url, "POST", {"Key": key, "Value": value})
    function = client.ensure(
        f"{base}/Functions",
        "functions",
        "friendly_name",
        "dial",
        {"FriendlyName": "dial"},
    )
    version = client.upload(
        f"{UPLOAD}/Services/{service['sid']}/Functions/{function['sid']}/Versions",
        "/dial",
        "protected",
        (HERE / "dial.js").read_bytes(),
        "application/javascript",
    )
    build = client.request(
        f"{base}/Builds",
        "POST",
        {
            "FunctionVersions": [version["sid"]],
            "Runtime": "node22",
            "Dependencies": json.dumps(
                [
                    {"name": "twilio", "version": "6.2.0"},
                    {"name": "@twilio/runtime-handler", "version": "2.0.1"},
                ]
            ),
        },
    )
    while build["status"] == "building":
        time.sleep(2)
        build = client.request(f"{base}/Builds/{build['sid']}")
    if build["status"] != "completed":
        raise Failure(
            f"Serverless build {build['sid']} failed; the TwiML app was not changed"
        )
    client.request(
        f"{base}/Environments/{environment['sid']}/Deployments",
        "POST",
        {"BuildSid": build["sid"]},
    )
    voice_url = f"https://{environment['domain_name']}/dial"
    app_fields = {
        "FriendlyName": VOICE_SDK,
        "VoiceUrl": voice_url,
        "VoiceMethod": "POST",
    }
    app = (
        client.request(
            f"{client.account}/Applications/{apps[0]['sid']}.json", "POST", app_fields
        )
        if apps
        else client.request(f"{client.account}/Applications.json", "POST", app_fields)
    )
    write_voice_entry(
        args.voice_entry,
        secret,
        {
            "account-sid": client.sid,
            "api-key-sid": key_sid,
            "twiml-app-sid": app["sid"],
            "caller-id": caller_id,
        },
    )
    print(
        f"AccountSid: {client.sid}\nApiKeySid: {key_sid}\nTwimlAppSid: {app['sid']}\nVoiceUrl: {voice_url}\n"
        f"CallerId: {caller_id}\nServiceSid: {service['sid']}\nBuildSid: {build['sid']}\nPass entry: {args.voice_entry}"
    )


def event_gathers(events):
    gathered = []
    for item in events:
        request = item.get("request", {})
        query = urllib.parse.parse_qs(
            urllib.parse.urlsplit(request.get("url", "")).query
        )
        params = request.get("parameters", {})
        step = query.get("step", [params.get("step")])[0]
        turn = query.get("turn", [params.get("turn")])[0]
        if step is None or turn is None:
            continue
        response = item.get("response", {})
        gathered.append(
            {
                "step": step,
                "turn": int(turn),
                "digits": params.get("digits"),
                "speech_result": params.get("speech_result"),
                "confidence": params.get("confidence"),
                "date_created": response.get("date_created"),
                "response_code": response.get("response_code"),
                "source": "call-events",
            }
        )
    return sorted(gathered, key=lambda g: (g["turn"], g.get("date_created") or ""))


def log_receipts(logs, call_sid):
    result = []
    for log in logs:
        for line in log.get("message", "").splitlines():
            if "EVA_HOTLINE " not in line:
                continue
            try:
                value = json.loads(line.split("EVA_HOTLINE ", 1)[1])
            except ValueError:
                continue
            if value.get("call_sid") == call_sid:
                result.append(
                    {
                        **value,
                        "source": "function-log",
                        "date_created": log.get("date_created"),
                    }
                )
    return result


def merge_gathers(events, logs, sync_receipts=()):
    # Sync is immediate and durable; Call Events cover failed Sync writes.
    canonical = [g for g in sync_receipts if g.get("kind") == "gather"]
    keys = {(g["step"], g["turn"]) for g in canonical}
    canonical += [
        g for g in event_gathers(events) if (g["step"], g["turn"]) not in keys
    ]
    keys = {(g["step"], g["turn"]) for g in canonical}
    fallback = [
        g
        for g in logs
        if g.get("kind") in {"gather", "gather_deferred"}
        and (g["step"], g["turn"]) not in keys
    ]
    return sorted(
        canonical + fallback,
        key=lambda g: (g["turn"], g.get("received_at") or g.get("date_created") or ""),
    )


def sync_receipts(client, service, environment, sid):
    variables = client.items(
        f"{SERVERLESS}/Services/{service['sid']}/Environments/{environment['sid']}/Variables",
        "variables",
    )
    sync_sid = next(
        (v["value"] for v in variables if v["key"] == "SYNC_SERVICE_SID"), None
    )
    if not sync_sid:
        raise Failure("SYNC_SERVICE_SID missing from hotline environment; rerun setup")
    items = client.items(f"{SYNC}/Services/{sync_sid}/Maps/call-{sid}/Items", "items")
    return [{**item["data"], "source": "sync"} for item in items]


def recording_channels(path):
    outputs = []
    with wave.open(str(path), "rb") as source:
        channels, width, rate = (
            source.getnchannels(),
            source.getsampwidth(),
            source.getframerate(),
        )
        pcm = source.readframes(source.getnframes())
    if channels == 2:
        for channel, label in enumerate(["caller", "hotline"]):
            mono = bytearray()
            for offset in range(channel * width, len(pcm), channels * width):
                mono.extend(pcm[offset : offset + width])
            target = path.with_name(path.stem + f"-{label}.wav")
            with wave.open(str(target), "wb") as dest:
                dest.setnchannels(1)
                dest.setsampwidth(width)
                dest.setframerate(rate)
                dest.writeframes(mono)
            outputs.append(str(target))
    return outputs


def iso_call_time(value):
    return (
        email.utils.parsedate_to_datetime(value).astimezone(dt.timezone.utc).isoformat()
    )


def verify(client, args):
    selected = number(client)
    service, environment = deployment(client)
    calls_url = f"{client.account}/Calls.json"
    if args.call_sid:
        call = client.request(f"{client.account}/Calls/{args.call_sid}.json")
        if (
            call.get("to") != selected["phone_number"]
            or call.get("direction") != "inbound"
        ):
            raise Failure("CallSid is not an inbound call to this EVA test number")
    else:
        calls = client.items(
            calls_url, "calls", {"To": selected["phone_number"], "PageSize": 20}
        )
        # A call from EVA's Voice SDK route also has a client parent call; the hotline leg is the PSTN one.
        calls = [
            c
            for c in calls
            if c["direction"] == "inbound"
            and not c.get("from", "").startswith("client:")
        ]
        if not calls:
            raise Failure("No inbound calls to the EVA number yet")
        call = max(
            calls, key=lambda c: email.utils.parsedate_to_datetime(c["date_created"])
        )
    sid = call["sid"]
    out = BUILD / f"twilio-{sid}"
    out.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.wait
    warnings = []
    while True:
        durable = []
        try:
            durable = sync_receipts(client, service, environment, sid)
        except Failure as error:
            warnings.append(f"Sync receipts unavailable: {error}")
        events = []
        try:
            events = client.items(f"{client.account}/Calls/{sid}/Events.json", "events")
        except Failure as error:
            warnings.append(f"Call Events unavailable: {error}")
        try:
            logs = client.items(
                f"{SERVERLESS}/Services/{service['sid']}/Environments/{environment['sid']}/Logs",
                "logs",
                {
                    "StartDate": iso_call_time(call["date_created"]),
                    "PageSize": 1000,
                },
            )
        except Failure as error:
            logs = []
            warnings.append(f"Function logs unavailable: {error}")
        immediate = log_receipts(logs, sid)
        receipts = durable + immediate
        gathers = merge_gathers(events, immediate, durable)
        try:
            recordings = client.items(
                f"{client.account}/Calls/{sid}/Recordings.json", "recordings"
            )
        except Failure as error:
            recordings = []
            warnings.append(f"Recording list unavailable: {error}")
        terminal = call["status"] in {
            "completed",
            "failed",
            "busy",
            "no-answer",
            "canceled",
        }
        recording_done = (
            bool(recordings)
            and all(
                r["status"] in {"completed", "absent", "failed"} for r in recordings
            )
        ) or any(
            r.get("kind") == "recording_failed"
            or r.get("kind") == "recording"
            and r.get("status") in {"completed", "absent", "failed"}
            for r in receipts
        )
        evidence_ready = bool(events) if args.require_events else bool(gathers)
        if (
            terminal and evidence_ready and recording_done
        ) or time.monotonic() >= deadline:
            break
        time.sleep(min(5, max(0, deadline - time.monotonic())))
        call = client.request(f"{client.account}/Calls/{sid}.json")
    artifacts = []
    for recording in recordings:
        requested_channels = 1 if recording.get("channels") == 1 else 2
        url = f"{client.account}/Recordings/{recording['sid']}.wav?RequestedChannels={requested_channels}"
        artifact = {
            "sid": recording["sid"],
            "status": recording["status"],
            "channels": recording.get("channels"),
            "url": url,
        }
        if recording["status"] == "completed":
            path = out / f"{recording['sid']}.wav"
            try:
                audio = client.request(url, binary=True)
                path.write_bytes(audio)
                artifact["path"] = str(path)
                artifact["channel_paths"] = recording_channels(path)
            except (Failure, wave.Error) as error:
                artifact["download_error"] = str(error)
        artifacts.append(artifact)
    if not durable and not events:
        warnings.append(
            "Only provisional Function logs are available. Sync is unavailable, and Call Events have a documented 15-minute delay. Run verify again later for durable evidence."
        )
    if any(g.get("kind") == "gather_deferred" for g in gathers):
        warnings.append(
            "A Gather exceeds the Function log budget and was not saved to Sync. Retrieve its full content from Call Events later."
        )
    if not gathers:
        warnings.append(
            "No Gather results found; uplink speech and DTMF are not verified."
        )
    if not recordings:
        warnings.append(
            "No recordings found. Inspect recording_failed receipts; trial accounts may prohibit inbound recording."
        )
    report = {
        "call_sid": sid,
        "status": call["status"],
        "duration": call.get("duration"),
        "price": call.get("price"),
        "price_unit": call.get("price_unit"),
        "gathers": gathers,
        "receipts": receipts,
        "recordings": artifacts,
        "warnings": list(dict.fromkeys(warnings)),
    }
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    # Keep only this call's receipts, not unrelated account logs or credential-bearing data.
    (out / "gathers.jsonl").write_text("".join(json.dumps(g) + "\n" for g in gathers))
    print(
        f"CallSid: {sid} | {call['status']} | {call.get('duration') or '?'}s | price={call.get('price') or '?'} {call.get('price_unit') or ''}"
    )
    for g in gathers:
        print(
            f"  {g['turn']:02d} {g['step']}: Digits={json.dumps(g.get('digits'))} SpeechResult={json.dumps(g.get('speech_result'))} Confidence={g.get('confidence')} [{g['source']}]"
        )
    for artifact in artifacts:
        print(
            f"  Recording {artifact['sid']}: {artifact['status']}, channels={artifact['channels']}\n    {artifact['url']}"
        )
        if artifact.get("path"):
            print(f"    {artifact['path']}")
        if artifact.get("download_error"):
            print(f"    Download failed: {artifact['download_error']}")
    for warning in report["warnings"]:
        print(f"  NOTE: {warning}")
    for receipt in receipts:
        if receipt.get("kind") == "recording_failed":
            print(
                f"  Recording failed: Twilio code={receipt.get('error_code')} HTTP={receipt.get('status')}"
            )
    print(f"Artifacts: {out}")
    if args.transcript:
        compare(args.transcript, report)
    return report


def normalized(text):
    return " ".join(re.findall(r"[a-z0-9]+", text.lower()))


def verification_matches(report):
    verification = " ".join(
        g.get("speech_result") or ""
        for g in report["gathers"]
        if g["step"] == "verification"
    )
    words = normalized(verification)
    digits = [
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
    ]
    number_words = re.sub(
        r"\b(" + "|".join(digits) + r")\b", lambda m: str(digits.index(m[0])), words
    )
    return {
        "name": "ivan malison" in words,
        "account": "4417" in number_words.replace(" ", ""),
    }


def compare(transcript, report):
    lines = [
        json.loads(line) for line in transcript.read_text().splitlines() if line.strip()
    ]
    utterances = [
        line["text"]
        for line in lines
        if line.get("who") == "AGENT" and line.get("text", "").strip()
    ]
    heard = [g for g in report["gathers"] if g.get("speech_result")]
    print(
        "\nAGENT intent | closest Twilio SpeechResult (text similarity, not time alignment)"
    )
    for text in utterances:
        candidate = max(
            heard,
            key=lambda g: difflib.SequenceMatcher(
                None, normalized(text), normalized(g["speech_result"])
            ).ratio(),
            default=None,
        )
        match = (
            f"{candidate['step']}: {candidate['speech_result']}"
            if candidate
            else "no speech evidence"
        )
        print(f"  {text} | {match}")
    requested = []
    for line in lines:
        if line.get("who") == "TOOL" and line["text"].startswith("send_dtmf "):
            requested.append(
                json.loads(line["text"].split(" ", 1)[1]).get("digits", "")
            )
    received = [g["digits"] for g in report["gathers"] if g.get("digits")]
    print(f"DTMF intent: {requested} | Twilio Digits: {received}")
    if requested and not received:
        print(
            "DTMF was not acknowledged. This prototype injects in-band tones; Twilio Gather requires RFC 2833/4733 signaling."
        )
    matches = verification_matches(report)
    print(
        f"Name exact text match: {matches['name']}; account 4417 text match: {matches['account']}"
    )
    print(
        "AGENT lines show intended speech, not proof of delivery. Gather/recording evidence determines what arrived."
    )


def idle_states(output):
    states = re.findall(r"\bmCallState\s*=\s*(\d+)", output)
    if not states or any(state != "0" for state in states):
        raise Failure(
            "Phone is busy or call state could not be read; no call was placed"
        )
    return states


def adb(serial, *args):
    result = subprocess.run(
        ["adb", "-s", serial, *args], capture_output=True, text=True, check=False
    )
    if result.returncode:
        raise Failure(
            "adb command failed; check the selected wireless device connection"
        )
    return result.stdout


def wait_for_idle(serial, timeout=300):
    deadline = time.monotonic() + timeout
    while True:
        output = adb(serial, "shell", "dumpsys", "telephony.registry")
        states = re.findall(r"\bmCallState\s*=\s*(\d+)", output)
        if not states:
            raise Failure("Phone call state is unreadable; no call was placed")
        if all(state == "0" for state in states):
            print(f"Phone idle: mCallState={','.join(states)}", flush=True)
            return
        if time.monotonic() >= deadline:
            raise Failure("Phone remained busy for five minutes; no call was placed")
        print("Phone busy; waiting before the test call", flush=True)
        time.sleep(min(15, max(0, deadline - time.monotonic())))


def connect_phone(serial):
    devices = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, check=True
    ).stdout
    if any(line.split() == [serial, "device"] for line in devices.splitlines()):
        return serial
    services = subprocess.run(
        ["adb", "mdns", "services"], capture_output=True, text=True, check=True
    ).stdout
    for line in services.splitlines():
        parts = line.split()
        if (
            len(parts) >= 3
            and parts[0] in {serial, serial.removesuffix("._adb-tls-connect._tcp")}
            and parts[1] == "_adb-tls-connect._tcp"
        ):
            endpoint = parts[-1]
            subprocess.run(
                ["adb", "connect", endpoint], capture_output=True, check=True
            )
            # adb may expose the reconnected device by endpoint rather than mDNS name.
            refreshed = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, check=True
            ).stdout
            for candidate in (serial, endpoint):
                if any(
                    row.split() == [candidate, "device"]
                    for row in refreshed.splitlines()
                ):
                    return candidate
    raise Failure(
        "Wireless Pixel not connected and its mDNS service was not found; no call was placed"
    )


def agent_python():
    executable = os.environ.get("CALL_AGENT_PYTHON", AGENT_PYTHON)
    if not Path(executable).is_file():
        expression = 'with import (builtins.getFlake "nixpkgs") {}; python3.withPackages (p: [p.numpy p.websockets])'
        built = subprocess.run(
            [
                "nix",
                "build",
                "--no-link",
                "--print-out-paths",
                "--impure",
                "--expr",
                expression,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if built.returncode:
            raise Failure(
                "Could not build call-agent Python with Nix; set CALL_AGENT_PYTHON to a Python with numpy and websockets"
            )
        executable = str(Path(built.stdout.strip().splitlines()[-1]) / "bin/python3")
    check = subprocess.run(
        [executable, "-c", "import numpy, websockets"], capture_output=True, check=False
    )
    if check.returncode:
        raise Failure("CALL_AGENT_PYTHON cannot import numpy and websockets")
    return executable


def e2e(client, args):
    selected = number(client)
    _service, environment = deployment(client)
    if selected.get("voice_url") != f"https://{environment['domain_name']}/hotline":
        raise Failure("Test number does not point at this hotline; run setup first")
    python = agent_python()
    if not shutil.which("adb") or not os.environ.get(
        "ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT")
    ):
        raise Failure(
            "Android tools missing; run e2e with direnv exec . from the worktree root"
        )
    if not (Path.home() / ".config/eva/chatgpt.json").is_file():
        raise Failure(
            "EVA desktop ChatGPT tokens missing at ~/.config/eva/chatgpt.json"
        )
    serial = connect_phone(args.serial)
    before = {
        c["sid"]
        for c in client.items(
            f"{client.account}/Calls.json", "calls", {"To": selected["phone_number"]}
        )
    }
    wait_for_idle(serial)
    select = "press 1 with send_dtmf" if args.ivr == "keypad" else "say billing"
    menu_instruction = (
        "At the automated menu, stay completely silent and use the keypad tool before speaking. "
        if args.ivr == "keypad"
        else "At the automated menu, say only billing. "
    )
    goal = (
        "Do not narrate actions or give acknowledgements. "
        f"{menu_instruction}"
        f"Test this fake hotline: when its menu asks, {select} for billing. "
        "When asked for account verification, say 'Ivan Malison, account number four four one seven'. "
        "Stay silent through the hold music and interjection. When the test representative asks how to help, "
        "ask for Test Company's business hours. Listen to the answer, then respond 'No, that's all, thank you' "
        "to 'Is there anything else?'. Wait for goodbye before finishing. "
        "If Twilio's trial preamble explicitly requires a keypress, send_dtmf 1 once at that prompt, then wait for the actual menu."
    )
    out = BUILD / time.strftime("call-twilio-%Y%m%d-%H%M%S")
    # Reserve an unused bridge port without displacing another agent's adb forward.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env = {**os.environ, "ANDROID_SERIAL": serial}
    wait_for_idle(serial)
    print(
        f"Calling only {selected['phone_number']}; agent artifacts: {out}", flush=True
    )
    # stdin=DEVNULL prevents call_agent's executor from waiting for an owner answer at exit.
    process = subprocess.Popen(
        [
            python,
            str(HERE / "agent_runner.py"),
            "--dial",
            selected["phone_number"],
            "--goal",
            goal,
            "--disclosures",
            "Ivan Malison; test account number 4417",
            "--serial",
            serial,
            "--port",
            str(port),
            "--out",
            str(out),
        ],
        cwd=ROOT,
        env=env,
        stdin=subprocess.DEVNULL,
    )
    try:
        process.wait(timeout=args.max_seconds)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        # End only the Twilio test call. Do not synthesize UI input if another call took over.
        new_calls = [
            c
            for c in client.items(
                f"{client.account}/Calls.json",
                "calls",
                {"To": selected["phone_number"]},
            )
            if c["sid"] not in before and c["direction"] == "inbound"
        ]
        for call in new_calls:
            if call["status"] == "in-progress":
                client.request(
                    f"{client.account}/Calls/{call['sid']}.json",
                    "POST",
                    {"Status": "completed"},
                )
        print(
            "Agent interrupted or exceeded the short test duration; Twilio test call ended. Verify artifacts for partial evidence.",
            file=sys.stderr,
        )
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    finally:
        adb(serial, "forward", "--remove", f"tcp:{port}")
    calls = [
        c
        for c in client.items(
            f"{client.account}/Calls.json", "calls", {"To": selected["phone_number"]}
        )
        if c["sid"] not in before and c["direction"] == "inbound"
    ]
    if len(calls) != 1:
        raise Failure(
            f"Expected one new inbound test call, found {len(calls)}; run verify with an explicit CallSid. Agent artifacts: {out}"
        )
    if calls[0]["status"] == "in-progress":
        client.request(
            f"{client.account}/Calls/{calls[0]['sid']}.json",
            "POST",
            {"Status": "completed"},
        )
        print(
            "Prototype exited while its Twilio call was active; ended that test call via Twilio.",
            file=sys.stderr,
        )
    args.call_sid = calls[0]["sid"]
    args.transcript = out / "transcript.jsonl"
    args.require_events = False
    report = verify(client, args)
    matches = verification_matches(report)
    menu = [g for g in report["gathers"] if g["step"] == "menu"]
    ivr_verified = (
        any(g.get("digits") == "1" for g in menu)
        if args.ivr == "keypad"
        else any("billing" in normalized(g.get("speech_result") or "") for g in menu)
    )
    representative = [
        normalized(g.get("speech_result") or "")
        for g in report["gathers"]
        if g["step"] == "representative"
    ]
    hours_requested = any(
        re.search(r"\b(hours|open|opening|close|closing)\b", text)
        for text in representative
    )
    final = [g for g in report["gathers"] if g["step"] == "anything_else"]
    goodbye = any(
        re.search(
            r"\b(no|nope|nothing|all set|that is all|that's all|goodbye)\b",
            g.get("speech_result") or "",
            re.IGNORECASE,
        )
        for g in final
    )
    success = (
        matches["name"]
        and matches["account"]
        and ivr_verified
        and hours_requested
        and goodbye
    )
    print(
        f"Test scenario evidence: {'complete' if success else 'incomplete or mismatched; inspect Gather results and audio'}"
    )
    return 0 if success else 1


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "--pass-entry",
        default=os.environ.get("TWILIO_PASS_ENTRY", "twilio.com"),
        help="pass entry; never a plaintext credential file",
    )
    result.add_argument("--authenticated", action="store_true", help=argparse.SUPPRESS)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "status",
        help="show account type, provisioned numbers, and inbound usage without secrets",
    )
    setup_parser = commands.add_parser(
        "setup", help="deploy Functions and reuse the provisioned US local number"
    )
    setup_parser.add_argument("--scenario", type=Path, default=HERE / "scenario.json")
    setup_parser.add_argument(
        "--provision",
        action="store_true",
        help="provision ONE US local voice number only if the account owns none",
    )
    setup_parser.add_argument(
        "--reuse-number",
        help="reuse this existing PN SID or E.164 number",
    )
    verify_parser = commands.add_parser(
        "verify", help="retrieve Gather receipts and dual-channel recordings"
    )
    verify_parser.add_argument(
        "call_sid", nargs="?", help="default: latest inbound call to the hotline"
    )
    verify_parser.add_argument(
        "--transcript", type=Path, help="call_agent.py transcript.jsonl for comparison"
    )
    verify_parser.add_argument(
        "--require-events",
        action="store_true",
        help="wait for complete Call Events rather than immediate Function logs",
    )
    verify_parser.add_argument(
        "--wait",
        type=float,
        default=45,
        help="seconds to wait for evidence/recordings (e.g. 960 for Call Events)",
    )
    voice_parser = commands.add_parser(
        "voice-sdk",
        help="plan (or with --apply create) the API key, dial Function and TwiML app for EVA's Twilio route",
    )
    voice_parser.add_argument(
        "--apply",
        action="store_true",
        help="create or update the resources; only after the owner approves the plan",
    )
    voice_parser.add_argument(
        "--caller-id", help="number the other party sees (default: the hotline number)"
    )
    voice_parser.add_argument(
        "--voice-entry",
        default=os.environ.get("TWILIO_VOICE_PASS_ENTRY", "twilio.com/eva-voice-sdk"),
        help="pass entry that receives the API key secret and the route's identifiers",
    )
    e2e_parser = commands.add_parser(
        "e2e", help="check Pixel idle, run ONE short test call, verify uplink"
    )
    e2e_parser.add_argument(
        "--serial", default=os.environ.get("ANDROID_SERIAL", SERIAL)
    )
    e2e_parser.add_argument(
        "--ivr",
        choices=["keypad", "voice"],
        default="voice",
        help="voice by default; keypad tests the prototype's in-band DTMF limitation",
    )
    e2e_parser.add_argument(
        "--max-seconds",
        type=float,
        default=210,
        help="short test-call budget; partial artifacts preserved",
    )
    e2e_parser.add_argument(
        "--wait",
        type=float,
        default=45,
        help="seconds to wait for immediate evidence/recordings",
    )
    return result


def main():
    args = parser().parse_args()
    try:
        if (
            args.command == "verify"
            and args.call_sid
            and not re.fullmatch(r"CA[0-9a-fA-F]{32}", args.call_sid)
        ):
            raise Failure("CallSid must be CA followed by 32 hex characters")
        if not args.authenticated:
            return authenticate_subprocess(args)
        client = Client()
        if args.command == "status":
            account = client.request(f"{client.account}.json")
            print(
                f"AccountSid: {account['sid']} | type={account['type']} | status={account['status']}"
            )
            numbers = client.items(
                f"{client.account}/IncomingPhoneNumbers.json", "incoming_phone_numbers"
            )
            print(f"Provisioned number count: {len(numbers)}")
            for item in numbers:
                print(
                    f"NumberSid: {item['sid']} | Phone: {item['phone_number']} | FriendlyName: {item['friendly_name']}"
                )
            usage = client.items(
                f"{client.account}/Usage/Records/ThisMonth.json",
                "usage_records",
                {"Category": "calls-inbound"},
            )
            for item in usage:
                print(
                    f"Inbound usage this month: {item['usage']} {item['usage_unit']} | price={item['price']} {item['price_unit']}"
                )
        elif args.command == "setup":
            (HERE / ".cache").mkdir(exist_ok=True)
            with (HERE / ".cache/setup.lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                setup(client, args)
        elif args.command == "voice-sdk":
            (HERE / ".cache").mkdir(exist_ok=True)
            with (HERE / ".cache/setup.lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                voice_sdk(client, args)
        elif args.command == "verify":
            report = verify(client, args)
            return (
                1
                if not report["gathers"]
                or any(g.get("kind") == "gather_deferred" for g in report["gathers"])
                else 0
            )
        else:
            return e2e(client, args)
        return 0
    except (
        Failure,
        OSError,
        ValueError,
        KeyError,
        subprocess.SubprocessError,
    ) as error:
        # Only controlled Failures are safe to print; unknown errors may include request data.
        print(
            f"ERROR: {error if isinstance(error, Failure) else type(error).__name__ + '; check prerequisites and rerun'}",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    sys.exit(main())
