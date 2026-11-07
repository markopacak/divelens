#!/usr/bin/env python3
"""Build the final variant B feature graphic from your two real images.

    python3 compose_b.py before.jpg after.jpg out.png [--focus-x 0.5] [--focus-y 0.5]

- Each image is scaled to cover a 508 x 500 slot and centre-cropped (same crop for both,
  steered by --focus-x/--focus-y, 0 = left/top, 1 = right/bottom).
- Nothing else is done to the pixels: no colour grading, no sharpening, no resampling tricks
  beyond a plain high-quality resize. The "after" image must be your real DiveLens export.
- The labels, divider and logo come from feature_graphic_B_overlay.png (same folder).
- Output is a 1024 x 500, 24-bit PNG without alpha, as Google Play requires.
Requires: pip install pillow
"""
import argparse, os
from PIL import Image

SLOT_W, SLOT_H, GAP = 508, 500, 8
NAVY = (0x0B, 0x2E, 0x42)

def cover(im, fx, fy):
    im = im.convert('RGB')
    scale = max(SLOT_W / im.width, SLOT_H / im.height)
    w, h = round(im.width * scale), round(im.height * scale)
    im = im.resize((max(w, SLOT_W), max(h, SLOT_H)), Image.LANCZOS)
    left = round((im.width - SLOT_W) * fx)
    top = round((im.height - SLOT_H) * fy)
    return im.crop((left, top, left + SLOT_W, top + SLOT_H))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('before'); ap.add_argument('after'); ap.add_argument('out')
    ap.add_argument('--focus-x', type=float, default=0.5)
    ap.add_argument('--focus-y', type=float, default=0.5)
    a = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    overlay = Image.open(os.path.join(here, 'feature_graphic_B_overlay.png')).convert('RGBA')
    canvas = Image.new('RGBA', (1024, 500), NAVY + (255,))
    canvas.paste(cover(Image.open(a.before), a.focus_x, a.focus_y), (0, 0))
    canvas.paste(cover(Image.open(a.after), a.focus_x, a.focus_y), (SLOT_W + GAP, 0))
    final = Image.alpha_composite(canvas, overlay).convert('RGB')
    final.save(a.out, 'PNG')
    print('saved', a.out, final.size, final.mode)

if __name__ == '__main__':
    main()
