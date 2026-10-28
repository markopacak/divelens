# Brand tools (maintainers)

`watermark_preview.py` helps tune the brightness above which exports use the dark watermark (`WATERMARK_DARK_ABOVE`
in `WatermarkVersion.kt`, and `BRAND.md`). It needs only Pillow:

```sh
.venv/bin/pip install -r tools/brand/requirements.txt
adb pull /sdcard/Pictures/DiveLens trainingset/watermark   # corrected exports made with the watermark off
.venv/bin/python tools/brand/watermark_preview.py          # writes trainingset/watermark/contact_sheet.png
```

The sheet shows each photo's bottom-right corner with the light mark (left) and the dark mark (right), sorted by the
corner's brightness, with a red line at the current threshold. Pick the brightness where the dark mark starts to read
better, then set it in the app and in `BRAND.md`. The photos and the sheet stay in the gitignored `trainingset/`;
the tool refuses to write anywhere else.

The tool measures the corner exactly as the app does; `WatermarkPreviewToolTest` keeps its constants equal to the app's.
