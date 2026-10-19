# video-processing Specification

## Purpose

Corrects underwater videos over their whole length, with colour that follows depth without flicker, and covers what is specific to video: preview playback, thumbnails, the export format and its metadata.

## Requirements

### Requirement: Correction follows the video over time
The system SHALL correct each video frame with the same correction model as photos, with parameters that follow the video's content over time: estimated on frames sampled through the clip, smoothed over time, and blended between samples. The grain-aware smoothing of the colour shift SHALL be computed for each frame from that frame's own content.

#### Scenario: Diver descends during a clip
- **WHEN** a clip starts in bright shallow water and ends in deep blue water
- **THEN** the start and the end are each corrected for their own water, and the change between them is gradual

#### Scenario: Moving edge
- **WHEN** the camera pans across a reef edge against open water
- **THEN** the corrected frames show no colour halo trailing the edge

### Requirement: No flicker
A corrected video SHALL NOT flicker. On the reference videos, the mean colour of the corrected video SHALL change between consecutive frames by at most 0.5 CIE76 ΔE more than the original's mean colour changes between the same frames.

#### Scenario: Static scene
- **WHEN** the user exports a clip of a still reef scene
- **THEN** the corrected clip's colours stay steady from frame to frame

### Requirement: Video quality against the reference set
On the reference videos with RefApp results, at the default strength, the correction over time SHALL be closer to the RefApp results than correcting every frame on its own (mean CIE76 ΔE over the videos), its mean ΔE over the videos SHALL be at most 9.0, and every video SHALL be closer to its RefApp result than the original is. The matched-strength limit of the auto-correction spec applies to photos and video frames together, as it does there.

#### Scenario: Video reference check
- **WHEN** the video reference check runs on a machine that has the reference videos
- **THEN** it reports the mean ΔE and the flicker measure per video, and passes only if all limits hold; without the reference videos it is skipped

### Requirement: Video export format
Each video SHALL be exported as a new MP4 file with the original's resolution, frame rate, duration and display orientation, encoded with the original's codec (H.264 or HEVC) at about the original's bitrate. The audio SHALL be copied unchanged. The original file SHALL NEVER be modified.

#### Scenario: GoPro clip
- **WHEN** the user exports a 2.7K, 60 fps H.264 clip of 15 seconds with stereo audio
- **THEN** the gallery shows a new 2.7K, 60 fps, 15-second MP4 that is colour-corrected and has the same audio

### Requirement: Video metadata
Each video export SHALL keep the original's creation date, and the gallery SHALL sort it by that date. The export SHALL contain only the video and audio tracks. It SHALL contain no location: neither a location tag nor any other track, such as camera telemetry, that may carry GPS data.

#### Scenario: GoPro telemetry
- **WHEN** the user exports a GoPro clip that has a telemetry track with GPS
- **THEN** the export has a video and an audio track only, no location, and the original's creation date

### Requirement: HDR input
A 10-bit HDR video (HLG or PQ) SHALL be converted to standard dynamic range before it is corrected, and SHALL be exported in standard dynamic range. Its preview SHALL show the same conversion.

#### Scenario: HLG clip
- **WHEN** the user exports a 10-bit HLG clip
- **THEN** the export is an SDR video, corrected, with natural-looking brightness

### Requirement: Video thumbnail
A video's thumbnail SHALL show one corrected frame from about 1 second into the clip, or the first frame for clips shorter than 2 seconds. When that frame is nearly black, a later frame from the first seconds SHALL be used. The thumbnail SHALL show the clip's duration.

#### Scenario: Clip starting in the dark
- **WHEN** the user adds a clip whose first second is black
- **THEN** its thumbnail shows a corrected frame with content, and the duration, such as 0:42

### Requirement: Video playback in the viewer
The viewer SHALL play a video in full and in a loop, with the correction applied as it plays, with play and pause buttons shown as symbols and a scrub bar. Hold to see the original SHALL show the uncorrected video while held, playing or paused. The strength slider SHALL change the playing video without stopping it, and a paused video SHALL show the new strength on its current frame. Videos SHALL NOT zoom.

#### Scenario: Watch and compare
- **WHEN** the user opens a video, plays it, and holds a finger on it
- **THEN** the original is shown while the finger is down and the video keeps playing; lifting the finger shows it corrected again

#### Scenario: Compare while paused
- **WHEN** the user pauses a video and holds a finger on it
- **THEN** the paused frame is shown uncorrected until the finger is lifted

#### Scenario: End of the clip
- **WHEN** a playing video reaches its end
- **THEN** it continues from the start

### Requirement: Playback starts without waiting
Playback SHALL be able to start before the whole clip is analysed. Until a part of the clip is analysed, it SHALL be shown with the correction of the nearest analysed part, and it SHALL change to its own correction gradually once analysed.

#### Scenario: Play a long clip at once
- **WHEN** the user opens a 10-minute clip and presses play immediately
- **THEN** it plays corrected straight away, and no visible jump in colour occurs as later parts are analysed

### Requirement: Preview matches the video export
At every moment of a video, the viewer SHALL apply the same correction and strength as the export, once the clip is analysed. The exported frames SHALL differ from the previewed frames only by video encoding.

#### Scenario: Export what was watched
- **WHEN** the user watches a clip at 40% strength and exports it
- **THEN** the exported clip shows the same colours at each moment as the viewer did

### Requirement: Video responsiveness
On the reference device (Xiaomi 14T), a 2.7K 60 fps clip SHALL start playing corrected within 1 second of being opened and play at its full frame rate without visible stutter. Its export SHALL take at most twice the clip's duration. The app SHALL stay responsive while videos are analysed.

#### Scenario: Export a 15-second GoPro clip
- **WHEN** the user exports a 15-second 2.7K 60 fps clip on the reference device
- **THEN** it is in the gallery within 30 seconds
