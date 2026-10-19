# Design

## Context

- **The correction of a photo** (auto-correction, add-preview-compare D1) is a `Correction`: the model's 3×4 matrix with strength and the dark-red cap, plus two guided-filter maps `a`, `b`, where `shift = a·grey + b` per pixel.
  - The maps are computed from *that* image on a 1024 px working copy, with a radius of 2 px, so they follow its edges closely.
  - `CorrectionShader.FRAGMENT` applies them on the GPU; `CpuCorrection` is its reference.
- **The reference videos** in `trainingset/` (7 clips with RefApp results) are mostly GoPro, 2.7K (2704×1520) at 59.94 fps, H.264 High at 60 Mbit/s, full range, with AAC audio and a GoPro data track (telemetry, GPMF). One clip is a 1080p60 phone video.
- **Earlier video previews** in this project corrected every frame on its own, with its own parameters and maps, on the desktop. That is the quality reference for this change.
- **The working set and batch** are photo-only.
  - `MediaItem.Video` exists but has no fields beyond source, name and MIME type.
  - `ExportItemUseCase` fails videos.
  - The batch runs in `ImportExportViewModel`'s scope, so it stops when the activity's view model is cleared.
  - `PhotoPreviewStore` (one worker, priorities) and the strengths live in the activity's wiring.
- **The project constraints** in `openspec/config.yaml` settle several choices:
  - Media3 Transformer with a custom `GlEffect` for export, and ExoPlayer with the same effect for preview;
  - sampling every N seconds with smoothing;
  - audio passed through;
  - no location;
  - foreground service types;
  - HDR tone-mapped to SDR.

## Goals / Non-Goals

**Goals:**
- One per-frame correction, defined once and used by the preview player, the export and (for its frame) the thumbnail.
- The same fragment shader as photos for the final step.
- Analysis cost proportional to the clip length, done once per clip and shared by preview and export.
- An export that survives leaving the app, with nothing left behind on cancel or failure.

**Non-Goals:**
- Frame-exact equality between preview and export pixels. Both are lossy video; the spec asks for the same correction at each moment, not equal pixels.
- Speed below real time on every device. The responsiveness target is for the reference device.
- Persisting analysis across app restarts.

## Decisions

### D1. Correction timeline: sample, smooth, interpolate (in `:core:analysis`)

- **Sampling.** One sample every second of the clip (`SAMPLE_INTERVAL_US = 1 s`), plus the first and last frame. Each sample is decoded scaled to about 400 px wide at the nearest sync frame (`MediaMetadataRetriever.getScaledFrameAtTime`, `OPTION_CLOSEST_SYNC`). This takes roughly 20–40 ms on the reference device; the frame's actual timestamp is recorded.
  - The sample is box-downscaled to the 200 px analysis copy, and `V1Model.estimate` gives its parameters. The maps are not used here (see D2).
- **Smoothing.** `CorrectionTimeline` smooths the matrix elements over time with a Gaussian, σ = 2 s, weighted by the actual sample times. Averaging matrices of valid corrections gives a valid correction, and the result changes continuously.
  - *Alternative:* smooth the image statistics before the model. That is more principled, but the model is nonlinear (gain cap, black point), so a stats average can make a correction no sample had. The video check (D9) decides between them if the matrix average fails the limits.
- **Per frame.** For a frame at time t, the parameters are linearly interpolated between the smoothed samples around t, and held constant before the first and after the last. The strength is the item's strength (manual-adjustment).
- **Partial timelines.** Playback can start before all samples exist (spec "Playback starts without waiting"). The timeline answers for t from the samples it has, as if the clip ended there.
  - Analysis runs from the start of the clip, but jumps ahead to the playback position after a seek.
  - When new samples change the answer for a time already shown, the effect eases from the old to the new parameters over 0.5 s of playback, so no jump is visible.
- **Constants.** σ and the sample interval are named constants used by both the app and `tools/reference/video_check.py`, like the model coefficients. They are not hand-tuned in code.
- **Independent of the look.** The timeline smooths whatever 3×4 matrices an estimator produces, and D2 runs on any parameters. A later look (for example one for deep blue) only adds its estimator; the video check can run per look.

