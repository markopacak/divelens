# DiveLens brand assets

Android app that fixes underwater photos and videos on-device (no account, no upload).
Tone: clean, calm, precise tool. Not cartoonish, not "techy".

The SVGs in this folder are the source of truth. Do not redraw them. Convert them
(e.g. Android Studio > Vector Asset) and keep the geometry unchanged.

## Symbol

A ring (the lens) with a small coral dot at its top right (red light coming back).
Geometry in the 108 x 108 adaptive-icon viewBox:

- ring: centre (49, 59), outer radius 24, inner radius 16
- dot: centre (74, 34), radius 6
- the ring and dot reach 34.3 from the centre (54, 54). Android only guarantees a circle of
  radius 33 (66 dp across); 36 is the edge of the visible area. The launcher layers in the app
  therefore draw the symbol at 95 % around (54, 54), so its far edge is at 32.6. The SVGs here
  stay unscaled.

There is intentionally no rounded-square / outlined-square version of the icon.
Show the icon circle-cropped in any previews or store graphics.

## Files

| File | Use |
|---|---|
| `icon/ic_launcher_foreground.svg` | Adaptive icon foreground (Foam ring, Coral Glow dot) |
| `icon/ic_launcher_background.svg` | Adaptive icon background, solid Deep Ocean |
| `icon/ic_launcher_monochrome.svg` | Themed icon / notification icon. Single flat colour (black in the file; Android tints it) |
| `play/play_store_icon_512.png` | Google Play store icon, 512 x 512, full-bleed (Google applies the mask) |
| `play/play_store_icon.svg` | Source of the Play icon |
| `watermark/watermark_light.svg` | Export watermark for dark or mid-tone areas: white with soft dark shadow |
| `watermark/watermark_dark.svg` | Export watermark for bright areas: navy with soft light halo |
| `colors.xml` | Brand, light-theme and dark-theme colours for Android |

SVGs use paths and `fill-opacity` only: no raster images, filters, gradients or text.
The wordmark is already converted to paths. It is Inter Light ("dive") + Inter Bold
("LENS"), SIL OFL, which is compatible with GPL-3.0. The Android Vector Asset import
has not been tested yet, so check the watermark shadow (`fill-opacity`) after importing.

## Name and wordmark

Written **diveLENS** in the wordmark (light "dive", bold "LENS"). The app name in text
elsewhere is "DiveLens".

## Palette

| Name | Hex | Role |
|---|---|---|
| Deep Ocean | `#0B2E42` | icon background, dark-mode background |
| Lens Blue | `#0E4A66` | primary (light theme), ring on light |
| Restored Coral | `#E8553D` | the dot on light backgrounds, graphics only (3.2:1, not for text) |
| Coral Deep | `#BC3E28` | buttons and text accent on light (white text 5.4:1) |
| Coral Glow | `#F46C53` | accent in dark theme, dot in the icon (4.8:1 on Deep Ocean) |
| Surf | `#8FD0E8` | primary in dark theme (8.3:1 on Deep Ocean) |
| Foam | `#F2F7F9` | ring on dark, text on dark |
| Mist | `#EAF3F6` | light-theme background |
| Slate | `#526A77` | secondary text on light (5.1:1 on Mist) |
| Fog | `#A9BFC9` | secondary text on dark (7.4:1 on Deep Ocean) |

All text pairs in `colors.xml` meet WCAG AA (4.5:1) or better. Avoid generic neon cyan.

## Watermark rules (photos and videos)

- Placement: **bottom-right** corner, always.
- Size: **12 % of the image width** (height follows the aspect ratio).
- Margin: **3 % of the image width** from both the right and the bottom edge.
- Opacity: **92 %**.
- Version choice: compute the mean luma (Rec. 709 on gamma-encoded sRGB values, 0 to 1)
  of the area under the mark plus 10 % padding. **Above 0.60, use `watermark_dark.svg`;
  otherwise use `watermark_light.svg`.** 0.60 is a starting value to tune on more photos.
  On the reef test photo, corners measured 0.38 to 0.40 and the light version looked good.
- Video: choose the version **once per clip** from the median luma of sampled frames,
  so the mark never flips mid-clip.
- The size was 20 % at first; 12 % was chosen after seeing real exports, so the mark stays discreet. The light
  "dive" is thin at this size: check it on real exports, and if it doesn't read well, a heavier "dive" weight
  for small sizes is the fix, not a larger mark.
- Discreet, not an advert. Location data is stripped from exports separately.

## Avoid

Camera clip-art, scuba-mask clichés, sharks, generic waves, photographic detail, and
anything resembling existing photo-editing or dive-app logos.
