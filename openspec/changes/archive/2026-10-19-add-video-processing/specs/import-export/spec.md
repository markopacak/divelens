# Spec Delta

## MODIFIED Requirements

### Requirement: Import photos through the system picker
The system SHALL let the user select one or more photos and videos from the device through the system photo picker, without requesting broad storage permissions. Selected photos and videos SHALL be added to the current working set, together, in the order the picker returned them. From the moment the picker closes until the items appear, the screen SHALL show that they are being added.

#### Scenario: Select a single photo
- **WHEN** the user opens the picker and selects one photo
- **THEN** the photo appears in the working set as a thumbnail

#### Scenario: Select several photos at once
- **WHEN** the user selects several photos in one picker session
- **THEN** all selected photos appear in the working set, in the order the picker returned them

#### Scenario: Select photos and videos together
- **WHEN** the user selects two photos and one video in one picker session
- **THEN** all three appear in the working set, the video with a duration badge

#### Scenario: Cancel the picker
- **WHEN** the user closes the picker without selecting anything
- **THEN** the working set is unchanged and no error is shown

### Requirement: Reject unsupported photo files
The system SHALL accept JPEG and HEIC/HEIF photos, and MP4 and MOV videos in H.264 or HEVC that the device can decode and re-encode at their resolution and frame rate. Any other file, and any file that cannot be read or decoded, SHALL be left out of the working set with a message naming the file and the reason, while the other selected items are still added.

#### Scenario: Unsupported format in a batch
- **WHEN** the user selects three JPEGs and one RAW (DNG) file
- **THEN** the three JPEGs are added and a message says the DNG file is not supported

#### Scenario: Unreadable file
- **WHEN** a selected photo cannot be opened or decoded
- **THEN** it is not added and a message names the file

#### Scenario: Video the device cannot process
- **WHEN** the user selects a video whose resolution or frame rate the device cannot encode, such as an 8K clip on a device that encodes up to 4K
- **THEN** it is not added and a message says it is too large for this device

### Requirement: Working-set limit
The working set SHALL hold at most 20 items, photos and videos together. A selection that would exceed the limit SHALL add items in the order the picker returned them until the limit is reached and tell the user how many were left out and that the limit is 20. While the working set is full, selecting more items SHALL be unavailable, with a message explaining the limit.

#### Scenario: Selection exceeds the limit
- **WHEN** the working set holds 15 photos and the user selects 8 more
- **THEN** the first 5 selected photos are added, and a message says 3 photos were left out because the limit is 20 items

#### Scenario: Videos count towards the limit
- **WHEN** the working set holds 18 photos and the user selects 3 videos
- **THEN** the first 2 videos are added and a message says 1 item was left out

#### Scenario: Working set is full
- **WHEN** the working set holds 20 photos
- **THEN** selecting more photos is unavailable and the screen explains the limit, until a photo is removed or the set is cleared

### Requirement: Export as new full-resolution files
The system SHALL export each photo as a new file in the device gallery with the same pixel dimensions as the original, and each video as a new file with the same resolution, frame rate and duration. The system SHALL NEVER modify, overwrite or delete the original file.

#### Scenario: Export keeps dimensions
- **WHEN** the user exports a 4000×3000 photo
- **THEN** a new 4000×3000 photo appears in the gallery and the original file is byte-for-byte unchanged

#### Scenario: Export does not overwrite earlier exports
- **WHEN** the user exports the same photo twice
- **THEN** two separate exported files exist and neither replaces the original

#### Scenario: Video keeps its format
- **WHEN** the user exports a 1920×1080, 60 fps video
- **THEN** a new 1920×1080, 60 fps video of the same duration appears in the gallery and the original is byte-for-byte unchanged

### Requirement: Exported file location and naming
Exported photos SHALL be saved as JPEG and exported videos as MP4, in a "DiveLens" album under the device Pictures collection. Each file name SHALL be the app name followed by the item's date and time taken (for a video, its creation date), or the export time when the item has no date taken. The system photo picker does not reveal original file names.

