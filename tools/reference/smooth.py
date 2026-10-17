"""Grain-aware application of the correction (design D10): the reference for the app's Kotlin and GPU code.

The colour shift `full(x) - x` is not applied pixel by pixel. It goes through a guided filter
(He et al.) whose guide is the photo's grey level: in flat areas such as open water the shift
becomes the local average, so grain is not amplified; at edges it follows the photo, so edges
stay sharp. The filter coefficients are computed on a working copy (box downscale to
SMOOTH_WIDTH) and applied at full resolution with bilinear interpolation ("fast guided filter"):
  shift_c(x) = A_c(x) * grey(x) + B_c(x)
  out = clamp(x + s * shift, 0, 1)
The red rules (never lowered; no red cast in dark water) are part of fit.full and so of the shift.
"""
import numpy as np
from common import box_downscale
import fit

SMOOTH_WIDTH = 1024
RADIUS_AT_SMOOTH_WIDTH = 2
EPS = 0.02 ** 2


def radius(working_width):
    return max(1, round(RADIUS_AT_SMOOTH_WIDTH * working_width / SMOOTH_WIDTH))


def box(a, r):
    """Mean over a (2r+1)² window, edges mirrored (d c b a | a b c d), separable; the app does the same."""
    def along(a, axis):
        n = a.shape[axis]
        idx = np.arange(-r, n + r)
        idx = np.where(idx < 0, -idx - 1, idx)
        idx = np.where(idx >= n, 2 * n - idx - 1, idx)
        c = np.cumsum(np.take(a, idx, axis=axis), axis=axis, dtype=np.float64)
        c = np.concatenate([np.zeros_like(np.take(c, [0], axis=axis)), c], axis=axis)
        k = 2 * r + 1
        return (np.take(c, np.arange(k, n + k), axis=axis) - np.take(c, np.arange(0, n), axis=axis)) / k
    return along(along(a.astype(np.float64), 0), 1)


def coefficients(work, p, r=None, eps=EPS):
    """A and B maps (h, w, 3) for the working copy `work` and model parameters `p` (None = identity)."""
    h, w = work.shape[:2]
    if p is None:
        return np.zeros((h, w, 3)), np.zeros((h, w, 3))
    r = min(radius(w) if r is None else r, w, h)  # one mirror is enough
    d = fit.full(work.reshape(-1, 3), p).reshape(work.shape) - work
    g = work.mean(2)
    mg = box(g, r); vg = box(g * g, r) - mg * mg
    A = np.empty((h, w, 3)); B = np.empty((h, w, 3))
    for c in range(3):
        mp = box(d[..., c], r)
        a = (box(g * d[..., c], r) - mg * mp) / (vg + eps)
        A[..., c] = box(a, r); B[..., c] = box(mp - a * mg, r)
    return A, B


def upsample(m, H, W):
    """Bilinear, pixel centres aligned, coordinates clamped to the edge."""
    h, w = m.shape[:2]
    ys = np.clip((np.arange(H) + .5) * h / H - .5, 0, h - 1); xs = np.clip((np.arange(W) + .5) * w / W - .5, 0, w - 1)
    y0 = np.floor(ys).astype(int); x0 = np.floor(xs).astype(int)
    y1 = np.minimum(y0 + 1, h - 1); x1 = np.minimum(x0 + 1, w - 1)
    fy = (ys - y0)[:, None, None]; fx = (xs - x0)[None, :, None]
    top = m[y0][:, x0] * (1 - fx) + m[y0][:, x1] * fx; bot = m[y1][:, x0] * (1 - fx) + m[y1][:, x1] * fx
    return top * (1 - fy) + bot * fy


def apply(x, A, B, s):
    """Corrects the full-size image x (H, W, 3) with the maps from `coefficients`, at strength s."""
    H, W = x.shape[:2]
    shift = upsample(A, H, W) * x.mean(2, keepdims=True) + upsample(B, H, W)
    return np.clip(x + s * shift, 0, 1)


def correct(x, p, s, work_width=SMOOTH_WIDTH):
    """The whole application step for image x: working copy, coefficients, full-size result."""
    A, B = coefficients(box_downscale(x, min(work_width, x.shape[1])), p)
    return apply(x, A, B, s)
