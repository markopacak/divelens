# auto-correction Specification

## Purpose

Automatically corrects the colours of underwater photos: rebuilds the red that water absorbs, removes the blue-green cast and restores contrast, estimated per photo on the device.

## Requirements

### Requirement: Automatic correction per photo
The system SHALL correct each photo automatically, with no user input, using parameters estimated from that photo's own colours. Different photos SHALL get different corrections when their colour casts differ.

#### Scenario: Two photos with different casts
- **WHEN** the user exports a deep blue open-water photo and a shallow green reef photo in one batch
- **THEN** each export is corrected with parameters estimated from its own photo, not shared between them

### Requirement: Red recovery scales with missing red
The system SHALL increase red more in photos that have lost more red relative to green. In photos that have lost little red, the system SHALL add little red.

#### Scenario: Deep photo with almost no red
- **WHEN** a photo's average red is less than a fifth of its average green
- **THEN** the corrected photo's average red is substantially higher than the original's, and the blue-green cast is visibly reduced

#### Scenario: Shallow photo with most red intact
- **WHEN** a photo's average red is more than two thirds of its average green
- **THEN** the corrected photo's average red rises only slightly

### Requirement: No red tint in dark open water
The correction SHALL NOT turn dark, flat blue areas (open water, shadows, a dark sea floor) red, magenta or purple, and SHALL NOT amplify noise in the red channel of such areas. Where an area comes out dark and its red was below its green, the area's average red SHALL NOT end up above its average green.

#### Scenario: Open blue water background
- **WHEN** the user exports a photo whose background is dark, featureless blue water
- **THEN** that background stays blue or blue-green in the export, without red or magenta blotches

#### Scenario: Dark sea floor in blue water
- **WHEN** a photo of blue water has a dark area at the bottom that the correction makes darker
- **THEN** that area stays dark blue or navy, with its average red not above its average green

### Requirement: Bright open water is not turned black
When a photo has no dark areas, such as bright open water, the correction SHALL NOT treat its darkest colours as black. Green and blue values above 45% of the range SHALL keep a visible level after correction.

#### Scenario: Photo of only bright blue water
- **WHEN** the user exports a photo whose green and blue values all lie in the upper half of the range
- **THEN** even its darkest areas keep visible colour in the export instead of turning black

### Requirement: Grain in flat areas is not amplified
The correction SHALL NOT noticeably amplify grain in flat areas such as open water: at any strength, the pixel-level noise of an area of uniform colour SHALL grow by at most 1.5× per channel, even where the contrast stretch is larger. Edges and detail SHALL stay as sharp as in the original.

#### Scenario: Grainy open water
- **WHEN** the user exports a photo of uniform, grainy blue water with low contrast
- **THEN** the water's grain in the export is at most 1.5× the original's in each channel, while its colour is corrected

### Requirement: Limited contrast stretch
The system SHALL stretch green and blue contrast to use the available range, but SHALL stretch each channel by at most a factor of 3 on low-contrast photos.

#### Scenario: Very flat, hazy photo
- **WHEN** a photo's green and blue values span only a narrow range
- **THEN** the export has more contrast than the original, with its green and blue ranges stretched by at most 3×

### Requirement: Correction changes colours only
The correction SHALL change colours only. It SHALL NOT crop, rotate, resize, sharpen or vignette the photo. Its colour change MAY follow the photo's content smoothly, so that flat areas get an averaged change and grain is not amplified, but it SHALL NOT depend on where in the frame something is: the same colours in the same surroundings SHALL produce the same output anywhere in the photo.

#### Scenario: Same subject in different places
- **WHEN** a photo contains the same patch of colour, with the same surroundings, in its top-left and bottom-right areas
- **THEN** both patches have the same colour in the export, and the export has the original's pixel dimensions

#### Scenario: No visible seams on large photos
- **WHEN** the user exports a 50-megapixel photo
- **THEN** the export shows no seams, bands or tile edges

### Requirement: Correction strength
The system SHALL apply the correction at a strength between 0 (original colours) and 1 (full correction): each pixel's colour change scales linearly with the strength, limited only by the available range. Every export SHALL use the app's default strength.

#### Scenario: Default strength
- **WHEN** the user exports a photo
- **THEN** the export is corrected at the default strength

#### Scenario: Zero strength
- **WHEN** a photo is corrected at strength 0
- **THEN** the corrected pixels equal the original pixels

### Requirement: Photos without an underwater cast change little
A photo without a colour cast, such as a photo taken on land, SHALL change only slightly: at the default strength, the mean colour difference (CIE76 ΔE) between the original and the export SHALL be at most 3.

#### Scenario: Neutral grey photo
- **WHEN** the user exports a photo of neutral greys from black to white
- **THEN** the mean ΔE between original and export is at most 3, and the greys stay neutral

### Requirement: Deterministic result
Correcting the same photo SHALL always produce the same result, on every export and every supported device. The only allowed difference between devices is the rounding of the final 8-bit values.

#### Scenario: Export twice
- **WHEN** the user exports the same photo twice
- **THEN** both exports have the same colours, apart from JPEG encoding

#### Scenario: Graphics hardware unavailable
- **WHEN** the device's graphics processor cannot be used for correction
- **THEN** the photo is still exported with the same correction, at most 1 step out of 255 different per channel from a GPU-corrected export

### Requirement: Quality against the reference set
On the project's reference set of underwater photos and video frames with RefApp results, the correction SHALL reach a mean CIE76 ΔE to the RefApp results of at most 6.5 when the strength is matched per image, and at most 9.0 at the default strength. Every reference scene SHALL be closer to its RefApp result than the uncorrected original is.

#### Scenario: Reference check
- **WHEN** the reference check runs on a machine that has the reference set
- **THEN** it reports the mean ΔE for both cases and each scene's ΔE, and passes only if all limits hold

#### Scenario: Reference set absent
- **WHEN** the reference check runs on a machine without the reference set, such as a contributor's machine or CI
- **THEN** it is skipped and does not fail the build

### Requirement: Correction runs on the device
Analysis and correction SHALL run entirely on the device. Photos, their pixels and their estimated parameters SHALL NOT be sent off the device.

#### Scenario: Offline correction
- **WHEN** the device has no network connection
- **THEN** photos are corrected and exported normally
