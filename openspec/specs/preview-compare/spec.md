# preview-compare Specification

## Purpose

Shows the automatic correction before export: corrected thumbnails, a full-screen viewer with zoom to real pixels, and press-and-hold comparison with the original, matching what will be exported.

## Requirements

### Requirement: Corrected thumbnails
The working-set grid SHALL show each photo with its correction applied. Until a photo's correction is ready, its thumbnail SHALL show the original with a visible "correcting" indicator. Thumbnails SHALL show the photo in its display orientation.

#### Scenario: Thumbnails become corrected
- **WHEN** the user selects several underwater photos
- **THEN** each thumbnail first shows the original with a "correcting" indicator, then the corrected photo, one photo after another, without blocking the screen

### Requirement: Full-screen viewer
Tapping a thumbnail SHALL open that photo full screen, corrected and fitted to the screen in its display orientation. The user SHALL be able to swipe to the previous and next photo of the working set, in selection order.

#### Scenario: Open and browse
- **WHEN** the user taps the third of five thumbnails and then swipes left
- **THEN** the viewer shows the third photo corrected, then the fourth

#### Scenario: Photo removed elsewhere
- **WHEN** the working set changes while the viewer is closed, and the user opens the viewer again
- **THEN** the viewer shows only photos that are still in the working set

### Requirement: Zoom to real pixels
In the viewer the user SHALL be able to zoom with pinch and double-tap, from fitting the screen up to at least 100% (one photo pixel per screen pixel). At every zoom level the displayed detail SHALL come from the photo's full resolution and its correction, without visible seams or tile edges.

#### Scenario: Check grain at 100%
- **WHEN** the user zooms into open water of a 20-megapixel photo until one photo pixel matches one screen pixel
- **THEN** the viewer shows the corrected pixels at full detail, without seams, after a short loading moment at most

### Requirement: Hold to see the original
While the user presses and holds on the photo in the viewer, the viewer SHALL show the original, uncorrected photo at the same position and zoom. Releasing SHALL show the corrected photo again. Holding SHALL NOT be mistaken for a pan, zoom or swipe.

#### Scenario: Compare at any zoom
- **WHEN** the user has zoomed into a detail, then presses and holds without moving
- **THEN** the same detail is shown uncorrected until the finger is lifted, and the zoom and position stay the same

### Requirement: Preview matches the export
The thumbnails and the viewer SHALL show the same correction that the export applies to that photo. At 100% zoom the viewer's pixels SHALL equal the exported photo's pixels apart from JPEG encoding, at most 1 step out of 255 per channel.

#### Scenario: Export what was previewed
- **WHEN** the user views a photo at 100% zoom and then exports it
- **THEN** the export's pixels match the viewed pixels, apart from JPEG encoding

### Requirement: Preview responsiveness
On the reference device (Xiaomi 14T), a 20-megapixel photo opened in the viewer SHALL be shown corrected at screen resolution within 1 second. Swiping to a neighbouring photo that was already prepared SHALL show it immediately. The screen SHALL stay responsive while corrections are computed.

#### Scenario: Open a large photo
- **WHEN** the user opens a 20-megapixel photo in the viewer
- **THEN** it is shown corrected within 1 second, and the app responds to touches meanwhile

### Requirement: Preview within memory limits
Previewing SHALL work for photos up to the import limit without running out of memory, with the working set at its limit of 50 photos. When a photo cannot be previewed, the system SHALL show the original with a message instead of failing, and the photo SHALL stay exportable.

#### Scenario: Browse many large photos
- **WHEN** the working set holds 50 photos of 50 megapixels and the user swipes through all of them in the viewer
- **THEN** every photo is shown corrected and the app does not crash or run out of memory

#### Scenario: Photo that cannot be previewed
- **WHEN** a photo's correction cannot be prepared for preview
- **THEN** its thumbnail and viewer show the original with a "preview unavailable" message, and the photo is still exported normally
