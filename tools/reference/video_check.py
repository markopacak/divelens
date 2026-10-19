"""Video reference check (add-video-processing design D1, D9): the correction over time against
per-frame correction and RefApp, and flicker.

usage: python video_check.py [--trainingset DIR] [--sigma 2.0] [--interval 1.0] [--only ID] [--print-timeline]
       python video_check.py --parity [OUT]   writes the CorrectionTimeline parity fixture (synthetic, no media)

Needs the reference videos in trainingset/ and the frames prepare.py wrote to trainingset/reference/;
without them it prints "skipped" and exits 0. For each video in pairs.csv:
  1. Samples: the sync frame closest to every SAMPLE_INTERVAL seconds and to the end, decoded SAMPLE_WIDTH
     wide (as MediaMetadataRetriever.getScaledFrameAtTime with OPTION_CLOSEST_SYNC does in the app),
     each estimated on its ANALYSIS_WIDTH copy.
  2. Timeline: the 3x4 matrices smoothed over time with a Gaussian of SIGMA seconds weighted by the
     real sample times, interpolated linearly between samples and held at both ends.
  3. Each prepared frame (every 0.5 s) is corrected twice at the default strength, with its own
     estimate ("per frame") and with the timeline's parameters at its time; both with the frame's own
     guided filter (design D2). Reported: ΔE to RefApp (masked, at EVAL_WIDTH, as ReferenceSetTest) at the
     default strength for both (the timeline must be closer on average, mean at most DEFAULT_LIMIT, every
     video closer than its original); for information the matched-strength ΔE (its ORACLE_LIMIT applies
     to photos and video frames together in ReferenceSetTest) and the mean ΔE between the two corrections.
  4. Flicker at FLICKER_POINTS pairs of consecutive frames: the change of the mean colour (CIE76 ΔE of
     the mean L*a*b*) between the two corrected frames minus the same change in the original
     (limit FLICKER_LIMIT). Per-frame correction is shown alongside for comparison.
The constants SAMPLE_INTERVAL and SIGMA are shared with CorrectionTimeline in :core:analysis.
"""
import argparse, csv, glob, os, re, subprocess, sys, tempfile
import numpy as np
from PIL import Image
from common import ANALYSIS_WIDTH, box_downscale, load, to_lab
from fit import DARK_END, DARK_FULL, params, stats
import fixture, smooth

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE_INTERVAL = 1.0   # seconds between timeline samples (CorrectionTimeline.SAMPLE_INTERVAL_US)
SIGMA = 2.0             # Gaussian smoothing of the matrices over time, seconds (CorrectionTimeline.SIGMA_US)
SAMPLE_WIDTH = 400      # width the app decodes samples at
EVAL_WIDTH = 400        # as ReferenceSetTest
STEP = 0.5              # prepare.py frame step; frame i is at STEP/2 + i*STEP
FLICKER_POINTS = 10
FLICKER_LIMIT = 0.5
ORACLE_LIMIT, DEFAULT_LIMIT = 6.5, 9.0  # auto-correction spec "Quality against the reference set"
IDENTITY = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0]], np.float64)


def ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        return "ffmpeg"


# --- model as a matrix (CorrectionParams / CpuCorrection.full) ---------------------------------

def estimate(img):
    """3x4 matrix of V1Model.estimate for an image (any size; statistics on the ANALYSIS_WIDTH copy)."""
    th = [fixture.COEF[k] for k in ("c0", "c1", "c2", "beta", "lo_pct", "red")]
    p = params(stats(box_downscale(img, ANALYSIS_WIDTH)), th, "prop", fixture.CAP, fixture.HI_PCT, fixture.BLACK_MAX,
               None, True, "black", True)
    if p is None:
        return IDENTITY.copy()
    a = p["a"]
    return np.array([[1, a, -a * p["beta"], p["offset"]],
                     [0, p["gg"], 0, -p["lg"] * p["gg"]],
                     [0, 0, p["gb"], -p["lb"] * p["gb"]]], np.float64)


def full(x, m):
    """CpuCorrection.full for pixels x (n, 3) and matrix m: red never lowered, dark red cap, clamped."""
    r, g, b = x[:, 0], x[:, 1], x[:, 2]
    out = x @ m[:, :3].T + m[:, 3]
    rf = np.maximum(out[:, 0], r); gc = np.clip(out[:, 1], 0, 1); bc = np.clip(out[:, 2], 0, 1)
    limit = gc + np.maximum(r - g, 0)
    w = np.clip((DARK_END - (gc + bc) / 2) / (DARK_END - DARK_FULL), 0, 1)
    rf = rf - w * np.maximum(rf - limit, 0)
    return np.clip(np.stack([rf, out[:, 1], out[:, 2]], 1), 0, 1)


