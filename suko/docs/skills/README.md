# SVG BH skill and SVG HF skill: README

Two Claude skills that prepare a finished YouTube voice-over for the Bezalel app (SVG Editor).
Both do the same job in two styles:

| | **SVG BH skill** (`svg-bh`) | **SVG HF skill** (`svg-hf`) |
|---|---|---|
| For | Bible, church history, history, true crime, mysteries, technology / AI / company stories, space and science told through people | Health, food, nutrition, medicine, wellness, money, investing, banking, business explainers |
| Art style | 2D cel-shaded illustration (hard-edged shadows, clean lines) | Semi-realistic illustration (macro texture on foods and objects) |
| Layouts | Comic layouts only (rectangles of different sizes, the key beat biggest) | Layout 1 (circles and rectangles in rows, dashed arrows only where one panel causes the next) or Layout 2 (comic layout, no arrows) |
| Outer border | **NONE.** No frame, box or line around the canvas. Panels sit directly on the white canvas, each with its own black outline | **ALWAYS.** One thick black square-cornered frame close to the image edge, closed on all four sides, with nothing around it |
| Headings | Plain white text slide (see section 5) | Black ribbon banner on top of the pictures of that section |

Pick ONE skill per video. Do not switch in the middle.

---

## 1. What you give the skill

1. The **MASTER SRT**: already cleaned and cut into caption cues (these skills do NOT clean, sync, merge, split or retime anything).
2. The **script .txt** the voice-over was read from. It tells the skill which cues belong to which paragraph.
3. Optional: a batch number or range ("1 to 20"), a pace note, a ready-made canvas plan.
4. SVG HF only: attach your **main man reference image** in the image tool when you generate pictures. The prompts only refer to it.

If the script is missing, the skill asks for it and waits.
If the SRT still has transcriber watermarks, sound tags like [Music], or wording that differs from the script, the skill tells you to clean it first.

## 2. What the skill gives back

**First reply (plan):** the number of canvases needed, the number of panels, single-panel canvases, which long paragraphs were split, and (HF) the ribbon sections. Then it asks how many canvases you want first.

**Every batch (exactly two code blocks, nothing else):**
1. The **timing JSON** for those canvases.
2. **One code block with every image prompt** of the batch, one prompt per canvas.

Rules:
- Hard cap: **20 canvases per reply.** Ask for the next range to continue ("21 to 40"). Best batch size is 10 to 20.
- "locks": the skill prints its working memory (characters, objects, layouts used) so you can paste it into a new chat with the SRT and the script. Use one chat per video; for more than 60 canvases start a new chat every 40 to 50 canvases.
- "Redo 5 to 8": regenerates exactly those canvases.

## 3. How the skill thinks (the same in both)

- **One script paragraph = one canvas (one image).**
- **One caption cue = one panel** inside that canvas.
- A paragraph longer than 8 cues is split evenly into several canvases (10 becomes 5 + 5).
- Canvases are numbered through the whole script (a later batch never restarts at 1).
- Canvas times follow the cues. A canvas starts at its first cue and ends where the next canvas starts, so slides touch with no gaps.
- Every panel gets a **title label** of 1 to 4 words, ALL CAPS, written from what the panel shows. The label is attached under its panel.
- Everything uses the **Patrick Hand** font.
- No arrows in comic layouts, no circles in comic layouts, no gore, no extra text beyond what the rules allow.
- **Locks:** characters, objects and era are decided once from the whole script and copied word for word into every later prompt, so the same person looks the same in every image.

### Strict rule: a label never repeats text inside its own panel
If a panel already contains writing (notebook cover, sign, parchment, banner, screen, map caption, verse or reference line), its title label must be different words. The audience should never read the same words twice. Every panel still has a label, because the app needs it. Where this changed the wording:
- Disclaimer panel: notebook says NOT MEDICAL ADVICE, label is **A QUICK NOTE**.
- Like and subscribe panel (HF): signs say LIKE and SUBSCRIBE, label is **THANKS FOR WATCHING**.
- Bible verse panel: the reference (for example JOHN 3:16) is already on the parchment, label is always **SCRIPTURE**.

## 4. Special cases

### Heading lines in the script
A line counts as a heading if it looks like `1. Egg Nutrition`, `Chapter 1`, `Tip 2: ...`, `Intro`, an ALL-CAPS line of up to 8 words, or a short line of up to 8 words with no sentence punctuation. A heading is never drawn as a panel.

