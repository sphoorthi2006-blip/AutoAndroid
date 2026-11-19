# Procedure: canvas plan and timing JSON (no scripts, done by reading)

No code is run. Everything here is done by careful reading and counting. The master SRT is already cleaned, script-verified and cut into cues by the user's cleaning step; this skill never cleans, merges, splits or re-times a cue. Cue numbers are the position of the cue in the master SRT (1, 2, 3 ...). Panel start = the cue's start time from the master SRT.

If the user also pastes a ready-made "canvas plan" (canvases with their cue numbers, times and ribbon or verse flags), use it EXACTLY and skip P1 to P5; still do P6 to P8.

## P1. Read the script as paragraphs
Paragraphs are blocks separated by blank lines. If the script has no blank lines, every script line is its own paragraph (tell the user in one line when this happens). Because the master SRT carries the user's exact script wording, match every cue to its paragraph by its words, in order. No cue ever crosses a paragraph.

## P2. Headings
A script line is a heading when it is one of: `Chapter 1`, `Part 2`, `1. ...`, `One: ...`, `Number 3 - ...`, `#3 ...`, `Tip 2: ...`, `Intro`, `Outro`, `Conclusion`, an ALL-CAPS line of at most 8 words, the video title, or a short line on its own with no sentence punctuation and at most 8 words. This skill has no ribbon. A heading cue becomes its own ONE-PANEL TEXT CANVAS, drawn as a TEMPLATE B plain text slide (pure white screen, the heading's exact words in black Patrick Hand lettering, no frame, box, color or label; see `template.md`). A cue that only asks the viewer to like and subscribe (for example "please like this video and subscribe") is also its own one-cue TEXT CANVAS, drawn as a Template B slide with the words LIKE AND SUBSCRIBE. If the plan has such a cue inside a longer paragraph, say so in one line and treat it as its own text canvas. JSON for a text canvas: `layout` "Single splash", one panel with `shape` "parchment", `label` `""`, and `verse` holding the exact words shown on the slide (the heading words, or LIKE AND SUBSCRIBE).

## P3. One paragraph = one canvas
One paragraph = one canvas, one cue = one panel, in cue order. A canvas holds at most 8 panels. A paragraph with more than 8 cues is split EVENLY into ceil(cues / 8) canvases (10 becomes 5 + 5; 9 becomes 5 + 4; 17 becomes 6 + 6 + 5; the larger parts come first). If an even cut falls in the middle of an action, move the cut to the nearest change of place, time or speaker, keeping every part at 8 panels or fewer. Number the canvases 1, 2, 3 ... through the whole script (global numbers, a later batch never restarts at 1).

## P4. Canvas times
A canvas starts at its first cue's start time (its own cue's start time). A canvas ends where the next canvas starts. The last canvas ends where the last cue ends. Slides therefore touch: the end of canvas n equals the start of canvas n+1.

## P5. Count before the first reply
Count: canvases, panels in total, single-panel canvases, and the paragraphs that were split.

## P6. Labels, shapes, captions
For every panel decide a title label of 1 to 4 words in ALL CAPS (see `rules.md`), except the panel of a text canvas (heading or like-and-subscribe), which has no label. Every canvas is a comic layout, so every panel is a rectangle; a verse cue is a parchment panel. Decide caption boxes and balloons as `rules.md` allows; the JSON carries exactly the caption and balloon text the prompt shows.

## P7. Timing JSON for the batch (version 2, the app's format)
Reply with ONE code block of JSON holding ONLY the canvases of the batch, with global numbers and real video times:

```
{
  "format": "ils-slides",
  "version": 2,
  "title": "<video title>",
  "timing_source": "srt",
  "slides": [
    {
      "n": <canvas number>,
      "start": <seconds>, "end": <seconds>,
      "system": "comic", "layout": "<layout name>",
      "text": "<the canvas's cue texts joined with single spaces>",
      "panels": [
        { "shape": "<shape>", "label": "<LABEL>", "caption": "<optional>", "balloon": "<optional>", "verse": "<only for a parchment panel>", "start": <seconds>, "cue": "<that panel's cue text>" }
      ]
    }
  ]
}
```
Rules the app checks (all must hold):
1. `format` is `ils-slides` and `version` is 2. Times are seconds with up to 2 decimals on the clock of the whole video, not relative to the slide. The first slide's `start` is its real time, not 0, when the batch does not begin at canvas 1.
2. `n` runs without gaps from the batch's first canvas. Slides touch: end of n = start of n+1. Every `end` is after its `start`.
3. At most 8 panels per slide; panel starts never go back in time and stay between the slide's start and end. The panel count equals the cue count of the canvas.
4. Every panel has a `shape`, a `label` of 1 to 4 words and a `start`. The only exception is the panel of a text canvas (heading or like-and-subscribe), whose `label` is `""` (empty). `caption` and `balloon` are left out when the panel has none.
5. `system` is always `comic`. `shape` is `rectangle`, or `parchment` for a verse panel. No circles.
6. A verse cue (a quoted passage followed by its reference) is a `parchment` panel: `verse` holds the exact quoted words with the quotation marks and the (Book 1:1) reference removed. `verse` is used only on parchment panels, and every parchment panel has it. A verse panel still gets a 1 to 4 word label like every panel.
7. There is no `heading` field (no ribbon); a heading cue is its own one-panel text canvas.
Output numbers as plain numbers (no quotes). Keep the JSON valid: double quotes, commas between items, no trailing commas, no comments.

## P8. Silent check before sending
Every canvas number, count and time matches the plan; panel starts equal the cue starts (except the ribbon rule above); labels in the JSON equal the labels in the prompts word for word (a text canvas has an empty label in the JSON and no label in its Template B prompt); every comic canvas prompt uses Template A with the strict NO OUTER BORDER line (no outer frame anywhere) and every text canvas uses Template B; the JSON is valid.
