# Spec Delta

## ADDED Requirements

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
