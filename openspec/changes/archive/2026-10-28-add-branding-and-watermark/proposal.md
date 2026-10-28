# Proposal

## Why

DiveLens still shows Android Studio's default icon and the template's purple colours, and its exports carry nothing that says where they came from. A logo now exists (`docs/brand/`, with `BRAND.md` as the source of truth). Google Play needs a real icon before the internal testing track that add-trial-and-purchase depends on, and a small, discreet mark on shared photos and videos lets people find the app. The mark must never be a paywall lever: everyone, free or paid, can turn it off.

## What Changes

- **App icon:** the adaptive launcher icon (normal and round) built from the brand layers. The ring and coral dot sit on Deep Ocean, with a monochrome layer for Android's themed icons. The symbol is drawn at 95 % around the centre so it stays inside Android's guaranteed 66 dp safe zone; the brand SVGs stay unchanged. The old raster launcher images are removed (minSdk 29 only needs the adaptive icon).
- **Notification icon:** the export notification shows the brand symbol instead of the generic download arrow.
- **Splash:** on Android 12+ the system launch screen shows the icon on Deep Ocean instead of white.
- **Colours:** the app uses the brand palette in light and dark mode. Android's wallpaper-based dynamic colour is turned off, so the palette shows on every phone.
- **Watermark on exports:** a small diveLENS mark in the bottom-right corner of every exported photo and video, following `BRAND.md`:
  - 12 % of the picture's width, 3 % margin, 92 % opacity;
  - the light (white) version, or the dark (navy) version where the corner is bright;
  - a video uses one version for the whole clip;
  - it is placed as the photo or video is seen, whatever its stored rotation;
  - it applies at any strength, including 0 %.
  - The viewer and the grid do not show it; it is only in the exported file.
- **Watermark setting:** the Settings section appears on the Settings & help screen with one switch, "Watermark on exports". It is on by default and available to everyone. The value in place when an export starts applies to the whole batch.
- **The brightness threshold** that picks the dark version starts at 0.60 and is tuned during this change on real photos from the local, gitignored `trainingset/`. The final value goes into the code and `BRAND.md`.
- **Brand folder:** `docs/brand/` is committed as the source of the assets. `BRAND.md`'s safe-zone sentence is corrected: the guaranteed area is a circle of radius 33, not 36.
- **Project config:** `openspec/config.yaml` lists this change as 7 (capability `branding`) and add-trial-and-purchase as 8. "No watermarking" in the trial decision is replaced by: the watermark is not tied to the trial, and anyone can turn it off.

Not in this change: a choice of watermark position, size or a custom text; a watermark preview in the viewer; store graphics other than the 512 px Play icon (feature graphic, screenshots), which come with the Play listing.

## Capabilities

### New Capabilities
- `branding`: the app's visual identity, meaning its launcher, themed and notification icons, the colour palette, and the watermark on exported photos and videos.

### Modified Capabilities
- `help-and-legal`: adds the Watermark setting requirement (the first setting, so the Settings section now shows).
- `video-processing`: Preview matches the video export now allows the watermark as the one intended difference besides encoding.

## Impact

- **Resources:**
  - new vector drawables for the icon layers, the notification icon and the two watermark versions;
  - `mipmap-anydpi` adaptive icons with a monochrome layer;
  - the `mipmap-*dpi` webp files removed;
  - brand colours in `values/colors.xml` (template purples removed);
  - a `values-v31` theme for the splash background;
  - new strings for the setting.
- **Code:**
  - `ui/theme/Color.kt` and `Theme.kt` get the brand palette, with dynamic colour off;
  - a new watermark component (placement, version choice, drawing), used by `MediaStorePhotoWriter` after the correction and by `MediaStoreVideoWriter` as a Media3 overlay effect after the correction effect;
  - `AndroidTimelineSampler` keeps a small corner crop per sampled frame for the clip's version choice;
  - a watermark preference next to `HelpPrefs` in the app's preferences file, wired in `AppGraph`;
  - `SettingsHelpScreen` gets its first `settings` content.
- **Tests:**
  - JVM tests for placement (all EXIF orientations, video rotations), version choice and the threshold;
  - instrumented tests that export a photo and a video with the setting on and off, and check the corner pixels, dimensions and orientation;
  - a Settings switch test;
  - existing export tests either set the watermark off or allow it.
- **Docs:** README status, `docs/brand/BRAND.md` (safe zone, final threshold), `openspec/config.yaml`.
- **Dependencies and permissions:** none. The overlay uses Media3 effect classes already in the app.
- **Privacy:** unaffected. The mark is drawn on the device and adds no metadata.
- **Licence:** the wordmark is Inter outlines (SIL OFL), fine in a GPL-3.0 app. NOTICE gets a line crediting Inter.
