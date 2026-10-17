# Spec Delta

## ADDED Requirements

### Requirement: Exports are corrected
Every export SHALL pass the photo's pixels through the app's automatic colour correction (see the auto-correction capability) before encoding. The correction SHALL change only the exported copy, never the original, and SHALL keep the photo's pixel dimensions.

#### Scenario: Exported photo is corrected
- **WHEN** the user exports an underwater photo with a blue-green cast
- **THEN** the export in the gallery is colour-corrected, and the original file is byte-for-byte unchanged

## REMOVED Requirements

### Requirement: Processing applies to exported pixels only
**Reason**: Exports no longer leave pixels unchanged. The processing step now applies the automatic colour correction.
**Migration**: Replaced by "Exports are corrected" in this capability and by the auto-correction capability.
