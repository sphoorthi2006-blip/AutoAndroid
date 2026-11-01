"""AUTO audio-analysis engine (Steps 11-16).

Order of work (as the project brief requires):
  1. decode  -> 16 kHz mono float32 (ffmpeg)
  2. Silero VAD -> speech segments / silences
  3. Faster-Whisper -> what is ACTUALLY spoken (verified transcript, rough word times)
  4. compare transcript with the supplied captions (missing / extra words, match ratio)
  5. WhisperX wav2vec2 forced alignment of the VERIFIED transcript -> precise word times
     (skipped with a warning if the transcript is too unreliable or WhisperX is not installed)
  6. clamp word times to real speech (VAD), report everything with confidences + warnings
Nothing here invents timings: when a stage cannot run, the result says so in `warnings` and `stages`.
"""
from __future__ import annotations
import difflib, re, subprocess
from dataclasses import dataclass, field, asdict
from typing import Callable, Optional, Protocol
import numpy as np

SR = 16000
VAD_WINDOW = 512                      # samples @16k = 32 ms (Silero requirement)
RESULT_VERSION = 1


# ---------------------------------------------------------------- audio
def decode_audio(path: str) -> np.ndarray:
    """Any audio/video file -> float32 mono 16 kHz. Raises RuntimeError with ffmpeg's message if unreadable."""
    p = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", path, "-vn", "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                       capture_output=True)
    if p.returncode != 0 or not p.stdout:
        raise RuntimeError("Audio could not be decoded: " + p.stderr.decode(errors="replace").strip()[:300])
    return np.frombuffer(p.stdout, dtype=np.float32).copy()


# ---------------------------------------------------------------- VAD
def vad_probabilities(x: np.ndarray) -> np.ndarray:
    from silero_vad_lite import SileroVAD
    vad = SileroVAD(SR)
    n = len(x) // VAD_WINDOW
    out = np.zeros(n, dtype=np.float32)
    for i in range(n):
        out[i] = vad.process(np.ascontiguousarray(x[i * VAD_WINDOW:(i + 1) * VAD_WINDOW], dtype=np.float32))
    return out


def probs_to_segments(probs, threshold=0.5, min_speech=0.25, min_silence=0.30, pad=0.04, total: Optional[float] = None):
    """Frame probabilities -> [(start, end)] speech segments. Pure function (unit tested)."""
    w = VAD_WINDOW / SR
    segs, start, last = [], None, None
    for i, p in enumerate(probs):
        t = i * w
        if p >= threshold:
            if start is None:
                start = t
            last = t + w
        elif start is not None and t - last >= min_silence:
            segs.append([start, last]); start = None
    if start is not None:
        segs.append([start, last])
    segs = [s for s in segs if s[1] - s[0] >= min_speech]
    end = total if total is not None else len(probs) * w
    return [(max(0.0, a - pad), min(end, b + pad)) for a, b in segs]


def silences_between(speech, min_len=0.3):
    """Inner gaps between speech segments (lead-in and tail are not pauses)."""
    return [(a[1], b[0]) for a, b in zip(speech, speech[1:]) if b[0] - a[1] >= min_len]


# ---------------------------------------------------------------- text helpers
def tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", text.lower().replace("’", "'"))


def compare_tokens(spoken: list[str], caption: list[str]) -> dict:
    """Word-level diff between what was spoken and what the captions say."""
    sm = difflib.SequenceMatcher(a=caption, b=spoken, autojunk=False)
    missing, extra = [], []      # missing = in captions but not spoken; extra = spoken but not in captions
    for op, a0, a1, b0, b1 in sm.get_opcodes():
        if op in ("delete", "replace"):
            missing.append({"at_caption_word": a0, "words": caption[a0:a1]})
        if op in ("insert", "replace"):
            extra.append({"at_spoken_word": b0, "words": spoken[b0:b1]})
    return {"ratio": round(sm.ratio(), 4), "caption_words": len(caption), "spoken_words": len(spoken),
            "missing_in_audio": missing[:200], "extra_in_audio": extra[:200]}