#### Scenario: Export file name from date taken
- **WHEN** the user exports a photo taken on 14 July 2026 at 10:32:05
- **THEN** the gallery shows a new JPEG in the DiveLens album named `DiveLens_20260714_103205.jpg`, or that name with a number added if it already exists

#### Scenario: Photo without date taken
- **WHEN** the user exports a photo that has no date taken
- **THEN** the export is named from the date and time of the export

#### Scenario: HEIC input
- **WHEN** the user exports a HEIC photo
- **THEN** the export is a JPEG with the same dimensions and metadata

#### Scenario: Video file name
- **WHEN** the user exports a video created on 4 October 2026 at 17:32:34 local time
- **THEN** the gallery shows a new MP4 in the DiveLens album named `DiveLens_20261004_173234.mp4`

### Requirement: No location in exports
Exports SHALL NOT contain GPS location data, whether or not the original has it, in photos and in videos. The original file SHALL keep its location.

#### Scenario: Original has GPS
- **WHEN** the user exports a photo whose original contains GPS coordinates
- **THEN** the export contains no GPS tags, and the original still contains its coordinates

#### Scenario: Video with location
- **WHEN** the user exports a video whose original has a location tag or a telemetry track with GPS
- **THEN** the export contains neither, and the original is unchanged

### Requirement: Batch export with progress and cancel
The system SHALL export all items in the working set, photos and videos, as one batch, showing progress (items done out of total) and letting the user cancel. While the batch runs, each item SHALL show whether it is queued, exporting or exported. Cancelling SHALL stop the item in progress and every later item; items already exported SHALL be kept and no partial file SHALL remain for the item in progress.

#### Scenario: Progress during batch
- **WHEN** the user exports five photos
- **THEN** the app shows progress from 0 of 5 to 5 of 5

#### Scenario: Cancel mid-batch
- **WHEN** the user cancels while the third of five photos is exporting
- **THEN** the first two exports remain in the gallery, no incomplete third file appears, and photos four and five are not exported

#### Scenario: Cancel during a long video
- **WHEN** the user cancels while a 10-minute video is half exported
- **THEN** the export stops within a few seconds and no part of that video remains in the gallery

### Requirement: Exports are corrected
Every export SHALL pass the photo's pixels, or every frame of the video, through the app's automatic colour correction (see the auto-correction and video-processing capabilities) before encoding. The correction SHALL change only the exported copy, never the original, and SHALL keep the photo's pixel dimensions or the video's resolution.

#### Scenario: Exported photo is corrected
- **WHEN** the user exports an underwater photo with a blue-green cast
- **THEN** the export in the gallery is colour-corrected, and the original file is byte-for-byte unchanged

#### Scenario: Exported video is corrected
- **WHEN** the user exports an underwater video with a blue-green cast
- **THEN** every frame of the export is colour-corrected, and the original file is byte-for-byte unchanged

## ADDED Requirements

### Requirement: Export continues in the background
A running batch SHALL continue when the user leaves the app or the screen turns off, and SHALL show its progress in a notification with a cancel action. If the user declines notifications, the batch SHALL still run. When the batch ends, the notification SHALL say how many items were exported.

#### Scenario: Leave the app during an export
- **WHEN** the user starts exporting three videos and switches to another app
- **THEN** the export continues, the notification shows its progress, and all three videos appear in the gallery

#### Scenario: Cancel from the notification
- **WHEN** the user taps cancel in the notification
- **THEN** the batch stops as if cancelled in the app

### Requirement: Storage check before a video
Before exporting a video, the system SHALL check that the free storage holds the expected size of the export plus a safety margin. If it does not, that video SHALL fail with an "insufficient storage" reason without writing anything, and the batch SHALL continue with the next item.

#### Scenario: Not enough space for a long video
- **WHEN** the device has 1 GB free and the user exports a 3 GB video
- **THEN** the video fails at once with "not enough storage", nothing is written for it, and the next item is exported

## REMOVED Requirements

### Requirement: Photos only in this version of import
**Reason**: Video processing is now available, so videos are importable (see "Import photos through the system picker").
**Migration**: None needed. The picker now offers photos and videos.
