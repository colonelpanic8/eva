import asyncio
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock


@unittest.skipUnless(
    importlib.util.find_spec("numpy") and importlib.util.find_spec("websockets"),
    "requires call-agent Python dependencies",
)
class AgentRunnerTest(unittest.TestCase):
    def test_phase_and_keypad_receipts_wait_for_remote_audio_instead_of_generating_speech(
        self,
    ):
        import agent_runner

        cache = Path(__file__).resolve().parent / ".cache"
        cache.mkdir(exist_ok=True)
        with (
            tempfile.TemporaryDirectory(dir=cache) as directory,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            call = agent_runner.HotlineCall(SimpleNamespace(out=directory, serial=None))
            call.ws = SimpleNamespace(send=AsyncMock())
            try:
                asyncio.run(call._tool("phase", "report_phase", {"phase": "hold"}))
                self.assertFalse(call.outgoing)
                asyncio.run(call._tool("keypad", "send_dtmf", {"digits": "1"}))
                self.assertGreater(len(call.outgoing), 0)
                messages = [
                    json.loads(item.args[0]) for item in call.ws.send.call_args_list
                ]
                self.assertEqual(
                    [message["type"] for message in messages],
                    ["conversation.item.create", "conversation.item.create"],
                )
                self.assertEqual(
                    json.loads(messages[-1]["item"]["output"])["sent"], "1"
                )
            finally:
                call.close()


if __name__ == "__main__":
    unittest.main()
