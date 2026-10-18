# Spec Delta

## ADDED Requirements

### Requirement: Working-set limit
The working set SHALL hold at most 50 photos. A selection that would exceed the limit SHALL add photos in the order the picker returned them until the limit is reached and tell the user how many were left out and that the limit is 50. While the working set is full, selecting more photos SHALL be unavailable, with a message explaining the limit.

#### Scenario: Selection exceeds the limit
- **WHEN** the working set holds 45 photos and the user selects 8 more
- **THEN** the first 5 selected photos are added, and a message says 3 photos were left out because the limit is 50 photos

#### Scenario: Working set is full
- **WHEN** the working set holds 50 photos
- **THEN** selecting more photos is unavailable and the screen explains the limit, until a photo is removed or the set is cleared

## MODIFIED Requirements

### Requirement: Back navigation
The system back action SHALL return to the start screen when photos are selected, SHALL ask for confirmation before cancelling a running export, and SHALL leave the app only from the start screen. In the photo viewer, Back SHALL return to the working-set grid without changing the working set.

#### Scenario: Back with photos selected
- **WHEN** the working set has photos and no export is running, and the user presses Back
- **THEN** the working set is cleared and the start screen is shown, without leaving the app

#### Scenario: Back during an export
- **WHEN** an export is running and the user presses Back
- **THEN** the app asks whether to cancel the export; choosing to keep exporting continues the batch, and choosing to cancel behaves like the Cancel button

#### Scenario: Back on the start screen
- **WHEN** the working set is empty and the user presses Back
- **THEN** the app closes as usual

#### Scenario: Back in the viewer
- **WHEN** the user is viewing a photo full screen and presses Back
- **THEN** the working-set grid is shown with the same photos, and nothing is cleared or cancelled
