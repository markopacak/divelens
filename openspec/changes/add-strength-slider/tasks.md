# Tasks

## 1. Strength per photo (design D1, D2)

- [x] 1.1 Add strengths to `PhotoPreviewStore`: `strengths` flow, `setStrength`, `setAllStrengths`, values rounded to 0.01 and clamped to 0..1, entries dropped on remove and clear; `correction(source)` returns the correction at the photo's strength. Verify with JVM tests: default when unset, set and read back, apply to all, removed and re-added photo is at the default, `correction()` carries the strength without a new estimate.
- [x] 1.2 Export at the photo's strength: `CorrectionPhotoProcessor` takes `strengthFor` for its fallback estimate and wiring passes the store. Verify with an instrumented test: a photo set to 40% exports equal to the CPU reference at 0.4 within 1/255; an unchanged photo still exports at the default (existing tests pass); at strength 0 the processor returns the input unchanged without calling the correction source or the GPU (instrumented test with counting fakes).

## 2. Thumbnails follow the strength (design D3)

- [x] 2.1 Shift encoding: encode and decode the full-strength shift per channel in an ARGB bitmap and render a thumbnail at strength s. Verify with a JVM test of the encode/decode arithmetic (round-trip error at most 1/255) and an instrumented test: the re-rendered thumbnail at 0.3 equals `CpuCorrection` of the uncorrected thumbnail at 0.3 within 2/255.
- [x] 2.2 Keep the uncorrected and shift bitmaps per photo in the store and re-render a thumbnail as a priority job when its strength changes. Verify with JVM tests using a fake renderer: one re-render per changed photo, "apply to all" re-renders every prepared photo, no `estimate` call.

## 3. Viewer panel (design D4, D5)

- [x] 3.1 `ViewerPhoto.setStrength(strength)` re-renders the base only; `tiles()` re-renders kept tiles made at another strength; the viewer requests no tiles while dragging. Verify with instrumented tests: after `setStrength(0)` the base equals its original, and the tiles returned next equal their originals (reused, not decoded again); tiles at 100% at 40% equal the export at 40%.
- [x] 3.2 Adjustment panel: tune button, slider 0–100% with the default mark and "Auto", value label, "Reset", "Apply to all" with the count message; the page's strength comes from the store and changes go to it; panel open state kept across swipes. Add the strings. Verify with Compose UI tests using a fake renderer and store:
  - an unadjusted photo shows 78%;
  - moving to 40% on photo 2, swiping to 3 and back shows 78% then 40%;
  - "Reset" returns to 78%;
  - "Apply to all" sets every photo and shows the message;
  - holding on the photo with the panel open shows the original;
  - the zoom level is unchanged by opening the panel and moving the slider.
- [x] 3.3 Remove the single `strength` test hook of `ViewerScreen` in favour of the per-photo strength; update the add-preview-compare tests that used it. Verify that all instrumented tests pass.

## 4. Docs

- [x] 4.1 Update the README status line: preview, comparison and the strength slider exist; other sliders and video come later. Verify by reading the README.

## 5. Device check (integration)

- [x] 5.1 On the Xiaomi 14T with the four training photos plus enough synthetic photos to fill the working set to 50:
  - log the time from a slider change to the base being shown while dragging (≤ 100 ms) and from release to full detail at 100% zoom (≤ 0.5 s);
  - choose "Apply to all" and check that all thumbnails update at once;
  - record peak Java and native memory;
  - the maintainer judges dragging, Reset and Apply to all, and an export at a changed strength against the viewer.

  Remove the test photos and exports afterwards.

  Result (2026-10-19, 4 training photos plus 46 renamed copies, 50 in the set):
  - Drag: a strength change was shown in median 37 ms, p90 51 ms, over 320 changes; one change of the 320 took 119 ms. The base re-render itself took median 20 ms.
  - Release at full zoom: 16 tiles at the new strength shown 60–116 ms after release.
  - Peak memory: Java heap 237 MB of 256 MB (245 MB in add-preview-compare), native heap 299 MB, graphics 271 MB.
  - Maintainer: dragging, 0% and the Auto mark, zoom, per-photo values, Reset, Apply to all with its message, and hold to compare with the panel open all work as described. The export at a changed strength was not compared on the device; that is covered by the instrumented tests (export at 40% and at 0%).
  - Clean-up: the DiveLensCheck album and the empty DiveLensTest folder were removed; no exports were made.

## Workflow follow-up

- Archive the change after review. The `auto-correction` delta changes "Correction strength". `manual-adjustment` becomes a new main spec.
