# import-export Specification

## Purpose

Gets the user's underwater photos (and later videos) into the app from the device and writes processed copies back to the device gallery at full resolution, with metadata preserved and the originals left untouched.

## Requirements

### Requirement: Import photos through the system picker
The system SHALL let the user select one or more photos from the device through the system photo picker, without requesting broad storage permissions. Selected photos SHALL be added to the current working set.

#### Scenario: Select a single photo
- **WHEN** the user opens the picker and selects one photo
- **THEN** the photo appears in the working set as a thumbnail

#### Scenario: Select several photos at once
- **WHEN** the user selects several photos in one picker session
- **THEN** all selected photos appear in the working set, in the order the picker returned them

#### Scenario: Cancel the picker
- **WHEN** the user closes the picker without selecting anything
- **THEN** the working set is unchanged and no error is shown

### Requirement: Photos only in this version of import
The picker SHALL offer photos only. Videos SHALL NOT be importable until video processing is available.

#### Scenario: Picker hides videos
- **WHEN** the user opens the picker
- **THEN** only photos are offered for selection

### Requirement: Reject unsupported photo files
The system SHALL accept JPEG and HEIC/HEIF photos. Any other file, and any file that cannot be read or decoded, SHALL be left out of the working set with a message naming the file and the reason, while the other selected photos are still added.

#### Scenario: Unsupported format in a batch
- **WHEN** the user selects three JPEGs and one RAW (DNG) file
- **THEN** the three JPEGs are added and a message says the DNG file is not supported

#### Scenario: Unreadable file
- **WHEN** a selected photo cannot be opened or decoded
- **THEN** it is not added and a message names the file

### Requirement: Manage the working set
The system SHALL let the user remove individual photos from the working set, or clear it, before exporting. Removing a photo SHALL NOT affect the original file.

#### Scenario: Remove one photo
- **WHEN** the user removes a photo from the working set
- **THEN** it no longer appears in the working set and is not exported, and the original stays in the gallery

### Requirement: Export as new full-resolution files
The system SHALL export each photo as a new file in the device gallery with the same pixel dimensions as the original. The system SHALL NEVER modify, overwrite or delete the original file.

#### Scenario: Export keeps dimensions
- **WHEN** the user exports a 4000×3000 photo
- **THEN** a new 4000×3000 photo appears in the gallery and the original file is byte-for-byte unchanged

#### Scenario: Export does not overwrite earlier exports
- **WHEN** the user exports the same photo twice
- **THEN** two separate exported files exist and neither replaces the original

### Requirement: Exported file location and naming
Exported photos SHALL be saved as JPEG in a "DiveLens" album under the device Pictures collection. Each file name SHALL be the app name followed by the photo's date and time taken, or the export time when the photo has no date taken. The system photo picker does not reveal original file names.

#### Scenario: Export file name from date taken
- **WHEN** the user exports a photo taken on 14 July 2026 at 10:32:05
- **THEN** the gallery shows a new JPEG in the DiveLens album named `DiveLens_20260714_103205.jpg`, or that name with a number added if it already exists

#### Scenario: Photo without date taken
- **WHEN** the user exports a photo that has no date taken
- **THEN** the export is named from the date and time of the export

#### Scenario: HEIC input
- **WHEN** the user exports a HEIC photo
- **THEN** the export is a JPEG with the same dimensions and metadata

### Requirement: Preserve photo metadata
Each export SHALL carry over the original's metadata, including date and time taken, camera make and model, lens and exposure data, and orientation, whenever the original contains it. Dimension tags SHALL describe the exported image.

#### Scenario: Date and camera preserved
- **WHEN** the user exports a photo that has a date taken and camera make and model
- **THEN** the export has the same date taken and the same camera make and model

#### Scenario: Export sorts by date taken
- **WHEN** the original's date taken includes a time-zone offset and the export appears in the gallery
- **THEN** the gallery's date taken for the export equals the original's date taken, not the export time

#### Scenario: Date taken without time-zone offset
- **WHEN** the original's date taken has no time-zone offset
- **THEN** the export carries the same date and time exactly as recorded, and no offset is added

#### Scenario: Original without metadata
- **WHEN** the original has no EXIF data
- **THEN** the export succeeds without it

### Requirement: No location in exports
Exports SHALL NOT contain GPS location data, whether or not the original has it. The original file SHALL keep its location.

#### Scenario: Original has GPS
- **WHEN** the user exports a photo whose original contains GPS coordinates
- **THEN** the export contains no GPS tags, and the original still contains its coordinates

### Requirement: Preserve orientation
An exported photo SHALL display with the same orientation as the original in gallery apps, including originals that store rotation as an EXIF orientation tag.

#### Scenario: Portrait photo stored rotated
- **WHEN** the user exports a portrait photo whose pixels are stored landscape with an EXIF rotation tag
- **THEN** the export displays as portrait in the gallery, the same as the original

### Requirement: Batch export with progress and cancel
The system SHALL export all photos in the working set as one batch, showing progress (items done out of total) and letting the user cancel. Cancelling SHALL stop before the next item; photos already exported SHALL be kept and no partial file SHALL remain for the item in progress.

#### Scenario: Progress during batch
- **WHEN** the user exports five photos
- **THEN** the app shows progress from 0 of 5 to 5 of 5

#### Scenario: Cancel mid-batch
- **WHEN** the user cancels while the third of five photos is exporting
- **THEN** the first two exports remain in the gallery, no incomplete third file appears, and photos four and five are not exported

### Requirement: Back navigation
The system back action SHALL return to the start screen when photos are selected, SHALL ask for confirmation before cancelling a running export, and SHALL leave the app only from the start screen.

#### Scenario: Back with photos selected
- **WHEN** the working set has photos and no export is running, and the user presses Back
- **THEN** the working set is cleared and the start screen is shown, without leaving the app

#### Scenario: Back during an export
- **WHEN** an export is running and the user presses Back
- **THEN** the app asks whether to cancel the export; choosing to keep exporting continues the batch, and choosing to cancel behaves like the Cancel button

#### Scenario: Back on the start screen
- **WHEN** the working set is empty and the user presses Back
- **THEN** the app closes as usual

### Requirement: Per-item failure handling
A failure exporting one photo SHALL NOT stop the rest of the batch. When the batch ends, the system SHALL report how many photos were exported and name each one that failed with a reason.

#### Scenario: One item fails
- **WHEN** one photo in a batch of four fails to export
- **THEN** the other three are exported and the summary lists the failed photo and why

#### Scenario: Not enough storage
- **WHEN** the device runs out of storage while exporting
- **THEN** the current item fails with an "insufficient storage" reason and no incomplete file remains in the gallery

### Requirement: Export permission check
Before writing each export, the system SHALL check whether that export is allowed. A refused export SHALL NOT be written and SHALL be reported to the user. In this version every export is allowed.

#### Scenario: Export allowed
- **WHEN** the user exports a photo and the check allows it
- **THEN** the photo is exported normally

### Requirement: Processing applies to exported pixels only
Every export SHALL pass the photo's pixels through the app's processing step before encoding. In this version the processing step SHALL leave pixels unchanged, apart from the loss inherent in JPEG re-encoding.

#### Scenario: Unprocessed export looks like the original
- **WHEN** the user exports a photo in this version
- **THEN** the export is visually indistinguishable from the original at 100% zoom

### Requirement: On-device only
Importing and exporting SHALL happen entirely on the device. Photos and their metadata SHALL NOT be sent off the device.

#### Scenario: Offline export
- **WHEN** the device has no network connection
- **THEN** import and export work normally
