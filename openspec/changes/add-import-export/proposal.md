# Proposal

## Why

Every later feature (auto-correction, compare, sliders, video, licensing) needs a way to get the user's photos into the app and corrected copies back out at full quality. Building this end to end first, with no correction yet, proves the hard parts early: full-resolution decoding on a phone, keeping the metadata that matters (date taken, camera, orientation) intact, and writing to the gallery on Android 10+. It also gives us an installable build to upload to Play's internal testing track, which billing work later depends on.

## What Changes

- Import one or many photos at once through the system photo picker (batch selection is in v1).
- Show the selected photos in the app as thumbnails, with the option to remove items before exporting.
- Export every selected photo at full resolution as a **new** file in the device gallery. Originals are never modified or deleted.
- Preserve the source metadata on each export, including date taken, camera and lens data, and orientation, so exports sort and display correctly in gallery apps.
- Exports carry **no GPS location** in v1. The system photo picker removes location from the photos it hands to apps, and getting it back would require a broad "access photos and videos" permission prompt. Originals keep their location.
- Show export progress for a batch, allow cancelling it, and report which items succeeded or failed. One failed item does not stop the rest.
- Route every export through a processing step that, in this change, leaves pixels unchanged. Change 2 (`add-auto-correction`) plugs the correction pipeline into that step.
- Provide a single check before export that decides whether an export is allowed. In this change it always allows; change 6 (`add-trial-and-purchase`) adds the N = 3 trial counter and the unlock behind it.
- Videos are not importable yet: the picker offers photos only. The import model already distinguishes photo and video so change 5 can add video without reshaping it.

## Capabilities

### New Capabilities

- `import-export`: Getting photos (and later videos) into the app from the device and writing processed copies back to the device gallery at full resolution, with metadata preserved and originals untouched.

### Modified Capabilities

None (no existing specs).

## Impact

- **Code:** replaces the template `MainActivity` greeting with an import/export screen. New import, export and processing-step code in `:app`.
- **Dependencies:** adds Coil (thumbnails), androidx.exifinterface (metadata), and a ViewModel/lifecycle-compose artifact; updates the outdated template versions of core-ktx, lifecycle and activity-compose. All Apache-2.0, compatible with the project license.
- **Permissions:** none. The photo picker needs no permission and writing the app's own gallery files needs none on Android 10+. No internet permission.
- **Release:** the result is the first build suitable for the Play internal testing track.
- **Out of scope:** applying any color correction, before/after compare, sliders, video import/export, the trial counter and purchases, "Open with" or share-sheet import from other apps, RAW files.
