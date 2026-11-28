// Pure helpers for the Voice Studio (no browser APIs, tested with Node).

// Gemini prebuilt voices that suit narration and conversation.
export const VOICES = [
  // male
  { id: "Puck", label: "Puck - male, upbeat and friendly", g: "m" },
  { id: "Charon", label: "Charon - male, calm narrator", g: "m" },
  { id: "Achird", label: "Achird - male, friendly", g: "m" },
  { id: "Zubenelgenubi", label: "Zubenelgenubi - male, casual", g: "m" },
  { id: "Sadaltager", label: "Sadaltager - male, knowledgeable", g: "m" },
  { id: "Orus", label: "Orus - male, firm", g: "m" },
  { id: "Algenib", label: "Algenib - male, deep and gravelly", g: "m" },
  { id: "Iapetus", label: "Iapetus - male, clear", g: "m" },
  { id: "Umbriel", label: "Umbriel - male, easy-going", g: "m" },
  { id: "Enceladus", label: "Enceladus - male, soft and breathy", g: "m" },
  // female
  { id: "Sulafat", label: "Sulafat - female, warm", g: "f" },
  { id: "Kore", label: "Kore - female, firm", g: "f" },
  { id: "Aoede", label: "Aoede - female, breezy", g: "f" },
  { id: "Achernar", label: "Achernar - female, soft", g: "f" },
  { id: "Vindemiatrix", label: "Vindemiatrix - female, gentle", g: "f" },
  { id: "Callirrhoe", label: "Callirrhoe - female, easy-going", g: "f" },
  { id: "Leda", label: "Leda - female, youthful", g: "f" },
  { id: "Gacrux", label: "Gacrux - female, mature", g: "f" },
];

// Newest first. If one is unavailable or out of free quota, the next is tried.
export const MODELS = [
  "gemini-3.8-flash-tts",
  "gemini-3.8-flash-lite-tts",
  "gemini-3.1-flash-tts-preview",
  "gemini-2.5-flash-preview-tts",
];

export const DEFAULT_STYLE = "Read this in a natural, relaxed, conversational way, like a person talking to a friend. Use natural pauses and expression.";

