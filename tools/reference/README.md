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
- `fixture.py` writes `core/analysis/src/test/resources/parity.txt` from synthetic images, so the Kotlin port is checked against `fit.py` and `smooth.py`. Rerun it after changing the model or its coefficients.

## Model

See `openspec/changes/add-auto-correction/design.md` (D1–D3, D10), or the auto-correction spec once that change is archived. The statistics use a 200 px wide area-averaged copy. That width and the downscale method are part of the model, and the app implements the same `box_downscale` bounds. `smooth.py` is the reference for applying the correction: a guided filter on a 1024 px working copy, so grain in flat water is not amplified.
