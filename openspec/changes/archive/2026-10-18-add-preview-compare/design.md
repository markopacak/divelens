# Design

## Context

- **Grid today.** `ImportExportScreen` shows the working set as a `LazyVerticalGrid` of Coil thumbnails of the originals. State lives in `ImportExportViewModel`; `MainActivity` wires everything by hand. There is no navigation library and no second screen.
- **Correction today** (archived `add-auto-correction`, D1–D10). For a photo of W×H, `CorrectionAnalyzer` builds a 200 px analysis copy and a 1024 px working copy in one band pass over the decoded full-resolution bitmap. The result is a `Correction`: a 3×4 matrix plus strength, and two shift maps of 1024×768×4 floats, about 25 MB together.
  - The maps are resolution-independent: a pixel at (x, y) of an image W'×H' samples them at `(x + 0.5)·mapW/W' − 0.5`. So the same `Correction` applies to the full photo, a downscaled copy or a tile, given the right image size and tile origin.
  - Applying it: GPU (`GlCorrectionRenderer`, one EGL context per call) or CPU (`CpuCorrection`). They agree within 1/255.
- **Export.** `MediaStorePhotoWriter` decodes the full photo and calls `PhotoProcessor.process(bitmap)`. The processor estimates and applies the correction: 0.54 s analysis and 0.24 s GPU per 20 MP photo on the Xiaomi 14T.
- **Limits.** Import accepts photos up to 100 MP. The working set has no count limit today; this change sets it to 50 photos (`WorkingSet.LIMIT`). The heap limit is a few hundred MB, so a few shift maps fit in memory, but one per photo for 50 photos (1.25 GB) does not.

## Goals / Non-Goals

**Goals:**
- One definition of "the correction of a photo", used by thumbnails, the viewer and the export, so the preview is exact.
- Responsive UI: all decoding and correction off the main thread; the viewer's current photo gets priority over thumbnail work.
- Bounded memory, independent of the number and size of photos.
- A renderer that re-renders when the strength or later settings change, without re-estimating.

**Non-Goals:**
- Persisting previews or corrections across app restarts. The working set itself is not persisted either.
- Sharing the viewer with video (change 5 uses ExoPlayer with the same shader).
- Animations beyond what the pager and zoom library provide.

## Decisions

### D1. The correction is estimated from a reduced decode, for preview and export alike

A photo's `Correction` is defined as the analyzer's result on a decode with `inSampleSize` = the largest power of two that keeps the width at least 2048 px (2 × the 1024 px working width); photos narrower than 4096 px are decoded at full size. The analyzer then box-downscales that decode to 200 px and 1024 px as today.

- Preview and export both compute it this way, so they get identical parameters and maps. The export no longer analyses its full-resolution bitmap: it gets the `Correction` for its source and only applies it.
- It is faster: a 20 MP JPEG decoded at 1/2 or 1/4 takes about a quarter of the full decode, and the analysis pass covers 4–16× fewer pixels.
- The estimate differs slightly from the full-resolution box downscale the reference fit uses: JPEG DCT scaling is close to, but not exactly, an area average. Task 1.2 measures it on the training photos with Pillow's `draft()` mode, which uses the same libjpeg scaling. The accepted deviation is a mean ΔE of 0.5 between outputs. If it is exceeded, fall back to estimating from a full decode for both preview and export; the definition stays shared either way.
- *Alternative:* keep export on the full-resolution estimate and preview on a reduced one. The preview would not match the export exactly, which breaks the spec.

### D2. Per-photo state: `PhotoPreviewStore`

The app layer gets one store keyed by the photo's source URI:

- `params` (`CorrectionParams`, a few dozen bytes) and the photo's display size and orientation, kept for every photo in the working set;
- the corrected thumbnail (longest side 384 px, ARGB, about 0.5 MB), kept in memory for every photo. The working-set limit of 50 bounds this to 25 MB. Raising the limit well beyond that would need a disk cache for thumbnails.
- the full `Correction` with its maps (about 25 MB), kept in an LRU of at most 3 photos: the viewer's current photo and its neighbours, or the export's current photo. A miss recomputes it from the reduced decode in D1, about 0.4 s.

A single worker coroutine prepares photos in selection order: reduced decode, `Correction`, thumbnail. It drops the maps unless the photo is in the LRU. The viewer's current photo, then its neighbours, jump the queue. Removing a photo or clearing the set cancels its work and frees its entries. Failures are kept per photo ("preview unavailable") and never block export, which estimates again (D1).

`PhotoProcessor.process` gains the source URI (`process(source, input)`), so the export can take the `Correction` from the store. `IdentityPhotoProcessor` and the tests change accordingly.

### D3. Thumbnails corrected on the CPU from the reduced decode

The worker downscales the reduced decode to the thumbnail size, applies the `Correction` with `CpuCorrection` (about 0.1 MP, a few milliseconds), and rotates it for display. The grid shows the original through Coil until the corrected thumbnail exists, with a small "correcting" label as for export status. No GPU context is needed for thumbnails.

