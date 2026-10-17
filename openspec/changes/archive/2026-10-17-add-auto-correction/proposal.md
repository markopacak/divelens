# Proposal

## Why

Exports are currently unchanged copies. The reason anyone buys DiveLens is the correction itself: getting back the red that water absorbs and removing the blue-green cast. The RefApp reference analysis (2026-10-14, 14 before/after pairs) shows that a well-liked result can be made with one global colour matrix per image. The coefficients are estimated from simple channel statistics. That makes the core correction small, fast and testable, and we can ship it before preview (change 3) and sliders (change 4).

## What Changes

- Every exported photo is corrected automatically. No extra tap or setting is needed.
- The correction estimates parameters per photo from a small downscaled copy, in a pure Kotlin JVM module (`:core:analysis`). Estimation is deterministic: the same photo always gives the same result.
- The model follows RefApp's structure ("v1"):
  - Red is rebuilt from green minus some blue. The amount depends on how much red is missing.
  - Green and blue get a levels stretch with a capped gain. The white point is the 99.9th percentile rather than the maximum, so a few bright specks (sun, bubbles) can't block the stretch.
  - Red is never stretched on its own. Doing so caused reddish, noisy shadows in open blue water.
- In open water DiveLens deliberately does better than RefApp (design D10, from the calibration set and the maintainer's review): the black point is limited so bright water isn't turned black, dark water gets no red cast, and the colour shift is smoothed in flat areas so grain isn't amplified. Edges stay sharp.
- A **strength** value blends the original with the full correction, like the RefApp slider. This change ships a fixed default. Change 4 exposes it as a slider.
- The default strength is the median of the estimated RefApp strengths in the final fit: 0.78. The calibration set (photos exported from RefApp at slider 25/50/75/100) showed this is about RefApp slider 65 on reef photos.
- Photos without an underwater cast change only slightly. The red offset scales with the amount of red added (it was a constant in v1), which cut ΔE on a neutral grey test from 6.9 to 2.0.
- The correction is applied on the GPU (OpenGL ES 3.0, offscreen EGL) at full resolution, in tiles. Change 5 reuses the shader for video. If the GPU path fails, the same maths runs on the CPU, so the export is still corrected.
- Not included:
  - Sharpening, clarity and S-curve tone. The RefApp data shows none of these; a global matrix explains its output to within 0.6–2.5 ΔE.
  - A choice of looks. Two alternative looks (E, F) are documented and kept in code for a possible later change.
  - Preview and before/after comparison (change 3), and user-adjustable sliders (change 4).
  - Video (change 5).
- An acceptance check measures the mean colour difference (ΔE) to the RefApp references in the local, gitignored `trainingset/`. It runs only where that folder exists, so the public build and CI never need personal media.

## Capabilities

### New Capabilities
- `auto-correction`: how a photo's correction is estimated and applied. Covers the colour model, strength, neutral-photo behaviour, determinism, full-resolution application, GPU/CPU equivalence and the quality bar against the reference set.

### Modified Capabilities
- `import-export`: the requirement "Processing applies to exported pixels only" (pixels unchanged) is removed and replaced by "Exports are corrected".

## Impact

- New Gradle module `:core:analysis` (Kotlin JVM, no Android dependencies). It adds the Kotlin JVM plugin to the version catalog. No new third-party libraries.
- New package `com.markopacak.divelens.correction` in `:app`: EGL/GLSL renderer, CPU fallback, and a `CorrectionPhotoProcessor` implementing the existing `PhotoProcessor` seam.
- `MainActivity` wires `CorrectionPhotoProcessor` in place of `IdentityPhotoProcessor`.
- `MediaStorePhotoWriter` decodes into a mutable bitmap so correction can run in place, which avoids a second full-size bitmap (about 200 MB for a 50 MP photo).
- Export takes longer per photo. Target: no more than about 1 s extra for a 12 MP photo on the reference device (Xiaomi 14T).
- `openspec/config.yaml` project context: replace the RefApp2-derived pipeline notes with the RefApp findings. Update the README status.
