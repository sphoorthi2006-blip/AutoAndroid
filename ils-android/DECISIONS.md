# Image Layer Studio for Android: decisions so far

The Android app is the product. The Windows app (v0.14.5) is only a reference for how the pipeline works.
Do not copy its timing values or defaults. Every tunable value lives in one settings file.

## Pipeline
1. **Slide map** (done, `core/`): script + raw SRT -> one slide per script line, with exact times and sentence times.
2. **Prompts + element lists** (outside the app, by an AI model): one image prompt per slide, plus an element list
   in narration order (label, sentence, trigger words, slot = row and column).
3. **App** (to build): images + JSON + audio -> extract elements -> match to the list by slot -> reveal at trigger words -> MP4 with audio.

## Decisions
- Watermark (TurboScribe etc.) is always removed from the raw SRT, by wording, before any matching.
- The raw SRT is only a clock. The script decides the text. Script words are matched to spoken words (99.5% on baking soda).
- Cuts fall only between sentences. No gaps: each image runs until the next starts; first starts at 0; last ends with the audio.
- Section headings (like "1. Skin Uses"), the disclaimer and the subscribe line each get their **own image**.
  For other genres (for example a Christian script) the same rule applies to verse cards and similar lines; the rules are settings
  (`SlideMapSettings`), and the AI model can override them later.
- No heading carry-over. Later images in a section have no heading.
- The intro has no title and no heading. A script line that is not in the audio (the video title) gets no image.
- Long paragraphs: split at a sentence end when longer than 30 s (setting), near an even split, preferring a longer pause.
  Parts of one paragraph have no heading.
- Pace: about 5 to 6 elements per 20 s. An icon with its label counts as one element.
- Timing: each element starts at its trigger word. Draw time depends on the element type and is cut short so it ends before the next starts.
  When everything is drawn, the finished picture holds until the next image. **No "fit to length" stretching. No manual timing mode.**
- Text (labels, headings): revealed as **handwriting** (pen strokes, letter by letter), never as a colour fill or wipe.
  Built and tested (`Pen.kt`, `Handwriting.kt`): the pen engine of the desktop Text option, ported to Kotlin, runs on the text pixels already in the image,
  so the lettering keeps its look. Letters are written left to right, line by line; i-dots and full stops come right after their letter.
  The writing time is given by the caller, and every moment is a fraction of it, so any duration works.
- Sentence times stay in the JSON, and the app builds the JSON itself.
- No preview and no editor. One minimal screen: pick files, run, progress, result.

## Decided for the first full build (change them in `ElementSettings`, `Elements.kt`)
- **Element detection is pure Kotlin, not OpenCV.** Background colour = the most common colour; ink = pixels that differ from it by more than a threshold;
  ink pieces that lie close together (a little further apart up and down than sideways) become one element, so an icon keeps its label.
  It lives in `core/`, so it is tested on the PC (`:core:elementsTest`) and adds no native library to the APK.
- **Element list** (`elements.json`, imported): per image, in narration order: `label`, `type` (`icon` or `text`), `sentence` (0 = first, as in slides.json),
  `trigger` (word or words), `row` and `col` (counted from 1). Format is described in `ElementList.kt`.
- **Matching by slot**: the entry with row r, col c gets the detected element in that row and column. An entry with no exact slot takes the nearest unused
  detected element and a warning is written. Detected elements without an entry stay visible all the time.
- **Reveal**: text is written by the pen (never filled or wiped). A picture fades in and grows from 90% to 100%. An icon with a label: picture first (40% of the draw time),
  then the label is written. Elements are hidden before their start; the unfinished area is filled with the image's background colour.
- **Draw times**: icon 0.9 s; text 0.5 s + 0.09 s per letter (max 3 s); icon with label = icon time + letter time. Each element ends 0.05 s before the next starts,
  the last 0.15 s before the image ends, and never takes less than 0.3 s. Starts are at least 0.12 s apart.
- **Images without an element list**: a heading image is written as handwriting (all its ink, starting 0.15 s in, at most 2.5 s). Every other image is shown as it is.
- **Images**: files in one folder named by image number (1.png, 02.jpg, slide-3.webp). Scaled to fit 16:9 and centred on the image's own background colour.
- **Video**: H.264, default 720p at 30 fps, frames fed with exact timestamps. Repeated frames (nothing moving) are not converted again.
  Audio is copied when it is AAC, otherwise converted to AAC. The video ends with the audio (last cue end).

## Not decided yet
- Draw times and thresholds still need tuning on real images (all in `ElementSettings`).
- A phone that sleeps or kills the app stops the export: keep the screen on while it runs.
