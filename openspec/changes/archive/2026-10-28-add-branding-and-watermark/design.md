# Design

## Context

See proposal.md (Why) and the delta specs for the behaviour. The current state that shapes the approach:

- **Brand assets:** `docs/brand/` holds plain SVGs (paths, `fill-opacity`, group `translate`; no filters, gradients or text), `colors.xml` and `BRAND.md`.
  - **Icon layers:** 108 × 108 viewBox; the ring is centred at (49, 59) with radii 24 and 16, the dot at (74, 34) with radius 6. The dot's far edge is 34.3 from the centre, outside Android's guaranteed 33.
  - **Watermark:** viewBox `-3 -3 232.5 63` (aspect 3.69 : 1). The shadow or halo is an offset copy at 35–45 % opacity under the main shape.
- **App resources:** the default template icon, i.e. `drawable/ic_launcher_foreground.xml` and `_background.xml`, `mipmap-anydpi/ic_launcher*.xml` whose monochrome layer is the foreground, and webp fallbacks in `mipmap-*dpi`. `values/colors.xml` holds template purples; `values/themes.xml` is `Theme.DiveLens` → `android:Theme.Material.Light.NoActionBar`. `ui/theme` is the Compose template with `dynamicColor = true`.
- **Photo export:** `MediaStorePhotoWriter` decodes the full-resolution bitmap in stored orientation, runs the `PhotoProcessor` (correction; returns the input unchanged at strength 0), encodes the result and copies the EXIF orientation tag.
- **Video export:** `MediaStoreVideoWriter` runs Media3 Transformer with `Effects(emptyList(), listOf(CorrectionEffect.forExport(timeline, strength)))`, and `CleanMp4Muxer` writes the display rotation.
- **Video sampling:** `AndroidTimelineSampler` takes `getScaledFrameAtTime` frames (small, already rotated to display orientation) every second to estimate correction parameters, then drops them.
- **Settings and preferences:** `SettingsHelpScreen` takes an optional `settings` composable and shows the Settings section only when it is given. `AppGraph` owns the one preferences file (`divelens`) and `HelpPrefs`.
- **Bundled texts:** the build already copies texts from the repository into the app (the legal pages, add-help-and-legal D4).

## Goals / Non-Goals

**Goals:**
- One source for the geometry: the SVGs in `docs/brand/`. Nothing is redrawn by hand.
- The watermark is sharp at any export size, including 50 MP+ photos.
- The placement, rotation and version-choice logic is plain Kotlin with JVM tests.

**Non-Goals:**
- A general SVG renderer. Only the subset the brand files use is supported, and the build fails if a file leaves it.
- Changing the correction or the encoders.

## Decisions

### D1. Launcher, themed and notification icons as hand-converted vector drawables
**Launcher layers.**
- `ic_launcher_foreground.xml` copies the SVG path data verbatim; Android path data supports the SVG `a` arc commands.
- The foreground sits in a `<group android:scaleX="0.95" android:scaleY="0.95" android:pivotX="54" android:pivotY="54">`, so the dot's far edge moves to 32.6.
- `ic_launcher_background.xml` is a Deep Ocean rectangle.
- A new `ic_launcher_monochrome.xml` has the same scaled geometry in one colour.
- Both `mipmap-anydpi` icons point to these three layers. The `mipmap-*dpi` webp files go: minSdk 29 always uses the adaptive icon.

**Notification icon.** `ic_notification_export.xml` becomes the monochrome symbol in white, 24 dp, with a viewport cropped around the symbol (about 64 units square). The ring is then about 3 dp thick and the dot 4.5 dp across.

A JVM test parses the foreground XML and checks the scaled geometry against the 33-unit safe circle, so a later edit can't push the dot out again.

Alternative: Android Studio's Vector Asset import. It produces the same XML but can't be scripted or reviewed against the source; the paths are short enough to copy and test.

### D2. Launch screen
A new `values-v31/themes.xml` gives `Theme.DiveLens` `android:windowSplashScreenBackground` = Deep Ocean; the system takes the adaptive icon itself. No splash-screen library is needed: the platform splash is enough, and the app has nothing to load before its first frame.

**Updated in the device check (2026-10-27):** HyperOS 3 (Android 16) on the Xiaomi 14T creates the splash window from the theme but draws it from the window background, ignoring the splash attributes, so the launch screen was plain white. The theme's `android:windowBackground` is therefore also the launch picture (`drawable/launch_background.xml`: Deep Ocean with the launcher foreground centred at 240 dp, the size the system splash uses), in both `values` and `values-v31`. Android 10 and 11, which have no system splash, get the same picture from it. `MainActivity.onCreate` replaces the window background with the screen colour (Mist, or Deep Ocean in dark mode) before the first frame, so the launch picture never shows behind a page transition. The `core-splashscreen` library would do the same; two lines didn't justify a dependency.

