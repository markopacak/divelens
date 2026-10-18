# Tasks

## 0. Working-set limit

- [x] 0.1 Add `WorkingSet.LIMIT = 50`: `add` keeps the picked photos in order up to the limit and reports how many were left out; the view model shows the message ("3 photos were left out. A batch holds at most 50 photos.") and disables "Select photos" with an explanation while the set is full; pass the remaining room as the picker's `maxItems` where the platform supports it. Add the strings. Verify:
  - JVM tests: 45 + 8 adds 5 and reports 3 left out; duplicates do not count; a full set adds nothing;
  - a Compose UI test: with 50 photos the select action is disabled and the explanation is shown;
  - the existing import tests pass.

## 1. One correction per photo (design D1, D2)

- [x] 1.1 Add the reduced-decode estimate: a function that, for a source URI, picks the `inSampleSize` of D1, decodes, feeds `CorrectionAnalyzer` in bands and returns the `Correction` plus the decode's size. Verify with an instrumented test: for a synthetic 6000×4000 JPEG the sample size is 2, and estimating twice gives equal parameters and maps.
- [x] 1.2 Measure the deviation of D1 on the training photos with a script in `tools/reference/` (`draft_check.py`, Pillow `draft()` for the libjpeg-scaled decode; no media committed). For each photo, record the mean ΔE between the output corrected with the reduced-decode estimate and with the full-resolution estimate. Verify: the mean ΔE is at most 0.5 on every photo, recorded here. If not, switch D1 to full decodes and record that instead.
  - Result (2026-10-18, 8 training photos, 5184×3888, decoded at sample size 2 = 2592×1944): mean ΔE 0.077–0.159, worst 0.159 (GOPR0832). Within budget; D1 stands.
  - For margin, forcing sample size 4 (1296 px): 0.146–0.593, worst GOPR0698, over budget. So the 2048 px minimum is needed.
- [x] 1.3 Implement `PhotoPreviewStore`: params, display size and orientation, and thumbnail for every photo; the `Correction` LRU of 3; failure per photo; a single worker in selection order with priority requests; cancellation and freeing on remove and clear. Verify with JVM tests using a fake estimator: selection order, a priority request jumps the queue, remove cancels and frees, LRU evicts the least recently used, a failure is kept and does not stop the queue.
- [x] 1.4 Pass the source to `PhotoProcessor.process(source, input)` and make `CorrectionPhotoProcessor` take the `Correction` from the store, falling back to the D1 estimate when the store has none. Update `IdentityPhotoProcessor`, `Seams.kt` docs and the existing tests. Verify:
  - the export instrumented tests pass;
  - a new instrumented test shows the export of a photo equals, before JPEG encoding, its full-resolution `CpuCorrection` with the store's `Correction` (within 1/255).

## 2. Corrected thumbnails (design D3)

- [x] 2.1 Generate the corrected thumbnail in the worker: downscale to a 384 px longest side, `CpuCorrection`, EXIF rotation. Verify with an instrumented test: a synthetic blue-cast JPEG with orientation 6 gives a portrait thumbnail with higher mean red than the original.
- [x] 2.2 Show it in the grid: the original through Coil with a "correcting" label until the thumbnail exists, then the corrected thumbnail; "preview unavailable" on failure. Add the strings. Verify with a Compose UI test using a fake store: label, then corrected image, then the failure label; the existing `ImportExportScreenTest` still passes.

## 3. Viewer (design D4, D5)

- [x] 3.1 Add `me.saket.telephoto:zoomable` to `libs.versions.toml` at a pinned version at least two weeks old. Add its Apache-2.0 notice to `NOTICE` and check that the merged manifest gains no permissions. Verify that `./gradlew :app:assembleDebug` passes and the merged manifest is unchanged apart from the library's components, if any.
- [x] 3.2 Add `GlCorrectionRenderer.openSession(correction)` with `apply(bitmap, origin, imageSize, strength)` and `close()`, on the `divelens-gl` thread. Re-implement the one-shot `apply` on it. Verify with instrumented tests:
  - session tiles at sample size 1 equal the one-shot result of the same rectangle;
  - a sampled tile at size 4 equals `CpuCorrection` on the same sampled tile within 1/255;
  - the existing GPU tests pass.