// "Name: words" lines. Returns { speakers: [names], lines: [{speaker, text}] } or null
// when the script is not a two-person conversation.
export function parseDialogue(text) {
  const raw = text.replace(/\r\n?/g, "\n").split("\n").map((l) => l.trim()).filter(Boolean);
  if (raw.length < 2) return null;
  const re = /^([A-Za-z][A-Za-z0-9 .'-]{0,24}?)\s*[:\-–—]\s+(.+)$/;
  const lines = [];
  let tagged = 0;
  for (const l of raw) {
    const m = l.match(re);
    if (m && !/^(note|chapter|scene|part|step|title)$/i.test(m[1].trim())) {
      lines.push({ speaker: m[1].trim(), text: m[2].trim() });
      tagged++;
    } else if (lines.length) {
      lines[lines.length - 1].text += " " + l; // continuation of the previous speaker
    } else {
      return null;
    }
  }
  const speakers = [...new Set(lines.map((x) => x.speaker))];
  if (speakers.length !== 2 || tagged < 2) return null;
  return { speakers, lines };
}

// Split plain narration into pieces of up to maxLen characters at sentence ends.
export function splitNarration(text, maxLen = 2500) {
  const paras = text.replace(/\r\n?/g, "\n").split(/\n\s*\n/).map((p) => p.replace(/\s+/g, " ").trim()).filter(Boolean);
  const out = [];
  let cur = "";
  const push = () => { if (cur.trim()) out.push(cur.trim()); cur = ""; };
  for (const p of paras) {
    const sentences = p.match(/[^.!?]+[.!?]+["')\]]*\s*|[^.!?]+$/g) || [p];
    for (let s of sentences) {
      s = s.trim();
      while (s.length > maxLen) { // very long sentence
        let cut = s.lastIndexOf(", ", maxLen);
        if (cut < maxLen * 0.5) cut = s.lastIndexOf(" ", maxLen);
        if (cut <= 0) cut = maxLen;
        push(); out.push(s.slice(0, cut + 1).trim()); s = s.slice(cut + 1).trim();
      }
      if (cur && (cur.length + s.length + 1) > maxLen) push();
      cur += (cur ? " " : "") + s;
    }
    if (cur) cur += "\n\n";
  }
  push();
  return out.filter((c) => /[A-Za-z0-9]/.test(c));
}

// Group dialogue lines into pieces of up to maxLen characters, keeping whole lines.
export function splitDialogue(lines, maxLen = 2500) {
  const out = [];
  let cur = [];
  let len = 0;
  for (const l of lines) {
    const s = `${l.speaker}: ${l.text}`;
    if (cur.length && len + s.length + 1 > maxLen) { out.push(cur.join("\n")); cur = []; len = 0; }
    cur.push(s); len += s.length + 1;
  }
  if (cur.length) out.push(cur.join("\n"));
  return out;
}

export function buildPrompt(piece, style, dialogue) {
  const st = (style || "").trim() || DEFAULT_STYLE;
  if (dialogue) return `${st}\nTTS the following conversation between ${dialogue.speakers[0]} and ${dialogue.speakers[1]}:\n${piece}`;
  return `${st}\n\n${piece}`;
}

export function buildRequest(prompt, voice, dialogue, voice2) {
  const speechConfig = dialogue
    ? { multiSpeakerVoiceConfig: { speakerVoiceConfigs: [
        { speaker: dialogue.speakers[0], voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } },
        { speaker: dialogue.speakers[1], voiceConfig: { prebuiltVoiceConfig: { voiceName: voice2 } } },
      ] } }
    : { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } };
  return {
    contents: [{ parts: [{ text: prompt }] }],
    generationConfig: { responseModalities: ["AUDIO"], speechConfig },
  };
}

// The API returns base64 audio: raw 16-bit PCM ("audio/L16;rate=24000") or a WAV file.
// Returns { pcm: Int16Array, rate }.
export function decodeAudio(bytes, mimeType) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const isWav = u8.length > 12 && String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) === "RIFF" &&
    String.fromCharCode(u8[8], u8[9], u8[10], u8[11]) === "WAVE";
  if (isWav) {
    const v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    let off = 12, rate = 24000, channels = 1, bits = 16;
    while (off + 8 <= u8.length) {
      const id = String.fromCharCode(u8[off], u8[off + 1], u8[off + 2], u8[off + 3]);
      let size = v.getUint32(off + 4, true);
      if (id === "fmt ") {
        channels = v.getUint16(off + 10, true);
        rate = v.getUint32(off + 12, true);
        bits = v.getUint16(off + 22, true);
      } else if (id === "data") {
        if (size === 0 || size === 0xffffffff || off + 8 + size > u8.length) size = u8.length - off - 8;
        if (bits !== 16) throw new Error("unsupported audio format");
        const frames = Math.floor(size / 2 / channels);
        const pcm = new Int16Array(frames);
        for (let i = 0; i < frames; i++) pcm[i] = v.getInt16(off + 8 + i * 2 * channels, true);
        return { pcm, rate };
      }
      off += 8 + size + (size % 2);
    }
    throw new Error("audio file had no data");
  }
  const m = /rate=(\d+)/i.exec(mimeType || "");
  const rate = m ? Number(m[1]) : 24000;
  const n = Math.floor(u8.length / 2);
  const pcm = new Int16Array(n);
  const v = new DataView(u8.buffer, u8.byteOffset, n * 2);
  for (let i = 0; i < n; i++) pcm[i] = v.getInt16(i * 2, true);
  return { pcm, rate };
}

// Join PCM pieces with a short pause between them, as one mono 16-bit WAV (bytes).
export function joinToWav(pieces, rate, pauseSec = 0.35) {
  const gap = Math.floor(rate * pauseSec);
  let total = 0;
  pieces.forEach((p, i) => { total += p.length + (i ? gap : 0); });
  const buf = new ArrayBuffer(44 + total * 2);
  const v = new DataView(buf);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, "RIFF"); v.setUint32(4, 36 + total * 2, true); w(8, "WAVE");
  w(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  w(36, "data"); v.setUint32(40, total * 2, true);
  let off = 44;
  pieces.forEach((p, i) => {
    if (i) off += gap * 2;
    for (let j = 0; j < p.length; j++, off += 2) v.setInt16(off, p[j], true);
  });
  return new Uint8Array(buf);
}

// Seconds to wait from a 429 error body (retryDelay like "21s"), default 20.
export function retryDelaySec(errBody) {
  try {
    const details = (errBody && errBody.error && errBody.error.details) || [];
    for (const d of details) {
      if (d && d.retryDelay) { const s = parseFloat(String(d.retryDelay)); if (s > 0) return Math.min(s, 90); }
    }
  } catch (e) { /* ignore */ }
  return 20;
}
