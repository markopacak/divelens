# Variant B: how to put your own images in

Slots (px): BEFORE = x 0, y 0, 508 x 500   |   AFTER = x 516, y 0, 508 x 500   |   8 px divider between them.
Prepare both photos with the SAME crop, ideally at 2x (1016 x 1000) so they stay sharp. Keep the subject
centred in each slot and inside y 50-450. The "after" must be a real DiveLens export: do not grade it.

## Option 1: script (simplest, pixels untouched)
    pip install pillow
    python3 compose_b.py before.jpg after.jpg feature_graphic_B_final.png
Optional: --focus-x / --focus-y (0 to 1) move the crop window. The script only scales and crops,
then puts feature_graphic_B_overlay.png (divider, labels, logo) on top. Output: 1024 x 500, 24-bit PNG, no alpha.
Note: if the DiveLens export carries the bottom-right watermark, it can land inside the AFTER slot.
Use --focus-x 0 (crops the right side) or accept it.

## Option 2: edit the SVG
In feature_graphic_B.svg, delete the two groups id="placeholder-before" and id="placeholder-after"
and put these two lines where they were (the file contains the same hint as a comment):
    <image href="before.jpg" x="0"   y="0" width="508" height="500" preserveAspectRatio="xMidYMid slice"/>
    <image href="after.jpg"  x="516" y="0" width="508" height="500" preserveAspectRatio="xMidYMid slice"/>
Keep the image files next to the SVG, then export at exactly 1024 x 500 (e.g. open in a browser or Inkscape).

## Option 3: any image editor (Figma, Canva, GIMP)
Place your two images in the slots on a Deep Ocean (#0B2E42) 1024 x 500 canvas, then put
feature_graphic_B_overlay.png (transparent) on top.

The BEFORE/AFTER chips are optional: delete the group id="labels" (SVG) or use the placeholders PNG without them.
