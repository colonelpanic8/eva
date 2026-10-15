"""Run the existing call prototype with silent phase and keypad tool receipts."""

import asyncio
import importlib.util
import json
from pathlib import Path

PROTOTYPE = Path(__file__).resolve().parent.parent / "call_agent.py"
SPEC = importlib.util.spec_from_file_location("call_agent", PROTOTYPE)
prototype = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prototype)
for tool in prototype.TOOLS:
    if tool["name"] == "report_phase":
        tool["description"] = (
            "Silently report waiting on hold, only after hearing hold music or a hold announcement. Never use this before replying to a menu or representative."
        )
        tool["parameters"]["properties"]["phase"]["enum"] = ["hold"]


class HotlineCall(prototype.Call):
    async def _tool(self, call_id, name, args):
        if name not in {"report_phase", "send_dtmf"}:
            return await super()._tool(call_id, name, args)
        self.note("TOOL", f"{name} {json.dumps(args)}")
        result = {"ok": True}
        if name == "send_dtmf":
            self.outgoing += prototype.dtmf_pcm(args.get("digits", ""))
            result["sent"] = args.get("digits", "")
        await self.ws.send(
            json.dumps(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": json.dumps(result),
                    },
                }
            )
        )


if __name__ == "__main__":
    prototype.Call = HotlineCall
    asyncio.run(prototype.main())
