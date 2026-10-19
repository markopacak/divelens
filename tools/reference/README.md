# Reference fit

These scripts fit DiveLens's automatic correction to before/after pairs from other apps (RefApp), and prepare the data for the app's reference check (`ReferenceSetTest` in `:core:analysis`).

They need personal reference media, which never goes into the repository. Without it, nothing here runs and nothing in the build depends on it.

## Training set layout

Put the media in `trainingset/` at the repository root. That folder is gitignored.

```
trainingset/
  pairs.csv
  <id>_original.jpg | .mp4     the untouched photo or video
  <id>_<app>.jpg | .mp4        the same photo or video exported from the reference app
```

`pairs.csv` columns:

| column | meaning |
|---|---|
| `id` | scene id, used for folder names |
| `kind` | `photo` or `video` |
| `original` | file name of the original |
| `processed` | file name of the reference app's result |
| `app` | `refapp` or `refapp2`. Only `refapp` pairs are fitted. The app also decides which watermark corner is masked. |

Extra columns (for example a scene description) are ignored.

## Running

```sh
python3 -m venv .venv && .venv/bin/pip install -r tools/reference/requirements.txt
.venv/bin/python tools/reference/prepare.py          # writes trainingset/reference/
.venv/bin/python tools/reference/fit.py --loso       # fits the model, prints in-sample and held-out ΔE
```

- `prepare.py` extracts video frames every 0.5 s. It uses ffmpeg when it is installed and otherwise `frames.swift` (macOS, needs the Xcode command line tools).
- It writes each pair at 1200 px wide with EXIF orientation applied, the reference resized to the original's size, and a `mask.png` that excludes the watermark.
- `fit.py --loso` takes several minutes: it refits once per held-out scene.
- `fit.py` prints the coefficients that go into `V1Model` in `:core:analysis`. Its defaults are the app's look (H2). The alternative looks kept in `V1Model` are fitted with `--gain-cap 2.0 --no-red-cap` (E) and additionally `--black-mean-fraction 0.5` (F).
- `draft_check.py` checks that estimating the correction from a reduced JPEG decode (as the app's preview and export do, sample size keeping the width ≥ 2048 px) gives nearly the same result as the full-resolution estimate: mean ΔE at most 0.5 per photo. `--sample N` forces a sample size.
- `video_check.py` is the video reference check (add-video-processing design D1, D9). For each reference video it samples the sync frame nearest every second (400 px wide, as the app does), smooths the correction over time (Gaussian, σ = 2 s) and interpolates it per frame. It then compares the result with correcting each frame on its own and with RefApp, and measures flicker between neighbouring frames. It needs the frames `prepare.py` writes; without the reference set it prints "skipped". It takes about 4 minutes. `--sigma` and `--interval` try other values, and `--print-timeline` prints the smoothed matrices. `--parity` writes `core/analysis/src/test/resources/timeline_parity.txt` from a synthetic sequence, so `CorrectionTimeline` is checked against this script; rerun it after changing σ or the smoothing.
- `video_compare.py ID` renders `trainingset/review/<ID>_compare.mp4`: original, each frame corrected on its own, the correction over time and RefApp, side by side, to judge the same by eye.
- `fixture.py` writes `core/analysis/src/test/resources/parity.txt` from synthetic images, so the Kotlin port is checked against `fit.py` and `smooth.py`. Rerun it after changing the model or its coefficients.

## Model

See the auto-correction spec (`openspec/specs/auto-correction/spec.md`) and the archived design, `openspec/changes/archive/2026-10-17-add-auto-correction/design.md` (D1–D3, D10). The statistics use a 200 px wide area-averaged copy. That width and the downscale method are part of the model, and the app implements the same `box_downscale` bounds. `smooth.py` is the reference for applying the correction: a guided filter on a 1024 px working copy, so grain in flat water is not amplified.

## Video test assets

`video_fixture.py` writes the synthetic videos the app's instrumented tests use, to `app/src/androidTest/assets/`. They are generated from formulas, with no personal media. The location tag is a public landmark (the Eiffel Tower), only there so tests can check that exports drop it. Rerun the script only when the tests need different assets. ffmpeg's view of them (2026-10-19):

```
synthetic_drift.mp4  299 KB
  creation_time 2026-07-14T08:32:05Z
  location +48.8584+002.2945/ twice: a QuickTime ©xyz box (as phones write) and a 3GPP loci box (as ffmpeg writes)
  Stream 0: h264 (High), yuv420p, 640x360, 30 fps, 2.00 s, displaymatrix: rotation of -90.00 degrees
             (ffmpeg counts counter-clockwise: Android reads rotation 90)
  Stream 1: aac (LC), 48000 Hz, mono, 96 kb/s (440 Hz tone)
  Stream 2: mov_text subtitle ("GPS 48.8584 2.2945"), standing in for camera telemetry
synthetic_hlg.mp4     70 KB
  creation_time 2026-07-14T08:32:05Z
  Stream 0: hevc (Main 10), yuv420p10le (tv, bt2020nc/bt2020/arib-std-b67), 640x360, 30 fps, 1.00 s
synthetic_long.mp4   714 KB
  creation_time 2026-07-14T08:32:05Z
  Stream 0: h264 (High), yuv420p, 320x180, 30 fps, 20.00 s, 245 kb/s (the same texture, drifting ten times slower)
  Stream 1: aac (LC), 48000 Hz, mono, 32 kb/s
```

The colour of `synthetic_drift.mp4` drifts from green-blue at 0 s to deep blue at 2 s, and a bright vertical bar moves from left to right, so the tests have a colour change over time and a moving edge.
