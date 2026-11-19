# Procedure: canvas plan and timing JSON (no scripts, done by reading)

No code is run. Everything here is done by careful reading and counting. The master SRT is already cleaned, script-verified and cut into cues by the user's cleaning step; this skill never cleans, merges, splits or re-times a cue. Cue numbers are the position of the cue in the master SRT (1, 2, 3 ...). Panel start = the cue's start time from the master SRT.

If the user also pastes a ready-made "canvas plan" (canvases with their cue numbers, times and ribbon or verse flags), use it EXACTLY and skip P1 to P5; still do P6 to P8.

## P1. Read the script as paragraphs
Paragraphs are blocks separated by blank lines. If the script has no blank lines, every script line is its own paragraph (tell the user in one line when this happens). Because the master SRT carries the user's exact script wording, match every cue to its paragraph by its words, in order. No cue ever crosses a paragraph.

## P2. Headings
A script line is a heading when it is one of: `1. Egg Nutrition`, `2) ...`, `3 - ...`, `One: ...`, `Number 3 - ...`, `#3 ...`, `Tip 2: ...`, `Chapter 1`, `Step 4`, `Intro`, `Introduction`, `Outro`, `Conclusion`, `Summary`, `Recap`, `Overview`, `Final thoughts`, `Disclaimer`, an ALL-CAPS line of at most 8 words, or a short line on its own with no sentence punctuation and at most 8 words. A heading cue is NEVER a panel. Its exact words become the ribbon text of every canvas of its section, until the next heading. Headings such as Intro, Introduction, Outro, Conclusion, Summary, Recap, Overview and Final thoughts give NO ribbon (the canvas gets the plain frame) and end the previous section. A script with no headings has no ribbons at all. The ribbon text is exactly the heading's words.

**Disclaimer canvas (special, always the same).** A disclaimer is a heading line `Disclaimer` and/or the cue that says the video is not medical, financial or other advice. It is ALWAYS its own ONE-PANEL canvas (Single splash), never a panel inside a longer paragraph (if the plan has such a cue inside a longer paragraph, say so in one line and cut it out as its own canvas). That canvas ALWAYS has a ribbon whose text is exactly `DISCLAIMER` (the heading line, when the script has one, is this ribbon and not a panel; the canvas starts at the heading cue's start time). The picture below the ribbon is the one panel: the disclaimer scene from the pack, with the title label `A QUICK NOTE`. The notebook cover already says NOT MEDICAL ADVICE (Finance: NOT FINANCIAL ADVICE), so the label must not repeat it. The ribbon repeats on no other canvas, and the disclaimer ends the previous section. JSON for it: `"system": "comic"`, `"layout": "Single splash"`, `"heading": "DISCLAIMER"`, one panel with `shape` "rectangle", `label` "A QUICK NOTE" and `start` at least 1.0 second after the canvas start (rule 6 below). The app does not need to change for this.

## P3. One paragraph = one canvas
One paragraph = one canvas, one cue = one panel, in cue order. A canvas holds at most 8 panels. A paragraph with more than 8 cues is split EVENLY into ceil(cues / 8) canvases (10 becomes 5 + 5; 9 becomes 5 + 4; 17 becomes 6 + 6 + 5; the larger parts come first). If an even cut falls in the middle of an action, move the cut to the nearest change of place, time or speaker, keeping every part at 8 panels or fewer. Number the canvases 1, 2, 3 ... through the whole script (global numbers, a later batch never restarts at 1).

## P4. Canvas times
A canvas starts at its first cue's start time (for the first canvas after a heading, the heading cue's start time). A canvas ends where the next canvas starts. The last canvas ends where the last cue ends. Slides therefore touch: the end of canvas n equals the start of canvas n+1.

## P5. Count before the first reply
Count: canvases, panels in total, single-panel canvases, and the paragraphs that were split.

## P6. Labels, shapes, captions
For every panel decide a title label of 1 to 4 words in ALL CAPS (see `rules.md`). The layout system decides the shapes: `rows` (Layout 1) uses circles and rectangles and needs a shape for every panel; `comic` (Layout 2) uses rectangles only. Decide caption boxes and balloons as `rules.md` allows; the JSON carries exactly the caption and balloon text the prompt shows.

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
      "system": "rows or comic", "layout": "<layout name>",
      "heading": "<ribbon text, only when the canvas has a ribbon; otherwise leave the line out>",
      "text": "<the canvas's cue texts joined with single spaces>",
      "panels": [
        { "shape": "<shape>", "label": "<LABEL>", "caption": "<optional>", "balloon": "<optional>", "start": <seconds>, "cue": "<that panel's cue text>" }
      ]
    }
  ]
}
```
Rules the app checks (all must hold):
1. `format` is `ils-slides` and `version` is 2. Times are seconds with up to 2 decimals on the clock of the whole video, not relative to the slide. The first slide's `start` is its real time, not 0, when the batch does not begin at canvas 1.
2. `n` runs without gaps from the batch's first canvas. Slides touch: end of n = start of n+1. Every `end` is after its `start`.
3. At most 8 panels per slide; panel starts never go back in time and stay between the slide's start and end. The panel count equals the cue count of the canvas.
4. Every panel has a `shape`, a `label` of 1 to 4 words and a `start`. `caption` and `balloon` are left out when the panel has none.
5. `system` is `rows` or `comic`. `shape` is `circle` or `rectangle`; a comic canvas has no circles; a rows canvas gives every panel its shape.
6. A canvas with a ribbon: if a panel's start would be earlier than 1.0 second after the canvas start, move that start to canvas start + 1.0 (never later than the canvas end minus 0.05), so the ribbon heading has time to be written first. Keep panel starts in order.
7. No `heading` field means no ribbon. A heading is never a panel.
Output numbers as plain numbers (no quotes). Keep the JSON valid: double quotes, commas between items, no trailing commas, no comments.

## P8. Silent check before sending
Every canvas number, count and time matches the plan; panel starts equal the cue starts (except the ribbon rule above); labels in the JSON equal the labels in the prompts word for word; the JSON is valid.
