# Spec Delta

## ADDED Requirements

### Requirement: Watermark setting
The Settings section SHALL show a "Watermark on exports" switch with a one-line explanation. It SHALL be on for a new installation, available to every user whether or not they paid, and remembered across restarts. The value in place when an export starts SHALL apply to every item of that batch; a change takes effect from the next export.

#### Scenario: First start
- **WHEN** the user opens Settings & help for the first time after installing
- **THEN** the screen starts with the Settings section, and its "Watermark on exports" switch is on

#### Scenario: Turn it off
- **WHEN** the user turns the switch off, closes the app, reopens it and exports a photo
- **THEN** the switch is still off and the export has no watermark

#### Scenario: Free users can turn it off
- **WHEN** a user who has not bought the unlock turns the switch off
- **THEN** the switch turns off and exports have no watermark, with no purchase prompt

#### Scenario: Change during an export
- **WHEN** an export of five items is running with the watermark on and the user turns the switch off after the second item
- **THEN** all five exports have the watermark, and the next export has none
