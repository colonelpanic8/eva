"""Prototype phone-call agent: a Realtime voice session conducting a cellular call on the phone.

The phone side is CallAudioProbe's `bridge`, running as shell over adb: it streams the call
downlink and injects whatever we send into the uplink. This script connects that audio to an
OpenAI Realtime session (EVA desktop's ChatGPT sign-in), runs the call agent's tools, and lets
the owner whisper to the agent or answer its questions by typing.

    python3 call_agent.py --dial +15551234567 --goal "Ask what their hours are on Saturday"
"""

import argparse
import asyncio
import base64
import json
import os
import subprocess
import sys
import time
import wave

import numpy as np
import websockets

RATE = 24000
FRAME = RATE // 50  # 20 ms
HERE = os.path.dirname(os.path.abspath(__file__))
DTMF = {
    "1": (697, 1209), "2": (697, 1336), "3": (697, 1477), "A": (697, 1633),
    "4": (770, 1209), "5": (770, 1336), "6": (770, 1477), "B": (770, 1633),
    "7": (852, 1209), "8": (852, 1336), "9": (852, 1477), "C": (852, 1633),
    "*": (941, 1209), "0": (941, 1336), "#": (941, 1477), "D": (941, 1633),
}

TOOLS = [
    {
        "type": "function",
        "name": "send_dtmf",
        "description": "Press keys on the phone keypad, for phone menus or entering numbers. Digits 0-9, * and #.",
        "parameters": {"type": "object", "properties": {"digits": {"type": "string"}}, "required": ["digits"]},
    },
    {
        "type": "function",
        "name": "ask_owner",
        "description": "Ask the person you are calling for a question only they can answer. Tell the other party "
        "you need a moment before calling this. The answer arrives as this tool's result.",
        "parameters": {"type": "object", "properties": {"question": {"type": "string"}}, "required": ["question"]},
    },
    {
        "type": "function",
        "name": "report_phase",
        "description": "Report what is happening on the call: ivr, hold, human, transferring, verifying.",
        "parameters": {
            "type": "object",
            "properties": {"phase": {"type": "string", "enum": ["ivr", "hold", "human", "transferring", "verifying"]}},
            "required": ["phase"],
        },
    },
    {
        "type": "function",
        "name": "finish",
        "description": "End the task and hang up after saying goodbye. Give the outcome and the evidence "
        "(what the other party actually said, reference numbers).",
        "parameters": {
            "type": "object",
            "properties": {
                "outcome": {"type": "string", "enum": ["accomplished", "not_accomplished", "uncertain"]},
                "summary": {"type": "string"},
            },
            "required": ["outcome", "summary"],
        },
    },
]


def instructions(args):
    return f"""You are EVA, an AI assistant on a phone call on behalf of {args.owner}.
Your goal: {args.goal}
Background you may use: {args.briefing or "none"}
Information you may disclose when asked: {args.disclosures or "only the owner's name"}

You hear only the other party. Talk like a courteous, concise person on the phone: short sentences,
one thing at a time, no lists or markdown. When you reach a person, say you are an AI assistant calling
on behalf of {args.owner}. For automated menus, press keys with send_dtmf or say the option. On hold
or during music, stay completely silent and call report_phase. Never invent facts: if you need something
you were not given, tell them you need a moment and call ask_owner. Do not agree to anything beyond the
goal without asking the owner. Before hanging up, confirm the result and any reference number, then call
finish with the outcome and the evidence. Messages labelled [Owner] come from {args.owner} privately and
are not heard by the other party; follow them."""


def dtmf_pcm(digits):
    out = []
    t = np.arange(int(RATE * 0.16)) / RATE
    gap = np.zeros(int(RATE * 0.12))
    for d in digits.upper():
        if d not in DTMF:
            continue
        lo, hi = DTMF[d]
        out += [(np.sin(2 * np.pi * lo * t) + np.sin(2 * np.pi * hi * t)) * 7000, gap]
    return np.concatenate(out).astype(np.int16).tobytes() if out else b""


