"""Fits the DiveLens correction model to the reference pairs written by prepare.py.

usage: python fit.py [--reference DIR] [--resample box|bilinear] [--offset prop|const] [--gain-cap 3.0]
                     [--black-max 0.45] [--black-mean-fraction F] [--no-red-floor] [--no-red-cap]
                     [--anchor black|white] [--loso] [--out params.json]

Model (design D1/D2), on sRGB-encoded values in 0..1, statistics on an ANALYSIS_WIDTH copy:
  a   = clamp(c0 + c1*mR/mG + c2*mB/mG, 0, 2.5)
  R_f = R + a*(G - beta*B) + offset       offset = red*a (prop) or red (const)
  G_f = (G - lG)*gG, gG = min(1/(hG - lG), cap)   lG = min(lo_pct percentile, black_max), hG = p99.9; B likewise
  R_f = max(R_f, R)                       red is never lowered (--no-red-floor)...
  R_f -= w*max(R_f - (G_f + max(R - G, 0)), 0)   ...except that in dark results red may not end above green
                                          unless it already was; w = 1 below DARK_FULL, 0 above DARK_END of
                                          the corrected (G_f + B_f)/2 (--no-red-cap)
  out = x + s*(clamp(f, 0, 1) - x)
Looks (design D10): the defaults are look "H2", the app's. Kept for a possible look picker:
  E: --gain-cap 2.0 --no-red-cap      F: E plus --black-mean-fraction 0.5 (black point at most half the mean)
The app also smooths the correction (design D10); the fit leaves that out, it barely moves mean colours.
The RefApp strength s is unknown per image, so it is estimated per image ("oracle s", least squares in RGB).
The default strength is the median oracle s.
"""
import argparse, glob, json, os
import numpy as np
from PIL import Image
from scipy.optimize import minimize
from common import ANALYSIS_WIDTH, downscale, load, to_lab

HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = ["c0", "c1", "c2", "beta", "lo_pct", "red"]
THETA0 = np.array([2.03, -2.16, 0.22, 0.45, 0.19, -0.1])
FRAME_STEP = 4
DARK_FULL, DARK_END = 0.2, 0.4  # corrected green/blue level where the red cap applies fully / no longer  # use every 4th video frame (2 s apart): neighbouring frames are nearly identical


def stats(img):
    """Statistics the model needs, from the analysis-size copy (whole image, as in the app)."""
    px = img.reshape(-1, 3)
    return dict(mean=px.mean(0), sorted_g=np.sort(px[:, 1]), sorted_b=np.sort(px[:, 2]))


def percentile_sorted(s, p):
    """numpy 'linear' percentile on a sorted array (the app implements the same)."""
    pos = p / 100 * (len(s) - 1)
    i = int(np.floor(pos)); f = pos - i
    return s[i] if i + 1 >= len(s) else s[i] * (1 - f) + s[i + 1] * f


def params(st, th, offset="prop", cap=2.0, hi_pct=100.0, black_max=None, black_mean_fraction=None, red_floor=True, anchor="black",
           red_cap=False):
    c0, c1, c2, beta, lo, red = th
    lo = float(np.clip(lo, 0, 20))
    mR, mG, mB = st["mean"]
    if mG < 0.01:
        return None  # identity
    a = float(np.clip(c0 + c1 * mR / mG + c2 * mB / mG, 0, 2.5))
    out = dict(a=a, beta=beta, offset=red * a if offset == "prop" else red, red_floor=red_floor, red_cap=red_cap)
    for i, ch, s in ((1, "g", st["sorted_g"]), (2, "b", st["sorted_b"])):
        l = percentile_sorted(s, lo)
        if black_max is not None:
            l = min(l, black_max)
        if black_mean_fraction is not None:
            l = min(l, black_mean_fraction * st["mean"][i])
        h = s[-1] if hi_pct >= 100 else percentile_sorted(s, hi_pct)
        gain = 1 / max(h - l, 1e-3)
        if cap and gain > cap:
            gain = cap
            if anchor == "white":  # a capped stretch keeps the white point; the black point gives way
                l = h - 1 / cap
        out["l" + ch] = l; out["g" + ch] = gain
    return out


