import { KokoroTTS } from "https://cdn.jsdelivr.net/npm/kokoro-js@1.2.1/+esm";
import {
  VOICE_BASE, VOICES, PRESETS,
  blendStyles, blendName, chunkText, floatTo16BitWav,
} from "./lib.js";

const MODEL_ID = "onnx-community/Kokoro-82M-v1.0-ONNX";

const $ = (id) => document.getElementById(id);
const loadBtn = $("loadBtn"), genBtn = $("genBtn"), prevBtn = $("prevBtn"), stopBtn = $("stopBtn");
const statusEl = $("status"), prog = $("prog");
const player = $("player"), dl = $("dl");
const selA = $("voiceA"), selB = $("voiceB"), selPreset = $("preset");
const mix = $("mix"), speed = $("speed");

let tts = null;
let cancelled = false;
let lastUrl = null;
const setStatus = (m) => (statusEl.textContent = m);

// ---------- controls ----------
for (const [id, label] of VOICES) {
  selA.add(new Option(label, id));
  selB.add(new Option(label, id));
}
selB.insertBefore(new Option("None (use one voice)", ""), selB.firstChild);

for (const p of PRESETS) selPreset.add(new Option(p.label, p.id));
selPreset.insertBefore(new Option("Custom", ""), selPreset.firstChild);

function updateLabels() {
  const a = Number(mix.value);
  const single = !selB.value || a >= 100;
  $("mixVal").textContent = single ? "100% voice A" : `A ${a}% / B ${100 - a}%`;
  $("speedVal").textContent = Number(speed.value).toFixed(2);
  mix.disabled = !selB.value;
}
function applyPreset(p) {
  selA.value = p.a; selB.value = p.b; mix.value = p.mix; speed.value = p.speed;
  updateLabels();
}
selPreset.addEventListener("change", () => {
  const p = PRESETS.find((x) => x.id === selPreset.value);
  if (p) applyPreset(p);
});
for (const el of [selA, selB, mix, speed]) {
  el.addEventListener("input", () => { selPreset.value = ""; updateLabels(); });
}
applyPreset(PRESETS[0]);