### D3. Palette in Compose, dynamic colour removed
- `Color.kt` holds the `BRAND.md` palette as constants, replacing the purples.
- `Theme.kt` builds `lightColorScheme` / `darkColorScheme` from it and drops the `dynamicColor` parameter and branch.
- The main roles:

  | Role | Light | Dark |
  |---|---|---|
  | primary | Lens Blue | Surf |
  | onPrimary | white | Deep Ocean |
  | secondary / tertiary | Coral Deep | Coral Glow |
  | onSecondary / onTertiary | white | Deep Ocean |
  | background | Mist | Deep Ocean |
  | surface | white | `#0F3A52` |
  | onBackground / onSurface | `#1C2B33` | Foam |
  | onSurfaceVariant | Slate | Fog |

- **Every other role is set explicitly,** so no baseline purple shows through: the containers, `surfaceVariant`, the `surfaceContainer*` family (used by sliders, dialogs, sheets and menus), `outline` and `outlineVariant`. These values are tints between the palette colours.
- **Contrast test:** a JVM test computes the WCAG contrast of every on-colour against its role in both schemes and requires at least 4.5:1, or 3:1 for `outline` against the surface.
- **Where the colours live:** `values/colors.xml` keeps only what XML needs, i.e. the icon background and splash colour. The template colours go.

Alternative: keep dynamic colour on Android 12+. The user chose the fixed palette so the brand is visible on every phone.

### D4. The watermark is drawn from the brand SVGs, not from a VectorDrawable
`VectorDrawable` renders through a cache bitmap capped at 2048 px and scales it up beyond that. At 12 % that is only reached by photos over 17000 px wide, but the cap also depends on the device, and drawing the paths directly avoids the question at any size. Instead:

- **Copy:** a Gradle task copies `docs/brand/watermark/watermark_{light,dark}.svg` into the app's generated assets, as the legal texts are copied.
- **Parse:** a small reader (`MarkSvg`, pure Kotlin) parses the subset the files use: the root viewBox, `<g>` with `translate` and inherited `fill`/`fill-opacity`, and `<path>` with `d`, `fill`, `fill-opacity` and `fill-rule`. The result is a list of shapes (path data, ARGB colour, even-odd flag, offset). Anything else is an error, and a JVM test reads the real files, so a new export from a design tool that adds a filter or text fails the build instead of rendering wrong.
- **Draw:** on Android each shape becomes a `Path` via `androidx.core.graphics.PathParser` (core-ktx is already a dependency), drawn with an antialiased `Paint` under a `Matrix` that maps the viewBox to the target rectangle. The paths are vectors, so the mark is sharp at any size.
- **The 92 % opacity** applies to the whole mark through a layer (`saveLayerAlpha`), so the shadow doesn't show through the main shape.

Alternative: rasterise once to a large PNG and scale it down. That is blurry or wasteful at the extremes, and it creates a second asset that drifts from the SVG.

### D5. Placement in display coordinates, mapped to the stored pixels
`WatermarkPlacement` (pure Kotlin) works in display coordinates, i.e. the picture as the gallery shows it:
- the box is the SVG's whole viewBox, `w = 0.20 · displayWidth` and `h = w · 63 / 232.5`;
- `right = displayWidth − 0.03 · displayWidth` and `bottom = displayHeight − 0.03 · displayWidth`;
- the measurement area is the box grown by 10 % of its size on each side and clamped to the picture.

**Photos.** The bitmap is in stored orientation with an EXIF orientation from 1 to 8. A display→stored `Matrix` for the eight cases (rotations and mirrors) is concatenated on the `Canvas` before drawing the upright mark in display coordinates. The mark therefore ends up upright and unmirrored in the gallery for every orientation, mirrored ones included. The JVM tests check where the box's corners land for all eight cases.

**Videos.** Media3 hands effects frames that are already upright (the input rotation is applied before the effect chain), so the overlay is placed in display coordinates as it is. A device test with a portrait clip (rotation 90) confirms this; if Media3 applies the rotation after effects on some path, the overlay gets the same rotation as the photo path.

### D6. Version choice
Luma is `0.2126 R + 0.7152 G + 0.0722 B` on the 8-bit encoded values / 255; the mean is taken over the measurement area (D5).

**Photos.** Measured on the corrected bitmap, after `PhotoProcessor` and before drawing. Above 8 MP the area is read every second pixel in each direction; the mean changes by far less than any useful threshold step.

