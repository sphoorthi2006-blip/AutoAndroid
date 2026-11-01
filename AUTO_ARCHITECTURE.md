# AUTO - ARCHITECTURE & DECISIONS

## Current (audited)
- `core/` (pure Kotlin/JVM, 1.5 kLOC): Captions (SRT/VTT/Whisper-JSON/timed lines) -> Details (Image N blocks) -> Bundle (file role detection) -> Align (Viterbi-style word->scene segmentation, spoken-title pull-in) -> AudioPauses (energy envelope; refineCuts, anchorCuts DP) -> Matching (number / filename words / folder names, Hungarian assignment, section inference, missing-picture flags) -> Pipeline.analyse().
- `app/` (3 files, plain `Activity`, View-based): AudioDecoder (MediaCodec -> 16 kHz mono floats), MainActivity (4 buttons + text list), Exporter (Media3 Transformer 1.3.1: one image item per scene + narration clips; text/black screens rendered with Canvas).
- New: `core/Timeline.kt` - immutable ms-precision timeline, edit ops, TimelineEditor (undo/redo), validate(), JSON persistence. Tested by Check7.kt.

## Decisions (do not change without a good reason)
1. Keep deterministic matching/alignment in :core; it is tested and AI-free. AI is additive and optional.
2. Timeline is integer milliseconds and immutable snapshots; clips contiguous by construction from `fromScenes`; edits never touch narration.
3. Backend output must be consumable by the existing `Captions.parse` (Whisper-JSON words) so the core stays unchanged.
4. Vision/LLM results produce warnings for manual review; they never silently reassign pictures.
5. No API keys in the app; backend holds them. If backend unreachable, the app falls back to captions + energy pauses and says so.
6. Preview position is derived from the audio player clock; image = Timeline.clipAt(position).
7. Export keeps Media3 Transformer; durations derived from timeline ms boundaries.

## Honest assessment of the requested AI stack (value over what exists)
- Faster-Whisper + WhisperX: HIGH value. Clipchamp captions are cue-level; Captions.spread() fakes word times by letter count, and the code never checks that captions match the audio. Real word times and a transcript diff are the genuine gap.
- Silero VAD: LOW-MEDIUM. Narration is clean TTS; the energy detector already needs a 12 dB speech/floor margin and bails out otherwise. Add only if real projects show missed pauses.
- Gemini text LLM: LOW as default; useful only as fallback for scenes the matcher marks uncertain.
- Gemini Vision: MEDIUM as opt-in verification (code never looks at pixels today).
