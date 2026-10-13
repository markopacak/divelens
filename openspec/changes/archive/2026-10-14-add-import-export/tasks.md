# Tasks

## 1. GPS spike (decides the reading path)

- [x] 1.1 Build a throwaway screen that picks a photo via `PickMultipleVisualMedia` and logs its EXIF GPS tags; run it on a real Android 10 device (or API 29 emulator with a GPS-tagged image pushed to it) and on a current Android version; verify by recording in this task whether GPS survived on each.
  - Result, API 37 emulator (2026-10-12), synthetic JPEG with GPS 43°30'12.34"N 16°26'56.78"E, orientation 6, owned by another app:
    - Photo picker: GPS zeroed (`0/1,0/1,0/1`); date and orientation intact. Same with `ACCESS_MEDIA_LOCATION` granted. `setRequireOriginal` on a picker URI throws `UnsupportedOperationException`.
    - Android 10: not tested; no longer needed after the decision below.
- [x] 1.2 If GPS was stripped, repeat with `ACCESS_MEDIA_LOCATION` granted and `ACTION_OPEN_DOCUMENT` / `MediaStore.setRequireOriginal`; verify by recording which path returns GPS. If no path does, stop and raise it with the user before continuing (spec requirement "Preserve photo metadata" would change). Remove the throwaway screen afterwards.
  - Result, API 37 emulator (2026-10-12):
    - `ACTION_OPEN_DOCUMENT` without `ACCESS_MEDIA_LOCATION`: GPS zeroed.
    - `ACTION_OPEN_DOCUMENT` with `ACCESS_MEDIA_LOCATION` granted: GPS intact. `setRequireOriginal` is not needed and throws `SecurityException` on document URIs.
    - Requesting `ACCESS_MEDIA_LOCATION` shows the system dialog "Allow DiveLens to access photos and videos on this device?" (Allow all / Allow limited access / Don't allow), not a location-specific prompt. After that, the photo picker switches to the "only the photos you select" mode with a Done button.
  - Decision (user, 2026-10-12): no GPS in exports for v1. Keep the photo picker, request no permission, exclude GPS tags from the metadata copy. Spec and design updated. Throwaway screen removed.

## 2. Dependencies and project setup

- [x] 2.1 Bump core-ktx, lifecycle and activity-compose to current stable (at least two weeks old) in `gradle/libs.versions.toml`; verify `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passes.
- [x] 2.2 Add androidx.exifinterface, Coil 3 compose, lifecycle-viewmodel-compose and lifecycle-runtime-compose to the version catalog and `:app`; verify the build passes and every new dependency is Apache-2.0.
  - Note: Coil 3.6.3 requires Kotlin 2.4 metadata, so the catalog's Kotlin was bumped from 2.2.10 to 2.4.10. Coil is used without its network module (no OkHttp).
- [x] 2.3 Add the app-name constant used for album and file suffix, and move all user-visible strings for this feature into `res/values/strings.xml`; verify lint reports no hardcoded-text warnings in new code.

## 3. Import

- [x] 3.1 Model the working set: a media item type distinguishing photo and video (video unused), with source URI, display name, MIME type, dimensions and per-item status; verify with JVM unit tests for add, remove and clear.
- [x] 3.2 Implement import validation (MIME allow-list JPEG/HEIC/HEIF, bounds-only decode, 100 MP guard) returning accepted items plus per-file rejection reasons; verify with instrumented tests using generated JPEG, HEIC, an unsupported file and a corrupt file.
- [x] 3.3 Wire the picker (images only, multi-select, max from `getPickImagesMaxLimit()` where available) with no permission request; verify on the API 29 and latest emulators that single, multiple and cancelled selections update the working set as specified.
  - API 37 (2026-10-12): cancel leaves the working set empty with no error; single selection adds the photo. Finding: photo picker URIs expose `_display_name` as `<media id>.jpg` (e.g. `68.jpg`), not the original file name, so the spec's "name derived from the original" cannot be met with the picker. Paused for a decision.
  - Decision (user, 2026-10-12): name exports `DiveLens_<yyyyMMdd>_<HHmmss>.jpg` from the date taken, falling back to the export time. Spec and design updated.
  - API 29 emulator (Pixel_4, 2026-10-13): the Google Play services photo picker backport opens (`com.google.android.gms/.photopicker.ui.PhotoPickerActivity`), a grid labelled "This app can only access the photos you select", no permission prompt. Multiple selection added all chosen photos and two batches exported correctly. With a video in the gallery, the picker showed photos only. Back from the picker returns to the unchanged app with no error. The backport can take a few seconds to open the first time; pressing Back before it appears leaves the app, and the picker then shows when the app is reopened. That is platform behaviour, not an app bug.

## 4. Export

- [x] 4.1 Define the `PhotoProcessor` seam with an identity implementation and the `ExportGate` seam (`check` + `onExported`) with an always-allow implementation; verify with JVM unit tests that the export use case calls the gate before writing, skips refused items, and calls `onExported` only after success.
- [x] 4.2 Implement the EXIF allow-list copy (dimensions rewritten from the output, no GPS, no thumbnail, no maker notes or serials) and the `DateTimeOriginal` + offset → epoch conversion; verify with JVM unit tests for date conversion and instrumented tests that every allow-listed tag, including orientation, matches the source and that a source with fake GPS produces an export with no GPS tags.
- [x] 4.3 Implement the per-item pipeline: full-resolution decode, processor, JPEG q95 to a temp file, EXIF copy, MediaStore insert into `Pictures/<album>` with `IS_PENDING`, `DATE_TAKEN` and a date-taken file name, cleanup in `finally`; verify with instrumented tests on API 29 and latest: dimensions match, original bytes unchanged, gallery orientation matches, `DATE_TAKEN` set when the EXIF date has an offset, HEIC input exports as JPEG.
  - Finding (API 37, 2026-10-12): without an EXIF offset MediaStore discards the app's `DATE_TAKEN` on publish (the original has none either). Decision: accept, never add an offset. Spec and design updated.
  - API 29 emulator (Pixel_4, 2026-10-13): all 12 instrumented tests pass, including dimensions, original unchanged, metadata and orientation, `DATE_TAKEN`, HEIC → JPEG, and cleanup after failure and cancel.
- [x] 4.4 Verify duplicate exports of the same photo produce two distinct files on API 29 and latest via an instrumented test; if API 29 refuses the duplicate name, append a timestamp and re-run the test.
  - API 29 and 37 (2026-10-13): `exportingTwiceCreatesTwoFiles` passes on both; MediaStore gives the second export a distinct name, so no counter is needed.
- [x] 4.5 Map failures to user-facing reasons (insufficient storage, unreadable source, out of memory, refused by gate) and guarantee no pending row or temp file remains; verify with instrumented tests that inject a write failure and a cancellation mid-item and then query MediaStore for leftover pending rows.

## 5. Batch and UI

- [x] 5.1 Implement the ViewModel batch runner (one item at a time, progress, cooperative cancel, per-item failure, end summary) with immutable UI state; verify with JVM unit tests using fakes: progress 0→N, cancel during item 3 of 5 keeps 1–2 and skips 4–5, one failure doesn't stop the batch.
- [x] 5.2 Replace the template greeting with the import/export screen: empty state, thumbnail grid with remove and clear, "Export N photos", progress with Cancel, summary with failures; verify with a Compose UI test covering empty → selected → exporting → summary using a fake ViewModel state, and manually on a device that thumbnails display upright for rotated photos.
  - API 37 emulator (2026-10-12): a 320×240 JPEG with orientation 6 shows rotated 90° relative to the same image without the tag, i.e. upright as the tag specifies. Exported both: names `DiveLens_20260714_103205.jpg` / `DiveLens_<export time>.jpg`, 320×240 stored, orientation 90 kept on the rotated one.
- [x] 5.3 Remove the template `Greeting`, `ExampleUnitTest` and `ExampleInstrumentedTest` once real tests exist; verify `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest` passes.
  - Note: Espresso 3.5.1 fails on API 37 (`InputManager.getInstance` missing); bumped espresso-core to 3.7.0 and androidx.test.ext:junit to 1.3.0. `ExampleInstrumentedTest` already removed.

- [x] 5.4 Handle Back: with photos selected, clear and return to the start screen; during an export, ask before cancelling; on the start screen, close the app; verify with a Compose UI test for each case and manually on the API 29 emulator.
  - API 29 emulator (2026-10-13): 3 new Compose UI tests pass (15/15 instrumented). Manual: picked 2 photos, Back returned to the start screen, Back again closed the app. Back during an export is covered by the UI test only, because exports of the sample photos finish in well under a second.

## 6. Integration and release readiness

- [x] 6.1 Manual end-to-end check on a real device with a batch of mixed JPEG/HEIC photos including a GPS-tagged portrait photo: exports appear in the DiveLens album, sorted by date taken, upright, with no location in the gallery's info panel, originals untouched and still showing their location; record the result in this task.
  - Xiaomi 14T, Android 16 (2026-10-14), debug build: 2 GoPro HERO9 JPEGs (one with orientation 3, upside-down mount) and 4 Xiaomi camera JPEGs with GPS (3 portrait, stored as 3072×4096 pixels with orientation 1; 1 landscape). All 6 exports are in Pictures/DiveLens, named from the date taken, with the original's dimensions, orientation, date/offset/subsec, make/model, f-number, ISO and focal length; exposure time equal up to rational rounding (0.05000108 → 0.0500010804). No GPS in any export while the originals keep theirs; GoPro serial number not copied. MediaStore `DATE_TAKEN` equals the original's, so exports sort by date taken. HEIC not covered on the device (the camera saves JPEG); HEIC → JPEG is covered by instrumented tests on API 29 and 37. Gallery display (user): every export looks the same as its original. The GoPro photo shot pointing down shows upside down in both, because the camera's tilt sensor recorded orientation 3; exports keep the camera's tag rather than correcting it. HEIC on a device is left to change 2 testing, when HEIC originals are at hand.
- [x] 6.2 Inspect the merged manifest (`app/build/intermediates/merged_manifest/`) and verify no INTERNET, storage, media or `ACCESS_MEDIA_LOCATION` permissions are present.
  - Release merged manifest (2026-10-12): only `com.markopacak.divelens.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX core, signature-level, internal). No INTERNET, storage, media or location permissions.
- [x] 6.3 Build a release AAB signed with a keystore read from a gitignored `keystore.properties` (document the expected keys in the README, keep debug builds working without it); verify `./gradlew :app:bundleRelease` succeeds and `git status` shows no keystore or properties file staged.
  - 2026-10-12: without `keystore.properties` the bundle builds unsigned; with a throwaway scratch keystore it is signed (`jarsigner -verify`: verified). The properties file shows as ignored in git and was deleted after the check. README rewritten with build and signing instructions.

## Workflow follow-up

- Upload the release AAB to the Play Console internal testing track (needed before billing work in change 6).
- Archive the change after review.