**Videos.**
- `AndroidTimelineSampler` keeps, for each sample, the frame's pixels under the measurement area, box-averaged into at most 24 × 8 cells (`CornerCrop`), a few hundred bytes per sample. Raw pixels of the 400 px sample frames would add up to tens of MB for twenty long clips. The cells are kept with the timeline next to the correction parameters (`CorrectionTimeline.withSample(..., corner)`).
- At export, each crop is corrected on the CPU with the timeline's parameters at that sample's time and the export strength (`CpuCorrection.full`, mixed in by the strength as the video shader adds its shift), and its mean luma taken. The median over the samples picks the version.
- The crops come from frames before HDR tone mapping. That only matters for HDR clips, whose corner brightness is approximate anyway.

The threshold is a constant, `WATERMARK_DARK_ABOVE`, starting at 0.60 and set by the tuning step (D9).

### D7. The video overlay is a Media3 `OverlayEffect` after the correction
The mark is drawn (D4) into an `ARGB_8888` bitmap of the box's pixel size. It is added as `OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)))` after `CorrectionEffect` in the video effects list. The `StaticOverlaySettings` anchor the overlay's bottom-right to the frame's bottom-right in normalised coordinates, offset by the margin, with `alphaScale = 1` because the bitmap already carries the 92 %.

The order matters: the mark is drawn over corrected pixels and is never colour-corrected itself. Videos at strength 0 still go through Transformer, so the overlay applies to them too.

Alternative: a second GL pass inside `CorrectionEffect`. That mixes two jobs in one shader, and the viewer would need a switch to leave it out.

### D8. The setting is read once per batch
- `ExportPrefs` sits next to `HelpPrefs`, on the same `Flags` abstraction. It exposes `watermark: StateFlow<Boolean>`, stored under the key `watermark_on_exports`, defaulting to `true` when absent.
- `ExportController` reads the value when it starts a batch and passes it to every item, via an `ExportOptions(watermark)` argument on the item use case and both writers, so a change mid-batch applies from the next export.
- The Settings content is a `ListItem` with the title, a one-line explanation and a `Switch`; the whole row toggles. It is passed to `SettingsHelpScreen` as `settings`.

### D9. Tuning the threshold on real photos
`tools/brand/watermark_preview.py` is a maintainer tool, not shipped, with its own `requirements.txt` (Pillow only, inside the existing `.venv`; CairoSVG was planned, but it needs the Cairo system library, so the tool draws the brand SVG subset itself, as `MarkSvg` does). It:
- takes corrected exports with the watermark off, pulled from the phone into the gitignored `trainingset/watermark/`;
- computes the corner luma as D5 and D6 do;
- writes a contact sheet sorted by luma, showing each corner with both versions and the current threshold marked.

The user picks the luma where the dark version starts reading better. The value goes into `WATERMARK_DARK_ABOVE` and `BRAND.md`. A JVM test reads the tool's constants (placement shares, opacity, luma weights, subsampling, threshold) and requires them to equal the app's, so the two can't drift. No personal media is committed: the sheet is written into `trainingset/`.

### D10. Text and docs
- **New strings:** `setting_watermark` ("Watermark on exports") and `setting_watermark_summary` ("Adds a small diveLENS mark to the corner of exported photos and videos").
- **Help:** the Getting started help gains no step; the setting explains itself.
- **NOTICE** credits Inter (SIL OFL 1.1) for the wordmark outlines. The NOTICE check only requires every shipped Maven group to be covered, so an entry without a "Maven groups" line (a bundled asset, like the Material Icons entry) already passes; the check is unchanged.
- **Also updated:** `BRAND.md` (safe zone radius 33, the final threshold), the README status and `openspec/config.yaml`.

## Risks / Trade-offs

- **[Media3 applies rotation after effects on some devices or paths, which would put the mark sideways]** → The portrait-clip device test (D5) catches it; the fallback is the same display→stored rotation on the overlay.
- **[The shadow is too faint to see on small videos (720p: the mark is 144 px wide, the shadow offset under 1 px)]** → Accepted: the light/dark choice carries legibility, and the shadow is secondary.
- **[A thin "dive" at 12 % (chosen over the planned 20 % after seeing real exports, 2026-10-27), below BRAND.md's original 16 % minimum]** → Checked on the phone in the device check; if it doesn't read well, the fix is a heavier "dive" weight from the brand designer, not a larger mark.
- **[Brightness from pre-tone-mapping HDR frames is approximate]** → Only the version choice is affected, and either version stays readable over mid tones.
- **[Turning dynamic colour off changes every screen's look]** → Light and dark mode are checked on the device across the start screen, viewer panel, sheets, dialogs and Settings & help. The contrast test guards the text.
- **[Existing instrumented export tests that compare pixels to the uncorrected or corrected source]** → They run with the watermark off unless they test it.

## Migration Plan

Not released yet, so there are no existing installs to migrate. A missing preference reads as "on", which is the specified default. Rollback is reverting the change; no stored data depends on it.
