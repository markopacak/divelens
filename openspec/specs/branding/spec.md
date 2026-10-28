# branding Specification

## Purpose

Gives DiveLens a consistent visual identity: its launcher, themed and notification icons, its colour palette, and a small, removable mark on exported photos and videos. The brand assets in `docs/brand/` are the source of truth.

## Requirements

### Requirement: App icon
The launcher icon SHALL be the DiveLens symbol from the brand assets: a Foam ring with a Coral Glow dot on Deep Ocean, as an adaptive icon for both the normal and the round launcher icon. The whole symbol SHALL lie inside the adaptive icon's guaranteed safe zone (a circle of 66 dp diameter at the centre of the 108 dp canvas).

#### Scenario: Icon on the home screen
- **WHEN** the app is installed on a phone whose launcher crops icons to circles, squircles or rounded squares
- **THEN** the home screen shows the ring and the dot complete, on a Deep Ocean background, with nothing clipped

#### Scenario: Symbol within the safe zone
- **WHEN** the icon's foreground layer is checked in its 108 × 108 dp canvas
- **THEN** no visible part of the ring or the dot is farther than 33 dp from the centre

### Requirement: Themed icon
The adaptive icon SHALL include a monochrome layer of the same symbol, so launchers that tint icons to the wallpaper (Android 13+) show the DiveLens symbol in the system's colours.

#### Scenario: Themed icons on
- **WHEN** the user turns on themed icons on an Android 13+ phone
- **THEN** the DiveLens icon shows the ring and dot in the theme's colours instead of the default icon or a generic letter

### Requirement: Notification icon
The export notification SHALL use the DiveLens symbol as its small icon, drawn as a single-colour silhouette.

#### Scenario: Export in progress
- **WHEN** an export runs and the user looks at the status bar
- **THEN** the status bar shows the ring-and-dot symbol for the export notification

### Requirement: Launch screen
On Android 12 and later, the system launch screen SHALL show the app icon on a Deep Ocean background.

#### Scenario: Cold start
- **WHEN** the user opens the app while it is not running, on an Android 12+ phone
- **THEN** the launch screen shows the DiveLens icon on Deep Ocean, not on white

### Requirement: Brand colours
The app's screens SHALL use the brand palette from the brand assets: the light palette in light mode and the dark palette in dark mode. The colours SHALL NOT change with the phone's wallpaper.

#### Scenario: Wallpaper does not recolour the app
- **WHEN** the user has a wallpaper with strong colours on an Android 12+ phone and opens DiveLens
- **THEN** buttons, the top bar and the background use the DiveLens palette, the same as on a phone with a different wallpaper

#### Scenario: Dark mode
- **WHEN** the phone is in dark mode
- **THEN** the app shows the Deep Ocean background with Foam text and Surf as the primary colour

#### Scenario: Readable text
- **WHEN** any text or button label is shown on its background, in light or dark mode
- **THEN** the contrast ratio is at least 4.5:1

### Requirement: Watermark on exports
When the watermark setting is on, every exported photo and every frame of an exported video SHALL show the diveLENS mark in the bottom-right corner as the picture is displayed. The mark's width SHALL be 12 % of the displayed width, its distance from the right and bottom edges 3 % of the displayed width, and its opacity 92 %. It SHALL apply at every correction strength, including 0 %.

#### Scenario: Landscape photo
- **WHEN** the user exports a 4000 × 3000 landscape photo with the watermark on
- **THEN** the export is 4000 × 3000 and shows the mark 480 px wide, 120 px from the right and bottom edges

#### Scenario: Rotated photo
- **WHEN** the user exports a portrait photo stored as landscape pixels with an EXIF rotation of 90°
- **THEN** in the gallery the mark sits upright in the bottom-right corner of the portrait picture, 12 % of the portrait width

#### Scenario: Rotated video
- **WHEN** the user exports a portrait video recorded with a rotation of 90°
- **THEN** every frame shows the mark upright in the bottom-right corner of the portrait picture

#### Scenario: Strength 0
- **WHEN** the user exports a photo at 0 % strength with the watermark on
- **THEN** the photo's colours are uncorrected, and the mark is present

#### Scenario: Watermark off
- **WHEN** the user exports a photo and a video with the watermark setting off
- **THEN** neither export shows the mark, and both are otherwise identical to an export with it on

### Requirement: Watermark version
The watermark SHALL come in a light version (white with a dark shadow) and a dark version (navy with a light halo). It SHALL use the dark version when the mean brightness of the corrected picture under the mark is above the brand threshold, and the light version otherwise. Brightness is measured over the area under the mark plus 10 % padding, as Rec. 709 luma of the gamma-encoded values (0 to 1).

#### Scenario: Dark water in the corner
- **WHEN** the bottom-right corner of the corrected photo is deep blue water with a mean luma of 0.38
- **THEN** the export uses the light (white) mark

#### Scenario: Bright sand in the corner
- **WHEN** the bottom-right corner of the corrected photo is bright sand with a mean luma above the threshold
- **THEN** the export uses the dark (navy) mark

#### Scenario: Strength changes the measurement
- **WHEN** the same photo is exported at two strengths that put the corner's luma on either side of the threshold
- **THEN** each export uses the version chosen from its own corrected pixels

### Requirement: One watermark version per video
A video SHALL use one watermark version for the whole clip, chosen from the median brightness of the area under the mark across frames sampled through the clip, after correction at the export's strength. The mark SHALL NOT change version, position or size during the clip.

#### Scenario: Clip passes over sand
- **WHEN** a clip spends most of its time in dark water and briefly passes over bright sand
- **THEN** every frame of the export shows the light mark, in the same place and size

### Requirement: Watermark only in exports
The watermark SHALL appear only in exported files. The grid thumbnails, the viewer and the hold-to-see-original comparison SHALL NOT show it, and the original files SHALL stay unchanged.

#### Scenario: Viewer before export
- **WHEN** the user opens a photo in the viewer with the watermark setting on
- **THEN** the viewer shows the corrected photo without the mark

### Requirement: Watermark adds no data
Adding the watermark SHALL NOT add metadata to the export, change its pixel dimensions, resolution, frame rate, duration or orientation, or send anything off the device.

#### Scenario: Metadata unchanged
- **WHEN** the same photo is exported with the watermark on and off
- **THEN** both exports have the same dimensions and the same metadata fields and values