def maps(frame, m):
    """Guided-filter maps A, B of the frame's own working copy for matrix m (smooth.coefficients)."""
    work = box_downscale(frame, min(smooth.SMOOTH_WIDTH, frame.shape[1]))
    h, w = work.shape[:2]
    r = min(smooth.radius(w), w, h)
    d = full(work.reshape(-1, 3), m).reshape(work.shape) - work
    g = work.mean(2)
    mg = smooth.box(g, r); vg = smooth.box(g * g, r) - mg * mg
    A = np.empty((h, w, 3)); B = np.empty((h, w, 3))
    for c in range(3):
        mp = smooth.box(d[..., c], r)
        a = (smooth.box(g * d[..., c], r) - mg * mp) / (vg + smooth.EPS)
        A[..., c] = smooth.box(a, r); B[..., c] = smooth.box(mp - a * mg, r)
    return A, B


def shift_at(frame, m, size):
    """Shift (before strength) of `frame` corrected with m, evaluated on its `size`-wide copy; returns (x, shift)."""
    A, B = maps(frame, m)
    x = box_downscale(frame, size)
    H, W = x.shape[:2]
    return x, smooth.upsample(A, H, W) * x.mean(2, keepdims=True) + smooth.upsample(B, H, W)


# --- timeline (CorrectionTimeline) ---------------------------------------------------------------

def smoothed(times, matrices, sigma=SIGMA):
    """Gaussian smoothing of the matrices at the sample times, weighted by the real times."""
    t = np.asarray(times, np.float64)
    w = np.exp(-((t[:, None] - t[None, :]) ** 2) / (2 * sigma * sigma))
    w /= w.sum(1, keepdims=True)
    return np.einsum("ij,jkl->ikl", w, np.asarray(matrices))


def at(times, smoothed_matrices, t):
    """Linear interpolation between the smoothed samples, held before the first and after the last."""
    times = np.asarray(times)
    if t <= times[0]:
        return smoothed_matrices[0]
    if t >= times[-1]:
        return smoothed_matrices[-1]
    k = int(np.searchsorted(times, t, side="right")) - 1
    f = (t - times[k]) / (times[k + 1] - times[k])
    return smoothed_matrices[k] * (1 - f) + smoothed_matrices[k + 1] * f


# --- video access ------------------------------------------------------------------------------------

def probe(video):
    """(duration s, frame rate) from ffmpeg's banner."""
    err = subprocess.run([ffmpeg(), "-hide_banner", "-i", video], capture_output=True, text=True).stderr
    h, m, s = re.search(r"Duration: (\d+):(\d+):([\d.]+)", err).groups()
    fps = float(re.search(r"([\d.]+) fps", err).group(1))
    return int(h) * 3600 + int(m) * 60 + float(s), fps


def sync_frames(video, out):
    """All sync frames, SAMPLE_WIDTH wide: list of (time, image)."""
    os.makedirs(out, exist_ok=True)
    err = subprocess.run([ffmpeg(), "-hide_banner", "-skip_frame", "nokey", "-i", video, "-map", "0:v:0",
                          "-vf", f"scale={SAMPLE_WIDTH}:-2,showinfo", "-fps_mode", "passthrough",
                          os.path.join(out, "k%05d.png")], capture_output=True, text=True, check=True).stderr
    times = [float(t) for t in re.findall(r"pts_time:([\d.]+)", err)]
    files = sorted(glob.glob(os.path.join(out, "k*.png")))
    return [(t, load(f)) for t, f in zip(times, files)]


def samples(video, duration, tmp, interval=SAMPLE_INTERVAL):
    """The sync frame closest to each multiple of `interval` and to the end, without repeats, in time order."""
    keys = sync_frames(video, os.path.join(tmp, "keys"))
    kt = np.array([t for t, _ in keys])
    targets = list(np.arange(0, duration, interval)) + [duration]
    chosen = sorted({int(np.argmin(np.abs(kt - t))) for t in targets})
    return [keys[i] for i in chosen]


