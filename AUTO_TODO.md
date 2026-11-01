# AUTO - TODO in execution order
Rule: run scripts/run_parity.sh before and after every change; update AUTO_PROGRESS.md.

1. (S6) Add Gradle wrapper (8.7) + `libs.versions.toml`; keep :core pure JVM. Make CI run `gradle :core:test` style check + assembleDebug. First real compile of :app - fix whatever breaks.
2. (S38-40) DONE in code, uncompiled: exact-boundary durations, one narration item, ExportSettings, MediaStore error message. REMAINING: compile + run on device and measure A/V length; settings screen; foreground service; captions burn-in (OverlayEffect/TextOverlay); handle partial-coverage case via Timeline too.
3. (S9) DONE in core: DOCX, ValidationReport. REMAINING: review screen with the 3 outcomes (accept / pick other image / adjust timing); numeric confidence.
4. (S7) Project store: app-private folder per project, `project.json` = inputs manifest + Timeline.toJson, atomic writes, recovery on start. Stop copying all pictures into cache; copy once into project folder.
5. (S37) Preview: ExoPlayer for narration + image drawn from `Timeline.clipAt(playerPositionMs)`; captions drawn from CaptionClip. Exact by construction.
6. (S29-36) Compose timeline UI on top of TimelineEditor: ruler, 4 tracks, pinch zoom, playhead drag, clip handles, waveform (precompute peaks from decoded samples), undo/redo buttons.
7. (S25-27) Pause policy setting (keep image / switch at pause / flag long pause > N s); numeric confidence per scene (from Matching + cut quality).
8. (S10-16) Optional backend `/analyze` (FastAPI): faster-whisper -> verified transcript; WhisperX alignment -> words. OUTPUT FORMAT = Whisper-JSON `{"segments":[{"words":[{"word","start","end","score"}]}]}` which Captions.parse() already reads - no core change needed. Add transcript-vs-caption diff (missing/extra words).
9. (S17-22) Only if step 8 shows ambiguity remains: LLM fallback for scenes the deterministic matcher flags "?"; vision check as an explicit "Verify pictures" button, results are WARNINGS only, never silent re-assignment.
10. (S41-45) Real-project tests, 300+ images, 1 h narration memory test (stream peaks/VAD instead of one FloatArray), handover docs.

Needs from the owner: a real sample project (audio + script + SRT + details + images zip); a physical device or emulator log of first run; (for step 8) hosting choice + Gemini API key held server-side only.
