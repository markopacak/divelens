# Design

## Context

- **Export path today.** `MediaStorePhotoWriter` decodes the full-resolution photo into an `ARGB_8888` bitmap and passes it through the `PhotoProcessor` seam (`export/Seams.kt`). `MainActivity` wires in `IdentityPhotoProcessor`. The seam allows the processor to return its input, and the writer recycles both bitmaps. Import accepts JPEG and HEIC only, so photos are opaque.
- **Colour model.** It comes from the RefApp reference analysis (`trainingset/analysis/REPORT.md`, gitignored):
  - RefApp applies one global 3×4 matrix per image to sRGB-encoded values.
  - Green and blue get a plain levels stretch.
  - Red gets `R + α(G − βB) + c` and is never stretched on its own.
  - The "v1" model reproduces this, with α predicted from mean R/G and B/G. Held-out mean ΔE to RefApp is 6.5 with the strength matched per image, against 20.6 for the uncorrected originals.
- **Strength.** The RefApp slider value differed per file and is unknown. The analysis therefore treats every RefApp output as `orig + s·(full − orig)` with `s` estimated per image. The estimates range from 0.41 to 0.88, with a median of 0.70 (0.60 with the D2 model).
- **Constraints from the project context.** One GLSL ES 3.0 pipeline shared with video. Analysis in a pure JVM module, testable without an emulator. No personal media in the repository.

## Goals / Non-Goals

**Goals:**
- One small, explainable correction model with its coefficients in one place, plus a reproducible way to refit them.
- GPU and CPU paths that agree to within 1/255 per channel, so the CPU path can serve as both fallback and test oracle.
- Full-resolution correction without a second full-size bitmap.
- A reference check that contributors can skip and the maintainer can run locally.

**Non-Goals:**
- Local operations (sharpening, clarity, CLAHE). OpenCV stays out. The one exception is the grain-aware smoothing of the correction itself (D10), which never sharpens.
- Linear-light or wide-gamut processing. The model is defined on encoded values because that is what RefApp does and what was fitted.
- A UI for strength (change 4), or showing the correction before export (change 3).
- Temporal smoothing for video (change 5). The parameter type is designed so change 5 can interpolate it.

## Decisions

### D1. The v1 model, in one place

`:core:analysis` holds the model as plain Kotlin:

```
stats     (on a downscaled copy, encoded values in 0..1)
          mR, mG, mB = channel means
          lG, lB     = 0.59th percentile of G, B
          hG, hB     = 99.9th percentile of G, B
alpha     a  = clamp(c0 + c1·mR/mG + c2·mB/mG, 0, 2.5)
full      R_f = R + a·(G − β·B) + γ·a
          G_f = (G − lG)·gG,  lG = min(low percentile, 0.45),  gG = min(1 / (hG − lG), 3.0)
          B_f likewise
          R_f = max(R_f, R), then the dark-water red cap (D10)
          f   = clamp(R_f, G_f, B_f to 0..1)
output    out = clamp(x + s·smooth(f − x))     (D10)
```

- The parameters are a 3×4 matrix plus the strength `s` (`CorrectionParams`). The red rules and the smoothing are the same code path in Python (`fit.py`, `smooth.py`) and Kotlin, so GPU and CPU share one definition.
- The coefficients c0, c1, c2, β, γ, the percentiles, the gain cap and the black-point limit live in one `V1Model` object. A comment points to the fit that produced them. Final values are in D10 (look H2). The values from task 1.4 (c0 1.5185, c1 −2.2978, c2 0.6156, β 0.4038, low percentile 0.59, γ −0.0903) were the model before D10.
- When the mean green is below 0.01 (a nearly black photo), the parameters are the identity.

*Alternatives considered:*
- A full 3×4 matrix regressed per image from statistics. It has too many free parameters for 14 scenes.
- A 3D LUT. It fits RefApp no better than the matrix, and it is harder to blend and smooth for video.

### D2. Red offset tied to red recovery (γ·a instead of a constant c)

v1 fitted a constant red offset of −0.117. Applied to a photo with no cast, where a ≈ 0.1, that pulls red down by about 0.08 at default strength and gives land photos a cyan tint. This breaks the "Photos without an underwater cast change little" requirement.

Making the offset proportional to `a` keeps the underwater behaviour, because the offset only mattered where `a` is large, and it vanishes on neutral photos.

