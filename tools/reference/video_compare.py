"""Side-by-side comparison video for judging video correction by eye.

usage: python video_compare.py ID [--trainingset DIR] [--width 640] [--sigma 2.0] [--seconds N] [--out FILE]

Writes trainingset/review/<ID>_compare.mp4 with three panels, labelled:
  original | each frame corrected on its own | correction over time (sampled every second, smoothed)
plus the RefApp result as a fourth panel when trainingset/<ID>_refapp.mp4 exists. Every frame of the
clip is corrected at the default strength with its own guided filter, as video_check.py does.
"""
import argparse, os, subprocess, sys, tempfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import fixture
import video_check as vc

HERE = os.path.dirname(os.path.abspath(__file__))


def frames(video, width, seconds=None):
    """Decodes the clip `width` px wide; yields float RGB frames."""
    dur, fps = vc.probe(video)
    err = subprocess.run([vc.ffmpeg(), "-hide_banner", "-i", video], capture_output=True, text=True).stderr
    w, h = map(int, __import__("re").search(r", (\d{3,5})x(\d{3,5})", err).groups())
    H = round(h * width / w / 2) * 2
    cmd = [vc.ffmpeg(), "-loglevel", "error", "-i", video, "-map", "0:v:0"]
    if seconds:
        cmd += ["-t", str(seconds)]
    cmd += ["-vf", f"scale={width}:{H}:flags=area", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    n = width * H * 3
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        yield np.frombuffer(buf, np.uint8).reshape(H, width, 3).astype(np.float32) / 255
    p.wait()


def corrected(img, m, s):
    x, d = vc.shift_at(img, m, img.shape[1])
    return np.clip(x + s * d, 0, 1)


def label(img, text, font):
    im = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))
    dr = ImageDraw.Draw(im)
    box = dr.textbbox((0, 0), text, font=font)
    dr.rectangle((0, 0, box[2] + 16, box[3] + 12), fill=(0, 0, 0))
    dr.text((8, 4), text, fill=(255, 255, 255), font=font)
    return np.asarray(im)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("id")
    ap.add_argument("--trainingset", default=os.path.join(HERE, "..", "..", "trainingset"))
    ap.add_argument("--width", type=int, default=640, help="width of each panel")
    ap.add_argument("--sigma", type=float, default=vc.SIGMA)
    ap.add_argument("--seconds", type=float, help="only the first N seconds")
    ap.add_argument("--out")
    args = ap.parse_args()
    root = os.path.abspath(args.trainingset)
    video = os.path.join(root, f"{args.id}_original.mp4")
    refapp = os.path.join(root, f"{args.id}_refapp.mp4")
    out = args.out or os.path.join(root, "review", f"{args.id}_compare.mp4")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    dur, fps = vc.probe(video)
    with tempfile.TemporaryDirectory() as tmp:
        smp = vc.samples(video, dur, tmp)
    times = [t for t, _ in smp]
    mats = vc.smoothed(times, [vc.estimate(img) for _, img in smp], args.sigma)
    s = fixture.STRENGTH
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", max(14, args.width // 28))
    except OSError:
        font = ImageFont.load_default()
    titles = ["Original", "Each frame on its own", f"Smoothed over time ({args.sigma:g} s)"]
    sources = [frames(video, args.width, args.seconds)]
    if os.path.exists(refapp):
        titles.append("RefApp")
        sources.append(frames(refapp, args.width, args.seconds))
    enc = None
    for i, group in enumerate(zip(*sources)):
        x = group[0]
        t = i / fps
        panels = [x, corrected(x, vc.estimate(x), s), corrected(x, vc.at(times, mats, t), s)] + list(group[1:])
        row = np.concatenate([label(p, ti, font) for p, ti in zip(panels, titles)], 1)
        if len(panels) == 4:  # 2x2 grid keeps the video watchable on a laptop screen
            hw = row.shape[1] // 2
            row = np.concatenate([row[:, :hw], row[:, hw:]], 0)
        if enc is None:
            hgt, wid = row.shape[:2]
            enc = subprocess.Popen([vc.ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                                    "-s", f"{wid}x{hgt}", "-r", f"{fps}", "-i", "-", "-c:v", "libx264", "-crf", "16",
                                    "-pix_fmt", "yuv420p", out], stdin=subprocess.PIPE)
        enc.stdin.write(row.tobytes())
        if i % 60 == 0:
            print(f"{args.id}: {t:.1f} s", flush=True)
    enc.stdin.close(); enc.wait()
    print(f"wrote {out}")


if __name__ == "__main__":
    sys.exit(main())
