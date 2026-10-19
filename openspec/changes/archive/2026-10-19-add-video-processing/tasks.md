# Tasks

## 1. Reference check and fixtures (design D1, D9, D11)

- [x] 1.1 Write `tools/reference/video_check.py` and document it in `tools/reference/README.md`. It samples every 1 s, builds the smoothed (σ = 2 s) and interpolated timeline, corrects frames every 0.5 s and consecutive frame pairs with `fit.py`/`smooth.py`, and reports per video: ΔE against per-frame correction, ΔE against RefApp, and flicker. It skips without the reference set. Verify by running it on the 7 reference videos: all limits of the spec ("No flicker", "Video quality against the reference set") hold, or the matrix-vs-statistics smoothing choice and σ are settled there and recorded in design.md.
- [x] 1.2 Write `tools/reference/video_fixture.py`, which makes the synthetic `androidTest` assets with ffmpeg:
  - 2 s at 640×360, 30 fps, H.264 + AAC, with a drifting colour, a creation time, a location tag and rotation 90;
  - a 10-bit HLG HEVC clip.

  Verify with ffprobe output in the README: streams, metadata and sizes under 1 MB each. Confirm the assets contain no personal media.

## 2. Correction timeline (design D1)

- [x] 2.1 Add `CorrectionTimeline` to `:core:analysis`: samples with times, Gaussian smoothing of the matrix, interpolation and holds, partial timelines, and the 0.5 s ease when samples arrive. The constants are shared with `video_check.py`. Verify with JVM tests:
  - a constant clip gives constant parameters;
  - a step change becomes gradual;
  - interpolation between samples, and holds at both ends;
  - uneven sample times;
  - a partial timeline answers like a clip ending at its last sample;
  - the ease reaches the new value after 0.5 s.

  A parity test against values printed by `video_check.py` for a synthetic sequence agrees within 1e-5.

## 3. Video items and import (design D4, D5)

- [x] 3.1 Extend `MediaItem.Video` with its fields (geometry, rotation, duration, frame rate, bitrate, codec, HDR flag, creation time) and `MediaItem.Photo` with its date taken. The working set and its limit count videos, and `WorkingSet.LIMIT` drops from 50 to 20. Verify with `WorkingSetTest` updated to 20, plus cases for mixed sets and the "1 item left out" count.
- [x] 3.2 Rename `PhotoImporter` to `MediaImporter`. It probes videos (`MediaExtractor`, `MediaMetadataRetriever`) and checks decoder and encoder support through an injectable codec probe; it reads the photo date taken. Switch the picker to images and videos, and add the rejection strings ("too large for this device", "format not supported"). Verify with:
  - JVM tests of the acceptance rules with a fake probe (HEVC without a matching encoder falls back to H.264; 8K on a 4K encoder is rejected);
  - an instrumented test that imports the fixture with the right fields and rejects a corrupt MP4.
- [x] 3.3 Remove the "photos only" picker behaviour and update the `import-export` UI texts that say "photos" where items are meant (limit message, export button), which now read "20 items". Verify with `ImportExportScreenTest` updates (full set and left-out messages at 20).

## 4. Per-frame GPU correction (design D2, D3)

- [x] 4.1 Add the Media3 dependencies `transformer`, `effect` and `exoplayer`, at one pinned version at least two weeks old, to `libs.versions.toml`. Record them in `NOTICE` (Apache-2.0). Verify that the build succeeds and that the merged manifest has no new permissions beyond the ones planned in 7.x.
- [x] 4.2 Implement the GPU guided filter passes (32F or 16F targets) and the GLSL port of `CpuCorrection.full`. Verify with instrumented tests:
  - GPU maps against `GuidedShift.map` on the same working image within 1e-3 (32F) or 4e-3 (16F);
  - a corrected 640×360 frame against `CpuCorrection` with CPU-built maps, worst 2/255.
- [x] 4.3 Implement `CorrectionEffect`: a Media3 `GlEffect`/`GlShaderProgram` that reads the timeline at each frame's time and a live strength provider, and ends in `CorrectionShader.FRAGMENT`. Verify with an instrumented test that runs the fixture through a Transformer with the effect: a decoded frame at 1 s is within a mean of 1.5/255 of the CPU correction of the original frame at the timeline's parameters. This also confirms the SDR working colour space.

## 5. Store, thumbnails and grid (design D5, D6)

- [x] 5.1 Prepare videos in `AndroidPhotoPreparer`: the frame at 1 s, the first frame for short clips, the black-frame skip, then the photo correction with the shift encoding. Verify with:
  - JVM tests of the frame-choice rule;
  - an instrumented test on the fixture (a corrected, rotated thumbnail at about 1 s).
- [x] 5.2 Add timeline analysis to `PhotoPreviewStore`: chunks of 10 samples, the priority order of D6, `timeline(source)` and `completeTimeline(source)`, and seek-driven priority. Verify with JVM tests using a fake sampler:
  - chunk order and interleaving with photo work;
  - an export request jumps the queue;
  - a seek moves analysis;
  - removal frees the timeline;
  - `completeTimeline` waits for the last sample.
- [x] 5.3 Grid: the duration badge on videos and the date caption on every item, with the strings and formatting. Verify with:
  - JVM tests of the duration and caption formatting (including no caption without a date);
  - `ImportExportScreenTest` checks for the badge and caption.