Preliminary check (2026-10-15; `trainingset/analysis/scripts/v3.py`; gain cap 1.6 in the fit; leave-one-scene-out over 14 scenes):

| Red offset | Held-out ΔE, oracle strength | Held-out ΔE, fixed strength | Neutral grey ΔE | Random land colours ΔE |
|---|---|---|---|---|
| constant c = −0.115 | 6.77 | 9.61 | 6.9 | 6.2 |
| **γ·a, γ = −0.07** | **6.52** | **9.08** | **2.0** | **2.9** |

The proportional offset is better on every measure, so it is adopted. Coefficients from this check: c0 2.25, c1 −2.83, c2 0.20, β 0.43, percentile 0.19, γ −0.07, median strength 0.60. Task 1.3 repeats the check on the box-downscaled data, and task 1.4 sets the final values.

*Alternative:* keep the constant offset and skip the correction when R/G is above a threshold. That creates a visible jump between nearly identical photos on either side of the threshold.

### D9. Fit results: white point at p99.9, gain cap 3.0 (task 1.3/1.4, 2026-10-15)

The refit on the box-downscaled reference pairs (`tools/reference/fit.py --loso`, 14 scenes, 61 samples) changed two parts of the preliminary model:

| Variant (box downscale) | Held-out ΔE, matched strength | Held-out ΔE, fixed strength |
|---|---|---|
| white point = maximum, proportional offset, cap 1.6 | 6.91 | 9.36 |
| white point = maximum, constant offset, cap 1.6 | 6.95 | 9.22 |
| p99.9, constant offset, no cap | 5.73 | 7.63 |
| p99.9, constant offset, cap 1.6 | 6.47 | 8.89 |
| p99.9, proportional offset, cap 1.6 | 6.22 | 9.12 |
| **p99.9, proportional offset, cap 3.0 (chosen)** | **5.41** | **7.65** |

- **White point.** With the box downscale, the maximum was set by a few bright specks (sun, bubbles), so flat open-water scenes were barely stretched. The 99.9th percentile fixed the two open-water failures: GH010814 went from 14.1 to 6.5 and GOPR0831 from 7.0 to 4.0 with matched strength.
- **Gain cap.** RefApp itself stretches green and blue by up to 2.9× on these scenes. A 1.6 cap forced the model away from it (+0.8 ΔE). A cap of 3.0 never binds on the reference set and still bounds extreme stretches on very flat photos. The spec's "Limited contrast stretch" limit was changed from 1.6 to 3 to match.
- **Red offset.** Proportional beats constant here too (5.41 vs 5.73), confirming D2.
- **Downscale.** Reproducing v1 exactly (PIL bilinear, maximum, constant offset, no cap) gives 6.45 held-out, which validates the tooling. Box stays because the app can reproduce it exactly (D3).

### D3. Analysis on a 200 px box-downscaled copy

- Statistics are computed on a box-filtered copy 200 px wide, the same width the coefficients were fitted at.
- The percentiles depend on resolution, so the analysis width is part of the model. Changing it means refitting.
- The copy is built by averaging rows of the full-resolution bitmap (raw tiles, one row band at a time). There is no second decode and no large allocation. The 1024 px working copy for the shift maps (D10) is built in the same pass.
- The Python reference switches from PIL bilinear to box resampling so both sides compute identical statistics (task 1.2).

### D4. GPU path: offscreen EGL, tiled, in place

- `GlCorrectionRenderer` creates an EGL 1.4 context for OpenGL ES 3 with a 1×1 pbuffer and renders into a framebuffer-object texture.
- It processes the bitmap in tiles of `min(2048, GL_MAX_TEXTURE_SIZE)`: upload the tile, draw one quad with the matrix shader, `glReadPixels`, then write the tile back into the same bitmap.
- Each pixel's result depends only on the pixel, its position and the shift maps (D10), so tiles cannot produce seams.
- The writer decodes with `inMutable = true` so the processor can work in place. A second 50 MP bitmap would add about 200 MB.
- EGL contexts are bound to a thread, so each photo runs on one dedicated thread. The context is created and destroyed per photo; that costs milliseconds, well below decode and encode time.
- The shader takes the shift maps as two `RGBA32F` textures read with `texelFetch`, and the strength, tile origin and sizes as uniforms. Change 5 wraps the same fragment-shader source as a Media3 `GlEffect`.
- Nothing hard-codes 8 bits beyond the tile texture format, so a 10-bit path stays open.

