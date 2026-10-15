#!/usr/bin/env python3
"""Records a call demo: device screens plus what the far end heard, muxed into one MP4.

Each --screen is mirrored read-only with scrcpy (no input, no device audio) and recorded;
--far-end records the emulator's audio over gRPC. The streams start together and are
aligned by their measured start times, then ffmpeg puts the screens side by side with the
far-end audio. Everything goes to a new folder under experiments/call-audio/build/demos/
(gitignored) and a line is added to its index.md. Screens can show private content: record
the owner's phone only for a planned demo, and never commit or upload the media.
Run with emulator.nix's python, and hold /tmp/eva-phone.lock while recording a shared phone.
"""

import argparse
import json
import re
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEMOS = HERE.parent / "build/demos"
HEIGHT = 1280


def scrcpy(tools, spec, out, seconds, started):
    """Records one screen; [started] gets the wall-clock time scrcpy reports recording began."""
    serial, _, display = spec.partition(":")
    # Line-buffered, so the "Recording started" line arrives when it is logged rather than at exit.
    command = ["stdbuf", "-oL", "-eL", str(tools / "bin/scrcpy"), "--serial", serial, "--no-control", "--no-playback", "--no-window", "--no-audio",
               "--record", str(out), "--time-limit", str(int(seconds)), "--max-size", "1920"]
    if display:
        command.append(f"--display-id={display}")
    launched = time.time()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log = []
    for line in process.stdout:
        log.append(line)
        if "Recording started" in line and spec not in started:
            started[spec] = time.time()
    if process.wait() != 0 or not out.exists() or started.get(spec, time.time()) - launched > seconds / 2:
        raise RuntimeError(f"scrcpy failed for {spec}:\n{''.join(log[-15:])}")


def far_end_audio(serial, out, seconds, started):
    sys.path.insert(0, str(HERE))
    import emulator_audio

    started["far-end"] = time.time()
    emulator_audio.record(serial, out, seconds)


def mux(tools, parts, offsets, audio, seconds, out):
    command = [str(tools / "bin/ffmpeg"), "-hide_banner", "-loglevel", "error", "-y"]
    for part in parts:
        command += ["-itsoffset", f"{offsets[part]:.3f}", "-i", str(part)]
    if audio:
        command += ["-itsoffset", f"{offsets[audio]:.3f}", "-i", str(audio)]
    scaled = "".join(f"[{i}:v]scale=-2:{HEIGHT},setsar=1[v{i}];" for i in range(len(parts)))
    joined = "".join(f"[v{i}]" for i in range(len(parts)))
    stack = f"{joined}hstack=inputs={len(parts)}[v]" if len(parts) > 1 else f"{joined}null[v]"
    command += ["-filter_complex", scaled + stack, "-map", "[v]"]
    if audio:
        command += ["-map", f"{len(parts)}:a", "-c:a", "aac", "-b:a", "128k"]
    command += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-t", str(seconds), str(out)]
    subprocess.run(command, check=True)


def record(args):
    if not args.screen and not args.far_end:
        sys.exit("Give at least one --screen or --far-end.")
    slug = re.sub(r"[^a-z0-9]+", "-", args.title.lower()).strip("-")[:40]
    created = datetime.now()
    folder = DEMOS / f"{created:%Y%m%d-%H%M%S}-{slug}"
    folder.mkdir(parents=True)
    parts = [folder / f"screen-{i + 1}.mp4" for i in range(len(args.screen))]
    audio = folder / "far-end.wav" if args.far_end else None
    started, errors = {}, []

    def guard(target, *params):
        try:
            target(*params)
        except BaseException as error:  # reported after all streams stop
            errors.append(error)

    threads = [threading.Thread(target=guard, args=(scrcpy, args.tools, spec, part, args.seconds, started))
               for spec, part in zip(args.screen, parts)]
    if audio:
        threads.append(threading.Thread(target=guard, args=(far_end_audio, args.far_end, audio, args.seconds, started)))
    print(f"Recording {args.seconds:.0f} s into {folder} ...", flush=True)
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    if errors:
        sys.exit(f"Recording failed; partial files are in {folder}: {errors[0]}")
    keys = {**{part: started.get(spec) for spec, part in zip(args.screen, parts)}, **({audio: started["far-end"]} if audio else {})}
    missing = [str(part) for part, at in keys.items() if at is None]
    if missing:
        sys.exit(f"Could not tell when recording started for {missing}; files kept in {folder}.")
    zero = min(keys.values())
    offsets = {part: at - zero for part, at in keys.items()}
    demo = folder / "demo.mp4"
    if parts:
        mux(args.tools, parts, offsets, audio, args.seconds, demo)
    commit = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    meta = {
        "title": args.title,
        "notes": args.notes,
        "created": created.isoformat(timespec="seconds"),
        "commit": commit,
        "screens": args.screen,
        "farEnd": args.far_end,
        "seconds": args.seconds,
        "offsets": {path.name: round(offset, 3) for path, offset in offsets.items()},
        "files": sorted(path.name for path in folder.iterdir()),
    }
    (folder / "meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    index = DEMOS / "index.md"
    if not index.exists():
        index.write_text("# Call demos\n\nLocal recordings; gitignored, never commit or upload them.\n\n")
    main_file = demo if demo.exists() else audio
    with index.open("a") as entries:
        line = f"- {meta['created']} — **{args.title}** — [{main_file.relative_to(DEMOS)}]({main_file.relative_to(DEMOS)})"
        line += f" (commit {commit}; screens: {', '.join(args.screen) or 'none'}; far end: {args.far_end or 'none'})"
        entries.write(line + (f" — {args.notes}" if args.notes else "") + "\n")
    print(f"Demo: {main_file}\nIndex: {index}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--title", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--seconds", type=float, required=True)
    parser.add_argument("--screen", action="append", default=[], help="adb serial, optionally :display-id; repeat for side by side")
    parser.add_argument("--far-end", help="the far-end emulator's serial, to record what it hears")
    parser.add_argument("--tools", type=Path, default=REPO / "build/whatsapp-far-end-tools", help="emulator.nix's tools output")
    record(parser.parse_args())


if __name__ == "__main__":
    main()
