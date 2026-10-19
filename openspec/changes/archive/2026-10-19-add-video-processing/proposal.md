# Proposal

## Why

DiveLens is meant for underwater photos *and* videos, but today the picker offers photos only (import-export, "Photos only in this version of import"). Divers film as much as they photograph, and video suffers worse than photos: the colour changes as the diver moves up and down, so a single correction for the whole clip is wrong at some depth. This change makes videos first-class items in the working set, from import to a corrected export. It reuses the correction model, the GPU shader and the strength slider.

## What Changes

- **Import videos.**
  - The picker offers photos and videos. Videos join the same working set and batch as photos, and each video counts as one item towards the limit. The limit drops from 50 to 20 items, which also leaves memory headroom for video work (the Java heap peaked at 237–245 MB of 256 MB with 50 photos).
  - MP4 and MOV clips in H.264 or HEVC are accepted if the device can decode and re-encode them at their size and frame rate. Other videos are left out with a reason.
- **Correction that follows the dive.**
  - The model's parameters are estimated on frames sampled through the clip, smoothed over time and interpolated per frame, so the colour follows depth without flicker.
  - The grain-aware smoothing of the shift (the guided filter) is computed for every frame on the GPU, because its maps belong to one image.
- **Grid.**
  - A video's thumbnail is one corrected frame at about 1 s (the first frame for clips shorter than 2 s), skipping a frame that is nearly black.
  - It has a duration badge.
  - Every cell, photo or video, gets a small caption with the date and time taken. File names are not shown, because the system picker hides them.
- **Viewer.**
  - A video plays in full with the correction applied live, with play/pause and a scrub bar.
  - The strength slider applies to the video, live, including during playback.
  - Hold to compare shows the original.
  - Playback starts at once: it uses the correction of the first analysed frames, and later parts follow as their frames are analysed.
  - Videos do not zoom in this version.
- **Export.**
  - Each video is exported as a new MP4 in the same DiveLens album. It keeps the original's resolution, frame rate, duration and orientation, and is encoded at about the original's bitrate.
  - Audio is copied unchanged. The creation date is kept, and the file name comes from it.
  - No location is written. GoPro telemetry tracks, which can hold GPS, are dropped.
  - HDR (10-bit HLG/PQ) clips are converted to SDR before correction, and exported as SDR.
- **Exports keep running in the background.**
  - The batch runs in a foreground service with a progress notification and a cancel action, so it survives leaving the app or turning off the screen.
  - Before each video, the free storage is checked against the expected file size.
- Not included:
  - zoom in the video viewer;
  - trimming, speed changes and stabilisation;
  - HDR output;
  - keeping location;
  - per-scene strength within one clip (the strength applies to the whole clip);
  - videos larger than the device can encode.

## Capabilities

### New Capabilities
- `video-processing`: correcting a video over time and everything specific to video, including:
  - sampling, smoothing and interpolating the correction, without flicker;
  - the video export format and its metadata, including audio, telemetry and HDR;
  - the video thumbnail and duration badge;
  - playback in the viewer with live correction;
  - the responsiveness targets for video.

### Modified Capabilities
- `import-export`:
  - The picker offers videos, and "Photos only in this version of import" is removed.
  - The rules for unsupported files cover videos.
  - The working-set limit is 20 items (was 50) and counts photos and videos.
  - Exports cover videos: new full-resolution files, location and naming, metadata, no location.
  - The batch runs in the background with a notification, and checks free storage before a video.
  - Exports are corrected, photos and videos.
- `preview-compare`:
  - The grid shows a date-taken caption on every item.
  - The full-screen viewer covers videos. Zoom to real pixels stays photo-only.
- `manual-adjustment`: the strength is kept per item, so photos and videos alike, and the slider is offered for videos.

## Impact

- **Dependencies.** AndroidX Media3 (Apache-2.0, GPLv3-compatible), with three modules:
  - `media3-transformer` and `media3-effect` for export;
  - `media3-exoplayer` for preview.

  The exact version is pinned at apply time, at least two weeks old.
- **Manifest.**
  - Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` (Android 15+), `FOREGROUND_SERVICE_DATA_SYNC` (Android 10–14) and `POST_NOTIFICATIONS`. The notification permission is asked at the first export; declining it does not stop the export.
  - A new export service.
  - Media3 also declares `WAKE_LOCK` and `ACCESS_NETWORK_STATE`. `WAKE_LOCK` stays: the export service holds a partial wake lock so the batch keeps running with the screen off. `ACCESS_NETWORK_STATE` is removed in the app's manifest; the player only uses it to detect the network type for streaming.
  - There is still no media or storage permission, and no `INTERNET` permission.
- **Code.**
  - `MediaItem.Video` gains its geometry and duration.
  - New: an importer for videos, a correction timeline per video (`:core:analysis`), a guided filter on the GPU and a Media3 `GlEffect` built on the existing shader, a video writer, and a video page in the viewer.
  - The export flow moves from the view model into a service-owned controller.
  - `PhotoPreviewStore` gains video entries.
- **Reference tools.** A new video check in `tools/reference/` compares the timeline correction with per-frame correction on the reference videos (quality and flicker). Coefficients are unchanged.
- **Trial (later change).** A video counts as one export, as `openspec/config.yaml` already says. Nothing changes here.