### D2. The guided filter runs on the GPU, per frame

- A sampled frame's maps cannot be reused for other frames: they follow that frame's edges, so moving edges would get colour halos.
- Computing them on the CPU for each frame is far too slow (about 100 ms per frame). So each frame builds its own maps on the GPU, then the existing apply shader runs unchanged:
  1. Downscale the frame to the working width, `min(1024, frame width)`, with the same integer-bounded box average as `AnalysisImageBuilder` (up to 3×3 texels per working pixel at 2.7K), so the maps equal the CPU's.
  2. Per working pixel, compute `g`, `g²`, the full-correction shift `d_c` (the GLSL port of `CpuCorrection.full`) and `d_c·g`. That is 8 values in two RGBA float textures.
  3. A separable box filter of radius `GuidedShift.radius(w)` on both textures (2 passes).
  4. Compute `a_c = cov(g, d_c)/(var g + EPS)` and `b_c = mean d_c − a_c·mean g`, into two textures.
  5. A box filter of `a` and `b` (2 passes).
  6. `CorrectionShader.FRAGMENT` with these textures as `uMapA` and `uMapB`, the frame as `uSource`, `uImageSize` set to the frame size and `uOrigin` (0, 0).
- **Cost.** About ten passes at 1024×576 per frame. That is small next to decoding and encoding 2.7K60.
- **Precision.**
  - RGBA32F render targets when `EXT_color_buffer_float` is present, which it is on current Adreno, Mali and Immortalis GPUs.
  - Otherwise RGBA16F. The `var g` term is cancellation-prone, but `EPS` (4·10⁻⁴) bounds the error.
  - A test compares the GPU maps with `GuidedShift.map` on the same working image. A second test compares a whole corrected frame with `CpuCorrection` on its CPU-built maps.
- *Alternative:* a uniform shift per frame (no guided filter). Rejected: grain in open water would be amplified, which the auto-correction spec forbids.

### D3. One Media3 effect for preview and export

- `CorrectionEffect : GlEffect` creates a `GlShaderProgram` that runs D2 for each frame, given the frame's presentation time. It is built from:
  - the clip's `CorrectionTimeline`;
  - a strength provider, read on every frame, so the slider and hold to compare work live without re-preparing the player.
- The same effect instance type is passed to:
  - `ExoPlayer.setVideoEffects` for preview;
  - the Transformer's `EditedMediaItem` effects for export.
- **Colour space.** Media3 processes SDR frames in their encoded (gamma) values by default, which is what the model was fitted on. A test confirms it: a synthetic clip is exported and a decoded frame is compared with the CPU correction of the same original frame, within codec tolerance. If a version linearises, the effect sets the working colour space explicitly.
- **HDR.**
  - Export uses `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`.
  - Preview requests SDR output from the player's effect pipeline as well, so both see the same tone-mapped frames.
  - Checked with a synthetic 10-bit HLG fixture and on the device.

### D4. Import and the video item

- The picker becomes `ImageAndVideo`.
- `MediaImporter` (the renamed `PhotoImporter`) probes videos with `MediaExtractor` for the following, and reads the creation date with `MediaMetadataRetriever`:
  - the track MIME type, size and frame rate;
  - the rotation, duration and bitrate;
  - the colour transfer, which marks HDR.
- **Accepted:**
  - `video/mp4` and `video/quicktime`;
  - H.264 or HEVC;
  - only when `MediaCodecList` has a decoder for the format, and an encoder in the same codec or H.264 that supports the size and frame rate. Otherwise the reason is "too large for this device" or "format not supported".
- `MediaItem.Video` gains the following fields:
  - width, height and rotation;
  - duration and frame rate;
  - bitrate and codec;
  - an HDR flag;
  - the creation time.
- `MediaItem.Photo` gains its date taken (EXIF `DateTimeOriginal` with any offset), for the caption.

### D5. Thumbnail, badge and caption

