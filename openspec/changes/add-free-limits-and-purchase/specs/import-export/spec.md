# Spec Delta

## MODIFIED Requirements

### Requirement: Working-set limit
The working set SHALL hold at most 20 items in the full version and at most 10 items in the free version, photos and videos together (see the licensing capability). A selection that would exceed the limit SHALL add items in the order the picker returned them until the limit is reached and tell the user how many were left out and what the limit is; in the free version the message SHALL also say that the full version holds 20 items and offer it. While the working set is at or above the limit, selecting more items SHALL be unavailable, with a message explaining the limit. Items already in the working set SHALL never be removed because the limit changed.

#### Scenario: Selection exceeds the limit
- **WHEN** a user with the full version has 15 photos in the working set and selects 8 more
- **THEN** the first 5 selected photos are added, and a message says 3 photos were left out because the limit is 20 items

#### Scenario: Videos count towards the limit
- **WHEN** a user with the full version has 18 photos in the working set and selects 3 videos
- **THEN** the first 2 videos are added and a message says 1 item was left out

#### Scenario: Working set is full
- **WHEN** a user with the full version has 20 photos in the working set
- **THEN** selecting more photos is unavailable and the screen explains the limit, until a photo is removed or the set is cleared

#### Scenario: Free version limit
- **WHEN** a free user has 7 photos in the working set and selects 5 more
- **THEN** the first 3 are added, and a message says 2 items were left out because the free version holds 10 items, that the full version holds 20, and offers it

#### Scenario: Limit drops below the working set
- **WHEN** a user has 20 items in the working set and the app returns to the free version
- **THEN** all 20 items stay and can be exported, and selecting more is unavailable with a message explaining the free limit of 10

### Requirement: Export permission check
Before writing each export, the system SHALL check whether that export is allowed by the licence (see the licensing capability): in the full version every export is allowed, and in the free version an export is allowed while the day's limit is not reached. A refused export SHALL NOT be written, SHALL leave the item in the working set, and SHALL be reported to the user with the reason.

#### Scenario: Export allowed
- **WHEN** the user exports a photo and the check allows it
- **THEN** the photo is exported normally

#### Scenario: Export refused
- **WHEN** a free user with no exports left today has an item in an export
- **THEN** that item is not written, stays in the working set marked not exported, and the summary gives the daily limit as the reason