### SVG BH: headings and like-and-subscribe
Each is its own one-panel canvas, drawn from **Template B**: pure white screen, the exact words in black Patrick Hand lettering, nothing else (no frame, box, colour or drawing). For like-and-subscribe the words are always `LIKE AND SUBSCRIBE`.
JSON for these: `layout` "Single splash", one panel with `shape` "parchment", empty `label`, and `verse` holding the exact words.

### SVG HF: ribbon headings
A numbered or titled heading becomes a black ribbon (white hand-lettered capitals) on the top edge of the frame, repeated on every canvas of that section until the next heading. These words give **no** ribbon and end the previous section: Intro, Introduction, Outro, Conclusion, Summary, Recap, Overview, Final thoughts. In the JSON the canvas carries a `heading` field; the first panel starts at least 1.0 second after the canvas starts so the ribbon can be written first.

### SVG HF: the Disclaimer canvas (always the same)
- Always its **own one-panel canvas** (Single splash), never a panel inside a longer paragraph.
- **Ribbon on top: `DISCLAIMER`** (a "Disclaimer" line in the script becomes this ribbon; the canvas starts at that line's time).
- **One picture below:** a doctor holding up a notebook that reads NOT MEDICAL ADVICE (finance videos: NOT FINANCIAL ADVICE), a hero object on the desk.
- Panel label: **A QUICK NOTE**.
- The ribbon appears on no other canvas and the disclaimer ends the previous section.
- JSON: `"heading": "DISCLAIMER"`, `"layout": "Single splash"`, one panel with `label` "A QUICK NOTE".

### SVG HF: like and subscribe
A normal single panel: the main man smiling beside a thumbs-up sign marked LIKE and a bell sign marked SUBSCRIBE. Panel label THANKS FOR WATCHING.

### SVG BH: Bible verses
A verse is a parchment text panel inside a comic canvas with the exact words, reference as the smaller line. Label SCRIPTURE. God is never drawn as a figure or a face.

## 5. The timing JSON

Format used by the app: `"format": "ils-slides"`, `"version": 2`. Times are seconds on the clock of the whole video. Slides touch (end of one equals start of the next). At most 8 panels per slide. Every panel has `shape`, `label` (1 to 4 words, same as in the prompt), and `start`; `caption` and `balloon` only when used. Example (HF, with ribbon):

```json
{
  "n": 1, "start": 0.00, "end": 6.50,
  "system": "comic", "layout": "Single splash",
  "heading": "1. EGG NUTRITION",
  "text": "Eggs are packed with protein.",
  "panels": [
    { "shape": "rectangle", "label": "EGG PROTEIN", "start": 1.00, "cue": "Eggs are packed with protein." }
  ]
}
```

## 6. How the Bezalel app uses the results (SVG Editor)

1. Generate the pictures from the prompts. Keep the numbers (image 1, image 2, ...).
2. In SVG Editor: load the pictures (ZIP or folder) and the timing JSON, then export the MP4.
3. For pictures, the app finds the panels, draws them in with a hand-drawn pen animation, and writes labels and captions in handwriting (switch in the Style tab, on by default).
4. **Heading and subscribe slides are replaced by the app.** If the JSON marks a canvas as a subscribe slide (one panel or fewer that mentions "subscribe") or a heading slide (one panel, no label, short `verse`), the app ignores the picture and draws a white screen with the words handwritten in black Patrick Hand. You can skip generating images for these canvases and still keep them in the JSON. The skills still write a prompt for them; you may simply not generate it.
5. The HF **Disclaimer** is a real picture (it has a label and a scene), so it needs its image. Alternative: in the Images tab set that image to Heading and type your own words to make it a handwritten slide.
6. Per-image control in the Images tab: Auto / Picture / Heading / Subscribe, with editable words.
7. Export runs in the background with a notification; everything works offline.

**Not yet confirmed on a phone:** the app has been tested by its automated build and tests only. Check on the phone: handwriting on text slides, whether a ribbon heading is handwritten or stays as drawn by the AI, a text slide with no picture loaded, and a background export.

## 7. What the skills do NOT do

- No cleaning, syncing, merging, splitting or retiming of the SRT.
- No web search, no images are produced (only prompts and JSON).
- No commentary after a batch.
- BH: no doctrine decisions, no events the script does not say. HF: no medical, financial or legal claim, cure or promise that the script does not state.
- No inset or overlapping panels.

## 8. Quick start

1. Clean your SRT first (master SRT) and have the script .txt ready.
2. Open a new chat, attach the skill, send the SRT and the script.
3. Read the plan line, answer with "1 to 20".
4. Copy the JSON block into a `.json` file, generate images from the prompt block.
5. Repeat with "21 to 40" until done. Load everything into the SVG Editor and export.
