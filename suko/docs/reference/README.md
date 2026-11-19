# Reference pictures (REAL ones, not synthetic) - read before touching panels, extras, raster or animation

These are the pictures the app is built for. Every change to `panels.py`, `raster.py`, `panelmatch.ts`, `compose.ts` or `slides.ts` must be tested against them, not only against `tests/synth.py`.
Do not delete, rename, resize or re-save them. Add new real pictures here with the same style (`refN_name.ext`) and add their truth to `backend/tests/test_reference.py`.

| File | Format | Size | What a person sees (the truth) |
|---|---|---|---|
| `ref1_lost_son_rows.jpg` | JPG (compressed, JPEG noise) | 1376x768 | Thick black frame. Top row: 2 big circle panels (Lost Son, Real Hero), each with a yellow label centred under it that overlaps the circle's bottom edge; a yellow caption box "LOOK CLOSELY" in the top-left corner of the right circle. Bottom row: 4 small rectangle panels (thin dark outline, about 3 px) with a yellow label under each (the last label has two lines and a faint sparkle), and 3 dashed arrows pointing right between them. No ribbon/heading. |
| `ref2_david_rows.png` | PNG (clean, 2752x1536, one tiny JPEG artifact near the last label) | 2752x1536 | Same layout: frame, 2 circles (Shepherd Boy -> Giant Defeated, with ONE dashed arrow between them), 3 rectangle panels with thick black outlines, yellow labels overlapping the bottom edge of each panel, caption box "BEFORE THE FIGHT" top-left of panel 3, 2 dashed arrows between the bottom panels. No ribbon. |

`ref3_david_margin.png` (1465x861, PNG): the same David picture as ref2 but with a checkerboard/grey MARGIN outside the black frame (came from the owner's upscaled test). Before `trim_margin` the detector found 1 of 5 panels, so the app fell back to the traced elements and the real picture only faded in at the end. Now 5/5 panels, 5/5 labels, caption box (panel 3, top-left), 3/3 arrows.

## What the code does on them today (v2 step 6b DONE) - status
Measured with `python backend/tests/test_reference.py` (the backend reduces pictures to 1600 px first):
- `ref1`: **6 of 6 panels** (2 circles, 4 rectangles), **6 of 6 labels**, the caption box 'LOOK CLOSELY' (on the right circle, top-left corner), **3 of 3 arrows** (each joins the right two panels), frame found, no ribbon.
- `ref2`: **5 of 5 panels**, **5 of 5 labels**, the caption box 'BEFORE THE FIGHT' (panel 3, top-left), **3 of 3 arrows**, frame found, no ribbon.
- Same results after JPEG q60 and blur (sigma 1.2). A half-size copy still gives all panels but may miss a label (info only).
- How: `detect_blobs` in `panels.py` (used by `find_panels` only when it finds MORE panels than the ring detector, so the synthetic scenes behave as before): label/caption boxes are found first (`_yellow_boxes`: flat pale-yellow box, wider than tall, dark text) and painted out; everything that is not background is a blob; a big opening keeps only each panel's core, which separates panels joined by an arrow; a circle is its fitted disc (so the part a label covers is restored), other panels their convex hull. Labels that overlap a panel's bottom edge count as that panel's label; a caption box sticking out of a circle's corner counts as the circle's caption; arrows are found as dashes joined by a closing of 2% of the picture size.
- Limits: panels that touch the frame, a ribbon/heading, parchment panels and the ribbon are not covered by these two pictures (the synthetic scenes cover the ring cases). Send a real picture if something is missed.

## How to use
- Run `python tests/test_reference.py` (in `backend`) after any change to detection. It must not get worse. `--strict` shows how far from the truth it still is. When you improve a result, raise `BASELINE` in the test.
- Send the final animation of these two pictures (once steps 8-12 exist) to the user for a visual check; the frame by frame check of step 9 should compare against these pictures.

## ref4 (added 2026-11-19, from the owner)
`ref4_garden_rows.jpg` (JPG, 1376x768): a different layout from ref1-3. NO outer frame, NO arrows, NO caption boxes, NO ribbon. Row 1: one very wide rectangle panel (garden, a man and a woman by a pond) with a yellow label "ASTONISHING GIFT" overlapping its bottom edge. Row 2: three rectangle panels (sheep, mountains, vineyard) with yellow labels "STILL CREATURES", "DIGNITY AND LIMITS", "ENTRUSTED STEWARDS" overlapping their bottom edges. Row 3: one wide rectangle panel (bread scene) with the label "REFLECTING GOODNESS". Truth: **5 panels, 5 labels, 0 arrows**. The phone engine (`app/src/main/python/ils/`, desktop code) finds 5/5 panels, 5/5 labels, 0 arrows in about 6 s on the sandbox CPU (checked by `app/src/test/python/check_engine.py`).
