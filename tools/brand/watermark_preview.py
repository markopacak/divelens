"""Contact sheet for tuning the watermark's brightness threshold (add-branding-and-watermark design D9).

usage: python watermark_preview.py [folder] [--threshold 0.60]   (default folder: trainingset/watermark)

The folder holds corrected exports made with the watermark switched off, pulled from the phone, for example
    adb pull /sdcard/Pictures/DiveLens trainingset/watermark
For every photo the tool measures the corner under the mark as the app does (box and padding from BRAND.md, Rec. 709
luma of the encoded values, every second pixel above 8 MP), and draws the corner twice: with the light and with the
dark mark. Rows are sorted by luma, and a red line marks the threshold: above it the app uses the dark mark.
The sheet is written to <folder>/contact_sheet.png; the folder must be inside the gitignored trainingset/.

Only Pillow is needed: the brand SVGs use a small subset (paths with M, L, H, V, C, Z and arcs, group translate,
fill and fill-opacity), which is drawn here directly.
"""
import argparse, math, os, re, sys
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
MARKS = {"light": os.path.join(REPO, "docs/brand/watermark/watermark_light.svg"),
         "dark": os.path.join(REPO, "docs/brand/watermark/watermark_dark.svg")}

# Kept equal to the app (WatermarkPlacement, WatermarkVersion); WatermarkPreviewToolTest checks them.
WIDTH_SHARE = 0.12
MARGIN_SHARE = 0.03
PADDING_SHARE = 0.10
OPACITY = 0.92
LUMA_WEIGHTS = (0.2126, 0.7152, 0.0722)
WATERMARK_DARK_ABOVE = 0.60
SUBSAMPLE_ABOVE_PIXELS = 8_000_000

SUPERSAMPLE = 4
TILE_WIDTH = 520


# ---- the brand SVG subset -------------------------------------------------------------------------------------------

