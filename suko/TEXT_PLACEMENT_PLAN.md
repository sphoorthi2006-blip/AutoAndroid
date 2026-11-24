# Text on video: 4-step plan (resume from here if credits run out)

Goal: type text for a cue range (e.g. 123-125 or 125); it is handwritten straight into the final video
instead of those cues' images. No separate export. Black bg + white text (default) or white bg + black text.
Centre/left, size, position kept. Button: "Add to video". Writes over ~80% of the range, then holds.
Overlap with a scene clip or another text = error. Headings/subscribe cues keep working. Text cues need no image.

1. [DONE] Logic: TextPlacement model, range parsing, Checker rules (range, empty, overlaps, no image needed),
   ExportPlanner TEXT segment, report CSV. Tests: app/src/test/.../TextPlacementTest.kt (11 pass).
   (placeholder replaced in step 2)
2. [DONE] Drawing: render TEXT segments in VideoExport (reuse HeadingWrite 80% write + hold, Handwriting/Pen, (TextLayout.kt, SlideRenderer.textInk, HeadingPainter background, VideoExport TEXT branch, clicks like a clip; tests TextLayoutTest. Phone drawing not yet run: needs a build.)
   click sounds), with layout for centre/left, size, x/y, and the two looks. Test the layout maths.
3. [DONE] Screen (VideoTextSection.kt, UiState.texts, addText/removeText, preview of placed text; not yet built on a phone. Drag-to-position in the preview is not done: sliders move the text instead.): "Text on video" card in the slide builder (range, text, look, centre/left, size, position,
   Add to video, list of added texts with remove) wired to AppViewModel -> Checker texts. Preview in cue preview.
4. [DONE] Remove: deleted the standalone Text Editor (web/ project with its MP4 export, the web host, bridge, prebuilt web assets, web tests), the Hub card, unused manifest entries and permissions, and the web steps of the build workflow. The Auto Editor is now the only tool.

Follow-up (done): the "Text on video" card shows a live picture of the finished text that can be dragged to move it
(AppViewModel.showDraft); the Slide preview's cue box now follows the preview, so the list's Preview buttons fill it in.
