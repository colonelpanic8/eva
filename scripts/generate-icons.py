#!/usr/bin/env python3

import argparse
import copy
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


SVG_NS = "http://www.w3.org/2000/svg"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
SVG = f"{{{SVG_NS}}}"
ANDROID = f"{{{ANDROID_NS}}}"

BACKGROUND = "#F5F0E6"
ANDROID_SAFE_SCALE = 0.8
MONOCHROME_PATH_IDS = {"path2", "path3", "path4"}

ROOT_TRANSFORM = re.compile(
    r"translate\(\s*([-\d.]+)[, ]+([-\d.]+)\s*\)\s*"
    r"scale\(\s*([-\d.]+)\s*\)\s*"
    r"translate\(\s*([-\d.]+)[, ]+([-\d.]+)\s*\)"
)
TRANSLATE = re.compile(r"translate\(\s*([-\d.]+)[, ]+([-\d.]+)\s*\)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate EVA app and repository icons from the canonical SVG"
    )
    parser.add_argument(
        "source",
        nargs="?",
        default="assets/branding/eva-face-profile-v8-teal-hair-blue-face.svg",
        type=Path,
    )
    return parser.parse_args()


def number(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".")


def write_xml(path: Path, root: ET.Element) -> None:
    ET.indent(root, space="  ")
    contents = ET.tostring(root, encoding="unicode", xml_declaration=True) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == contents:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def cleaned_mark(mark: ET.Element) -> ET.Element:
    cleaned = ET.Element(
        f"{SVG}g",
        {key: mark.attrib[key] for key in ("id", "transform")},
    )
    for source_path in mark.findall(f"{SVG}path"):
        attributes = {
            key: source_path.attrib[key]
            for key in ("id", "d", "fill", "transform")
            if key in source_path.attrib
        }
        cleaned.append(ET.Element(f"{SVG}path", attributes))
    return cleaned


def svg_document(
    view_box: str,
    title: str,
    description: str,
    mark: ET.Element,
    *,
    background: str | None = None,
) -> ET.Element:
    root = ET.Element(
        f"{SVG}svg",
        {
            "width": "512",
            "height": "512",
            "viewBox": view_box,
            "role": "img",
            "aria-labelledby": "title description",
        },
    )
    ET.SubElement(root, f"{SVG}title", {"id": "title"}).text = title
    ET.SubElement(root, f"{SVG}desc", {"id": "description"}).text = description
    if background:
        _, _, width, height = (float(value) for value in view_box.split())
        ET.SubElement(
            root,
            f"{SVG}rect",
            {
                "width": number(width),
                "height": number(height),
                "rx": number(width * 112 / 512),
                "fill": background,
            },
        )
    root.append(copy.deepcopy(mark))
    return root


def root_scale(mark: ET.Element) -> tuple[float, float, float]:
    transform = mark.get("transform", "")
    match = ROOT_TRANSFORM.fullmatch(transform)
    if not match:
        raise ValueError(f"unsupported eva-mark transform: {transform!r}")
    pivot_x, pivot_y, scale, reverse_x, reverse_y = map(float, match.groups())
    if reverse_x != -pivot_x or reverse_y != -pivot_y:
        raise ValueError(f"eva-mark transform does not scale around its pivot: {transform!r}")
    return pivot_x, pivot_y, scale


def android_path(parent: ET.Element, source_path: ET.Element, fill: str) -> None:
    target = parent
    transform = source_path.get("transform")
    if transform:
        match = TRANSLATE.fullmatch(transform)
        if not match:
            raise ValueError(f"unsupported path transform: {transform!r}")
        translate_x, translate_y = map(float, match.groups())
        target = ET.SubElement(
            parent,
            "group",
            {
                f"{ANDROID}translateX": number(translate_x),
                f"{ANDROID}translateY": number(translate_y),
            },
        )
    ET.SubElement(
        target,
        "path",
        {
            f"{ANDROID}fillColor": fill,
            f"{ANDROID}pathData": source_path.attrib["d"],
        },
    )


def android_vector(mark: ET.Element, *, monochrome: bool) -> ET.Element:
    pivot_x, pivot_y, source_scale = root_scale(mark)
    root = ET.Element(
        "vector",
        {
            f"{ANDROID}width": "108dp",
            f"{ANDROID}height": "108dp",
            f"{ANDROID}viewportWidth": "1254",
            f"{ANDROID}viewportHeight": "1254",
        },
    )
    scaled = ET.SubElement(
        root,
        "group",
        {
            f"{ANDROID}pivotX": number(pivot_x),
            f"{ANDROID}pivotY": number(pivot_y),
            f"{ANDROID}scaleX": number(source_scale * ANDROID_SAFE_SCALE),
            f"{ANDROID}scaleY": number(source_scale * ANDROID_SAFE_SCALE),
        },
    )
    paths = mark.findall(f"{SVG}path")
    if monochrome:
        paths = [path for path in paths if path.get("id") in MONOCHROME_PATH_IDS]
    for path in paths:
        android_path(scaled, path, "#FFFFFFFF" if monochrome else path.attrib["fill"])
    return root


def render_png(source: Path, destination: Path) -> None:
    inkscape = shutil.which("inkscape")
    if not inkscape:
        raise RuntimeError("inkscape is required; run this command from the project dev shell")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary_directory:
        rendered = Path(temporary_directory) / destination.name
        subprocess.run(
            [
                inkscape,
                str(source),
                f"--export-filename={rendered}",
                "--export-width=512",
                "--export-height=512",
            ],
            check=True,
        )
        if destination.exists() and destination.read_bytes() == rendered.read_bytes():
            return
        shutil.copyfile(rendered, destination)


def main() -> None:
    args = parse_args()
    repository = Path(__file__).resolve().parent.parent
    source = args.source if args.source.is_absolute() else repository / args.source
    document = ET.parse(source)
    source_root = document.getroot()
    view_box = source_root.attrib["viewBox"]
    _, _, width, height = (float(value) for value in view_box.split())
    if width != height:
        raise ValueError(f"canonical icon must be square, got viewBox={view_box!r}")

    mark = next(
        (element for element in source_root.iter() if element.get("id") == "eva-mark"),
        None,
    )
    if mark is None:
        raise ValueError("canonical SVG does not contain an element with id='eva-mark'")
    mark = cleaned_mark(mark)

    ET.register_namespace("", SVG_NS)
    ET.register_namespace("android", ANDROID_NS)
    write_xml(
        repository / "favicon.svg",
        svg_document(
            view_box,
            "EVA",
            "A minimal feminine face with teal hair and a closed eye",
            mark,
        ),
    )
    fdroid_svg = repository / "fdroid/icon.svg"
    write_xml(
        fdroid_svg,
        svg_document(
            view_box,
            "EVA",
            "The EVA app icon",
            mark,
            background=BACKGROUND,
        ),
    )
    write_xml(
        repository / "app/src/main/res/drawable/ic_launcher_foreground.xml",
        android_vector(mark, monochrome=False),
    )
    write_xml(
        repository / "app/src/main/res/drawable/ic_launcher_monochrome.xml",
        android_vector(mark, monochrome=True),
    )
    render_png(fdroid_svg, repository / "fdroid/icon.png")


if __name__ == "__main__":
    main()
