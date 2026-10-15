#!/usr/bin/env python3
"""Checks a far-end recording (16-bit mono WAV): loudness over time, keypad tones, sound present.

Standard library only, so it runs anywhere: python3 audio_check.py levels far-end.wav
"""

import argparse
import math
import struct
import sys
import wave

DTMF_LOW = (697, 770, 852, 941)
DTMF_HIGH = (1209, 1336, 1477, 1633)
KEYS = ("123A", "456B", "789C", "*0#D")


def samples(path):
    with wave.open(str(path)) as wav:
        if wav.getsampwidth() != 2 or wav.getnchannels() != 1:
            sys.exit(f"{path}: expected 16-bit mono WAV.")
        rate = wav.getframerate()
        data = wav.readframes(wav.getnframes())
    return rate, struct.unpack(f"<{len(data) // 2}h", data)


def levels(path, window=0.5):
    """(start seconds, dBFS) per window; digital silence is -inf."""
    rate, pcm = samples(path)
    step = max(1, int(rate * window))
    out = []
    for start in range(0, len(pcm), step):
        chunk = pcm[start:start + step]
        rms = math.sqrt(sum(x * x for x in chunk) / len(chunk)) / 32768
        out.append((start / rate, 20 * math.log10(rms) if rms > 0 else -math.inf))
    return out


def power(chunk, rate, frequency):
    k = 2 * math.cos(2 * math.pi * frequency / rate)
    a = b = 0.0
    for x in chunk:
        a, b = x + k * a - b, a
    return a * a + b * b - k * a * b


def dtmf(path, window=0.04, floor_db=-45):
    """Keypad keys heard, in order, as (start seconds, key); one entry per press."""
    rate, pcm = samples(path)
    step = int(rate * window)
    keys, last, seen = [], None, -1.0
    for start in range(0, len(pcm) - step, step):
        chunk = pcm[start:start + step]
        rms = math.sqrt(sum(x * x for x in chunk) / step) / 32768
        key = None
        if rms > 0 and 20 * math.log10(rms) > floor_db:
            low = [power(chunk, rate, f) for f in DTMF_LOW]
            high = [power(chunk, rate, f) for f in DTMF_HIGH]
            total = sum(x * x for x in chunk) * step / 2
            row, column = low.index(max(low)), high.index(max(high))
            if (low[row] + high[column]) > 0.5 * total:
                key = KEYS[row][column]
        at = start / rate
        # A tone broken by one dropped window is still one press.
        if key and key != last and not (keys and keys[-1][1] == key and at - seen <= 3 * window):
            keys.append((at, key))
        if key:
            seen = at
        last = key
    return keys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("levels", "dtmf", "expect-sound"):
        command = commands.add_parser(name)
        command.add_argument("wav")
    commands.choices["expect-sound"].add_argument("--min-db", type=float, default=-45)
    args = parser.parse_args()
    if args.command == "levels":
        for at, db in levels(args.wav):
            print(f"{at:7.1f}s {db:7.1f} dBFS")
    elif args.command == "dtmf":
        keys = dtmf(args.wav)
        print(" ".join(f"{key}@{at:.2f}s" for at, key in keys) or "no keypad tones")
    else:
        loud = [at for at, db in levels(args.wav) if db > args.min_db]
        if not loud:
            sys.exit(f"{args.wav}: no sound above {args.min_db} dBFS.")
        print(f"{args.wav}: sound above {args.min_db} dBFS from {loud[0]:.1f} s to {loud[-1]:.1f} s.")


if __name__ == "__main__":
    main()
