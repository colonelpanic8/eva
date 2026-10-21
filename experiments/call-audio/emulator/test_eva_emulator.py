import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import eva_emulator


class HarnessTest(unittest.TestCase):
    def test_gate_phrases_include_bundled_fallback_keys(self):
        old = b"messages:\n  mutation-uncertain: >-\n    Old refusal. Check status.\n"
        new = (
            old
            + b"  uncertain-repeat: >-\n    Earlier {title} has an unknown outcome. Check status.\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            wording = Path(directory) / "eva-wording.yaml"
            wording.write_bytes(new)
            with (
                patch.object(eva_emulator, "read_private", return_value=old),
                patch.object(eva_emulator, "WORDING", wording),
            ):
                self.assertEqual(
                    eva_emulator.gate_wording("emulator-test"),
                    ["Old refusal", "has an unknown outcome"],
                )

    def test_rejected_start_does_not_end_an_open_turn_before_retry(self):
        refused = {
            "call_id": "first",
            "capability_id": "eva.call.start",
            "status": "NOT_EXECUTED",
            "message": "Missing credential",
        }
        open_turn = {"id": "turn", "status": "OPEN"}
        task = {"id": "second", "state": {"type": "CallTaskState.NotAccomplished"}}
        before = {"invocations": [refused], "notices": [], "turns": [open_turn]}
        after = {"invocations": [refused], "notices": [], "turns": [open_turn]}
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(eva_emulator, "pull_databases", return_value=[]),
            patch.object(
                eva_emulator.card_test, "read_records", side_effect=[before, after]
            ),
            patch.object(
                eva_emulator.card_test, "fold_tasks", side_effect=[[], [task]]
            ),
            patch.object(eva_emulator.time, "sleep"),
        ):
            _, _, tasks = eva_emulator.card_wait(
                "emulator-test", Path(directory), set(), 10
            )
            self.assertEqual(tasks, [task])