def frame_pair(video, t, width, tmp):
    """Two consecutive frames starting at time t."""
    out = os.path.join(tmp, f"pair_{t:.3f}")
    os.makedirs(out, exist_ok=True)
    subprocess.run([ffmpeg(), "-loglevel", "error", "-ss", f"{t:.3f}", "-i", video, "-frames:v", "2",
                    "-vf", f"scale='min({width},iw)':-2", os.path.join(out, "f%d.png")], check=True)
    return load(os.path.join(out, "f1.png")), load(os.path.join(out, "f2.png"))


# --- measures ------------------------------------------------------------------------------------

def mean_de(a, b):
    return float(np.linalg.norm(to_lab(a) - to_lab(b), axis=-1).mean())


def mean_lab(img):
    return to_lab(img).reshape(-1, 3).mean(0)


def oracle_s(x, d, y):
    return float(np.clip((d * (y - x)).sum() / max((d * d).sum(), 1e-9), 0, 1.3))


def check(sid, video, ref_dir, args, tmp):
    duration, fps = probe(video)
    smp = samples(video, duration, tmp, args.interval)
    times = [t for t, _ in smp]
    mats = smoothed(times, [estimate(img) for _, img in smp], args.sigma)
    if args.print_timeline:
        for t, m in zip(times, mats):
            print(f"  t={t:7.3f} " + " ".join(f"{v:.5f}" for v in m.ravel()))
    s = fixture.STRENGTH
    mask_img = np.asarray(Image.open(os.path.join(ref_dir, "mask.png")), np.float64)[..., None] / 255
    originals = sorted(glob.glob(os.path.join(ref_dir, "*_original.png")))
    vs_frame, de_orig, de_def, de_orc, de_pf = [], [], [], [], []
    for i, path in enumerate(originals):
        t = STEP / 2 + i * STEP
        frame = load(path)
        y = box_downscale(load(path.replace("_original", "_reference")), EVAL_WIDTH)
        mask = box_downscale(np.repeat(mask_img, 3, 2), EVAL_WIDTH)[..., 0] > 0.999
        x, d_frame = shift_at(frame, estimate(frame), EVAL_WIDTH)
        _, d_time = shift_at(frame, at(times, mats, t), EVAL_WIDTH)
        vs_frame.append(mean_de(np.clip(x + s * d_time, 0, 1), np.clip(x + s * d_frame, 0, 1)))
        xm, dm, ym = x[mask], d_time[mask], y[mask]
        de_orig.append(mean_de(xm, ym))
        de_def.append(mean_de(np.clip(xm + s * dm, 0, 1), ym))
        de_orc.append(mean_de(np.clip(xm + oracle_s(xm, dm, ym) * dm, 0, 1), ym))
        de_pf.append(mean_de(np.clip(xm + s * d_frame[mask], 0, 1), ym))
    flick_t, flick_f = [], []
    for k in range(FLICKER_POINTS):
        t = (k + 0.5) * (duration - 2 / fps) / FLICKER_POINTS
        a, b = frame_pair(video, t, 1200, tmp)
        dt = 1 / fps
        base = float(np.linalg.norm(mean_lab(a) - mean_lab(b)))
        def corrected(img, m):
            xx, dd = shift_at(img, m, EVAL_WIDTH)
            return np.clip(xx + s * dd, 0, 1)
        ct = float(np.linalg.norm(mean_lab(corrected(a, at(times, mats, t))) - mean_lab(corrected(b, at(times, mats, t + dt)))))
        cf = float(np.linalg.norm(mean_lab(corrected(a, estimate(a))) - mean_lab(corrected(b, estimate(b)))))
        flick_t.append(ct - base); flick_f.append(cf - base)
    return dict(sid=sid, duration=duration, samples=len(smp), frames=len(originals),
                vs_frame=float(np.mean(vs_frame)), original=float(np.mean(de_orig)), default=float(np.mean(de_def)),
                oracle=float(np.mean(de_orc)), per_frame=float(np.mean(de_pf)), flicker=max(flick_t), flicker_per_frame=max(flick_f))


PARITY_TIMES = [0.0, 0.9, 2.1, 3.0, 4.4, 5.0, 7.5, 8.0]  # uneven, like sync frames
PARITY_QUERIES = [-1.0, 0.0, 0.45, 1.3, 2.1, 3.7, 6.0, 7.9, 8.0, 9.0]


def parity_matrix(k):
    """Synthetic matrix k (CorrectionTimelineTest has the same formula)."""
    return np.array([np.sin(0.7 * k + 0.3 * i) * 0.5 + (1.0 if i in (0, 5, 10) else 0.0) for i in range(12)]).reshape(3, 4)