// ---------- voice blending ----------
// The library loads each voice from a .bin file (a table of style vectors).
// To blend, we load two tables ourselves, average them, and hand the result to the
// library under a new voice name by answering its download request for that name.
const blendBuffers = new Map(); // blend name -> ArrayBuffer
const voiceCache = new Map();   // voice id -> Float32Array
const realFetch = window.fetch.bind(window);
window.fetch = (input, init) => {
  try {
    const url = typeof input === "string" ? input : (input && input.url) || String(input);
    const m = url.match(/\/voices\/([^/?#]+)\.bin(?:[?#]|$)/);
    if (m && blendBuffers.has(m[1])) {
      return Promise.resolve(new Response(blendBuffers.get(m[1]).slice(0), {
        status: 200, headers: { "Content-Type": "application/octet-stream" },
      }));
    }
  } catch (e) { /* fall through to the real fetch */ }
  return realFetch(input, init);
};

async function loadVoiceTable(id) {
  if (voiceCache.has(id)) return voiceCache.get(id);
  const url = VOICE_BASE + id + ".bin";
  let buf = null;
  try {
    const cache = await caches.open("narrator-voices");
    const hit = await cache.match(url);
    if (hit) buf = await hit.arrayBuffer();
    else {
      const res = await realFetch(url);
      if (!res.ok) throw new Error("HTTP " + res.status);
      await cache.put(url, res.clone());
      buf = await res.arrayBuffer();
    }
  } catch (e) {
    const res = await realFetch(url);
    if (!res.ok) throw new Error(`Could not download voice ${id} (HTTP ${res.status})`);
    buf = await res.arrayBuffer();
  }
  const table = new Float32Array(buf);
  voiceCache.set(id, table);
  return table;
}

// Returns the voice name to pass to the model
async function resolveVoice(a, b, pctA) {
  if (!b || pctA >= 100) return a;
  if (pctA <= 0) return b;
  const name = blendName(a, b, pctA);
  if (!blendBuffers.has(name)) {
    const [ta, tb] = await Promise.all([loadVoiceTable(a), loadVoiceTable(b)]);
    blendBuffers.set(name, blendStyles(ta, tb, pctA / 100).buffer);
  }
  const list = tts.voices;
  if (!list || typeof list !== "object") throw new Error("voice list not available");
  if (!list[name]) list[name] = { ...(list[a] || {}), name };
  return name;
}

// ---------- model loading ----------
loadBtn.addEventListener("click", async () => {
  loadBtn.disabled = true;
  prog.hidden = false;
  setStatus("Downloading model...");
  try {
    tts = await KokoroTTS.from_pretrained(MODEL_ID, {
      dtype: "q8",
      device: "wasm",
      progress_callback: (p) => {
        if (p && typeof p.progress === "number") {
          prog.value = p.progress;
          setStatus(`Downloading ${p.file ? p.file.split("/").pop() : "model"}: ${Math.round(p.progress)}%`);
        }
      },
    });
    prog.hidden = true;
    loadBtn.hidden = true;
    genBtn.disabled = false; prevBtn.disabled = false;
    setStatus("Model ready. Pick a preset, paste a script, and tap Generate.");
  } catch (err) {
    console.error(err);
    prog.hidden = true;
    loadBtn.disabled = false;
    setStatus("Could not load the model: " + (err && err.message ? err.message : err) + ". Check your internet and try again.");
  }
});

// ---------- generation ----------
async function run(previewOnly) {
  const text = $("text").value.trim();
  if (!text) { setStatus("Please paste some text first."); return; }
  let chunks = chunkText(text);
  if (!chunks.length) { setStatus("Nothing to read in that text."); return; }
  if (previewOnly) chunks = chunks.slice(0, 1);

  cancelled = false;
  genBtn.disabled = true; prevBtn.disabled = true; stopBtn.hidden = false;
  player.hidden = true; dl.hidden = true;

  const a = selA.value, b = selB.value, pctA = Number(mix.value);
  const spd = Number(speed.value);
  let voice = a;
  let notice = "";

  try {
    setStatus(b ? "Blending voices..." : "Preparing voice...");
    try {
      voice = await resolveVoice(a, b, pctA);
    } catch (e) {
      console.error(e);
      voice = a;
      notice = " (Voice blending failed, so voice A alone was used.)";
    }

    const parts = [];
    let rate = 24000;
    const gap = new Float32Array(Math.floor(rate * 0.25));

    for (let i = 0; i < chunks.length; i++) {
      if (cancelled) break;
      setStatus(`Generating ${i + 1} of ${chunks.length}...`);
      await new Promise((r) => setTimeout(r, 0));
      let audio;
      try {
        audio = await tts.generate(chunks[i], { voice, speed: spd });
      } catch (e) {
        if (voice === a) throw e;
        console.error(e);
        voice = a;
        notice = " (Voice blending is not working here, so voice A alone was used.)";
        audio = await tts.generate(chunks[i], { voice, speed: spd });
      }
      rate = audio.sampling_rate || rate;
      parts.push(audio.audio, gap);
    }
    if (cancelled) { setStatus("Stopped."); return; }

    const total = parts.reduce((n, p) => n + p.length, 0);
    const all = new Float32Array(total);
    let o = 0;
    for (const p of parts) { all.set(p, o); o += p.length; }

    const blob = floatTo16BitWav(all, rate);
    if (lastUrl) URL.revokeObjectURL(lastUrl);
    lastUrl = URL.createObjectURL(blob);
    player.src = lastUrl; player.hidden = false;
    dl.href = lastUrl; dl.download = "voiceover.wav"; dl.hidden = false;
    setStatus((previewOnly ? "Preview ready." : "Done. Play it below or download the WAV file.") + notice);
  } catch (err) {
    console.error(err);
    setStatus("Something went wrong: " + (err && err.message ? err.message : err));
  } finally {
    genBtn.disabled = false; prevBtn.disabled = false; stopBtn.hidden = true;
  }
}

genBtn.addEventListener("click", () => run(false));
prevBtn.addEventListener("click", () => run(true));
stopBtn.addEventListener("click", () => { cancelled = true; setStatus("Stopping after this sentence..."); });

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