*Alternative:* `RenderEffect` or AGSL. Excluded by the project's architecture (Android 13+ only and not shared with video).

### D5. CPU path as fallback and oracle

`:core:analysis` also implements `CpuCorrection.applyInPlace` with the same maths as the shader (D10). The app uses it, one row band at a time, when EGL or GL fails at any step, and logs the reason. Instrumented tests run both paths on the same image and require them to agree within 1/255 per channel.

### D6. Colour space of the bitmap is kept

The correction operates on the bitmap's encoded values in whatever colour space it was decoded into (sRGB or Display P3 for some phone HEICs), and the bitmap keeps that colour space. The model was fitted on sRGB values; on P3 photos this causes a small error, much smaller than the 5.4 ΔE model error.

*Alternative:* decoding into sRGB would clip wide-gamut colours before correction.

### D7. Default strength from the fit, set once from calibration

The default strength is one constant, `V1Model.DEFAULT_STRENGTH`: the median estimated RefApp strength from the final fit. It was 0.70 before D10 and is 0.78 for look H2.

When the calibration set exists (2–3 originals exported from RefApp at slider 0/25/50/75/100), task 5 fits:
- how the RefApp slider maps onto `s`;
- whether RefApp already reduces strength on flat open-water photos.

The default strength is then replaced from that fit. If RefApp does adapt, a strength rule based on photo statistics becomes a follow-up change with its own requirement, not part of this one.

### D8. Reproducible fit and reference check

- The fit scripts move from the gitignored analysis folder into the repository at `tools/reference/`. They are code and contain no media.
- `prepare.py` turns `trainingset/` (pairs.csv, photos and videos) into normalised PNG pairs with orientation applied, frames every 0.5 s and RefApp resized to the original's size, written to `trainingset/reference/`. It also writes the watermark mask.
- `fit.py` refits `V1Model`.
- `ReferenceSetTest` in `:core:analysis` reads `trainingset/reference/` through a Gradle-provided path. It is skipped with `Assume` when the path is missing, and it checks the limits in the "Quality against the reference set" requirement.
- It evaluates at 400 px width. Analysis runs at 200 px and the shift maps at 1024 px as in production; the maps are applied at the evaluation size.
- The limits are in-sample once the model is refit on all data. They stay meaningful because the held-out results of the final model (6.33 oracle, 8.79 fixed, look H2, D10) are already within them.

### D10. Open-water revision: looks compared, H2 chosen (2026-10-17)

The calibration set (task 5.1) and the maintainer's review showed three problems with the model of D9 in open water:
- **Darkening.** In an all-blue photo the darkest blue is still bright (0.59 on GOPR0296). Taken as the black point, it turns the water navy.
- **Grain.** Stretches up to 3× amplify sensor noise in flat water (green noise 3.0 → 6.6 on GOPR0296).
- **Red cast.** RefApp itself reddens dark water at high slider values. Avoiding that is a goal: DiveLens should do better than RefApp here, not copy it.

**Rules adopted:**
- *Black-point limit.* The green/blue black point is at most 0.45, so bright open water is not turned black.
- *Red never lowered* (`R_f = max(R_f, R)`)...
- *...except in dark water* (dark red cap): where the corrected (G_f + B_f)/2 is below 0.2, red may not end above `G_f + max(R − G, 0)`. The cap fades out linearly up to 0.4, so orange subjects and bright areas keep their red. Without it the 3× stretch pulls green down in dark water while red stays, and a dark sea floor turns red-purple (shark video GH010814).
- *Grain-aware smoothing (fast guided filter).* The colour shift `f − x` is not applied pixel by pixel. A guided filter with the grey level as guide (He et al., ε = 0.02²) makes the shift the local average in flat areas and lets it follow the photo at edges. It runs on a working copy 1024 px wide (box downscale, built in the same band pass as the analysis copy) with radius 2 (scaled with the working width, mirrored edges). Its coefficient maps `a_c`, `b_c` are applied at full resolution with bilinear interpolation: `shift_c = a_c·grey + b_c`, `out = clamp(x + s·shift)`. The red rules act on the shift before smoothing, so they hold for areas, not for every pixel. On GOPR0296 green noise is 4.2 (original 3.0, D9 model 6.6, RefApp slider 50: 4.3). Across the training scenes it is below the D9 model's everywhere and within about 1.5 of RefApp's on most; the flattest patch of a real photo still grows by up to 1.8× (GH010918) because it holds faint detail that the stretch is meant to bring out. The spec's 1.5× limit is defined and tested on a uniform area with sensor noise.
  - Alternatives: the guided filter at full resolution (several GPU passes with tile overlaps; within ΔE 0.3 of the working-copy version on the calibration photos); a working copy 1296 px wide (same result, 60% more memory).

