import argparse
import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
import urllib.request
import wave
from pathlib import Path
from unittest.mock import Mock, patch

import hotline as h

SID = "AC" + "a" * 32
TOKEN = "b" * 32
CALL = "CA" + "c" * 32
NUMBER = {
    "sid": "PNtest",
    "friendly_name": h.NAME,
    "phone_number": "+14155550100",
    "capabilities": {"voice": True},
}


class HotlineTest(unittest.TestCase):
    def test_password_and_unrelated_fields_are_discarded_before_credential_parser(self):
        source_text = f"account-sid: password-line-must-be-ignored\nusername: ignored\naccount-sid: {SID}\nauth-token: {TOKEN}\npassword: ignored\n"
        real_popen = subprocess.Popen
        with (
            patch.object(
                h.subprocess,
                "Popen",
                side_effect=lambda *args, **kwargs: real_popen(
                    [
                        sys.executable,
                        "-c",
                        "import sys; sys.stdout.write(sys.argv[1])",
                        source_text,
                    ]
                    if args[0][0] == "pass"
                    else args[0],
                    **kwargs,
                ),
            ),
            patch.object(h, "parse_credentials", wraps=h.parse_credentials) as parser,
        ):
            self.assertEqual(h.read_credentials("twilio.com"), (SID, TOKEN))
        self.assertEqual(
            parser.call_args.args[0], f"account-sid: {SID}\nauth-token: {TOKEN}\n"
        )

    def test_credentials_accept_both_documented_formats_and_reject_ambiguity(self):
        for raw in [
            f"{SID}\n{TOKEN}",
            f"Account SID: {SID}\nAuth Token: {TOKEN}",
            f"TWILIO_ACCOUNT_SID: {SID}\nTWILIO_AUTH_TOKEN: {TOKEN}",
        ]:
            self.assertEqual(h.parse_credentials(raw), (SID, TOKEN))
        for raw in [
            f"{TOKEN}\n{SID}",
            f"{SID}\n{TOKEN}\nextra",
            f"Account SID: {SID}\nAuth Token: {TOKEN}\nunknown: value",
            "not credentials",
        ]:
            with self.assertRaises(h.Failure) as caught:
                h.parse_credentials(raw)
            self.assertNotIn(TOKEN, str(caught.exception))

    def test_existing_numbers_are_reused_and_other_numbers_never_trigger_a_purchase(
        self,
    ):
        self.assertEqual(h.choose_number([NUMBER]), NUMBER)
        renamed = {**NUMBER, "friendly_name": "EVA Testing"}
        self.assertEqual(h.choose_number([renamed]), renamed)
        self.assertIsNone(h.choose_number([]))
        other = {**NUMBER, "friendly_name": "other"}
        with self.assertRaises(h.Failure):
            h.choose_number([other])
        self.assertEqual(h.choose_number([other], "PNtest"), other)
        with self.assertRaises(h.Failure):
            h.choose_number([NUMBER, NUMBER])

    def test_idle_guard_rejects_busy_missing_and_mixed_sim_states(self):
        self.assertEqual(h.idle_states("mCallState=0\n  mCallState=0"), ["0", "0"])
        for value in ["mCallState=1", "mCallState=0\nmCallState=2", "unreadable"]:
            with self.assertRaises(h.Failure):
                h.idle_states(value)

    def test_idle_wait_rechecks_busy_phone_without_dialing(self):
        with (
            patch.object(h, "adb", side_effect=["mCallState=2", "mCallState=0"]),
            patch.object(h.time, "sleep"),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            h.wait_for_idle("test")
        with (
            patch.object(h, "adb", return_value="mCallState=2"),
            patch.object(h.time, "sleep"),
            patch.object(h.time, "monotonic", side_effect=[0, 301]),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaises(h.Failure),
        ):
            h.wait_for_idle("test")

    def test_sync_is_preferred_and_events_fill_gaps_without_dropping_empty_gathers(
        self,
    ):
        events = [
            {
                "request": {
                    "url": "https://hotline/hotline?step=menu&turn=0",
                    "parameters": {
                        "digits": "1",
                        "speech_result": "billing",
                        "confidence": "0.9",
                    },
                }
            },
            {
                "request": {
                    "url": "https://hotline/hotline?step=verification&turn=1",
                    "parameters": {},
                }
            },
        ]
        logs = [
            {"kind": "gather", "step": "menu", "turn": 0, "digits": "log"},
            {
                "kind": "gather",
                "step": "representative",
                "turn": 2,
                "speech_result": "hours",
            },
        ]
        sync = [
            {
                "kind": "gather",
                "step": "menu",
                "turn": 0,
                "digits": "1",
                "source": "sync",
            }
        ]
        result = h.merge_gathers(events, logs, sync)
        self.assertEqual([g["turn"] for g in result], [0, 1, 2])
        self.assertEqual(result[0]["source"], "sync")
        self.assertIsNone(result[1]["speech_result"])
        self.assertEqual(result[2]["speech_result"], "hours")

    def test_log_filter_keeps_only_this_calls_receipts(self):
        value = {"kind": "gather", "call_sid": CALL, "turn": 1}
        logs = [
            {"message": "noise\nEVA_HOTLINE " + json.dumps(value)},
            {"message": "EVA_HOTLINE {incomplete"},
            {"message": 'EVA_HOTLINE {"call_sid":"other"}'},
        ]
        self.assertEqual(len(h.log_receipts(logs, CALL)), 1)

    def test_channel_split_preserves_inbound_caller_and_hotline_samples(self):
        cache = h.HERE / ".cache"
        cache.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=cache) as directory:
            path = Path(directory) / "recording.wav"
            with wave.open(str(path), "wb") as wav:
                wav.setnchannels(2)
                wav.setsampwidth(2)
                wav.setframerate(8000)
                wav.writeframes(b"\x01\x00\x02\x00\x03\x00\x04\x00")
            caller, hotline = h.recording_channels(path)
            with wave.open(caller) as wav:
                self.assertEqual(wav.readframes(2), b"\x01\x00\x03\x00")
            with wave.open(hotline) as wav:
                self.assertEqual(wav.readframes(2), b"\x02\x00\x04\x00")

    def test_synthesized_hold_music_has_exact_segment_duration(self):
        with wave.open(io.BytesIO(h.hold_music(12))) as wav:
            self.assertEqual(wav.getnframes() / wav.getframerate(), 12)

    def test_recording_redirect_does_not_forward_credentials_to_cdn(self):
        request = urllib.request.Request(
            "https://api.twilio.com/audio", headers={"Authorization": "Basic test"}
        )
        redirect = h.SafeRedirect().redirect_request(
            request, None, 302, "found", {}, "https://media.twiliocdn.com/audio"
        )
        self.assertIsNone(redirect.get_header("Authorization"))
        with self.assertRaises(h.Failure):
            h.SafeRedirect().redirect_request(
                request, None, 302, "found", {}, "http://media.twiliocdn.com/audio"
            )

    def test_name_and_account_report_matches_spoken_digits_and_rejects_wrong_number(
        self,
    ):
        report = {
            "gathers": [
                {
                    "step": "verification",
                    "speech_result": "Ivan Malison, account four four one seven",
                }
            ]
        }
        self.assertEqual(
            h.verification_matches(report), {"name": True, "account": True}
        )
        report["gathers"][0]["speech_result"] = "Ivan Malison 4418"
        self.assertEqual(
            h.verification_matches(report), {"name": True, "account": False}
        )

    def test_setup_twice_reuses_the_existing_number_and_all_named_resources(self):
        client = FakeClient()
        args = argparse.Namespace(scenario=h.HERE / "scenario.json", reuse_number=None)
        with (
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            h.setup(client, args)
            h.setup(client, args)
        self.assertEqual(client.purchases, 0)
        self.assertEqual(
            client.resources[f"{h.SYNC}/Services"][0]["friendly_name"], h.NAME
        )
        self.assertEqual(len(client.resources[f"{h.SERVERLESS}/Services"]), 1)
        self.assertEqual(
            len(client.resources[f"{h.SERVERLESS}/Services/ZSservice/Environments"]), 1
        )
        self.assertEqual(
            len(client.resources[f"{h.SERVERLESS}/Services/ZSservice/Functions"]), 1
        )
        self.assertTrue(
            all(
                upload[2] in {"private", "protected", "public"}
                for upload in client.uploads
            )
        )
        self.assertEqual(
            client.numbers[0]["voice_url"], "https://eva-test.twil.io/hotline"
        )

    def test_setup_build_failure_does_not_buy_a_number(self):
        client = FakeClient(build_status="failed")
        args = argparse.Namespace(scenario=h.HERE / "scenario.json", reuse_number=None)
        with (
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
            self.assertRaises(h.Failure),
        ):
            h.setup(client, args)
        self.assertEqual(client.purchases, 0)

    def test_setup_never_purchases_when_the_number_is_missing(self):
        client = FakeClient()
        client.numbers = []
        args = argparse.Namespace(scenario=h.HERE / "scenario.json", reuse_number=None)
        with self.assertRaises(h.Failure):
            h.setup(client, args)
        self.assertEqual(client.purchases, 0)
        self.assertFalse(client.resources)

    def test_explicit_provisioning_buys_once_then_reuses_the_number(self):
        client = FakeClient()
        client.numbers = []
        args = argparse.Namespace(
            scenario=h.HERE / "scenario.json", reuse_number=None, provision=True
        )
        with (
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            h.setup(client, args)
            h.setup(client, args)
        self.assertEqual(client.purchases, 1)

    def test_default_entry_can_be_overridden_without_exposing_credentials(self):
        with patch.dict(h.os.environ, {"TWILIO_PASS_ENTRY": "custom-entry"}):
            self.assertEqual(
                h.parser().parse_args(["status"]).pass_entry, "custom-entry"
            )

    def test_trial_provisioning_rejection_names_the_required_owner_action(self):
        self.assertIn("owner to upgrade", str(h.ApiFailure(400, 21404)))

    def test_api_error_detail_redacts_credentials_before_reporting(self):
        client = object.__new__(h.Client)
        client.authorization = (
            "Basic " + h.base64.b64encode(f"{SID}:{TOKEN}".encode()).decode()
        )
        payload = {
            "code": 20001,
            "message": f"Invalid dependency; {TOKEN}; {client.authorization}",
        }
        error = h.urllib.error.HTTPError(
            "https://serverless.twilio.com/v1/Services",
            400,
            "Bad Request",
            {},
            io.BytesIO(json.dumps(payload).encode()),
        )
        client.opener = Mock()
        client.opener.open.side_effect = error
        with self.assertRaises(h.ApiFailure) as caught:
            client.request("https://serverless.twilio.com/v1/Services", "POST", {})
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertNotIn(client.authorization, str(caught.exception))
        self.assertIn("Invalid dependency", str(caught.exception))

    def test_paginated_api_does_not_silently_drop_a_second_page(self):
        client = object.__new__(h.Client)
        with patch.object(
            client,
            "request",
            side_effect=[
                {
                    "items": [1],
                    "meta": {"next_page_url": "https://sync.twilio.com/v1/next"},
                },
                {"items": [2]},
            ],
        ):
            self.assertEqual(
                client.items("https://sync.twilio.com/v1/first", "items"), [1, 2]
            )

    def test_verify_uses_immediate_sync_when_logs_and_call_events_are_unavailable(self):
        client = FakeVerifyClient()
        args = argparse.Namespace(
            call_sid=CALL, wait=0, require_events=False, transcript=None
        )
        with tempfile.TemporaryDirectory(dir=h.HERE / ".cache") as directory:
            with (
                patch.object(h, "BUILD", Path(directory)),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                report = h.verify(client, args)
            self.assertEqual(report["gathers"][0]["source"], "sync")
            self.assertEqual(
                report["gathers"][0]["speech_result"], "Ivan Malison, 4417"
            )
            self.assertEqual(report["duration"], "85")
            artifact = Path(directory) / f"twilio-{CALL}" / "report.json"
            self.assertTrue(artifact.is_file())
            self.assertNotIn(TOKEN, artifact.read_text())
            self.assertFalse(
                any("provisional" in warning for warning in report["warnings"])
            )


class FakeVerifyClient:
    account = f"{h.API}/Accounts/{SID}"

    def request(self, url, method="GET", data=None, binary=False):
        if "/Calls/" in url:
            return {
                "sid": CALL,
                "to": NUMBER["phone_number"],
                "direction": "inbound",
                "status": "completed",
                "duration": "85",
                "date_created": "Wed, 14 Oct 2026 12:00:00 +0000",
            }
        raise AssertionError(f"Unexpected request: {url}")

    def items(self, url, key, params=None):
        if key == "incoming_phone_numbers":
            return [NUMBER]
        if key == "services":
            return [{"sid": "ZSservice", "unique_name": h.NAME}]
        if key == "environments":
            return [{"sid": "ZEenv", "unique_name": "test"}]
        if key == "variables":
            return [{"key": "SYNC_SERVICE_SID", "value": "ISsync"}]
        if key == "items":
            return [
                {
                    "data": {
                        "kind": "gather",
                        "call_sid": CALL,
                        "step": "verification",
                        "turn": 1,
                        "speech_result": "Ivan Malison, 4417",
                        "confidence": "0.95",
                    }
                }
            ]
        if key == "events":
            raise h.ApiFailure(404)
        if key == "logs":
            raise h.ApiFailure(503)
        if key == "recordings":
            return []
        raise AssertionError(f"Unexpected list: {url}")


class FakeClient:
    ensure = h.Client.ensure

    def __init__(self, build_status="completed"):
        self.account = f"{h.API}/Accounts/{SID}"
        self.numbers = [dict(NUMBER)]
        self.resources = {}
        self.purchases = 0
        self.uploads = []
        self.build_status = build_status

    def items(self, url, key, params=None):
        if url.endswith("IncomingPhoneNumbers.json"):
            return self.numbers
        return self.resources.get(url, [])

    def request(self, url, method="GET", data=None):
        if "/AvailablePhoneNumbers/" in url:
            return {
                "available_phone_numbers": [{"phone_number": NUMBER["phone_number"]}]
            }
        if url.endswith("IncomingPhoneNumbers.json"):
            self.purchases += 1
            self.numbers.append({**NUMBER, "voice_url": data.get("VoiceUrl", "")})
            return self.numbers[0]
        if "/IncomingPhoneNumbers/PNtest.json" in url:
            self.numbers[0]["voice_url"] = data["VoiceUrl"]
            return self.numbers[0]
        if url.endswith("Builds"):
            self.assert_build(data)
            return {"sid": "ZBbuild", "status": self.build_status}
        if url.endswith(("Deployments", "ZSservice")) or "/Variables/" in url:
            return {}
        if url == f"{self.account}.json":
            return {"type": "Trial"}
        identifiers = {
            "Services": "ISsync" if url.startswith(h.SYNC) else "ZSservice",
            "Environments": "ZEenv",
            "Functions": "ZHfunction",
            "Assets": "ZHasset",
            "Variables": "ZVvariable",
        }
        kind = url.rsplit("/", 1)[-1]
        if method == "POST" and kind in identifiers:
            resource = {"sid": identifiers[kind], "domain_name": "eva-test.twil.io"}
            for key, value in data.items():
                resource[
                    {
                        "UniqueName": "unique_name",
                        "FriendlyName": "friendly_name",
                        "Key": "key",
                        "Value": "value",
                    }.get(key, key)
                ] = value
            self.resources.setdefault(url, []).append(resource)
            return resource
        raise AssertionError(f"Unexpected API operation: {method} {url}")

    @staticmethod
    def assert_build(data):
        assert len(data["FunctionVersions"]) == 1
        assert len(data["AssetVersions"]) == 2
        assert data["Runtime"] == "node22"

    def upload(self, url, path, visibility, content, mime):
        self.uploads.append((url, path, visibility, mime))
        return {"sid": "ZN" + str(len(self.uploads))}


if __name__ == "__main__":
    unittest.main()
