---
name: svg-hf
description: Turns a finished health, food, nutrition, medicine, wellness, money, investing or business-explainer voice-over (a MASTER SRT already cleaned and cut into cues, plus the script .txt) into storyboard-canvas image prompts, one canvas per script paragraph with one panel per caption cue, in semi-realistic style with circle/rectangle panel layouts, comic layouts and ribbon headings, plus the matching timing JSON for the batch. Does NOT clean, sync or re-cut the SRT. Use when the user wants canvas prompts, panel-layout prompts, ribbon-heading prompts, paragraph-per-image prompts or timing JSON for such a video, or says "1 to 20 canvases".
---

# SVG HF - Health and Finance

One **script paragraph = one canvas image**. Every caption cue inside that paragraph = one **panel**. Each canvas uses one of two layout systems, chosen from what the paragraph does: **Layout 1** (circles and rectangles in rows, with dashed arrows where one panel causes the next) to explain, compare, list and chain causes, or **Layout 2** (comic layouts, the key beat in the biggest panel, no arrows) to narrate a story, case or sequence. A **black ribbon heading** appears only on canvases of a section that has a spoken heading. The art inside the panels is the semi-realistic Health style (Finance uses the same style with money objects as heroes). The skill reads the master SRT and the script, tells the user how many canvases the script needs, writes the prompts for the batch the user asks for, and writes the matching timing JSON.

Use this for: foods, drinks, herbs, vitamins, minerals, organs, body systems, symptoms, medicine and wellness (HEALTH pack); money, saving, investing, banking, debt, taxes, economics, business and tech-cost explainers (FINANCE pack). A video that mixes both keeps ONE look (this one) and uses the pack of each cue's subject. If the video is a Bible, church-history, history, true-crime or technology/business *story*, say so in one line and tell the user to use `svg-bh`.

Files (no scripts, nothing is run):
- `rules.md`: fixed limits, fidelity, locks, labels, output, self-check. Read in full once per chat, before the first reply.
- `layouts.md`: how to choose, both layout libraries, ribbon.
- `template.md`: the prompt skeleton pasted into every canvas prompt (with a strict, mandatory outer frame on every canvas).
- `procedure.md`: how to make the canvas plan by reading, and the timing JSON format with its checks.
- `health.md`: the style, heroes, people, main man. `finance.md`: only for money content.
Read each file in full once per chat, before the first reply.

## Disclaimer canvas
A disclaimer is always its own one-panel canvas: ribbon `DISCLAIMER` on top, and below it ONE picture (doctor with a "NOT MEDICAL ADVICE" notebook) with the title label `A QUICK NOTE` (the notebook already says NOT MEDICAL ADVICE). Details in `procedure.md`. The ribbon appears on no other canvas.

## Inputs
Required: (1) the MASTER SRT (already cleaned and cut into cues by the user's cleaning step), (2) the script `.txt` the voice-over was read from. Optional: pace note, a batch number or range, a rotation or gender instruction for new characters, a ready-made canvas plan. The main man's reference image is attached by the user in the image tool; the prompts only refer to it.
- Script missing: the first reply is only `Please also send your script .txt file. I use it to know which cues belong to which paragraph, and then I tell you how many canvases it needs.` Wait.
- If the SRT still shows transcriber watermarks, sound tags like [Music], or cue wording that clearly differs from the script, say in one line that it should go through the cleaning step first, then continue only if the user says to use it as is.
- Use the cues and timestamps exactly as given. Never merge, split, retime or reword a cue.

## First reply: status lines and the batch question, nothing else
Make the canvas plan silently by `procedure.md` (P1 to P5), then reply with:
1. `Plan ready. Your script needs C canvases (C prompts) with P panels in total: S single-panel canvases, and paragraphs longer than 8 cues were split evenly (list them).` Add `The script has no blank lines, so every line is a paragraph.` when that applies.
2. If there are ribbon sections, one line: `Ribbon sections: 1. ..., 2. ...` (the exact heading words).
3. `How many canvases do you want first? Give a number (for example 20) or a range (for example 1 to 20). Best batch size: 10 to 20.`
Do all silent analysis before the next reply, not in this one. Read the whole script once and decide silently the pack for each cue, the setting of every cue (script-only; American by default), the cast (the main man is the narrator of "I" and "you" scripts) and the recurring objects; these are the **locks**.

## Each batch (canvases A to B)
1. From your plan, take canvases A to B with their cues. Use the locks. The previous canvas's layout is in your layout log.
2. Silently, for each canvas: name the story shape, count the beats, choose the layout system and the layout by `layouts.md` (rhythm rules included), find the key beat or the circles, decide where arrows are real causes (Layout 1 only), set the gutter, write the 1 to 4 word label of every panel, decide caption boxes and balloons, plan every panel's scene by `rules.md` and the pack.
3. Reply with **exactly two code blocks and nothing else**: first the timing JSON for canvases A to B (format and checks in `procedure.md`), then ONE code block with every canvas prompt of the batch, built from `template.md`, using the same labels as the JSON. No text before, between or after.
4. Update the locks (new characters, the layouts used in this batch, in order). Stop. Wait for the next number or range.

**Hard cap: 20 canvases per reply.** A canvas prompt is long (about 1,300 to 1,800 tokens) because the whole template is pasted every time. If the user asks for more than 20, deliver the first 20 and add one short line after the blocks: `Delivered canvases A to B. Send the next number or range to continue.` Never shorten or drop template blocks to save space; a complete shorter batch beats a truncated longer one.

## The locks (working memory of this chat)
The locks are: the pack; the era per stretch of the script; every character's full lock (the exact `CHARACTER RULES` wording; the main man's exact line); every recurring object with its fixed color, shape and size (coin, card and cost colors in Finance); and a layout log, one line per delivered canvas (`12 Layout 2 Turning point | standard | ribbon "1. Heading"`). Later batches copy the lock lines word for word. Print the locks only when the user asks for them (they send `locks`), in one code block, so they can paste them into a new chat together with the master SRT and the script. One chat per video keeps the context small; for more than 60 canvases suggest a new chat every 40 to 50 canvases.

## Redo and later requests
"Redo A to B" regenerates exactly those canvases (two code blocks), reusing their layouts unless the user asks otherwise. A request for a batch you already delivered is a redo.

## What this skill does not do
No web search, no images, no commentary after a batch. It adds no medical, financial or legal claim, cure, promise or result that the script does not state. Inset and overlapping panels are not used (their labels cannot sit cleanly with them); organ label lines with arrowheads are not used inside canvas panels.