def _numbers(text):
    return [float(n) for n in re.findall(r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", text)]


def _arc(x1, y1, rx, ry, phi, large, sweep, x2, y2, steps=48):
    """Points of an SVG elliptical arc from (x1, y1) to (x2, y2), endpoint to centre parameterisation (SVG spec F.6.5)."""
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(phi)
    cos, sin = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    xp, yp = cos * dx + sin * dy, -sin * dx + cos * dy
    rx, ry = abs(rx), abs(ry)
    lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    coef = math.sqrt(max(0.0, num / (rx * rx * yp * yp + ry * ry * xp * xp)))
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * yp / ry, -coef * ry * xp / rx
    cx = cos * cxp - sin * cyp + (x1 + x2) / 2
    cy = sin * cxp + cos * cyp + (y1 + y2) / 2
    t1 = math.atan2((yp - cyp) / ry, (xp - cxp) / rx)
    t2 = math.atan2((-yp - cyp) / ry, (-xp - cxp) / rx)
    dt = t2 - t1
    if sweep and dt < 0:
        dt += 2 * math.pi
    elif not sweep and dt > 0:
        dt -= 2 * math.pi
    return [(cx + rx * math.cos(t1 + dt * k / steps) * cos - ry * math.sin(t1 + dt * k / steps) * sin,
             cy + rx * math.cos(t1 + dt * k / steps) * sin + ry * math.sin(t1 + dt * k / steps) * cos)
            for k in range(1, steps + 1)]


def subpaths(d):
    """Closed polygons (lists of points) of path data d with the commands the brand files use."""
    polys, current, x, y, start = [], [], 0.0, 0.0, (0.0, 0.0)
    for token in re.findall(r"[MLHVCZAmlhvcza][^MLHVCZAmlhvcza]*", d):
        cmd, args = token[0], _numbers(token[1:])
        rel = cmd.islower()
        c = cmd.upper()
        if c == "Z":
            if current:
                polys.append(current)
            current, (x, y) = [], start
            continue
        n = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "A": 7}[c]
        for k in range(0, len(args), n):
            a = args[k:k + n]
            if c == "M":
                if current:
                    polys.append(current)
                x, y = (x + a[0], y + a[1]) if rel else (a[0], a[1])
                start, current = (x, y), [(x, y)]
                c = "L"  # further pairs after M are lines
            elif c == "L":
                x, y = (x + a[0], y + a[1]) if rel else (a[0], a[1]); current.append((x, y))
            elif c == "H":
                x = x + a[0] if rel else a[0]; current.append((x, y))
            elif c == "V":
                y = y + a[0] if rel else a[0]; current.append((x, y))
            elif c == "C":
                p = [(x + a[j], y + a[j + 1]) if rel else (a[j], a[j + 1]) for j in (0, 2, 4)]
                x0, y0 = x, y
                for s in range(1, 17):
                    t = s / 16; u = 1 - t
                    current.append((u ** 3 * x0 + 3 * u * u * t * p[0][0] + 3 * u * t * t * p[1][0] + t ** 3 * p[2][0],
                                    u ** 3 * y0 + 3 * u * u * t * p[0][1] + 3 * u * t * t * p[1][1] + t ** 3 * p[2][1]))
                x, y = p[2]
            elif c == "A":
                ex, ey = (x + a[5], y + a[6]) if rel else (a[5], a[6])
                current += _arc(x, y, a[0], a[1], a[2], int(a[3]), int(a[4]), ex, ey)
                x, y = ex, ey
    if current:
        polys.append(current)
    return polys


def read_mark(path):
    """(viewBox, [(polygons, (r, g, b, a))]) of a brand watermark SVG, offsets applied."""
    root = ET.parse(path).getroot()
    view = _numbers(root.get("viewBox"))
    shapes = []

    def walk(node, fill, opacity, dx, dy):
        for child in node:
            tag = child.tag.split("}")[-1]
            f = child.get("fill", fill)
            o = float(child.get("fill-opacity", opacity))
            if tag == "g":
                t = _numbers(child.get("transform", "translate(0,0)"))
                walk(child, f, o, dx + t[0], dy + (t[1] if len(t) > 1 else 0))
            elif tag == "path":
                h = f.lstrip("#")
                h = "".join(ch * 2 for ch in h) if len(h) == 3 else h
                rgb = tuple(int(h[j:j + 2], 16) for j in (0, 2, 4))
                polys = [[(px + dx, py + dy) for px, py in poly] for poly in subpaths(child.get("d"))]
                shapes.append((polys, rgb + (round(o * 255),)))
    walk(root, "#000", 1.0, 0.0, 0.0)
    return view, shapes


def render_mark(mark, width, height):
    """The mark as an RGBA image of width × height, antialiased by supersampling, at the app's 92 % opacity."""
    view, shapes = mark
    sw, sh = width * SUPERSAMPLE, height * SUPERSAMPLE
    sx, sy = sw / view[2], sh / view[3]
    layer = Image.new("RGBA", (sw, sh), (0, 0, 0, 0))
    for polys, colour in shapes:
        mask = Image.new("1", (sw, sh), 0)
        for poly in polys:  # even-odd: each contour toggles coverage (the glyphs' contours don't overlap)
            one = Image.new("1", (sw, sh), 0)
            ImageDraw.Draw(one).polygon([((px - view[0]) * sx, (py - view[1]) * sy) for px, py in poly], fill=1)
            mask = ImageChops.logical_xor(mask, one)
        fill = Image.new("RGBA", (sw, sh), colour)
        alpha = Image.new("L", (sw, sh), 0)
        alpha.paste(colour[3], mask=mask)
        fill.putalpha(alpha)
        layer = Image.alpha_composite(layer, fill)
    layer = layer.resize((width, height), Image.LANCZOS)
    a = layer.getchannel("A").point(lambda v: round(v * OPACITY))
    layer.putalpha(a)
    return layer


# ---- placement and brightness, as the app ---------------------------------------------------------------------------

def placement(w, h, aspect):
    bw = WIDTH_SHARE * w
    bh = bw * aspect
    m = MARGIN_SHARE * w
    box = (w - m - bw, h - m - bh, w - m, h - m)
    px, py = PADDING_SHARE * bw, PADDING_SHARE * bh
    measured = (max(0.0, box[0] - px), max(0.0, box[1] - py), min(float(w), box[2] + px), min(float(h), box[3] + py))
    return box, measured


def mean_luma(image, area):
    w, h = image.size
    left, top = min(max(int(math.floor(area[0])), 0), w - 1), min(max(int(math.floor(area[1])), 0), h - 1)
    right, bottom = min(max(int(math.ceil(area[2])), left + 1), w), min(max(int(math.ceil(area[3])), top + 1), h)
    step = 2 if w * h > SUBSAMPLE_ABOVE_PIXELS else 1
    crop = image.crop((left, top, right, bottom)).convert("RGB")
    pixels = crop.load()
    total, n = 0.0, 0
    for y in range(0, bottom - top, step):
        for x in range(0, right - left, step):
            r, g, b = pixels[x, y]
            total += (LUMA_WEIGHTS[0] * r + LUMA_WEIGHTS[1] * g + LUMA_WEIGHTS[2] * b) / 255.0
            n += 1
    return total / n


# ---- the sheet ------------------------------------------------------------------------------------------------------

def tile(photo, mark, box):
    """The bottom-right corner of the photo (twice the mark's size around it) with the mark drawn."""
    out = photo.convert("RGBA")
    bw, bh = round(box[2] - box[0]), round(box[3] - box[1])
    out.alpha_composite(render_mark(mark, bw, bh), (round(box[0]), round(box[1])))
    w, h = photo.size
    view = (max(0, round(box[0] - bw * 0.5)), max(0, round(box[1] - bh * 2.5)), w, h)
    corner = out.crop(view).convert("RGB")
    return corner.resize((TILE_WIDTH, round(TILE_WIDTH * corner.height / corner.width)), Image.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", default=os.path.join(REPO, "trainingset", "watermark"))
    parser.add_argument("--threshold", type=float, default=WATERMARK_DARK_ABOVE)
    args = parser.parse_args()
    folder = os.path.abspath(args.folder)
    trainingset = os.path.join(REPO, "trainingset")
    if os.path.commonpath([folder, trainingset]) != trainingset:
        sys.exit(f"{folder} is not inside {trainingset}: the sheet shows personal photos and must stay out of the repository")
    names = sorted(f for f in os.listdir(folder) if f.lower().endswith((".jpg", ".jpeg")) and not f.startswith("contact_sheet"))
    if not names:
        sys.exit(f"no JPEG exports in {folder}")
    marks = {k: read_mark(p) for k, p in MARKS.items()}
    aspect = marks["light"][0][3] / marks["light"][0][2]

    rows = []
    for name in names:
        photo = ImageOps.exif_transpose(Image.open(os.path.join(folder, name)))  # as the gallery shows it
        box, measured = placement(*photo.size, aspect)
        luma = mean_luma(photo, measured)
        rows.append((luma, name, tile(photo, marks["light"], box), tile(photo, marks["dark"], box)))
        print(f"{luma:.3f}  {'dark ' if luma > args.threshold else 'light'}  {name}")
    rows.sort(key=lambda r: r[0])

    label_h, gap = 28, 12
    heights = [max(r[2].height, r[3].height) + label_h + gap for r in rows]
    sheet = Image.new("RGB", (2 * TILE_WIDTH + 3 * gap, sum(heights) + gap), (32, 32, 32))
    draw = ImageDraw.Draw(sheet)
    y, line_drawn = gap, False
    for (luma, name, light, dark), height in zip(rows, heights):
        if not line_drawn and luma > args.threshold:
            draw.rectangle((0, y - gap // 2 - 2, sheet.width, y - gap // 2 + 2), fill=(230, 40, 40))
            line_drawn = True
        chosen = "dark" if luma > args.threshold else "light"
        draw.text((gap, y), f"{name}   luma {luma:.3f}   app picks: {chosen}", fill=(240, 240, 240))
        sheet.paste(light, (gap, y + label_h))
        sheet.paste(dark, (2 * gap + TILE_WIDTH, y + label_h))
        y += height
    out = os.path.join(folder, "contact_sheet.png")
    sheet.save(out)
    print(f"wrote {os.path.relpath(out, REPO)}: left light mark, right dark mark, red line at {args.threshold}")


if __name__ == "__main__":
    main()
