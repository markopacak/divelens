# Proposal

## Why

Every photo is now corrected at the same default strength (0.78). Some photos need less, such as shallow reefs with sunlight, and some need more, such as deep, dark water. The user can see the result in the viewer (change 3) but cannot change it. A strength slider is the smallest adjustment that covers this. It matches the RefApp slider the correction was fitted against, and the renderer already re-renders on a strength change.

## What Changes

- **Strength slider in the viewer.** A collapsible panel at the bottom of the full-screen viewer has one slider, "Strength", from 0% (original colours) to 100% (full correction).
  - It starts at the automatic default, 78%, with a mark at that position.
  - The photo updates while dragging, and full detail follows shortly after release.
  - Hold to compare keeps working while the panel is open.
- **Per photo.** Each photo keeps its own strength for as long as it is in the working set.
  - "Reset" returns the current photo to the default.
  - "Apply to all" copies the current photo's strength to every photo in the working set.
- **Thumbnails and export follow the setting.**
  - A changed photo's thumbnail is re-rendered.
  - The export applies each photo's strength. Photos the user did not change are exported at the default, as today.
- **README.** The status line still says preview and comparison "come later". It is updated to the current state.
- Not included:
  - other sliders (warmth, brightness, contrast);
  - saving settings across app restarts (the working set is not saved either);
  - an Instagram-like look picker (looks E and F stay in `V1Model` for later);
  - strength above 100%.

## Capabilities

### New Capabilities
- `manual-adjustment`: user adjustments of the automatic correction per photo. It covers the strength slider, its scope (per photo, reset, apply to all), how the preview and thumbnails follow a change, and responsiveness. Later sliders belong here.

### Modified Capabilities
- `auto-correction`: "Correction strength" no longer says every export uses the default strength. The export uses the photo's strength, which is the default unless the user changed it.

## Impact

- **UI.** `ViewerScreen` gets the adjustment panel. The view model holds the strength per photo and passes it to the viewer, the grid and the export.
- **Per-photo state** (`PhotoPreviewStore`). It stores the strength per photo. The thumbnail can be re-rendered at another strength without the full shift maps, which are kept for only 3 photos.
- **Export.** `CorrectionPhotoProcessor` applies the photo's strength to the shared correction. The GPU shader already takes the strength as a uniform.
- **No change** to the correction model, its coefficients, the reference tools or the default strength.
