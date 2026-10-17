"""Shared helpers for the DiveLens reference fit: image loading, downscaling, colour difference.

Pixel values are sRGB-encoded floats in 0..1, shape (h, w, 3).
"""
import numpy as np
from PIL import Image, ImageOps

ANALYSIS_WIDTH = 200  # width the model statistics are computed at; part of the model (design D3)


def load(path):
    """Loads an image file as float RGB with EXIF orientation applied."""
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    return np.asarray(im, dtype=np.float32) / 255.0


def box_downscale(img, width):
    """Area average with integer bounds: output column j averages input columns
    floor(j*w/W) .. floor((j+1)*w/W) - 1, rows likewise. Height is round(h*W/w).
    The app's Kotlin implementation uses exactly the same bounds."""
    h, w = img.shape[:2]
    if width >= w:
        return img.astype(np.float64)
    H = max(1, round(h * width / w))
    xs = (np.arange(width + 1) * w) // width
    ys = (np.arange(H + 1) * h) // H
    c = np.cumsum(np.cumsum(np.pad(img.astype(np.float64), ((1, 0), (1, 0), (0, 0))), 0), 1)
    s = c[ys[1:]][:, xs[1:]] - c[ys[:-1]][:, xs[1:]] - c[ys[1:]][:, xs[:-1]] + c[ys[:-1]][:, xs[:-1]]
    n = np.outer(ys[1:] - ys[:-1], xs[1:] - xs[:-1])[..., None]
    return s / n


def bilinear_downscale(img, width):
    """PIL bilinear resize (used by the first v1 analysis; kept to reproduce it)."""
    h, w = img.shape[:2]
    H = round(h * width / w)
    im = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.resize((width, H), Image.BILINEAR), dtype=np.float64) / 255.0


def downscale(img, width, method="box"):
    return box_downscale(img, width) if method == "box" else bilinear_downscale(img, width)


def watermark_mask(shape, app):
    """True where pixels are usable (the app's watermark corner is excluded)."""
    h, w = shape[:2]
    m = np.ones((h, w), bool)
    if app == "refapp":
        m[int(h * 0.82):, : int(w * 0.16)] = False
    elif app == "refapp2":
        m[int(h * 0.88):, int(w * 0.30): int(w * 0.55)] = False
    return m


def srgb_to_lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def to_lab(rgb):
    """sRGB (D65) to CIE L*a*b*."""
    lin = srgb_to_lin(np.clip(rgb, 0, 1))
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = lin @ M.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def delta_e(x, y):
    """CIE76 colour difference per pixel."""
    return np.linalg.norm(to_lab(x) - to_lab(y), axis=-1)
