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
Tapping a thumbnail SHALL open that photo or video full screen, corrected and fitted to the screen in its display orientation. The user SHALL be able to swipe to the previous and next item of the working set, photos and videos alike, in selection order. Videos play as described in the video-processing capability.

#### Scenario: Open and browse
- **WHEN** the user taps the third of five thumbnails and then swipes left
- **THEN** the viewer shows the third photo corrected, then the fourth

#### Scenario: Photo removed elsewhere
- **WHEN** the working set changes while the viewer is closed, and the user opens the viewer again
- **THEN** the viewer shows only photos that are still in the working set

#### Scenario: Swipe from a playing video
- **WHEN** a video is playing and the user swipes to the next photo
- **THEN** the video stops and the photo is shown corrected

### Requirement: Zoom to real pixels
In the viewer the user SHALL be able to zoom photos with pinch and double-tap, from fitting the screen up to at least 100% (one photo pixel per screen pixel). At every zoom level the displayed detail SHALL come from the photo's full resolution and its correction, without visible seams or tile edges. Videos are shown fitted to the screen and do not zoom.

#### Scenario: Check grain at 100%
- **WHEN** the user zooms into open water of a 20-megapixel photo until one photo pixel matches one screen pixel
- **THEN** the viewer shows the corrected pixels at full detail, without seams, after a short loading moment at most

#### Scenario: Pinch on a video
- **WHEN** the user pinches on a video in the viewer
- **THEN** the video stays fitted to the screen

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

### Requirement: Date caption in the grid
Each item in the working-set grid SHALL show a short caption with its date and time taken (for a video, its creation date) in the device's language and time zone. Items without a date SHALL show no caption. File names SHALL NOT be shown, because the system picker does not reveal them.

#### Scenario: Photo with a date
- **WHEN** the working set holds a photo taken on 14 July 2026 at 10:32
- **THEN** its cell shows a caption such as "14 Jul 2026, 10:32"

#### Scenario: Photo without a date
- **WHEN** a photo has no date taken
- **THEN** its cell shows no caption

### Requirement: Viewer transition
The viewer SHALL open with a short transition, fading in while growing slightly to full size, and SHALL close with the reverse transition, back to the grid at the scroll position it had. The transition SHALL take at most 300 ms and SHALL run the same way for every way of closing. When the system setting to remove animations is on, the viewer SHALL open and close without a transition. A playing video SHALL stop as soon as closing starts.

#### Scenario: Open and close with a transition
- **WHEN** the user taps a thumbnail, and later presses the system Back in the viewer
- **THEN** the viewer fades in and grows to full screen, and on Back it fades out and shrinks slightly, showing the grid at the same scroll position

#### Scenario: Closing a playing video
- **WHEN** a video is playing and the user closes the viewer
- **THEN** playback stops as the transition starts, the video's thumbnail fades out, and no sound plays during the transition

#### Scenario: Animations removed
- **WHEN** the system setting to remove animations is on and the user opens and closes the viewer
- **THEN** the viewer appears and disappears without a transition

### Requirement: Back button in the viewer
The viewer's bottom panel SHALL show a "Back" button at the right end of the row with Reset and Apply to all, on photos and videos alike. It SHALL close the viewer exactly like the system Back.

#### Scenario: Back button
- **WHEN** the user taps "Back" in the viewer's bottom panel
- **THEN** the viewer closes with the same transition as the system Back, and the strength set for the item stays as it was

#### Scenario: Back on a playing video
- **WHEN** a video is playing and the user taps "Back"
- **THEN** playback stops and the viewer closes