## 6. Video export (design D7)

- [x] 6.1 Implement `MediaStoreVideoWriter`:
  - storage check;
  - pending `MediaStore.Video` row and `.mp4` naming from the creation time;
  - Transformer with the effect at the item's strength, matching codec and bitrate, and audio passed through;
  - HDR tone-mapping;
  - the muxer wrapper (no location, no extra tracks, creation time);
  - output through the row's file descriptor, with the temp-file fallback;
  - cleanup on failure and cancel.

  `ExportItemUseCase` routes videos to it. Verify with instrumented tests on the fixtures:
  - same size, frame rate, duration and display rotation;
  - the audio samples equal the original's;
  - creation time kept and `DATE_TAKEN` set;
  - no location and only two tracks;
  - corrected colour (as in 4.3);
  - the HLG clip exports as SDR;
  - cancel mid-export leaves no row;
  - a forced low-storage estimate fails with `InsufficientStorage` and writes nothing.

## 7. Export in the background (design D8)

- [x] 7.1 Add `DiveLensApp` and `AppGraph` (store, strengths, importer, `ExportController`) and move the batch from the view model to the controller. The view model observes it. Verify that the existing `BatchExporter`, `ExportItemUseCase` and screen tests pass against the controller, plus a JVM test that the batch survives clearing the view model.
- [x] 7.2 Add `ExportService`:
  - foreground types (`mediaProcessing` on 15+, `dataSync` on 10–14);
  - progress notification with Cancel, and a summary notification;
  - stops at the end;
  - the `POST_NOTIFICATIONS` request at the first export;
  - start-up removal of stale pending rows.

  Verify with:
  - instrumented tests (service starts with a batch and stops after it; Cancel from the notification's intent cancels the batch; a planted pending row is removed at start-up);
  - a check that the merged manifest lists exactly the planned permissions and still no media or storage permission.

## 8. Video in the viewer (design D10)

- [x] 8.1 Video page:
  - one `ExoPlayer` for the current page only, with the `CorrectionEffect` and the live strength;
  - play/pause, scrub and time, with auto-hide;
  - hold to compare at strength 0;
  - no zoom; neighbouring pages show their thumbnail frame;
  - stops when swiped away.

  Verify with Compose UI tests using the fixture:
  - it plays and pauses, and the scrub moves the position;
  - holding shows "original" while playing continues;
  - a slider change keeps it playing;
  - pinch keeps it fitted;
  - swiping away releases the player.
- [x] 8.2 Update the README status line (videos supported: import, preview playback, export in the background) and verify by reading it.

## 9. Device check (integration)

- [x] 9.1 On the Xiaomi 14T, with the 7 reference videos plus a few photos in one working set:
  - **Timing.** Log the time from opening to corrected playback (≤ 1 s) and dropped frames during 2.7K60 playback. Log the export time per clip (≤ 2× duration).
  - **Background export.** Leave the app during the export, then cancel one batch from the notification.
  - **Exports.** Check the creation date and caption against the clip's real time (the GoPro time-zone risk). Check with ffprobe on the desktop that the exports have no location and no telemetry track.
  - **Memory.** Record peak memory.
  - **Maintainer verdict.** The maintainer judges playback, the slider during playback, hold to compare, and the exported clips against RefApp in `trainingset/review/`.

  Remove the test media and exports afterwards.

  **Results (2026-10-19/20, Xiaomi 14T, HyperOS on Android 16):**

  | Measure | Target | Measured |
  |---|---|---|
  | First corrected frame, 2.7K60 | ≤ 1 s | 224, 339, 522 ms |
  | Dropped frames during 2.7K60 playback | no visible stutter | 1 in 8.2 s |
  | Export time | ≤ 2× the clip; 15 s clip ≤ 30 s | 15.4 s clip in 9.6 s, 16.0 s in 9.5 s, 5.5 s in 3.5 s (about 0.6×) |
  | Photo strength change while dragging | ≤ 100 ms | 19–65 ms |
  | Peak memory | | Java heap 93 MB allocated (limit 256 MB), native 248 MB, graphics 428 MB |

  - **Exports (ffprobe on the 7 pulled clips):** only a video and an audio track (the GoPro `tmcd`, `gpmd` and `fdsc` tracks are gone), no location box (`©xyz`, `loci`) and no GPS telemetry (`GPS5`, which the GoPro originals carry), creation time equal to the original, same resolution, frame rate, duration and about the same bitrate, the GoPro full range kept.
  - **Creation time (GoPro risk):** GoPro `creation_time` is real UTC, not local time written as UTC (GH010918 at 03:20Z is 7 minutes before GOPR0922 at 05:27+02:00). The risk did not materialise.
  - **Background and cancel:** the export continued after Home with its notification; a batch was cancelled from the notification; the notification test passed once notifications were allowed.
  - **Maintainer verdict:** the side-by-side comparisons (original, DiveLens, RefApp) in `trainingset/review/` "look good for now". Feedback from the check led to: bottom controls with play/pause symbols, the strength panel always visible, thinner sliders, readable panel text, loop, hold while paused, the "Adding…" indicator, "Queued", and spinners next to "Exporting".

## Workflow follow-up

- Archive the change after review:
  - `video-processing` becomes a new main spec;
  - the `import-export`, `preview-compare` and `manual-adjustment` deltas apply;
  - the import-export requirement "Photos only in this version of import" is removed.