# ---------------------------------------------------------------- pluggable models
@dataclass
class Word:
    word: str
    start: float
    end: float
    score: float = 1.0


class ASR(Protocol):
    name: str
    def transcribe(self, x: np.ndarray, language: Optional[str], prompt: Optional[str]) -> tuple[list[Word], str]: ...


class Aligner(Protocol):
    name: str
    def align(self, x: np.ndarray, words: list[Word], language: str) -> list[Word]: ...


class FasterWhisperASR:
    name = "faster-whisper"

    def __init__(self, model_size: str = "small", device: str = "auto", compute_type: str = "default"):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(model_size, device=device, compute_type=compute_type)

    def transcribe(self, x, language, prompt):
        segs, info = self.model.transcribe(x, language=language, word_timestamps=True, vad_filter=False,
                                           condition_on_previous_text=False, initial_prompt=prompt, beam_size=5)
        words = [Word(w.word.strip(), float(w.start), float(w.end), float(w.probability)) for s in segs for w in (s.words or []) if w.word.strip()]
        return words, info.language


class WhisperXAligner:
    """wav2vec2 forced alignment of the verified words. Needs `pip install whisperx` (torch)."""
    name = "whisperx"

    def __init__(self, device: str = "cpu"):
        import whisperx
        self.wx, self.device, self._models = whisperx, device, {}

    def align(self, x, words, language):
        if language not in self._models:
            self._models[language] = self.wx.load_align_model(language_code=language, device=self.device)
        model, meta = self._models[language]
        text = " ".join(w.word for w in words)
        seg = [{"text": text, "start": words[0].start, "end": words[-1].end}]
        res = self.wx.align(seg, model, meta, x, self.device, return_char_alignments=False)
        out = []
        for s in res["segments"]:
            for w in s.get("words", []):
                if "start" in w and "end" in w:
                    out.append(Word(w["word"], float(w["start"]), float(w["end"]), float(w.get("score", 0.0))))
        return out


def clamp_to_speech(words: list[Word], speech) -> tuple[list[Word], int]:
    """A word that begins in silence is moved to the next real speech start (and kept in order). Returns (words, moved)."""
    if not speech:
        return words, 0
    moved, out, prev_end = 0, [], 0.0
    for w in words:
        s, e = w.start, w.end
        inside = any(a - 0.08 <= s <= b + 0.08 for a, b in speech)
        if not inside:
            nxt = next((a for a, _ in speech if a >= s), None)
            if nxt is not None and nxt - s < 1.5:
                dur = max(e - s, 0.05); s = nxt; e = s + dur; moved += 1
        s = max(s, prev_end - 0.0); e = max(e, s + 0.01); prev_end = e
        out.append(Word(w.word, round(s, 3), round(e, 3), w.score))
    return out, moved


# ---------------------------------------------------------------- the whole analysis
@dataclass
class Result:
    version: int = RESULT_VERSION
    duration: float = 0.0
    language: str = ""
    transcript: str = ""
    segments: list = field(default_factory=list)         # Whisper-JSON compatible (what Captions.parse() reads)
    speech: list = field(default_factory=list)
    silences: list = field(default_factory=list)
    caption_diff: Optional[dict] = None
    alignment: str = "none"                               # "whisperx" | "whisper" | "none"
    word_score_mean: float = 0.0
    stages: dict = field(default_factory=dict)
    warnings: list = field(default_factory=list)

    def to_dict(self): return asdict(self)


