# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Date caption in the grid
Each item in the working-set grid SHALL show a short caption with its date and time taken (for a video, its creation date) in the device's language and time zone. Items without a date SHALL show no caption. File names SHALL NOT be shown, because the system picker does not reveal them.

#### Scenario: Photo with a date
- **WHEN** the working set holds a photo taken on 14 July 2026 at 10:32
- **THEN** its cell shows a caption such as "14 Jul 2026, 10:32"

#### Scenario: Photo without a date
- **WHEN** a photo has no date taken
- **THEN** its cell shows no caption
