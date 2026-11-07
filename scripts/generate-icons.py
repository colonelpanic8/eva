#!/usr/bin/env python3

import argparse
import copy
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from shapely import affinity
from shapely.geometry import MultiPolygon, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union
from svgelements import Matrix
from svgelements import Path as SvgPath


SVG_NS = "http://www.w3.org/2000/svg"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
SVG = f"{{{SVG_NS}}}"
ANDROID = f"{{{ANDROID_NS}}}"

BACKGROUND = "#F5F0E6"
ANDROID_SAFE_SCALE = 0.8
MONOCHROME_PATH_IDS = {"path2", "path3", "path4"}

# Status bar glyph, in source path units unless noted. The streak becomes a gap
# between hair and face, and the eye is widened so both survive at ~17dp.
STATUS_FACE_ID = "path2"
STATUS_HAIR_ID = "path3"
STATUS_STREAK_ID = "path4"
STATUS_EYE_ID = "path6"
STATUS_STREAK_GAP = 44.0
STATUS_EYE_GROWTH = 10.0
STATUS_PADDING_DP = 1.0
STATUS_SIMPLIFY_DP = 0.015
STATUS_MIN_AREA_DP = 0.05

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


def path_shape(source_path: ET.Element) -> Polygon:
    path = SvgPath(source_path.attrib["d"])
    transform = source_path.get("transform")
    if transform:
        path = path * Matrix(transform)
    path.reify()
    rings = []
    for subpath in path.as_subpaths():
        segment = SvgPath(subpath)
        count = max(64, int(segment.length(error=1e-2) / 2))
        points = [segment.point(index / count) for index in range(count + 1)]
        if len(points) >= 3:
            rings.append(Polygon([(point.x, point.y) for point in points]).buffer(0))
    if not rings:
        raise ValueError(f"path {source_path.get('id')!r} has no closed subpaths")
    rings.sort(key=lambda ring: -ring.area)
    shape = rings[0]
    for ring in rings[1:]:
        if shape.contains(ring.representative_point()):
            shape = shape.difference(ring)
        else:
            shape = shape.union(ring)
    return shape


def path_data(polygons: list[Polygon]) -> str:
    def coordinate(value: float) -> str:
        text = f"{value:.2f}".rstrip("0").rstrip(".")
        text = text.replace("-0.", "-.") if text.startswith("-0.") else text
        return text[1:] if text.startswith("0.") else text or "0"

    def ring(coordinates) -> str:
        points = list(coordinates)[:-1]
        start_x, start_y = points[0]
        data = f"M{coordinate(start_x)},{coordinate(start_y)}"
        previous_x, previous_y = start_x, start_y
        for x, y in points[1:]:
            data += f"l{coordinate(x - previous_x)},{coordinate(y - previous_y)}"
            previous_x, previous_y = x, y
        return data + "z"

    return "".join(
        ring(polygon.exterior.coords) + "".join(ring(hole.coords) for hole in polygon.interiors)
        for polygon in polygons
    )


def status_vector(mark: ET.Element) -> ET.Element:
    paths = {path.get("id"): path for path in mark.findall(f"{SVG}path")}
    face = path_shape(paths[STATUS_FACE_ID])
    face = Polygon(face.exterior)
    hair = path_shape(paths[STATUS_HAIR_ID])
    streak = path_shape(paths[STATUS_STREAK_ID])
    eye = path_shape(paths[STATUS_EYE_ID])
    glyph = (
        unary_union([face, hair, streak])
        .difference(streak.buffer(STATUS_STREAK_GAP / 2, join_style=1))
        .difference(eye.buffer(STATUS_EYE_GROWTH, join_style=1))
        .buffer(0)
    )
    min_x, min_y, max_x, max_y = glyph.bounds
    scale = (24 - 2 * STATUS_PADDING_DP) / max(max_x - min_x, max_y - min_y)
    glyph = affinity.translate(glyph, -(min_x + max_x) / 2, -(min_y + max_y) / 2)
    glyph = affinity.scale(glyph, scale, scale, origin=(0, 0))
    glyph = affinity.translate(glyph, 12, 12)
    glyph = glyph.simplify(STATUS_SIMPLIFY_DP, preserve_topology=True)
    polygons = list(glyph.geoms) if isinstance(glyph, MultiPolygon) else [glyph]
    polygons = [orient(polygon, 1.0) for polygon in polygons if polygon.area > STATUS_MIN_AREA_DP]
    root = ET.Element(
        "vector",
        {
            f"{ANDROID}width": "24dp",
            f"{ANDROID}height": "24dp",
            f"{ANDROID}viewportWidth": "24",
            f"{ANDROID}viewportHeight": "24",
        },
    )
    ET.SubElement(
        root,
        "path",
        {
            f"{ANDROID}fillColor": "#FFFFFFFF",
            f"{ANDROID}pathData": path_data(polygons),
        },
    )
    return root


def render_png(source: Path, destination: Path, size: int = 512) -> None:
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
                f"--export-width={size}",
                f"--export-height={size}",
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
    write_xml(
        repository / "app/src/main/res/drawable/ic_stat_eva.xml",
        status_vector(mark),
    )
    render_png(fdroid_svg, repository / "fdroid/icon.png")
    render_png(fdroid_svg, repository / "eva-desktop/src/main/resources/eva-icon.png", size=128)


if __name__ == "__main__":
    main()
