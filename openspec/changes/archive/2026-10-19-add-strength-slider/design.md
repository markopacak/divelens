# Design

## Context

- **Strength in the pipeline.** `CorrectionParams.strength` (0..1) scales the colour shift: `out = clamp(x + s·shift)`. The shift maps do not depend on it.
  - The GPU shader takes it as the uniform `uStrength`.
  - `GlCorrectionRenderer.Session.apply` and `ViewerPhoto.setStrength` already accept a strength (add-preview-compare D6).
  - The default is `V1Model.DEFAULT_STRENGTH` = 0.78.
- **Per-photo state** lives in `PhotoPreviewStore`, keyed by source URI.
  - Every photo keeps its params and corrected thumbnail.
  - Full corrections, whose maps take 25 MB each, are kept for only 3 photos.
  - The export takes its correction from the store through `CorrectionSource`, and falls back to estimating from the decoded bitmap.
- **Thumbnails** are corrected once on the CPU at the default strength, from the reduced decode, and the maps are then dropped. Re-rendering a thumbnail at another strength today would need the maps again, about 0.5–1.5 s per photo. "Apply to all" on 50 photos would then take close to a minute.
- **Viewer.** `ViewerScreen` takes one `strength` for all pages, as a test hook. `GpuViewerRenderer.Photo.setStrength` re-renders the base and all cached tiles of that photo from the kept originals.
- **Memory.** The Java heap peaked at 245 MB of 256 MB in the add-preview-compare device check. New per-photo data must not live on the Java heap.

## Goals / Non-Goals

**Goals:**
- One strength per photo, used by the viewer, the thumbnail and the export.
- Moving the slider never decodes again or re-estimates; dragging stays smooth.
- Thumbnails re-render in milliseconds without the shift maps, so "Apply to all" is instant.

**Non-Goals:**
- Other sliders. The panel and the per-photo settings are shaped so they can be added later, but none are added now.
- Persisting settings, and undo history beyond "Reset".

## Decisions

### D1. Slider value = strength × 100

- The slider shows the strength itself as a percentage, 0–100%, in steps of 1%. The stored value is rounded to 0.01, so "40%" exports at exactly 0.4.
- The default appears as 78%, with a small mark on the track and "Auto" under it.
- *Alternative:* map the default to 100% and allow up to about 128%. Rejected: "100%" would then not mean full correction, and the export and the reference tools speak in s.

### D2. Strength in `PhotoPreviewStore`

- The store gets the following:
  - `strengths: StateFlow<Map<String, Float>>`, where photos without an entry are at the default;
  - `setStrength(source, s)`;
  - `setAllStrengths(s)`.
- Removing a photo or clearing the set drops its entry, so a photo added again starts at the default.
- `correction(source)` returns the photo's `Correction` with `params.withStrength(strength)`. This is a new small object sharing the maps. The export and the viewer therefore get the photo's strength through the path they already use.
- `CorrectionPhotoProcessor`'s fallback (estimating from the bitmap) also needs the strength. It gets `strengthFor: (source) -> Float`.
- At strength 0 the processor returns the decoded pixels unchanged, without asking for a correction or running the GPU. The export still decodes and re-encodes, because it must write a JPEG (also for HEIC input), keep only the allow-listed metadata and drop GPS.
  - *Alternative:* copy the original file byte for byte. Rejected: it would keep GPS, XMP and maker notes, and a HEIC original would stay HEIC.

### D3. Thumbnails re-render from a stored shift

- When preparing a photo, the worker keeps two bitmaps of the thumbnail besides the shown one, both native, off the Java heap:
  - the uncorrected thumbnail;
  - its full-strength shift per pixel, from `CpuCorrection.shift`, encoded per channel as `round((shift + 1) · 127.5)` in an ARGB bitmap.
- Rendering at strength s is then `clamp(x + s · decode(shift))` on the CPU: 0.1 MP, a few milliseconds.
- The encoding error is at most 1/255 · s per channel, below the 1/255 that thumbnails already allow against the export.
- Memory: about 1.3 MB of native memory per photo instead of 0.45 MB, so about 66 MB for 50 photos. It is checked in the device check.
- A strength change re-renders the photo's thumbnail on the store's worker as a priority job. "Apply to all" queues all thumbnails; 50 take about 0.25 s.
- *Alternative:* re-estimate the maps for each changed thumbnail. Rejected: about a minute for "Apply to all".

### D4. Panel in the viewer

- A tune button at the bottom right opens a panel over the bottom of the viewer. The panel contains:
  - a "Strength" label and the value, such as 64%;
  - the slider, with the default mark;
  - "Reset" and "Apply to all".

  After "Apply to all", a short message names the count: "Strength 60% applied to 12 photos".
- The panel is a sibling of the pager, not part of the page. Its touches therefore reach neither the hold-to-compare filter nor the zoom and pager gestures. Holding on the photo above the panel still compares.
- The panel stays open or closed across swipes and shows the current page's strength.

### D5. Rendering while dragging

- While the slider moves, only the base layer is re-rendered: about 1.3 MP for 20 MP photos, one GPU pass. Updates are conflated, so the newest value wins and work never queues up.
- Tiles at the old strength are hidden during the drag, so the base never shows through a mismatched tile. When zoomed in, the photo is briefly less sharp while dragging.
- On release, the visible tiles are re-rendered at the final strength from their kept originals.
- `ViewerPhoto.setStrength(strength)` re-renders the base only. Each tile remembers the strength it was made at, and `tiles()` corrects a kept tile again from its original when that differs. The viewer requests no tiles while the slider is dragged, so on release only the visible tiles are re-rendered, not every cached one.
- *Alternative:* re-render visible tiles on every drag frame, about 20 tiles of 512² at 100%. Rejected for now: likely over 100 ms per frame on the device. It can be revisited after measuring.

### D6. Testing

- JVM:
  - store strengths: default, set, apply to all, drop on remove, and the strength carried by `correction()`;
  - shift encoding round-trip within 1/255.
- Instrumented:
  - thumbnail re-render equals a CPU correction of the thumbnail at that strength within 2/255 (encoding plus rounding);
  - export at 40% equals the CPU reference at 0.4 within 1/255;
  - the viewer at 100% at 40% equals that export within 1/255;
  - UI: slider default and label, per-photo value across swipes, reset, apply to all, hold while the panel is open.
- Device: drag responsiveness (time from value change to the base being shown, logged), release-to-detail time, and memory with 50 photos after "Apply to all".

## Risks / Trade-offs

- [Dragging while zoomed shows a softer image] → It is brief and restored on release (D5). Re-rendering tiles live is the fallback if the maintainer finds it disturbing.
- [GC churn from rotating the base each frame] → One 5 MB bitmap per frame at most, with conflated updates. If it stutters, keep a reusable rotated buffer.
- [Native memory grows by about 45 MB at 50 photos] → Checked in the device check. If it is too high, the shown thumbnail can be dropped and rendered on demand.
- ["Apply to all" overwrites individual adjustments] → The message names the count. "Reset" is per photo. An undo is left for later.
