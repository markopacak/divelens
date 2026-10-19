# Spec Delta

## MODIFIED Requirements

### Requirement: Correction strength
The system SHALL apply the correction at a strength between 0 (original colours) and 1 (full correction): each pixel's colour change scales linearly with the strength, limited only by the available range. Each export SHALL use the photo's strength, which is the app's default strength unless the user changed it (see the manual-adjustment spec).

#### Scenario: Default strength
- **WHEN** the user exports a photo without changing its strength
- **THEN** the export is corrected at the default strength

#### Scenario: Strength chosen by the user
- **WHEN** the user sets a photo to 40% and exports it
- **THEN** the export is corrected at strength 0.4

#### Scenario: Zero strength
- **WHEN** a photo is corrected at strength 0
- **THEN** the corrected pixels equal the original pixels

#### Scenario: Export at zero strength
- **WHEN** the user sets a photo to 0% and exports it
- **THEN** the export has the original's pixels, apart from JPEG encoding, and no correction is computed or applied for it
