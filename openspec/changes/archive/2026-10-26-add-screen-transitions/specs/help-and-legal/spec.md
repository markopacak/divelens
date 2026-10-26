# Spec Delta

## ADDED Requirements

### Requirement: Page transitions
Opening the Settings & help screen SHALL slide it in from the right while the start screen moves a little to the left and fades, and going back SHALL reverse it. The legal pages opened from the screen SHALL open and close with the same slide. Each transition SHALL take at most 300 ms and SHALL run the same way for the system Back, the back gesture and the on-screen back arrow. When the system setting to remove animations is on, the screens SHALL switch without a transition.

#### Scenario: Slide in and out
- **WHEN** the user taps the settings icon, and later taps the back arrow
- **THEN** the Settings & help screen slides in from the right, and slides out to the right, revealing the start screen

#### Scenario: Legal page slides
- **WHEN** the user opens the privacy policy from About and presses the system Back
- **THEN** the page slides in from the right and slides out again, showing the Settings & help screen

#### Scenario: Animations removed
- **WHEN** the system setting to remove animations is on and the user opens Settings & help
- **THEN** the screen appears without a transition

### Requirement: Scroll position kept when going back
Going back from the Settings & help screen SHALL show the start screen's grid at the scroll position it had, and going back from a legal page SHALL show the Settings & help screen at the scroll position it had.

#### Scenario: Grid where it was
- **WHEN** the user scrolls the grid down, opens Settings & help and goes back
- **THEN** the grid shows the same items as before opening Settings & help

#### Scenario: Settings list where it was
- **WHEN** the user scrolls down to About, opens the privacy policy and goes back
- **THEN** the About entries are still on screen without scrolling
