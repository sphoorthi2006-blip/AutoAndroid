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
  { id: "uk-male", label: "Calm British (male)", a: "bm_george", b: "bm_fable", mix: 60, speed: 0.95 },
  { id: "uk-female", label: "Elegant British (female)", a: "bf_emma", b: "bf_isabella", mix: 70, speed: 1.0 },
  { id: "upbeat", label: "Upbeat explainer (female)", a: "af_heart", b: "af_nicole", mix: 60, speed: 1.05 },
  { id: "heart", label: "Single voice: Heart", a: "af_heart", b: "", mix: 100, speed: 1.0 },
];

// ---------- voice blending ----------

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

// ---------- text preparation ----------

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", ndash: "-", mdash: "-", hellip: "...", lsquo: "'", rsquo: "'", ldquo: '"', rdquo: '"' };

export function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === "#") {
      const code = e[1].toLowerCase() === "x" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : "";
    }
    const v = ENTITIES[e.toLowerCase()];
    return v === undefined ? m : v;
  });
}

// Tidy whitespace and remove invisible junk so the voice reads cleanly
export function normalizeText(t) {
  return t
    .replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F​-‍⁠﻿]/g, "")
    .replace(/[ \t ]+/g, " ")
    .split("\n").map((l) => l.trim()).join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

// Join lines that were only wrapped by the page width (common in PDFs)
export function joinWrappedLines(t) {
  return t.replace(/([^.!?:;\n"”')\]])\n(?=[a-z0-9(])/g, "$1 ");
}

export function htmlToText(html) {
  const s = html
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<(script|style|head|noscript|svg)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<(br|hr)\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|h[1-6]|li|tr|blockquote|section|article|title)>/gi, "\n")
    .replace(/<[^>]+>/g, " ");
  return normalizeText(decodeEntities(s));
}

export function markdownToText(md) {
  const s = md
    .replace(/^```[^\n]*$/gm, "")
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/^\s{0,3}#{1,6}\s*/gm, "")
    .replace(/^\s{0,3}>\s?/gm, "")
    .replace(/^\s*([-*+]|\d+[.)])\s+/gm, "")
    .replace(/^\s*([-*_]\s*){3,}$/gm, "")
    .replace(/(\*\*|__|\*|_|~~|`)/g, "");
  return normalizeText(s);
}

// SRT / VTT subtitles: keep only the spoken words
export function subtitleToText(t) {
  const lines = t.replace(/\r\n?/g, "\n").split("\n").filter((l) => {
    const x = l.trim();
    if (!x) return false;
    if (/^WEBVTT/i.test(x) || /^(NOTE|STYLE)\b/.test(x)) return false;
    if (/^\d+$/.test(x)) return false;
    if (/-->/.test(x)) return false;
    return true;
  });
  return normalizeText(decodeEntities(lines.join(" ").replace(/<[^>]+>/g, "").replace(/\{\\[^}]*\}/g, "")));
}

// Basic RTF: drop control words and groups, keep the text
export function rtfToText(rtf) {
  let s = rtf
    .replace(/\{\\\*[^{}]*\}/g, "")
    .replace(/\{\\(fonttbl|colortbl|stylesheet|info|pict)[^]*?\n?\}/g, "")
    .replace(/\\par[d]?\b ?/g, "\n")
    .replace(/\\tab\b ?/g, " ")
    .replace(/\\'([0-9a-f]{2})/gi, (m, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\u(-?\d+)\??/g, (m, n) => String.fromCharCode(n < 0 ? Number(n) + 65536 : Number(n)))
    .replace(/\\[a-z]+-?\d* ?/gi, "")
    .replace(/[{}]/g, "");
  return normalizeText(s);
}

// Word (docx), OpenDocument (odt), PowerPoint (pptx) and similar XML: one paragraph per line
export function xmlToText(xml) {
  const s = xml
    .replace(/<\/(w:p|text:p|text:h|a:p)>/g, "\n")
    .replace(/<(w:br|w:cr|text:line-break)\s*\/?>/g, "\n")
    .replace(/<(w:tab|text:tab)\s*\/?>/g, " ")
    .replace(/<[^>]+>/g, "");
  return normalizeText(decodeEntities(s));
}

// What kind of file is this, going by its name?
export function fileKind(name) {
  const ext = (name.split(".").pop() || "").toLowerCase();
  if (["txt", "text", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "tex", "rst", "org"].includes(ext)) return "text";
  if (["md", "markdown", "mdx"].includes(ext)) return "markdown";
  if (["html", "htm", "xhtml", "mhtml"].includes(ext)) return "html";
  if (["srt", "vtt", "sbv", "ass", "ssa"].includes(ext)) return "subtitle";
  if (ext === "rtf") return "rtf";
  if (ext === "docx" || ext === "docm") return "docx";
  if (ext === "odt" || ext === "ott") return "odt";
  if (ext === "pptx") return "pptx";
  if (ext === "epub") return "epub";
  if (ext === "pdf") return "pdf";
  if (["doc", "ppt", "xls", "pages", "key", "numbers", "mobi", "azw3"].includes(ext)) return "unsupported";
  return "unknown";
}

// For files with unknown extensions: does this look like readable text?
export function looksLikeText(s) {
  if (!s.length) return false;
  let bad = 0;
  const n = Math.min(s.length, 5000);
  for (let i = 0; i < n; i++) {
    const c = s.charCodeAt(i);
    if (c === 0xFFFD || (c < 32 && c !== 9 && c !== 10 && c !== 13)) bad++;
  }
  return bad / n < 0.02;
}

// Rough audio length: about 1000 characters per minute of speech
export function estimateMinutes(chars) {
  return Math.max(0, Math.round(chars / 1000));
}

// Split a script into sentence-sized chunks so long text works on a phone
export function chunkText(text, maxLen = 300) {
  const sentences =
    text.replace(/[ \t]+/g, " ").trim().match(/[^.!?\n]+[.!?]*["')\]]*\s*/g) || [];
  const chunks = [];
  let cur = "";
  for (const s of sentences) {
    if (s.length > maxLen) {
      // very long sentence: break at commas, semicolons or spaces
      if (cur.trim()) { chunks.push(cur.trim()); cur = ""; }
      let rest = s.trim();
      while (rest.length > maxLen) {
        let cut = Math.max(rest.lastIndexOf(", ", maxLen), rest.lastIndexOf("; ", maxLen));
        if (cut < maxLen * 0.4) cut = rest.lastIndexOf(" ", maxLen);
        if (cut <= 0) cut = maxLen;
        chunks.push(rest.slice(0, cut + 1).trim());
        rest = rest.slice(cut + 1).trim();
      }
      cur = rest ? rest + " " : "";
      continue;
    }
    if ((cur + s).length > maxLen && cur) { chunks.push(cur.trim()); cur = s; }
    else cur += s;
  }
  if (cur.trim()) chunks.push(cur.trim());
  return chunks.filter((c) => /[A-Za-z0-9]/.test(c));
}

// ---------- audio ----------

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
