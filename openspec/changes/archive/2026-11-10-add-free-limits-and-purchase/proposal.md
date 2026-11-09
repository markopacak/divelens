# Proposal

## Why

DiveLens is meant to be paid, but today every export is free and unlimited: the export check from add-import-export always allows. The maintainer decided on 2026-10-28 on a free version that stays useful forever, with a cheap one-time unlock. The free version has 10 exports a day and holds 10 items at a time. The unlock costs 1.19 CHF / 1.19 EUR. This is the last planned change before the Android release, and Google Play needs billing in place before the app goes to testers.

## What Changes

- **Free version limits:**
  - 10 exports per local calendar day; a photo and a video count as 1 each, and the count resets at midnight;
  - a working set of at most 10 items, instead of 20 in the full version;
  - the counter is stored only on the phone, so reinstalling or changing the clock can reset it (accepted).
- **Partial exports:** when an export has more items than are left for the day, the items that fit are exported in working-set order. The rest are not exported, and the summary explains why: the daily limit, that it resets at midnight, and that the full version removes it. With nothing left for the day, Export shows that explanation and starts nothing.
- **Remaining exports:** in the free version, the start screen shows how many exports are left today.
- **Limit messages:** the existing "a batch holds at most N items" messages use the current limit. In the free version they say that the full version holds 20 and offer the unlock.
- **Full version unlock:** a one-time Google Play purchase (non-consumable) that removes the daily limit and raises the working set to 20.
  - The price comes from Play, in the user's currency.
  - The purchase is restored from Play on a reinstall or a new phone, and nobody pays twice.
  - Pending payments don't unlock until they're paid.
  - Every purchase is acknowledged to Play, so Play doesn't refund it automatically after 3 days.
  - The app remembers the unlock, so it keeps working offline, for example on a dive trip.
- **Lost unlock:** if Play later reports that the unlock is gone (refund or chargeback), the app returns to the free version. Items already selected stay, but no more can be added above 10, and exports follow the daily limit at once.
- **Support DiveLens section:** two new entries in Settings & help.
  - **Full version:** shows the price and buys the unlock, or says the full version is active. A "Restore purchase" action asks Play again.
  - **Donate:** a free user is first offered the full version; a paid user picks one of three tips (consumable Play products), any number of times.
- **Builds without Google Play:** debug builds, which open-source contributors make, use a fake store with no Google code involved. A debug-only panel can simulate buying, pending payments, refunds and the daily count. Release builds use Google Play Billing. The fake store and its panel are not in release builds.
- **Docs:**
  - `docs/TERMS.md` describes the free version's limits;
  - `README.md` describes the status, the Play Console product IDs and how to test billing;
  - `openspec/config.yaml` renames planned change 8 to add-free-limits-and-purchase.

Not in this change:
- the Play Console account, the store listing, closed testing and the public release, which are steps the maintainer does in Play Console;
- subscriptions, promo codes, or a server that checks purchases (purchases are checked on the phone, as `config.yaml` decides);
- an F-Droid or other non-Play distribution.

## Capabilities

### New Capabilities
- `licensing`: the free version and the full version.
  - the daily export limit, with partial exports and the remaining count;
  - the unlock purchase, its restore, pending payments and acknowledgement;
  - losing the unlock;
  - donations;
  - the builds without Google Play.

### Modified Capabilities
- `import-export`:
  - "Working-set limit" now depends on the version (10 free, 20 full), and an unlock lost while over 10 keeps the items;
  - "Export permission check" stops allowing every export and follows the daily limit.

## Impact

- **Dependencies:**
  - Google Play Billing Library (`billing-ktx`), release builds only. It is covered by the GPL section 7 exception already in `LICENSE`/`NOTICE`.
  - It adds the `com.android.vending.BILLING` permission. The merged release manifest is checked, so the privacy claims stay true ("Photos and videos never leave your device"; no "no network permission" claim is made).
- **Code:**
  - a new `licensing` package: the daily counter, the licence state, the store interface with Play and fake implementations, and the real `ExportGate` replacing `AlwaysAllowExportGate` in `AppGraph`;
  - `WorkingSet` takes its limit from the licence;
  - the start screen gets the remaining count, the limit messages and the daily-limit summary;
  - `SettingsHelpScreen` gets the Full version and Donate entries;
  - a debug-only panel.
- **Resources:** new strings for the limits, the purchase states and donations.
- **Tests:**
  - JVM tests for the counter (with an injected clock, including midnight), the gate, partial batches, the limit per version and the purchase states (fake store);
  - instrumented UI tests for the messages, the Support entries and the Donate flow on the fake store;
  - existing export tests run in the full version or with the always-allow gate.
- **Play Console (maintainer, outside the repo):**
  - one-time product `full_version` at 1.19 CHF / 1.19 EUR;
  - consumable tips `tip_small`, `tip_medium`, `tip_large`;
  - a licence-tester account.
  - Real purchases can only be tried once a signed build is on the internal testing track. Until the account exists, all work runs on the fake store.
- **Privacy:** no change for photos and videos. Purchases go through Google Play, as `docs/PRIVACY.md` already says. The app stores only the unlock flag and the day's count, on the phone.
