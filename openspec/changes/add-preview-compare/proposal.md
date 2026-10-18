# Proposal

## Why

The correction is now applied at export, but the user only sees it afterwards, in the gallery. To decide whether to export a photo, and later to fine-tune it with sliders (change 4), the user has to see the corrected photo in the app, compare it with the original, and check details such as grain at full resolution.

## What Changes

- **Corrected thumbnails.** The working-set grid shows each photo corrected. Until a photo's correction is ready, its thumbnail shows the original with a small "correcting" indicator.
- **Full-screen viewer.** Tapping a thumbnail opens the photo full screen, corrected:
  - swipe between the photos of the working set, in selection order;
  - pinch or double-tap to zoom, up to 100% real pixels, rendered from the real correction (the same GPU path as the export);
  - press and hold anywhere to see the original, release to go back.

  Back returns to the grid.
- **One correction per photo, shared by preview and export.** Each photo's correction is estimated once, cached for the session, and used for the thumbnail, the viewer and the export. What the user sees is what gets exported. The estimate comes from a reduced-size decode of the photo, which is faster than the full-resolution analysis the export runs today.
- **Ready for sliders.** The renderer re-renders the visible photo when its correction settings change. Change 4 then only adds the sliders and stores the settings per photo.
- **Working-set limit of 50 photos.** Today there is no limit: every pick adds to the set. A selection that would go over 50 adds the first photos up to the limit and says how many were left out. This keeps preview memory and export time bounded. It can be raised later.
- **Project context.** `openspec/config.yaml` calls DiveLens a working or temporary name in two places. The name is final, so both are fixed.
- Not included:
  - sliders and per-photo settings (change 4);
  - video preview (change 5);
  - split-screen comparison: hold-to-compare only, by the maintainer's choice;
  - editing, cropping or rotating in the viewer.

## Capabilities

### New Capabilities
- `preview-compare`: showing the automatic correction before export. Covers corrected thumbnails, the full-screen viewer (swipe, zoom to real pixels, hold to see the original), preview matching the export, and responsiveness and memory limits.

### Modified Capabilities
- `import-export`:
  - "Back navigation" gains a scenario: Back in the viewer returns to the grid without clearing the working set.
  - "Manage the working set" gains the limit of 50 photos.

## Impact

- **New UI.** A viewer screen (Compose) with a pager, zoom gestures and tiled rendering, plus corrected thumbnails in `ImportExportScreen`.
- **New per-photo correction cache** in the app layer (`correction/`). `CorrectionPhotoProcessor` uses the cached correction instead of estimating again at export.
- **GPU renderer.** `GlCorrectionRenderer` gains a long-lived session per viewed photo, keeping the EGL context and shift-map textures alive while the photo is on screen. The export path stays a one-shot call.
- **Decoding.** `BitmapRegionDecoder` and `inSampleSize` decodes for tiles and thumbnails; EXIF orientation applied in the viewer.
- **Possible new dependency** for zoom gestures (`me.saket.telephoto:zoomable`, Apache-2.0, compatible with GPL-3.0), decided in design.
- **No change** to the correction model, its coefficients or the reference check.