### D4. Viewer: pager, zoom gestures from telephoto, own tiled rendering

- **Screen.** A second screen state in the view model (`viewing = index`), no navigation library. A `HorizontalPager` over the working set; Back closes it (import-export delta). Swiping is disabled while zoomed in, except at the edges, as the zoom library handles it.
- **Gestures.** `me.saket.telephoto:zoomable` (Apache-2.0, compatible with GPL-3.0) provides pinch, pan, fling, double-tap and pager interplay as a `Modifier.zoomable` with a transform state. It draws nothing itself. Task 3.1 pins the version and records the licence in `NOTICE`.
  - *Alternatives:* telephoto's `ZoomableImage` with subsampling decodes tiles itself, so the correction can't be applied to them. Hand-written gestures: pager interplay and fling are a lot of code to get right.
- **Rendering.** Two layers in display (EXIF-rotated) coordinates:
  - *Base:* the whole photo decoded at the sample size that fits the screen, corrected once on the GPU. It is shown as soon as it exists and stays below the tiles.
  - *Tiles:* when the zoom needs more detail than the base, the visible region is covered by 512 px tiles decoded with `BitmapRegionDecoder` at the power-of-two sample size for the zoom level (1 at 100%), corrected on the GPU, and drawn over the base. Tiles outside the viewport are evicted from an LRU with a 64 MB budget.
- **Same correction everywhere.** Each tile is corrected with the photo's `Correction`, the sampled image size and the tile's origin in sampled coordinates. The result is therefore the export's pixel values (sample size 1) or their downscale. Tiles share the maps, so no seams appear between them.
- **Hold to compare.** A pointer filter observes events in the `Initial` pass without consuming them. One pointer held for 250 ms within touch slop switches both layers to their uncorrected bitmaps until it lifts. A second finger or movement beyond the slop cancels the hold, so pinch and pan keep working. The base and each tile keep their decoded original next to the corrected copy, which doubles tile memory within the same budget.
  - *Alternative:* re-decode originals on hold. That adds a visible delay at the moment of comparison.

### D5. A GPU session per viewed photo

`GlCorrectionRenderer` gets `openSession(correction)`. The session keeps one EGL context, the program and the two map textures, and offers `apply(bitmap, origin, imageSize, strength)` for the base and for tiles. All calls run on the existing single `divelens-gl` thread. The viewer opens a session for the current photo and closes it when the photo leaves the LRU. Export keeps the one-shot `apply`, implemented on a session. A GPU failure in a session falls back to `CpuCorrection` for that bitmap, as export does.

### D6. Ready for settings (change 4)

The viewer and thumbnails render from `(Correction, settings)`, where settings is today only the default strength. Strength is a shader uniform, so a strength change re-renders the base and visible tiles from their kept originals with no new estimate or decode. Change 4 adds the settings per photo in `PhotoPreviewStore` and the sliders.

### D7. Testing

- JVM: store and queue logic (ordering, priority, cancellation on remove, LRU eviction, failure kept per photo) with fake decoders.
- Instrumented:
  - **Parity:** the viewer's 100% tiles, read back from the renderer, are within 1/255 of the export of the same photo decoded before JPEG encoding. The export path is checked by a test hook.
  - **UI flow:** open, swipe, Back to the grid, hold to compare.
  - **Memory:** 10 synthetic 50 MP photos browsed without running out of memory.
- Device (Xiaomi 14T): time to the corrected view, and a review of grain at 100% against the export.

## Risks / Trade-offs

- [The reduced decode changes the estimate slightly compared with the fitted definition] → Measured in task 1.2 against a 0.5 ΔE budget, with a fallback to full decodes. The reference check keeps testing the model itself.
- [`BitmapRegionDecoder` on HEIC, or on unusual JPEGs, may fail or be slow] → Fall back to the base layer only: zoom enlarges the base and shows a "full detail unavailable" hint. Export is unaffected.
- [Telephoto is a new dependency] → It is gesture-only and replaceable behind one modifier. Pin the version and keep the licence notice.
- [Hold-to-compare could conflict with a slow pan start] → It needs 250 ms without movement. Movement or a second finger cancels it. A UI test covers pan after a short hold.
- [Export of a photo that left the LRU recomputes its maps (+0.4 s)] → The export queue prepares the next photo while the current one encodes. The LRU size is 3.
- [Users with more than 50 photos per dive must export in batches] → Accepted for now: the limit is one constant and the message says how many were left out.
- [Memory: maps (3 × 25 MB), tiles (64 MB), base bitmaps, thumbnails (50 × 0.5 MB)] → About 200 MB peak by design, checked by the memory test; budgets are constants in one place.

## Migration Plan

No data migration. To roll back, open no viewer and use original thumbnails; the export can go back to estimating from its own bitmap through the old processor path.