def analyze(audio_path: str, asr: ASR, aligner: Optional[Aligner] = None, script_text: str = "", caption_text: str = "",
            language: Optional[str] = None, progress: Callable[[str, float], None] = lambda s, f: None,
            vad_probs_fn=vad_probabilities, min_caption_ratio: float = 0.6) -> Result:
    r = Result()
    progress("decode", 0.02)
    x = decode_audio(audio_path)
    r.duration = round(len(x) / SR, 3)
    if r.duration < 0.5:
        raise RuntimeError("The audio is empty or shorter than half a second.")
    r.stages["decode"] = "ok"

    progress("vad", 0.08)
    try:
        probs = vad_probs_fn(x)
        r.speech = [list(map(lambda v: round(v, 3), s)) for s in probs_to_segments(probs, total=r.duration)]
        r.silences = [list(map(lambda v: round(v, 3), s)) for s in silences_between(r.speech)]
        r.stages["vad"] = "ok"
        if not r.speech:
            r.warnings.append("Voice activity detection found NO speech in the audio.")
    except Exception as e:
        r.stages["vad"] = f"failed: {e}"; r.warnings.append(f"Silero VAD failed ({e}); speech/silence regions are unavailable.")

    progress("asr", 0.15)
    prompt = " ".join(script_text.split()[:120]) or None      # spelling hints only; the transcript is still what the audio says
    words, lang = asr.transcribe(x, language, prompt)
    r.language = lang; r.stages["asr"] = f"ok ({asr.name}, {len(words)} words)"
    if not words:
        raise RuntimeError("Speech recognition returned no words; the narration may be silent or unreadable.")
    r.transcript = " ".join(w.word for w in words)

    spoken = tokens(r.transcript)
    if caption_text.strip():
        r.caption_diff = compare_tokens(spoken, tokens(caption_text))
        ratio = r.caption_diff["ratio"]
        if ratio < min_caption_ratio:
            r.warnings.append(f"Captions and audio differ a lot (match {ratio:.0%}). Timing is taken from the AUDIO, not the captions.")
        elif r.caption_diff["missing_in_audio"] or r.caption_diff["extra_in_audio"]:
            r.warnings.append(f"Captions differ from the spoken words (match {ratio:.0%}); {len(r.caption_diff['missing_in_audio'])} place(s) with words not spoken, "
                              f"{len(r.caption_diff['extra_in_audio'])} with extra spoken words.")
    if script_text.strip():
        sd = compare_tokens(spoken, tokens(script_text))
        r.stages["script_vs_audio_ratio"] = sd["ratio"]
        if sd["ratio"] < min_caption_ratio:
            r.warnings.append(f"The narration matches the script only {sd['ratio']:.0%}. Check the audio and script belong together.")

    r.alignment = "whisper"
    if aligner is not None:
        progress("align", 0.6)
        try:
            aligned = aligner.align(x, words, lang)
            if len(aligned) >= 0.9 * len(words):
                words, r.alignment = aligned, aligner.name
                r.stages["align"] = f"ok ({aligner.name}, {len(aligned)}/{len(spoken)} words)"
            else:
                r.stages["align"] = f"rejected: only {len(aligned)} of {len(words)} words aligned"
                r.warnings.append("Forced alignment covered too few words; Faster-Whisper's own word times are used instead.")
        except Exception as e:
            r.stages["align"] = f"failed: {e}"
            r.warnings.append(f"WhisperX alignment failed ({e}); Faster-Whisper's own word times are used instead (usually good to ~0.1-0.2 s).")
    else:
        r.stages["align"] = "skipped (aligner not installed)"
        r.warnings.append("WhisperX is not installed on the server; Faster-Whisper's own word times are used (usually good to ~0.1-0.2 s).")

    progress("finish", 0.9)
    words, moved = clamp_to_speech(words, r.speech)
    if moved:
        r.stages["clamped_to_speech"] = moved
    r.word_score_mean = round(float(np.mean([w.score for w in words])), 3)
    low = [w for w in words if w.score < 0.4]
    if len(low) > 0.1 * len(words):
        r.warnings.append(f"{len(low)} of {len(words)} words have low recognition/alignment confidence; check the timeline near them.")
    if r.speech and words[-1].end > r.speech[-1][1] + 1.0:
        r.warnings.append("The last words end well after the last detected speech; their timing may be off.")
    r.segments = [{"start": words[0].start, "end": words[-1].end, "text": r.transcript,
                   "words": [{"word": w.word, "start": w.start, "end": w.end, "score": round(w.score, 3)} for w in words]}]
    progress("done", 1.0)
    return r