- [x] 3.3 Viewer screen: `viewing` state in the view model; `HorizontalPager` over the working set opened at the tapped photo; Back returns to the grid (import-export delta). Show the base layer, decoded to fit the screen and corrected through a session, with EXIF orientation. Verify with Compose UI tests using a fake store: tapping the third thumbnail opens the third photo, swiping shows the fourth, Back shows the grid with the same photos, and the working set is unchanged.
- [x] 3.4 Tiles: `BitmapRegionDecoder` tiles of 512 px at the zoom's sample size, corrected through the session, drawn over the base, with a 64 MB LRU and eviction outside the viewport. Fall back to the base only when region decoding fails, with a "full detail unavailable" hint. Verify with instrumented tests:
  - at 100% zoom the rendered tiles of a synthetic photo equal the export's pixels before JPEG encoding, within 1/255 (spec "Preview matches the export");
  - adjacent tiles are continuous across their edge (no seam);
  - a HEIC asset either zooms with tiles or shows the hint without crashing.
  - Found while testing (2026-10-18): `BitmapRegionDecoder` differs from a full decode by up to 2/255 within 1 px of a region's border (JPEG chroma upsampling; 30 of 1.65 M pixels). Tiles are therefore decoded with a 32 px margin and cropped; the test now also checks that tile originals equal the full decode exactly, and tiles at 100% equal the export with difference 0.
- [x] 3.5 Hold to compare: the `Initial`-pass pointer filter of D4 (250 ms, touch slop, cancelled by movement or a second pointer) switches the base and tiles to their originals while held. Verify with Compose UI tests: holding shows the original and releasing shows the corrected photo at the same zoom; a pan that starts after 100 ms still pans; a pinch never triggers the original.

## 4. Settings-ready rendering (design D6)

- [x] 4.1 Render thumbnails, base and tiles from `(Correction, settings)` with settings = the default strength, and re-render the visible base and tiles from their kept originals when settings change, without re-estimating or decoding. Verify with an instrumented test: changing the strength of the viewed photo through a test hook updates the displayed base, and the estimator and decoder are not called again.

## 5. Project context

- [x] 5.1 In `openspec/config.yaml`, replace "DiveLens (working name)" and the "temporary name" line: the name is final and the user-visible name stays in one string resource. Verify that `openspec validate add-preview-compare --strict` passes.

## 6. Device check (integration)

- [x] 6.1 On the Xiaomi 14T, import the four training photos used in the auto-correction device check plus 10 synthetic 50 MP photos. Verify and record here:
  - time from tap to the corrected viewer for a 20 MP photo (≤ 1 s, logged);
  - thumbnails appear one after another without blocking the screen;
  - swiping through all photos and zooming to 100% causes no out-of-memory, with peak heap logged;
  - hold to compare works at fit and at 100% zoom;
  - the maintainer judges the viewer at 100% against the exported photo of the same image and finds them identical.

  Remove the test photos and exports from the phone afterwards.
  - Run 1 and 2 (2026-10-18, 4 training photos plus 10 synthetic 50 MP photos):
    - Tap → corrected viewer, 20 MP: 0.30–0.42 s when already prepared, 0.71 s when estimated on tap. 50 MP estimated on tap: 1.5–1.9 s.
    - Thumbnails became corrected one after another (maintainer: "thumbnails become corrected, yes!").
    - No crash or out-of-memory while viewing all 14 and exporting all 14. Peak Java heap 245 MB of a 256 MB limit while viewing; peak total PSS 790 MB while exporting 50 MP photos. Accepted for now by the maintainer; reducing it is a follow-up (map copies held by pages, temporary arrays of the estimate).
    - Found: zoom snapped back after every gesture (telephoto's zoom factors are relative to the fitted photo; the maximum was 1×). Fixed to 1 / fit scale, with a UI test on a 20 MP-sized photo (pinch stays, double-tap reaches 100%). Viewer GPU tiles reduced to 1024 px (less GPU memory per open photo).
  - After the zoom fix, the maintainer checked on their own photos: zoom stays and is sharp at 100% without seams; hold to compare works at fit and at 100%. The viewer against the export at 100% was not compared by eye; it is covered by `GpuViewerRendererTest` (tiles equal the export, difference 0).
  - Zoom gestures still feel hard to the maintainer; accepted for now, to address later.
  - Test photos (SYNTH50) and the 18 exports of these runs removed from the phone; the 4 training photos removed after the last check.

## Workflow follow-up

- Archive the change after review; the `import-export` delta adds "Back in the viewer" to "Back navigation".