- **The frame.** `AndroidPhotoPreparer` prepares a video by taking the frame at 1 s (`OPTION_CLOSEST`, scaled to the thumbnail size), or the first frame for clips shorter than 2 s.
  - If its mean grey is below 0.06 ("nearly black"), it tries 2, 3 and 4 s, and keeps the first one that is not.
  - It then corrects the frame exactly as a photo: its own estimate, its own CPU guided filter, and the shift encoding of add-strength-slider D3, so the strength re-renders in milliseconds.
  - The thumbnail's parameters are that frame's raw estimate, not the smoothed timeline value. For one frame the difference is small, and it keeps thumbnails independent of the full analysis.
- **The badge and caption.** The duration badge shows m:ss, or h:mm:ss for an hour or more. The caption uses `DateTimeFormatter.ofLocalizedDateTime(MEDIUM, SHORT)`:
  - photos: the EXIF wall-clock time, as recorded;
  - videos: the MP4 creation time (UTC), converted to the device's time zone.

### D6. Analysis scheduling in the store

- `PhotoPreviewStore` becomes the store for photos and videos. It keeps a `CorrectionTimeline` per video, which is small: 13 floats per sample.
- Timeline analysis runs on the same single worker, in chunks of 10 samples per job, so photo preparation and thumbnail re-renders interleave and the CPU is not oversubscribed. A video's first job takes a single sample at the playback position, so playback has its correction after one sample (about 30 ms) instead of ten. Priority order:
  1. correction requests;
  2. thumbnail re-renders;
  3. timeline chunks of the video being exported;
  4. timeline chunks of the video being viewed, near its playback position;
  5. preparing items;
  6. timeline chunks of other videos, in selection order.
- `timeline(source)` returns the current, possibly partial, timeline as a `StateFlow`. `completeTimeline(source)` waits until the whole clip is analysed; the export uses it.

### D7. Video export with Transformer

- `MediaStoreVideoWriter` exports one video through these steps:
  1. **Storage check.** Expected size = bitrate × duration × 1.1 + 200 MB margin, checked with `StatFs` before anything is written (spec "Storage check before a video").
  2. **Pending gallery row.** A pending `MediaStore.Video` row in `Pictures/DiveLens`, named `DiveLens_<creation time in local time>.mp4`, with `DATE_TAKEN` from the creation time.
  3. **Transformer run** on the completed timeline:
     - the `CorrectionEffect` at the item's strength;
     - output codec = input codec if an encoder supports the size and rate, else H.264;
     - `VideoEncoderSettings` with the original's bitrate;
     - no audio effects, so the audio is copied unchanged.
     - At strength 0 it still re-encodes. Copying the stream would keep telemetry and location (same reasoning as add-strength-slider D2).
  4. **Muxing straight into the pending row** through its file descriptor, so the file is not copied once more: the muxer factory builds Media3's `Mp4Muxer` on a `FileOutputStream` of the row and ignores the path Transformer passes. No temporary file is needed.
  5. **Publish** by clearing `IS_PENDING`. On failure or cancel the pending row is deleted.
- **Exact size.** Media3's encoder size fallback is turned off. Hardware encoders such as MediaTek's declare 16-pixel alignment and so report 1920×1080 as unsupported; with the fallback on, Media3 changes the size (640×360 became 640×368 on the reference device). Configured with the exact size they encode it, padding internally and cropping in the stream, as the camera does. The importer's codec check rounds the size up to the codec's alignment for the same reason.
- **Metadata.**
  - The muxer is wrapped (`CleanMp4Muxer`). It keeps only audio and video tracks and, of the metadata, only the orientation (`Mp4OrientationData`, also derived from the video format's rotation as Media3's own muxer does). It writes `Mp4TimestampData` with the original's creation time and drops everything else, including `Mp4LocationData` and XMP: an allow-list, so a new metadata type cannot leak location.
  - MediaStore hides location metadata from apps without `ACCESS_MEDIA_LOCATION`, even in their own files, so the tests check the muxer's output as raw bytes in app storage, and the device check inspects pulled exports with ffprobe.
  - Rotation and frame rate pass through Transformer unchanged.
  - Tests check both the absent location and the absent telemetry track, and the creation time.

### D8. The batch moves into a foreground service

