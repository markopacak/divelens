"""Turns the local training set into normalised reference pairs.

usage: python prepare.py [trainingset_dir]   (default: <repo>/trainingset)

Reads <trainingset>/pairs.csv and writes <trainingset>/reference/<id>/:
  NNN_original.png, NNN_reference.png   orientation applied, REF_WIDTH wide, reference resized to the original's size
  mask.png                              white where pixels count (the reference app's watermark is black)
  scene.txt                             "<app> <kind>"
Photos give one pair (000). Videos give one pair every STEP seconds, extracted with ffmpeg when it is
installed, otherwise with frames.swift (macOS, AVFoundation).
"""
import csv, os, shutil, subprocess, sys, tempfile
import numpy as np
from PIL import Image
from common import load, watermark_mask

REF_WIDTH = 1200
STEP = 0.5
HERE = os.path.dirname(os.path.abspath(__file__))


def save(img, path):
    Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)).save(path)


def fit_width(img, width=REF_WIDTH):
    h, w = img.shape[:2]
    if w <= width:
        return img
    im = Image.fromarray((img * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.resize((width, round(h * width / w)), Image.BOX), np.float32) / 255


def to_size(img, shape):
    if img.shape[:2] == shape[:2]:
        return img
    im = Image.fromarray((img * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.resize((shape[1], shape[0]), Image.BILINEAR), np.float32) / 255


def extract_frames(video, out):
    """Writes one PNG per STEP seconds, at STEP/2, 3*STEP/2, ...; returns the sorted paths."""
    os.makedirs(out, exist_ok=True)
    if shutil.which("ffmpeg"):
        subprocess.run(["ffmpeg", "-loglevel", "error", "-i", video, "-vf",
                        f"fps=1/{STEP}:start_time={STEP / 2},scale='min({REF_WIDTH},iw)':-2",
                        os.path.join(out, "f%04d.png")], check=True)
    else:
        tool = os.path.join(tempfile.gettempdir(), "divelens_frames")
        if not os.path.exists(tool):
            subprocess.run(["swiftc", "-O", os.path.join(HERE, "frames.swift"), "-o", tool], check=True)
        subprocess.run([tool, video, out, str(STEP), str(REF_WIDTH)], check=True, stdout=subprocess.DEVNULL)
    return sorted(os.path.join(out, f) for f in os.listdir(out) if f.endswith(".png"))


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "trainingset"))
    dest = os.path.join(root, "reference")
    for r in csv.DictReader(open(os.path.join(root, "pairs.csv"))):
        sid, app, kind = r["id"], r["app"], r["kind"]
        out = os.path.join(dest, sid)
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        if kind == "photo":
            pairs = [(load(os.path.join(root, r["original"])), load(os.path.join(root, r["processed"])))]
        else:
            with tempfile.TemporaryDirectory() as tmp:
                a = extract_frames(os.path.join(root, r["original"]), os.path.join(tmp, "o"))
                b = extract_frames(os.path.join(root, r["processed"]), os.path.join(tmp, "p"))
                pairs = [(load(x), load(y)) for x, y in zip(a, b)]
        shape = None
        for i, (o, p) in enumerate(pairs):
            o = fit_width(o)
            p = to_size(p, o.shape)
            save(o, os.path.join(out, f"{i:03d}_original.png"))
            save(p, os.path.join(out, f"{i:03d}_reference.png"))
            shape = o.shape
        Image.fromarray(watermark_mask(shape, app).astype(np.uint8) * 255).save(os.path.join(out, "mask.png"))
        open(os.path.join(out, "scene.txt"), "w").write(f"{app} {kind}\n")
        print(f"{sid}: {len(pairs)} pair(s), {shape[1]}x{shape[0]}, {app}")


if __name__ == "__main__":
    main()
