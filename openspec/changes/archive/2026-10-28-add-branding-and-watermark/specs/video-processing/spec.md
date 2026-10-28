# Spec Delta

## MODIFIED Requirements

### Requirement: Preview matches the video export
At every moment of a video, the viewer SHALL apply the same correction and strength as the export, once the clip is analysed. The exported frames SHALL differ from the previewed frames only by video encoding and, when the watermark setting is on, by the watermark in the bottom-right corner (see the branding capability).

#### Scenario: Export what was watched
- **WHEN** the user watches a clip at 40% strength and exports it
- **THEN** the exported clip shows the same colours at each moment as the viewer did

#### Scenario: Watermark is the only addition
- **WHEN** the user watches a clip and exports it with the watermark on
- **THEN** outside the watermark's area, the exported clip shows the same colours at each moment as the viewer did