- An app-scoped `AppGraph`, created in a new `DiveLensApp : Application`, owns the following. The view model observes them and no longer runs the batch itself:
  - the preview store and strengths;
  - the importer;
  - an `ExportController`, which holds the batch job and its `StateFlow`.
- Starting a batch starts `ExportService`:
  - It runs in the foreground with type `mediaProcessing` on Android 15 and later, and `dataSync` on Android 10–14. The manifest declares both. HyperOS on Android 16 (the reference device) refuses `mediaProcessing` with "type none", so the service falls back to `dataSync` when the system rejects the type.
  - Its notification shows "Exporting 2 of 5" with a progress bar and a Cancel action. When the batch ends, it posts a summary ("5 exported") and stops itself.
  - Photos use the same path, so there is one export flow.
- The service holds a partial wake lock (`WAKE_LOCK`, also declared by Media3) while a batch runs, so the CPU keeps working with the screen off; it is released when the batch ends.
- `POST_NOTIFICATIONS` is requested once, at the first export, on Android 13 and later. A refusal only hides the notification.
- **Process death mid-export.** At start-up, the app deletes its own MediaStore rows that are still pending in `Pictures/DiveLens`, so no partial file survives (spec "Batch export with progress and cancel").
- *Alternative:* WorkManager with a long-running worker. Rejected: progress and cancel tie naturally to the controller's state, the batch is user-initiated and immediate, and WorkManager adds a dependency and persistence that the working set does not have.

### D9. Video reference check (`tools/reference/video_check.py`)

- On the reference videos, the check works as follows:
  1. It extracts frames every 0.5 s, plus consecutive frame pairs at 10 points per clip.
  2. It samples every 1 s and builds the smoothed, interpolated timeline (same constants as the app).
  3. It corrects with the desktop reference (`fit.py`, `smooth.py`).
- **It reports:**
  - ΔE against RefApp at the default strength, for the timeline and for per-frame correction. The timeline must be closer on average, the mean over the videos at most 9.0, and every video closer than its original;
  - ΔE against RefApp at the matched strength, reported only: the 6.5 limit applies to photos and video frames together in `ReferenceSetTest`;
  - flicker: the change of mean colour between consecutive frames, corrected minus original, at most 0.5 ΔE;
  - for information, the mean ΔE between the timeline and per-frame correction.
- It is skipped without the reference set, like `ReferenceSetTest`.
- `tools/reference/video_compare.py` renders a side-by-side video (original, per frame, timeline, RefApp) to judge the same by eye.
- **Results (2026-10-19, 7 reference videos, default strength 0.78):**

  | | σ = 1 s | σ = 2 s (chosen) | σ = 4 s | per frame |
  |---|---|---|---|---|
  | mean ΔE to RefApp | 8.70 | 8.83 | 9.11 | 9.03 |
  | videos closer than the original | 7/7 | 7/7 | 7/7 | 7/7 |
  | worst flicker | 0.43 | 0.41 | 0.37 | 1.88 |
  | mean ΔE to per-frame | 2.5 | 3.1 | 4.0 | – |
  | mean ΔE to RefApp, matched strength | 7.37 | 7.46 | 7.59 | – |

  - Per-frame estimates jitter between neighbouring frames (green gain 2.15 at 0.25 s, 2.25 at 0.75 s on GH010814), so the first limit, "within 0.5 ΔE of per-frame correction", measured that jitter and no smoothing could meet it. It was replaced by "closer to RefApp than per-frame".
  - σ = 2 s was kept: σ = 1 s is 0.13 ΔE closer to RefApp, which is not visible, while 2 s leaves more margin under the flicker limit and suits clips with sparse sync frames. The maintainer judged the comparison videos: per-frame looks good, the timeline better (GH010814); on the sunlit GH010960 hardly any difference is visible.
  - Observation for later: on sunlit shallow water the correction slightly strengthens the light ripple already in the original (contrast stretch), where RefApp calms it.

### D10. Viewer page for a video

