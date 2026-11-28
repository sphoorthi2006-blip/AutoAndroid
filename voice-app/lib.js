// Pure helpers (no browser or network needs), so they can be tested with Node.

export const VOICE_BASE =
  "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/";

// American (af_/am_) and British (bf_/bm_) English voices in Kokoro v1.0
export const VOICES = [
  ["af_heart", "Heart (US, female)"], ["af_bella", "Bella (US, female)"],
  ["af_nicole", "Nicole (US, female)"], ["af_sarah", "Sarah (US, female)"],
  ["af_sky", "Sky (US, female)"], ["af_nova", "Nova (US, female)"],
  ["af_river", "River (US, female)"], ["af_kore", "Kore (US, female)"],
  ["af_aoede", "Aoede (US, female)"], ["af_alloy", "Alloy (US, female)"],
  ["af_jessica", "Jessica (US, female)"],
  ["am_michael", "Michael (US, male)"], ["am_fenrir", "Fenrir (US, male)"],
  ["am_puck", "Puck (US, male)"], ["am_adam", "Adam (US, male)"],
  ["am_eric", "Eric (US, male)"], ["am_liam", "Liam (US, male)"],
  ["am_echo", "Echo (US, male)"], ["am_onyx", "Onyx (US, male)"],
  ["bf_emma", "Emma (UK, female)"], ["bf_isabella", "Isabella (UK, female)"],
  ["bf_alice", "Alice (UK, female)"], ["bf_lily", "Lily (UK, female)"],
  ["bm_george", "George (UK, male)"], ["bm_fable", "Fable (UK, male)"],
  ["bm_lewis", "Lewis (UK, male)"], ["bm_daniel", "Daniel (UK, male)"],
];

// Suggested starting points for English narration. "mix" is the percent of voice A.
// These are starting points to tune by ear, not tested guarantees.
export const PRESETS = [
  { id: "storyteller", label: "Warm storyteller (female + male)", a: "af_heart", b: "am_michael", mix: 65, speed: 0.95 },
  { id: "friendly", label: "Friendly conversation (female)", a: "af_heart", b: "af_bella", mix: 60, speed: 1.0 },
  { id: "balanced", label: "Balanced narrator (female + male)", a: "af_bella", b: "am_michael", mix: 55, speed: 1.0 },
  { id: "documentary", label: "Deep documentary (male)", a: "am_michael", b: "am_fenrir", mix: 60, speed: 0.95 },
  { id: "uk-male", label: "Calm British (male)", a: "bm_george", b: "bm_fable", mix: 60, speed: 0.95 },
  { id: "uk-female", label: "Elegant British (female)", a: "bf_emma", b: "bf_isabella", mix: 70, speed: 1.0 },
  { id: "upbeat", label: "Upbeat explainer (female)", a: "af_heart", b: "af_nicole", mix: 60, speed: 1.05 },
  { id: "heart", label: "Single voice: Heart", a: "af_heart", b: "", mix: 100, speed: 1.0 },
];

// Weighted average of two voice style tables: out = wA*a + (1-wA)*b
export function blendStyles(a, b, wA) {
  const n = Math.min(a.length, b.length);
  const out = new Float32Array(n);
  const wB = 1 - wA;
  for (let i = 0; i < n; i++) out[i] = a[i] * wA + b[i] * wB;
  return out;
}

// Name for a blended voice. It starts with voice A's first letter (a = US, b = UK),
// because Kokoro picks the pronunciation language from the first letter.
export function blendName(a, b, pctA) {
  return `${a[0]}_mix_${a}_${b}_${pctA}`;
}

// Split a script into sentence-sized chunks so long text works on a phone
export function chunkText(text, maxLen = 300) {
  const sentences =
    text.replace(/\s+/g, " ").trim().match(/[^.!?\n]+[.!?]*["')\]]*\s*/g) || [];
  const chunks = [];
  let cur = "";
  for (const s of sentences) {
    if ((cur + s).length > maxLen && cur) { chunks.push(cur.trim()); cur = s; }
    else cur += s;
  }
  if (cur.trim()) chunks.push(cur.trim());
  return chunks;
}

// Mono 16-bit PCM WAV file from float samples
export function floatTo16BitWav(float32, sampleRate) {
  const buffer = new ArrayBuffer(44 + float32.length * 2);
  const v = new DataView(buffer);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, "RIFF"); v.setUint32(4, 36 + float32.length * 2, true); w(8, "WAVE");
  w(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, sampleRate, true); v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  w(36, "data"); v.setUint32(40, float32.length * 2, true);
  let off = 44;
  for (let i = 0; i < float32.length; i++, off += 2) {
    const s = Math.max(-1, Math.min(1, float32[i]));
    v.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Blob([buffer], { type: "audio/wav" });
}
