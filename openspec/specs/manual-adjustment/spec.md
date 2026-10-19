# manual-adjustment Specification

## Purpose

Lets the user adjust the automatic correction of each photo before export, starting with its strength, and shows the result in the viewer, the thumbnails and the export.

## Requirements

### Requirement: Strength slider
The full-screen viewer SHALL offer a strength slider for the photo shown, in a panel the user can open and close. The slider SHALL range from 0% (original colours) to 100% (full correction), SHALL start at the automatic default strength, and SHALL mark the default's position. Opening, closing or moving the slider SHALL NOT change the zoom or position of the photo.

#### Scenario: Default position
- **WHEN** the user opens the panel for a photo they have not adjusted
- **THEN** the slider is at the default strength, shown as a percentage

#### Scenario: Less correction
- **WHEN** the user moves the slider to 0%
- **THEN** the viewer shows the photo with its original colours

### Requirement: Strength per photo
Each photo SHALL keep its own strength while it is in the working set: swiping to another photo shows that photo's strength, and returning shows the value set before. "Reset" SHALL return the photo shown to the default strength. "Apply to all" SHALL set every photo in the working set to the strength of the photo shown. A photo removed from the working set and added again SHALL start at the default.

#### Scenario: Strength stays with its photo
- **WHEN** the user sets photo 2 to 40%, swipes to photo 3, and swipes back
- **THEN** photo 3 shows the default strength and photo 2 shows 40% again

#### Scenario: Apply to all
- **WHEN** the user sets a photo to 60% and chooses "Apply to all"
- **THEN** every photo in the working set is at 60%, in the viewer, the thumbnails and the export

#### Scenario: Reset
- **WHEN** the user chooses "Reset" on a photo set to 40%
- **THEN** the photo returns to the default strength

### Requirement: Adjustments are shown everywhere
A changed strength SHALL be shown in the viewer while adjusting, in the photo's thumbnail once it is re-rendered, and in the export. The viewer at 100% zoom SHALL still match the export of that photo at its strength, as the preview-compare spec requires. Hold to see the original SHALL keep working while the panel is open.

#### Scenario: Thumbnail follows the slider
- **WHEN** the user sets a photo to 20% and returns to the grid
- **THEN** that photo's thumbnail shows the photo at 20%

#### Scenario: Compare while adjusting
- **WHEN** the panel is open and the user holds a finger on the photo
- **THEN** the original is shown until the finger is lifted, and the strength is unchanged

### Requirement: Adjustment responsiveness
On the reference device (Xiaomi 14T), the viewer SHALL show a strength change at screen resolution within 100 ms while the slider is dragged. It SHALL show full detail at the current zoom within 0.5 s after release. No adjustment SHALL decode the photo again or re-estimate its correction.

#### Scenario: Drag the slider on a 20-megapixel photo
- **WHEN** the user drags the slider across its range on a 20-megapixel photo
- **THEN** the photo follows the slider without visible lag, and full detail returns within 0.5 s of releasing it
