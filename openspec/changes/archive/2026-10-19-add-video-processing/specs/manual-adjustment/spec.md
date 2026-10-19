# Spec Delta

## MODIFIED Requirements

### Requirement: Strength slider
The full-screen viewer SHALL show a strength slider for the photo or video shown, in a panel that is always visible at the bottom of the screen. The slider SHALL range from 0% (original colours) to 100% (full correction), SHALL start at the automatic default strength, and SHALL mark the default's position. Moving the slider SHALL NOT change the zoom or position of the photo.

#### Scenario: Default position
- **WHEN** the user opens a photo they have not adjusted
- **THEN** the slider is shown at the default strength, as a percentage

#### Scenario: Less correction
- **WHEN** the user moves the slider to 0%
- **THEN** the viewer shows the photo with its original colours

#### Scenario: Video with its controls
- **WHEN** the user opens a video
- **THEN** the playback controls are shown at the bottom, directly above the strength panel and clear of the status bar, with a white time bar and the elapsed and total time, so the two sliders cannot be mistaken for each other

### Requirement: Strength per photo
Each photo and each video SHALL keep its own strength while it is in the working set: swiping to another item shows that item's strength, and returning shows the value set before. "Reset" SHALL return the item shown to the default strength. "Apply to all" SHALL set every photo and video in the working set to the strength of the item shown. An item removed from the working set and added again SHALL start at the default.

#### Scenario: Strength stays with its photo
- **WHEN** the user sets photo 2 to 40%, swipes to photo 3, and swipes back
- **THEN** photo 3 shows the default strength and photo 2 shows 40% again

#### Scenario: Apply to all
- **WHEN** the user sets a photo to 60% and chooses "Apply to all"
- **THEN** every photo in the working set is at 60%, in the viewer, the thumbnails and the export

#### Scenario: Reset
- **WHEN** the user chooses "Reset" on a photo set to 40%
- **THEN** the photo returns to the default strength

#### Scenario: Strength of a video
- **WHEN** the user sets a video to 50% while it plays
- **THEN** the playing video changes to 50% without stopping, its thumbnail follows, and the whole clip is exported at 50%