- Only the current page has an `ExoPlayer`. It is created when the page settles and released when it leaves, so at most one decoder runs. Neighbouring video pages show their corrected thumbnail frame.
- **Surface.** A `SurfaceView` in an `AndroidView`, fitted to the screen in display orientation.
- **Controls.** At the bottom, directly above the strength panel and clear of the status bar: play/pause symbols, the elapsed time, a white scrub `Slider` and the total time. They stay visible (the first version hid them after 3 s at the top of the screen, under the status bar; changed after the device check). The clip loops.
- **Paused frames.** A paused player renders no new frames, so a strength change (the slider or hold to compare) seeks to the current position, which decodes the frame again through the effect. The spinner shows only until the first frame is rendered.
- **Gestures.** Hold to compare sets the effect's strength provider to 0 while held, reusing `holdToCompare`. Seeking calls the store to analyse near the new position (D6). Pinch and double-tap do nothing on video pages.
- **Panel.** The adjustment panel is the same as for photos (add-strength-slider D4), now always visible: the Adjust and Done buttons are gone (maintainer's request after the device check).

### D11. Testing

- **JVM:**
  - timeline smoothing, interpolation, holds at both ends, partial timelines and the ease-in;
  - black-frame choice;
  - `.mp4` naming from the creation time, and caption formatting;
  - the working set with videos;
  - the importer's acceptance rules, with a fake codec probe;
  - store scheduling of timeline chunks;
  - the storage estimate.
- **Fixtures.** Synthetic `androidTest` assets made by `tools/reference/video_fixture.py` with ffmpeg. They contain no personal media:
  - 2 s at 640×360, 30 fps, H.264 + AAC, with a colour that drifts over time, a creation time, a location tag and rotation 90;
  - a 10-bit HLG HEVC clip.
- **Instrumented:**
  - GPU guided filter against `GuidedShift.map`;
  - the corrected frame against the CPU;
  - export of the fixture: same size, rate and duration, audio stream equal to the original's, creation time kept, no location, no extra tracks, colour of a decoded frame against the CPU reference;
  - HDR export is SDR;
  - cancel leaves no row;
  - storage check;
  - service start and stop;
  - UI: video page plays, pauses and scrubs, hold to compare, slider during playback.
- **Device:** described in `tasks.md`.

## Risks / Trade-offs

- [GoPro writes local time as UTC in `creation_time`, so names and captions could be off by the time-zone offset] → The device check compares a clip's caption with the time on its photo EXIF. If confirmed, read the GoPro's own time from its `udta` atom, and record the rule in the import-export spec.
- [Media3 APIs for effects in ExoPlayer and FD output in Transformer differ between versions] → Pin one version at apply time (at least two weeks old). D7 has a temporary-file fallback.
- [HDR preview with effects may not tone-map like the export] → The synthetic HLG fixture is checked in preview and export on the device. If they differ, the preview shows the tone-mapped frames from the same Transformer path, or HDR clips preview without live strength changes, decided then.
- [Timeline from sync frames only: long GOPs, such as some phone clips with 2–4 s between sync frames, give fewer, uneven samples] → Sample times are the real frame times, and σ = 2 s still averages several samples. The video check covers the phone clip.
- [GPU passes per frame cost battery during long exports] → Small next to the encoder. Measured in the device check.
- [The service refactor touches the working photo export] → The existing export tests run against the controller unchanged, and the device check exports photos too.
- [Large clips need storage for the export] → D7's check fails early, with nothing written, instead of filling the disk.

## Migration Plan

Nothing is persisted, so there is no data migration. Users of earlier builds get the new picker and export path on update. Rollback means installing the earlier build.

## Open Questions

- None. σ = 2 s and the flicker limit of 0.5 ΔE were settled by the video check (D9).
- **Video times after travelling (decided 2026-10-20: keep for now).** A video's caption and export name use its UTC creation time in the phone's current time zone, as the gallery apps do, while photos show the camera's wall-clock time. After a trip in another zone the two differ (a clip filmed at 17:32 local in UTC+8 is shown and named 11:32 at home in UTC+2); the gallery's order is unaffected. Showing dive-site time would need a guess of the recording zone (for example from photos in the same set), so it is left for a possible later change.
