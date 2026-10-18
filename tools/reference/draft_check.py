"""Checks the reduced-decode estimate of add-preview-compare (design D1) on the training photos.

The app estimates a photo's correction from a JPEG decode with inSampleSize = the largest power of
two that keeps the width at least 2048 px, which libjpeg does by DCT scaling. Pillow's draft()
uses the same libjpeg scaling. For each original this script corrects the same image twice, with
the correction estimated from the full-resolution decode and from the reduced decode, and prints
the mean ΔE between the two results (budget: 0.5 on every photo). With --sample it forces a sample
size instead, to see the margin.

usage: python draft_check.py [--trainingset DIR] [--sample N] [--eval-width 1296]
"""
import argparse, glob, os
import numpy as np
from PIL import Image, ImageOps
from common import ANALYSIS_WIDTH, box_downscale, delta_e
from fit import params, stats
import fixture, smooth

HERE = os.path.dirname(os.path.abspath(__file__))
MIN_ESTIMATE_WIDTH = 2048
BUDGET = 0.5


def sample_size(width):
    """Largest power of two that keeps width / size at least MIN_ESTIMATE_WIDTH (the app's rule)."""
    n = 1
    while width // (n * 2) >= MIN_ESTIMATE_WIDTH:
        n *= 2
    return n


def decode(path, n):
    im = Image.open(path)
    if n > 1:
        im.draft("RGB", (im.width // n, im.height // n))
    return np.asarray(ImageOps.exif_transpose(im).convert("RGB"), dtype=np.float32) / 255.0


def estimate(img):
    th = [fixture.COEF[k] for k in ("c0", "c1", "c2", "beta", "lo_pct", "red")]
    p = params(stats(box_downscale(img, ANALYSIS_WIDTH)), th, "prop", fixture.CAP, fixture.HI_PCT, fixture.BLACK_MAX,
               None, True, "black", True)
    A, B = smooth.coefficients(box_downscale(img, min(smooth.SMOOTH_WIDTH, img.shape[1])), p)
    return A, B


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trainingset", default=os.path.join(HERE, "..", "..", "trainingset"))
    ap.add_argument("--sample", type=int, help="force this sample size instead of the app's rule")
    ap.add_argument("--eval-width", type=int, default=1296, help="width at which both results are compared")
    args = ap.parse_args()
    worst = 0.0
    for path in sorted(glob.glob(os.path.join(args.trainingset, "*_original.jpg"))):
        full = decode(path, 1)
        n = args.sample or sample_size(Image.open(path).width)
        reduced = decode(path, n)
        x = box_downscale(full, args.eval_width)
        a = smooth.apply(x, *estimate(full), fixture.STRENGTH)
        b = smooth.apply(x, *estimate(reduced), fixture.STRENGTH)
        de = float(delta_e(a, b).mean())
        worst = max(worst, de)
        print(f"{os.path.basename(path):24s} sample {n}  decode {reduced.shape[1]}x{reduced.shape[0]}  mean ΔE {de:.3f}")
    print(f"worst mean ΔE {worst:.3f} (budget {BUDGET}): {'OK' if worst <= BUDGET else 'EXCEEDED'}")


if __name__ == "__main__":
    main()
