# Spec Delta

## Purpose

Decides what the free version allows and how a user gets the full version: a daily export limit and a smaller working set for free, a one-time Google Play unlock that removes them, and optional donations on top.

## ADDED Requirements

### Requirement: Daily export limit
In the free version the app SHALL export at most 10 items per day, where a photo and a video count as 1 each whatever their size or length, and only successful exports count. The day SHALL be the phone's local calendar day: the count starts again at midnight. The full version SHALL have no daily limit. The count SHALL be stored only on the phone.

#### Scenario: Ten in one day
- **WHEN** a free user exports 6 photos and later the same day 4 videos
- **THEN** all 10 are exported, and no export is left for that day

#### Scenario: Next day
- **WHEN** a free user used all 10 exports yesterday and exports a photo today
- **THEN** the photo is exported, and 9 exports are left for today

#### Scenario: Failed exports do not count
- **WHEN** a free user exports 3 photos and one fails for lack of storage
- **THEN** 2 exports count toward the day's limit

#### Scenario: Full version
- **WHEN** a user with the full version exports 20 items, then 20 more the same day
- **THEN** all 40 are exported

### Requirement: Partial export at the daily limit
When a free user starts an export with more items than are left for the day, the app SHALL export the items that fit, in working-set order, and SHALL NOT export the rest. The summary SHALL say how many items were not exported because of the daily limit, that the limit is 10 exports per day and starts again at midnight, and SHALL offer the full version. A batch still running at midnight SHALL use the new day's exports for its remaining items.

#### Scenario: Four left, ten selected
- **WHEN** a free user with 4 exports left today exports 10 items
- **THEN** the first 4 items are exported, the other 6 are marked not exported, and the summary explains the daily limit, the reset at midnight and offers the full version

#### Scenario: Nothing left
- **WHEN** a free user with no exports left today taps Export
- **THEN** no export starts, and a message explains the daily limit, the reset at midnight and offers the full version

### Requirement: Remaining exports shown
In the free version the start screen SHALL show how many exports are left today while items are selected. The full version SHALL NOT show a count.

#### Scenario: Count after exporting
- **WHEN** a free user exports 3 items and returns to the start screen with items selected
- **THEN** the screen says 7 of 10 free exports are left today

### Requirement: Full version purchase
The app SHALL offer the full version as a one-time purchase through Google Play, showing the price Google Play returns in the user's currency. A completed purchase SHALL unlock the full version immediately and permanently for that Google account, and the app SHALL confirm it to Google Play so it is not refunded automatically. A cancelled purchase SHALL change nothing.

#### Scenario: Buy the full version
- **WHEN** a free user taps Full version, sees the price and completes the purchase in Google Play
- **THEN** the app thanks the user, the daily limit is gone and up to 20 items can be selected

#### Scenario: Cancel the purchase
- **WHEN** the user opens the purchase and closes Google Play's sheet without paying
- **THEN** the app stays in the free version and shows no error

#### Scenario: Google Play unavailable
- **WHEN** the user taps Full version on a phone where Google Play cannot be reached
- **THEN** the app says the purchase is not available right now and stays in the free version

### Requirement: Pending payment
While Google Play reports a full-version payment as pending, the app SHALL stay in the free version and the Full version entry SHALL say the purchase is waiting for payment. The app SHALL unlock when Google Play reports the payment completed, including after a restart.

#### Scenario: Pay later
- **WHEN** the user chooses a payment method that completes later
- **THEN** the app stays in the free version and the Full version entry says the purchase is waiting for payment

#### Scenario: Payment completes
- **WHEN** the pending payment completes while the app is closed, and the user opens the app
- **THEN** the full version is active

### Requirement: Restore the full version
The app SHALL ask Google Play for the user's purchases when it starts and when it returns to the foreground, and SHALL restore the full version from a purchase made earlier on any phone with the same Google account, without charging again. The Full version entry SHALL offer "Restore purchase" to ask again on demand. The app SHALL remember the full version on the phone, so it stays unlocked without a network connection.

#### Scenario: Reinstall
- **WHEN** a user who bought the full version reinstalls the app, or installs it on a new phone with the same Google account, and opens it
- **THEN** the full version is active without paying again

#### Scenario: Offline
- **WHEN** a user with the full version opens the app in flight mode
- **THEN** the full version is active

### Requirement: Losing the full version
When Google Play reports that the user no longer owns the full version (for example after a refund or a chargeback), the app SHALL return to the free version. Items already in the working set SHALL stay, no item SHALL be added while the set holds 10 or more, and exports SHALL follow the daily limit from then on.

#### Scenario: Refund with 20 items selected
- **WHEN** a user with 20 items selected comes back to the app after the purchase was refunded
- **THEN** the 20 items stay, adding items is unavailable with a message explaining the free limit of 10, and the export follows the daily limit

### Requirement: Donations
The Support DiveLens section SHALL show a Donate entry. For a free user, Donate SHALL first explain that buying the full version supports DiveLens and offer it, and SHALL say that donations are possible after that. For a user with the full version, Donate SHALL offer three donation amounts, priced by Google Play in the user's currency, which can be given any number of times. A donation SHALL unlock nothing.

#### Scenario: Free user taps Donate
- **WHEN** a free user taps Donate
- **THEN** the app offers the full version first, says donations are possible after buying it, and shows no donation amounts

#### Scenario: Paid user donates
- **WHEN** a user with the full version taps Donate, picks an amount and pays
- **THEN** the app thanks the user, and the same amount can be donated again later

### Requirement: Full version entry
The Support DiveLens section SHALL show a Full version entry. In the free version it SHALL show the price and what the full version adds (no daily limit, 20 items at a time). In the full version it SHALL say the full version is active.

#### Scenario: Free version
- **WHEN** a free user opens Settings & help
- **THEN** the Full version entry shows the price and says it removes the daily limit and allows 20 items at a time

#### Scenario: Full version active
- **WHEN** a user with the full version opens Settings & help
- **THEN** the Full version entry says the full version is active and offers no purchase

### Requirement: Builds without Google Play
Builds made for development, including those built by anyone from the public source code, SHALL work without Google Play and without any account, key or secret: they SHALL use a simulated store that lets the developer buy, refund, leave a payment pending and change the day's count. Builds published on Google Play SHALL use Google Play and SHALL NOT contain the simulated store.

#### Scenario: Contributor build
- **WHEN** someone builds the debug app from the public repository without any private file and taps Full version
- **THEN** a simulated purchase opens, and choosing to buy unlocks the full version on that phone

#### Scenario: Published build
- **WHEN** the release app is inspected
- **THEN** it contains no simulated store or developer panel

### Requirement: Licensing stays on the device
The licence check SHALL NOT send photos, videos or their metadata anywhere. The only data stored for licensing SHALL be the full-version state and the day's export count, on the phone.

#### Scenario: Export in the free version
- **WHEN** a free user exports a photo
- **THEN** nothing about the photo leaves the phone, and only the day's count changes