class Call:
    def __init__(self, args):
        self.args = args
        self.adb = ["adb"] + (["-s", args.serial] if args.serial else [])
        self.outgoing = bytearray()
        self.pending_question = None
        self.done = asyncio.Event()
        self.log = open(os.path.join(args.out, "transcript.jsonl"), "a")
        self.down_wav = self._wav("downlink.wav")
        self.up_wav = self._wav("uplink.wav")
        self.started = time.time()

    def _wav(self, name):
        w = wave.open(os.path.join(self.args.out, name), "wb")
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        return w

    def note(self, who, text):
        stamp = time.time() - self.started
        print(f"[{stamp:6.1f}s] {who}: {text}", flush=True)
        self.log.write(json.dumps({"t": round(stamp, 2), "who": who, "text": text}) + "\n")
        self.log.flush()

    async def run(self):
        port = self.args.port
        subprocess.run(self.adb + ["forward", f"tcp:{port}", f"tcp:{port}"], check=True, capture_output=True)
        subprocess.run([os.path.join(HERE, "probe.sh"), "check"], check=True, capture_output=True,
                       env={**os.environ, **({"ANDROID_SERIAL": self.args.serial} if self.args.serial else {})})
        self.bridge = await asyncio.create_subprocess_exec(
            *self.adb, "shell", "CLASSPATH=/data/local/tmp/call-audio-probe.dex", "app_process", "/", "CallAudioProbe",
            "bridge", str(port), str(RATE), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        asyncio.create_task(self._bridge_log())
        await asyncio.sleep(1.5)
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        if self.args.dial:
            subprocess.run(self.adb + ["shell", "am", "start", "-a", "android.intent.action.CALL", "-d",
                                       "tel:" + self.args.dial], check=True, capture_output=True)
            self.note("SYSTEM", f"dialing {self.args.dial}")
        tokens = json.load(open(os.path.expanduser("~/.config/eva/chatgpt.json")))
        headers = {"Authorization": "Bearer " + tokens["access_token"], "originator": "eva",
                   "chatgpt-account-id": tokens["account_id"]}
        async with websockets.connect(f"wss://api.openai.com/v1/realtime?model={self.args.model}",
                                      additional_headers=headers, max_size=None) as ws:
            self.ws = ws
            await ws.send(json.dumps({"type": "session.update", "session": {
                "type": "realtime",
                "instructions": instructions(self.args),
                "output_modalities": ["audio"],
                "audio": {
                    "input": {
                        "format": {"type": "audio/pcm", "rate": RATE},
                        "transcription": {"model": "gpt-transcribe"},
                        "turn_detection": {"type": "server_vad", "create_response": True, "interrupt_response": True,
                                           "silence_duration_ms": 700},
                    },
                    "output": {"format": {"type": "audio/pcm", "rate": RATE}, "voice": self.args.voice},
                },
                "tools": TOOLS,
                "tool_choice": "auto",
            }}))
            tasks = [asyncio.create_task(c) for c in
                     (self._downlink(reader), self._uplink(writer), self._events(), self._owner())]
            finished = asyncio.create_task(self.done.wait())
            await asyncio.wait(tasks + [finished], return_when=asyncio.FIRST_COMPLETED)
            for t in tasks:
                if t.done() and t.exception():
                    self.note("ERROR", repr(t.exception()))
                t.cancel()

    async def _bridge_log(self):
        async for line in self.bridge.stdout:
            text = line.decode().strip()
            self.note("BRIDGE", text)
            if text.startswith("call ended"):
                self.done.set()

    async def _downlink(self, reader):
        chunk = b""
        while not self.done.is_set():
            data = await reader.read(FRAME * 2 * 5)
            if not data:
                self.done.set()
                break
            self.down_wav.writeframes(data)
            chunk += data
            if len(chunk) >= FRAME * 2 * 5:
                await self.ws.send(json.dumps({"type": "input_audio_buffer.append",
                                               "audio": base64.b64encode(chunk).decode()}))
                chunk = b""

    async def _uplink(self, writer):
        silence = bytes(FRAME * 2)
        next_at = time.monotonic()
        while not self.done.is_set():
            frame = bytes(self.outgoing[: FRAME * 2])
            del self.outgoing[: len(frame)]
            frame = frame.ljust(FRAME * 2, b"\0") if frame else silence
            writer.write(frame)
            self.up_wav.writeframes(frame)
            await writer.drain()
            next_at += 0.02
            await asyncio.sleep(max(0, next_at - time.monotonic()))
        writer.close()

    async def _events(self):
        agent_text = {}
        async for raw in self.ws:
            if self.done.is_set():
                break
            e = json.loads(raw)
            kind = e["type"]
            if kind == "response.output_audio.delta":
                self.outgoing += base64.b64decode(e["delta"])
            elif kind == "input_audio_buffer.speech_started":
                self.outgoing.clear()
            elif kind == "conversation.item.input_audio_transcription.completed":
                if e.get("transcript", "").strip():
                    self.note("REMOTE", e["transcript"].strip())
            elif kind == "response.output_audio_transcript.done":
                self.note("AGENT", e.get("transcript", ""))
            elif kind == "response.function_call_arguments.done":
                asyncio.create_task(self._tool(e["call_id"], e["name"], json.loads(e["arguments"] or "{}")))
            elif kind == "error":
                self.note("ERROR", json.dumps(e.get("error")))
            elif kind == "session.updated":
                self.note("SYSTEM", "agent session configured")

    async def _tool(self, call_id, name, args):
        self.note("TOOL", f"{name} {json.dumps(args)}")
        result = {"ok": True}
        if name == "send_dtmf":
            self.outgoing += dtmf_pcm(args.get("digits", ""))
            result = {"ok": True, "sent": args.get("digits", "")}
        elif name == "ask_owner":
            self.pending_question = asyncio.get_running_loop().create_future()
            print(f"\n>>> EVA asks: {args.get('question')}\n>>> type your answer and press Enter\n", flush=True)
            answer = await self.pending_question
            self.pending_question = None
            self.note("OWNER_TO_AGENT", answer)
            result = {"answer": answer}
        elif name == "finish":
            self.note("OUTCOME", f"{args.get('outcome')}: {args.get('summary')}")
            await asyncio.sleep(0.5)
            while self.outgoing:
                await asyncio.sleep(0.1)
            await asyncio.sleep(1.0)
            subprocess.run(self.adb + ["shell", "input", "keyevent", "KEYCODE_ENDCALL"], capture_output=True)
            self.done.set()
            return
        await self.ws.send(json.dumps({"type": "conversation.item.create", "item": {
            "type": "function_call_output", "call_id": call_id, "output": json.dumps(result)}}))
        await self.ws.send(json.dumps({"type": "response.create"}))

    async def _owner(self):
        loop = asyncio.get_running_loop()
        while not self.done.is_set():
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                await self.done.wait()
                return
            text = line.strip()
            if not text:
                continue
            if text == "/hangup":
                subprocess.run(self.adb + ["shell", "input", "keyevent", "KEYCODE_ENDCALL"], capture_output=True)
                self.done.set()
            elif self.pending_question and not self.pending_question.done():
                self.pending_question.set_result(text)
            else:
                self.note("OWNER_TO_AGENT", text)
                await self.ws.send(json.dumps({"type": "conversation.item.create", "item": {
                    "type": "message", "role": "user", "content": [{"type": "input_text", "text": "[Owner] " + text}]}}))

    def close(self):
        self.down_wav.close()
        self.up_wav.close()
        self.log.close()


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dial", help="number to call; omit to attach to a call you place yourself")
    p.add_argument("--goal", required=True)
    p.add_argument("--briefing", default="")
    p.add_argument("--disclosures", default="")
    p.add_argument("--owner", default="Ivan Malison")
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    p.add_argument("--port", type=int, default=47311)
    p.add_argument("--model", default="gpt-realtime-2.1")
    p.add_argument("--voice", default="marin")
    p.add_argument("--out", default=os.path.join(HERE, "build", time.strftime("call-%Y%m%d-%H%M%S")))
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    call = Call(args)
    try:
        await call.run()
    except (asyncio.CancelledError, KeyboardInterrupt):
        pass
    finally:
        call.close()
        if getattr(call, "bridge", None) and call.bridge.returncode is None:
            call.bridge.kill()
        print(f"recordings and transcript in {args.out}", flush=True)
        os._exit(0)


if __name__ == "__main__":
    asyncio.run(main())
