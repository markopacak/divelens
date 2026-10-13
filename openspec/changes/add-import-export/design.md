# Design

## Context

The app is still the Android Studio template: one `MainActivity` with a greeting, no ViewModel, image or EXIF libraries, and template dependency versions from 2023 (core-ktx 1.10.1, lifecycle 2.6.1, activity-compose 1.8.0). AGP 9 with built-in Kotlin, minSdk 29, targetSdk 37. No specs exist yet. See proposal.md for why this change comes first, and `specs/import-export/spec.md` for the required behavior.

Constraints that shape this design: everything runs on the device; the repo is public, so test fixtures must be synthetic (no personal photos or real GPS); change 2 will replace the pixel processing step with a GPU pipeline, and change 6 will put the trial counter behind the export check.

## Goals / Non-Goals

**Goals:**
- A minimal import → export flow that is correct on full-resolution photos from phones and action cameras (up to ~50 MP).
- Two seams that later changes fill in without touching import/export code: a pixel processing step and an export check.
- Metadata and orientation handling that later changes can rely on.

**Non-Goals:**
- Surviving process death mid-batch, or restoring the working set after the app is killed (the picker's read grants are temporary).
- Navigation, design system or polished UI; one functional screen is enough until change 3.
- Video, share-sheet/"Open with" import, XMP sidecar data, maker notes.

## Decisions

### 1. Picker: AndroidX `PickMultipleVisualMedia`, images only
One multi-select launcher covers single and batch selection. On Android 10–12 without the system photo picker, AndroidX falls back to the Google Play services backport or `ACTION_OPEN_DOCUMENT`, so no storage permission is needed anywhere. The max item count comes from `MediaStore.getPickImagesMaxLimit()` where available.
*Alternative:* `ACTION_OPEN_DOCUMENT` everywhere. Rejected as the default because the photo picker is the better, permission-free UX; kept as the fallback in decision 2.

### 2. No GPS in exports; never copy GPS tags
A spike on the API 37 emulator (recorded in tasks.md, group 1) showed that photo picker URIs return GPS tags zeroed (`0/1,0/1,0/1`), even with `ACCESS_MEDIA_LOCATION` granted. Only `ACTION_OPEN_DOCUMENT` plus `ACCESS_MEDIA_LOCATION` returns real coordinates, and requesting that permission shows the general "access photos and videos" dialog. Decision (user, 2026-10-12): exports carry no location in v1.
Consequence: GPS tags are excluded from the copy, not passed through. Copying the zeroed tags would stamp exports with a false 0°N 0°E location.
*Alternatives:* always use the document picker with the permission; or an opt-in "keep location" setting. Deferred; the opt-in setting is the candidate if users ask for location.

### 3. Keep pixels in stored orientation and copy the orientation tag
Decode with `BitmapFactory` (HEIF supported on API 28+), which never applies EXIF rotation, and write the original orientation tag unchanged to the export. Gallery apps then show the export exactly like the original.
*Alternative:* rotate pixels upright and reset the tag to "normal". Rejected: it doubles peak memory for a 50 MP image (~200 MB → ~400 MB) and costs a lossy resample for no visible gain. Later consumers that need upright pixels (preview, the GPU pipeline) can apply the rotation themselves at render time, which is free on the GPU. Coil already honors the tag for thumbnails.

### 4. Export pipeline per item
1. Ask the export check; if refused, record the item as refused and skip it.
2. Decode the full-resolution bitmap (no `inSampleSize`).
3. Run the processing step (identity in this change).
4. Encode as JPEG, quality 95, into a temp file in `cacheDir`.
5. Copy metadata into the temp file (decision 5).
6. Insert a MediaStore row with `IS_PENDING = 1`, `RELATIVE_PATH = Pictures/<album>`, display name, MIME type and `DATE_TAKEN`; stream the temp file in; set `IS_PENDING = 0`.
7. In `finally`: delete the temp file; on any failure or cancellation, delete the pending row so no partial file appears.

Writing EXIF into a temp `File` and then copying is simpler and more reliable than `ExifInterface` rewriting through a MediaStore file descriptor, at the cost of one extra local write per photo. `IS_PENDING` hides the item from other apps until it is complete. No storage permission is needed for the app's own MediaStore inserts on API 29+.

### 5. Metadata: explicit allow-list copied with androidx.exifinterface
A single list of EXIF tags to copy: date/time (original, digitized, offsets, subseconds), make, model, lens make and model, exposure time, f-number, ISO, focal length (and 35 mm equivalent), flash, white balance, metering, exposure program/bias, and orientation. Dimension tags are written from the exported bitmap. Not copied: GPS tags (decision 2), embedded thumbnails (would show stale pixels after correction), maker notes (vendor blobs with internal offsets that break when re-written), camera serial numbers.
*Alternative:* copy every `TAG_*` via reflection. Rejected: brittle under R8 and silently copies tags that become wrong after editing.

`DATE_TAKEN` for MediaStore is derived from `DateTimeOriginal` plus `OffsetTimeOriginal` when present, otherwise interpreted in the device time zone; if there is no EXIF date it is left unset. Found during implementation (API 37): publishing the row (`IS_PENDING = 0`) re-scans the file, and MediaStore then sets `DATE_TAKEN` only from EXIF that carries an offset; app-supplied values are discarded, before and after publishing. Originals without an offset have no `DATE_TAKEN` either. Decision (user, 2026-10-12): accept this and never invent an offset; Google Photos reads the EXIF date itself, while galleries that rely on MediaStore sort such exports by export time.

### 6. Naming and album
Album and file prefix come from one constant tied to the app name, because "DiveLens" is temporary. Photo picker URIs expose `_display_name` as `<media id>.jpg` (found during implementation, tasks.md 3.3), so the original name is unavailable. Decision (user, 2026-10-12): `<AppName>_<yyyyMMdd>_<HHmmss>.jpg` from EXIF `DateTimeOriginal` as recorded by the camera (local wall-clock time, no zone conversion, so it matches what the diver saw), falling back to the export time in the device zone. Duplicates (two exports of one photo, burst shots in the same second) are left to MediaStore's automatic de-duplication (" (1)" etc.); task 4.4 verifies this on API 29 as well, otherwise a counter is appended.
*Alternatives:* the document picker (real names, clunkier UI); the picker's media id (meaningless to users).

### 7. Two seams for later changes
- `PhotoProcessor`: `suspend (Bitmap) -> Bitmap`. This change ships `IdentityPhotoProcessor`; change 2 swaps in the GPU pipeline.
- `ExportGate`: `suspend fun check(item): Allowed | Refused(reason)` called before each export, and `suspend fun onExported(item)` called after a successful one. This change ships an always-allow implementation; change 6 implements the N = 3 counter (it needs `onExported` to count only successful exports).

Both are constructor parameters of the export use case, wired manually in one place. No DI framework yet; the app is small enough that one hand-written container is clearer.

### 8. Batch execution
A ViewModel owns the working set and runs the batch in `viewModelScope` on `Dispatchers.Default` (decode/encode) and `Dispatchers.IO` (file/MediaStore), strictly one item at a time to cap memory. Cancellation is checked between pipeline steps; the `finally` cleanup in decision 4 runs on cancel. The ViewModel exposes one immutable UI state (items with per-item status, progress, summary). It survives rotation; it does not survive process death (non-goal).
*Alternative:* WorkManager. Deferred: a photo batch takes seconds to a few minutes and the user is watching it. Change 5 adds a foreground service for video, which photo batches can reuse if needed.

### 9. Format acceptance and import validation
At import, read the MIME type from the content resolver and decode bounds only (`inJustDecodeBounds`), which is cheap. Accept `image/jpeg`, `image/heic`, `image/heif`; reject everything else, and anything whose bounds can't be decoded, with a per-file message. Photos above 100 MP are rejected with a clear reason, as a guard against running out of memory on full-resolution decode.

### 10. UI
One Compose screen: empty state with a "Select photos" button; a thumbnail grid (Coil `AsyncImage`) with remove per item and "Clear"; an "Export N photos" action; a progress indicator ("3 of 5") with Cancel; and a summary listing failures with reasons. All user-visible text in string resources.

Back (added after testing on API 29, 2026-10-13): with photos selected it clears the working set and shows the start screen; during an export it asks before cancelling, because closing the activity would otherwise cancel the batch silently; on the start screen it closes the app. Implemented with Compose `BackHandler` in the screen, so no navigation library is needed.

### 11. Dependencies
Add `androidx.exifinterface`, `io.coil-kt.coil3:coil-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose` and `lifecycle-runtime-compose`; bump core-ktx, lifecycle and activity-compose to current stable. Versions are picked at implementation time (current stable, at least two weeks old). All Apache-2.0.

### 12. Testing
- **JVM unit tests:** file naming, EXIF date → epoch conversion, batch state transitions (progress, cancel, per-item failure) using fake `PhotoProcessor`/`ExportGate`/writer.
- **Instrumented tests (emulator API 29 and latest):** end-to-end export into MediaStore with synthetic fixtures generated in the test (JPEG with known EXIF including fake GPS coordinates and a rotation tag, plus one HEIC). Assertions: dimensions, copied tags, no GPS tags in the export, orientation tag, `DATE_TAKEN`, original unchanged, no pending rows left after cancel/failure, duplicate naming.
- No personal photos in the repo.

## Risks / Trade-offs

- [Users want location in exports] → Originals keep it; an opt-in setting using the document picker is the known path (decision 2).
- [Out of memory on very large photos or low-end devices] → One item at a time; bitmaps live in native memory on API 26+; 100 MP guard; catch `OutOfMemoryError` per item and report it as that item's failure.
- [JPEG re-encoding loses some quality] → Quality 95; unavoidable once change 2 alters pixels.
- [HEIC originals become JPEG exports] → Accepted for v1: universal compatibility; HEIC output can be added later.
- [Process killed mid-batch loses progress] → Accepted (non-goal); completed exports are already in the gallery, pending rows are cleaned up by MediaStore after its expiry.
- [Album/suffix name changes later] → One constant; exports already made keep their old album, which is acceptable.
- [Template dependency bumps break the build] → Bump in its own task and verify the build and existing tests before feature work.