**Looks compared** (in-sample mean ΔE over the 14 scenes, matched / default strength; reviewed by the maintainer on the calibration photos, the training photos, one frame per video and the full GH010814 video):

| Look | Rules | ΔE | Default s | Verdict |
|---|---|---|---|---|
| D9 model ("now") | cap 3.0, no black limit, no smoothing | 5.16 / 6.89 | 0.70 | grainy, navy open water; best on the shark video |
| B | cap 2.0, black ≤ 0.25 | 7.18 / 8.22 | 0.87 | eagle ray violet, cast not removed |
| E | cap 2.0, black ≤ 0.45, red never lowered | 5.57 / 6.99 | 0.77 | good on photos; darkens and purples deep-blue video, GH010814 worse than the original |
| F | E, black also ≤ half the channel mean | 6.17 / 8.13 | 0.89 | better than E on some blue scenes, far off on GH010918 |
| G | E, a capped stretch keeps the white point | 6.18 / 7.47 | 0.72 | GH010918, GH010960 worse |
| H | E with cap 3.0 | 5.38 / 7.14 | 0.78 | good on photos; red-purple sea floor on the shark video |
| **H2 (chosen)** | **H plus the dark red cap** | **6.14 / 7.42** | **0.78** | **approved on photos, frames and video** |

H2's coefficients: c0 1.5409, c1 −2.1504, c2 0.4625, β 0.4840, low percentile 0.2277, γ −0.0422, white point p99.9, gain cap 3.0, black point ≤ 0.45; default strength 0.78 (median 0.783). Held-out (leave one scene out): 6.33 matched, 8.79 fixed. The cost against D9 is about 1 ΔE: RefApp itself reddens dark water and darkens open water, and H2 deliberately does neither.

E and F are kept as `V1Model.LOOK_E` and `LOOK_F` (coefficients, strengths and `fit.py` flags), unused by the app, for a possible later choice of looks.

## Risks / Trade-offs

- [Coefficients from 14 scenes, all from one diver and two cameras] → Keep the model small (six coefficients). The fit stays reproducible from `tools/reference/`, so new pairs can be added and refit.
- [The default strength is a guess until calibration] → It's a single constant. The reference check reports both oracle and default-strength ΔE, so the effect of changing it can be measured.
- [`glReadPixels` byte order and premultiplied alpha differ from `Bitmap` int ARGB] → Photos are opaque (JPEG/HEIC). An instrumented GPU-vs-CPU test with saturated primaries catches channel swaps.
- [GPU drivers without OpenGL ES 3.0, or emulators with buggy EGL] → Use the CPU fallback (D5). Every Android 10+ device must support ES 3.0, so the fallback should be rare.
- [Out of memory on very large photos] → The in-place design avoids a second bitmap. The existing writer already maps `OutOfMemoryError` to a per-item failure.
- [Model error on P3 photos] → Accepted (D6). Revisit if users report a cast on phone photos.
- [Shift maps cost memory and time: about 50 MB of float arrays while they are computed, two 12.6 MB textures] → Bounded by the 1024 px working width, independent of photo size. Measured on the device (task 7.5).
- [The red rules hold for areas, not every pixel, after smoothing] → Tests check area averages. Where dark and bright pixels are finely mixed, up to 1.5/255 of the dark-area red reduction can reach bright neighbours.
- [The looks were judged by one person on 18 scenes] → The alternatives stay documented and refittable (D10).

## Migration Plan

There is no data migration. To roll back, wire `IdentityPhotoProcessor` back in `MainActivity`.

## Open Questions

- None for this change. The calibration answered D7: RefApp adapts its strength in open water, and D10 handles open water with rules instead of a strength rule.