def write_parity(out):
    """Smoothed, interpolated matrices of a synthetic sequence: full, and partial (first 4 samples)."""
    lines = ["# generated by tools/reference/video_check.py --parity; do not edit",
             f"# sigma {SIGMA} s; samples at " + " ".join(map(str, PARITY_TIMES)) + "; matrix k = parity_matrix(k)",
             "# kind samples t m0..m11"]
    for n in (len(PARITY_TIMES), 4):
        times = PARITY_TIMES[:n]
        mats = smoothed(times, [parity_matrix(k) for k in range(n)])
        for t in PARITY_QUERIES:
            lines.append(f"at {n} {t} " + " ".join(f"{v:.9g}" for v in at(times, mats, t).ravel()))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w").write("\n".join(lines) + "\n")
    print(f"wrote {out}")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--parity":
        write_parity(sys.argv[2] if len(sys.argv) > 2 else os.path.join(
            HERE, "..", "..", "core", "analysis", "src", "test", "resources", "timeline_parity.txt"))
        return 0
    ap = argparse.ArgumentParser()
    ap.add_argument("--trainingset", default=os.path.join(HERE, "..", "..", "trainingset"))
    ap.add_argument("--sigma", type=float, default=SIGMA)
    ap.add_argument("--interval", type=float, default=SAMPLE_INTERVAL)
    ap.add_argument("--only", help="check only this scene id")
    ap.add_argument("--print-timeline", action="store_true", help="print the smoothed matrices of each video")
    args = ap.parse_args()
    root = os.path.abspath(args.trainingset)
    pairs = os.path.join(root, "pairs.csv")
    if not os.path.exists(pairs):
        print(f"skipped: no reference set at {root}")
        return 0
    rows = [r for r in csv.DictReader(open(pairs)) if r["kind"] == "video" and r["app"] == "refapp"
            and (args.only is None or r["id"] == args.only)]
    rows = [r for r in rows if os.path.exists(os.path.join(root, r["original"]))
            and os.path.isdir(os.path.join(root, "reference", r["id"]))]
    if not rows:
        print("skipped: no reference videos (run prepare.py first)")
        return 0
    print(f"sample interval {args.interval} s, sigma {args.sigma} s, strength {fixture.STRENGTH}")
    print(f"{'video':22} {'dur s':>6} {'samples':>7} {'frames':>6} {'vs frame':>8} {'original':>8} {'default':>8} "
          f"{'oracle':>7} {'pf dflt':>7} {'flicker':>8} {'(per-frame)':>11}")
    results = []
    for r in rows:
        with tempfile.TemporaryDirectory() as tmp:
            res = check(r["id"], os.path.join(root, r["original"]), os.path.join(root, "reference", r["id"]), args, tmp)
        results.append(res)
        print(f"{res['sid']:22} {res['duration']:6.1f} {res['samples']:7d} {res['frames']:6d} {res['vs_frame']:8.3f} "
              f"{res['original']:8.2f} {res['default']:8.2f} {res['oracle']:7.2f} {res['per_frame']:7.2f} {res['flicker']:8.3f} {res['flicker_per_frame']:11.3f}",
              flush=True)
    mean = lambda k: float(np.mean([x[k] for x in results]))
    print(f"{'MEAN':22} {'':6} {'':7} {'':6} {mean('vs_frame'):8.3f} {mean('original'):8.2f} {mean('default'):8.2f} {mean('oracle'):7.2f} {mean('per_frame'):7.2f}")
    failures = []
    if mean("default") > mean("per_frame"):
        failures.append("timeline farther from RefApp than per-frame correction")
    if max(x["flicker"] for x in results) > FLICKER_LIMIT:
        failures.append(f"flicker > {FLICKER_LIMIT}")
    if mean("default") > DEFAULT_LIMIT:
        failures.append(f"mean ΔE at default strength > {DEFAULT_LIMIT}")
    worse = [x["sid"] for x in results if x["default"] >= x["original"]]
    if worse:
        failures.append(f"not closer than the original: {worse}")
    print(f"limits: default ≤ {DEFAULT_LIMIT} and ≤ per-frame default (means over videos), flicker ≤ {FLICKER_LIMIT}, "
          f"every video closer than its original; vs frame and oracle are reported only (oracle ≤ {ORACLE_LIMIT} "
          f"applies to photos and video frames together in ReferenceSetTest)")
    print("FAILED: " + "; ".join(failures) if failures else "OK")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