def full(x, p):
    if p is None:
        return x
    r = x[:, 0] + p["a"] * (x[:, 1] - p["beta"] * x[:, 2]) + p["offset"]
    g = (x[:, 1] - p["lg"]) * p["gg"]; b = (x[:, 2] - p["lb"]) * p["gb"]
    if p.get("red_floor"):
        r = np.maximum(r, x[:, 0])
    if p.get("red_cap"):  # in dark areas red may not end above green unless it already was: no red cast in dark water
        limit = np.clip(g, 0, 1) + np.maximum(x[:, 0] - x[:, 1], 0)
        dark = (np.clip(g, 0, 1) + np.clip(b, 0, 1)) / 2  # brightness after correction
        w = np.clip((DARK_END - dark) / (DARK_END - DARK_FULL), 0, 1)  # 1 in dark areas, fading to 0
        r = r - w * np.maximum(r - limit, 0)
    return np.clip(np.stack([r, g, b], 1), 0, 1)


def load_scenes(ref, resample, app="refapp", eval_resample=None):
    eval_resample = eval_resample or resample
    data = []
    for d in sorted(glob.glob(os.path.join(ref, "*"))):
        if not os.path.isdir(d) or open(os.path.join(d, "scene.txt")).read().split()[0] != app:
            continue
        sid = os.path.basename(d)
        mask = np.asarray(Image.open(os.path.join(d, "mask.png")), np.float64)[..., None] / 255
        originals = sorted(glob.glob(os.path.join(d, "*_original.png")))[::FRAME_STEP]
        m = downscale(np.repeat(mask, 3, 2), ANALYSIS_WIDTH, "box")[..., 0] > 0.999
        for o_path in originals:
            full_o = load(o_path)
            o = downscale(full_o, ANALYSIS_WIDTH, eval_resample)
            y = downscale(load(o_path.replace("_original", "_reference")), ANALYSIS_WIDTH, eval_resample)
            st = stats(downscale(full_o, ANALYSIS_WIDTH, resample))
            data.append(dict(scene=sid, st=st, x=o[m], y=y[m], lab_y=to_lab(y[m])))
    return data


def model_params(st, th, args):
    return params(st, th, args.offset, args.gain_cap, args.hi_pct, args.black_max, args.black_mean_fraction, not args.no_red_floor, args.anchor, args.red_cap)


def oracle_s(x, f, y):
    d = f - x
    return float(np.clip((d * (y - x)).sum() / max((d * d).sum(), 1e-9), 0, 1.3))


def evaluate(th, ds, args, s_fixed=None):
    errs, ss = [], []
    for d in ds:
        f = full(d["x"], model_params(d["st"], th, args))
        s = oracle_s(d["x"], f, d["y"]) if s_fixed is None else s_fixed
        errs.append(np.linalg.norm(to_lab(d["x"] + s * (f - d["x"])) - d["lab_y"], axis=-1).mean()); ss.append(s)
    return errs, ss


def fit(ds, args, th0=THETA0):
    r = minimize(lambda t: np.mean(evaluate(t, ds, args)[0]), th0, method="Nelder-Mead",
                 options=dict(maxiter=400, xatol=1e-3, fatol=1e-3))
    return r.x


def per_scene(ds, values):
    out = {}
    for d, v in zip(ds, values):
        out.setdefault(d["scene"], []).append(v)
    return {k: float(np.mean(v)) for k, v in out.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", default=os.path.join(HERE, "..", "..", "trainingset", "reference"))
    ap.add_argument("--resample", choices=["box", "bilinear"], default="box")
    ap.add_argument("--eval-resample", choices=["box", "bilinear"], help="downscale for ΔE evaluation (default: --resample)")
    ap.add_argument("--offset", choices=["prop", "const"], default="prop")
    ap.add_argument("--gain-cap", type=float, default=3.0, help="0 for no cap")
    ap.add_argument("--black-max", type=float, default=0.45, help="upper limit of the G/B black point")
    ap.add_argument("--black-mean-fraction", type=float, help="also limit the black point to this fraction of the channel mean (look F: 0.5)")
    ap.add_argument("--anchor", choices=["black", "white"], default="black", help="point a capped stretch keeps in place")
    ap.add_argument("--no-red-cap", dest="red_cap", action="store_false", help="allow red above green in dark areas")
    ap.add_argument("--no-red-floor", action="store_true", help="allow the correction to lower red")
    ap.add_argument("--hi-pct", type=float, default=99.9, help="percentile used as the G/B white point (100 = maximum)")
    ap.add_argument("--loso", action="store_true", help="also run leave-one-scene-out validation")
    ap.add_argument("--out")
    args = ap.parse_args()

    data = load_scenes(args.reference, args.resample, eval_resample=args.eval_resample)
    scenes = sorted({d["scene"] for d in data})
    print(f"{len(data)} samples from {len(scenes)} scenes; resample={args.resample} offset={args.offset} cap={args.gain_cap} "
          f"black_max={args.black_max} black_mean_fraction={args.black_mean_fraction} red_floor={not args.no_red_floor}")

    th = fit(data[::2], args)
    e_or, ss = evaluate(th, data, args)
    s_def = float(np.median(list(per_scene(data, ss).values())))
    e_def, _ = evaluate(th, data, args, s_fixed=s_def)
    ident = [np.linalg.norm(to_lab(d["x"]) - d["lab_y"], axis=-1).mean() for d in data]
    sc_or, sc_def, sc_id, sc_s = per_scene(data, e_or), per_scene(data, e_def), per_scene(data, ident), per_scene(data, ss)
    M = lambda m: float(np.mean(list(m.values())))
    print("coefficients: " + ", ".join(f"{n} {v:.4f}" for n, v in zip(NAMES, th)) + f"; default strength {s_def:.3f}")
    print(f"\n{'scene':22} original  oracle s  default s  strength")
    for sc in scenes:
        print(f"{sc:22} {sc_id[sc]:8.2f} {sc_or[sc]:9.2f} {sc_def[sc]:10.2f} {sc_s[sc]:9.2f}")
    print(f"{'MEAN (in-sample)':22} {M(sc_id):8.2f} {M(sc_or):9.2f} {M(sc_def):10.2f}")
    result = dict(coefficients=dict(zip(NAMES, map(float, th))), default_strength=s_def, offset=args.offset,
                  gain_cap=args.gain_cap, black_max=args.black_max,
                  black_mean_fraction=args.black_mean_fraction, red_floor=not args.no_red_floor, resample=args.resample, analysis_width=ANALYSIS_WIDTH,
                  in_sample=dict(original=M(sc_id), oracle=M(sc_or), default=M(sc_def)))

    if args.loso:
        lo_or, lo_fix = {}, {}
        for sc in scenes:
            tr = [d for d in data if d["scene"] != sc][::2]; te = [d for d in data if d["scene"] == sc]
            t = fit(tr, args, th)
            s_tr = float(np.median(evaluate(t, tr, args)[1]))
            lo_or[sc] = float(np.mean(evaluate(t, te, args)[0]))
            lo_fix[sc] = float(np.mean(evaluate(t, te, args, s_fixed=s_tr)[0]))
        print(f"\n{'held-out scene':22} oracle s  fixed s")
        for sc in scenes:
            print(f"{sc:22} {lo_or[sc]:8.2f} {lo_fix[sc]:8.2f}")
        print(f"{'MEAN (held-out)':22} {M(lo_or):8.2f} {M(lo_fix):8.2f}")
        result["held_out"] = dict(oracle=M(lo_or), fixed=M(lo_fix))

    if args.out:
        json.dump(result, open(args.out, "w"), indent=1)


if __name__ == "__main__":
    main()
