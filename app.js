import { KokoroTTS } from "https://cdn.jsdelivr.net/npm/kokoro-js@1.2.1/+esm";
import {
  VOICE_BASE, VOICES, PRESETS,
  blendStyles, blendName, chunkText, floatTo16BitWav, estimateMinutes,
} from "./lib.js";
import { fileToText } from "./files.js";

const MODEL_ID = "onnx-community/Kokoro-82M-v1.0-ONNX";
const PART_SECONDS = 240; // each downloadable file holds about 4 minutes of audio

const $ = (id) => document.getElementById(id);
const genBtn = $("genBtn"), prevBtn = $("prevBtn"), stopBtn = $("stopBtn");
const statusEl = $("status"), prog = $("prog");
const selA = $("voiceA"), selB = $("voiceB"), selPreset = $("preset");
const mix = $("mix"), speed = $("speed");
const textEl = $("text"), partsEl = $("parts");

let tts = null;
let cancelled = false;
let running = false;
let urls = [];
let wakeLock = null;
const SAFE = new URLSearchParams(location.search).has("safe");
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
selPreset.value = PRESETS[0].id;

// ---------- text and files ----------
function updateCount() {
  const n = textEl.value.length;
  $("count").textContent = `${n.toLocaleString()} characters`;
  const mins = estimateMinutes(n);
  $("est").textContent = n ? `about ${mins < 1 ? "under 1" : mins} min of audio` : "";
}
textEl.addEventListener("input", updateCount);
$("clearBtn").addEventListener("click", () => { textEl.value = ""; updateCount(); });

$("files").addEventListener("change", async (e) => {
  const files = Array.from(e.target.files || []);
  e.target.value = "";
  if (!files.length) return;
  const problems = [];
  let added = 0;
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    setStatus(`Reading file ${i + 1} of ${files.length}: ${f.name}`);
    try {
      const text = await fileToText(f);
      textEl.value = (textEl.value.trim() ? textEl.value.trim() + "\n\n" : "") + text;
      added++;
    } catch (err) {
      console.error(err);
      problems.push(`${f.name}: ${err && err.message ? err.message : err}`);
    }
  }
  updateCount();
  setStatus(
    (added ? `Added ${added} file${added > 1 ? "s" : ""}. Review the text, then tap Generate.` : "No text was added.") +
    (problems.length ? "\n" + problems.join("\n") : "")
  );
});

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

// ---------- speed boost ----------
function speedNote() {
  const su = SETUPS[level] || SETUPS[SETUPS.length - 1];
  if (su.dtype !== "fp32") return "Using the " + su.name + ". " + coreNote();
  if (device === "webgpu") return "Using the best model on the graphics chip (fastest).";
  return "Using the best model. " + coreNote();
}
function coreNote() {
  if (window.crossOriginIsolated) {
    const cores = navigator.hardwareConcurrency || 4;
    const n = Math.min(4, Math.ceil(cores / 2));
    return `Using ${n} processor core${n > 1 ? "s" : ""}.`;
  }
  return SAFE ? "Safe mode: using 1 processor core (slower)." : "Using 1 processor core. Close and reopen the app to speed it up.";
}
function offerSafeMode() {
  if (document.getElementById("safeBtn")) return;
  const b = document.createElement("button");
  b.id = "safeBtn"; b.className = "secondary";
  b.textContent = "Try again in safe mode";
  b.addEventListener("click", () => { location.href = "index.html?safe=1"; });
  statusEl.after(b);
}

// ---------- model loading ----------
// Tries the clearest setup first. If the app closed during the last load or generation
// (usually the phone running out of memory), it steps down to a lighter setup by itself.
const SETUPS = [
  { device: "webgpu", dtype: "fp32", name: "best model on the graphics chip" },
  { device: "wasm", dtype: "fp32", name: "best model on the processor" },
  { device: "wasm", dtype: "q8", name: "lighter model (fits phones with less memory)" },
];
const mem = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* optional */ } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* optional */ } },
};
let level = Math.min(Number(mem.get("setupLevel")) || 0, SETUPS.length - 1);
let crashNote = "";
if (mem.get("busy")) {
  // the app was closed in the middle of work last time: use a lighter setup
  level = SETUPS.length - 1; mem.set("setupLevel", String(level));
  crashNote = "The app closed last time, so it now uses a lighter setup that fits this phone. ";
  mem.del("busy");
}
const markBusy = () => mem.set("busy", "1");
const clearBusy = () => mem.del("busy");
// leaving the app normally is not a crash
window.addEventListener("pagehide", () => { if (!running && !loading) clearBusy(); });

async function hasWebGPU() {
  try { return !!(navigator.gpu && (await navigator.gpu.requestAdapter())); } catch (e) { return false; }
}

let device = "wasm";
let loading = false;
let modelPromise = null;
function loadModel() {
  if (!modelPromise) modelPromise = doLoad().catch((err) => { modelPromise = null; throw err; });
  return modelPromise;
}
async function doLoad() {
  loading = true;
  prog.hidden = false;
  setStatus(crashNote + "Getting the voice model ready...");
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (e) { /* optional */ }
  if (level === 0 && !(await hasWebGPU())) level = 1;
  try {
    let lastErr = null;
    for (; level < SETUPS.length; level++) {
      const su = SETUPS[level];
      const size = su.dtype === "fp32" ? "about 330 MB" : "about 90 MB";
      markBusy();
      try {
        tts = await KokoroTTS.from_pretrained(MODEL_ID, {
          dtype: su.dtype, device: su.device,
          progress_callback: (p) => {
            if (p && typeof p.progress === "number") {
              prog.value = p.progress;
              setStatus(crashNote + `Loading voice model: ${Math.round(p.progress)}% (first time only, ${size})`);
            }
          },
        });
        device = su.device;
        break;
      } catch (e) {
        console.error(e); tts = null; lastErr = e;
      }
    }
    clearBusy();
    if (!tts) throw lastErr || new Error("no setup worked");
    mem.set("setupLevel", String(level));
    loading = false;
    prog.hidden = true;
    setStatus(crashNote + "Ready. " + speedNote());
    return tts;
  } catch (err) {
    console.error(err);
    loading = false; clearBusy();
    level = Math.min(Number(mem.get("setupLevel")) || 0, SETUPS.length - 1);
    tts = null;
    prog.hidden = true;
    setStatus("Could not load the voice model: " + (err && err.message ? err.message : err) + ". Check your internet and reopen the app.");
    if (window.crossOriginIsolated) offerSafeMode();
    throw err;
  }
}

// ---------- generation ----------
function addPart(samples, rate, index) {
  const blob = floatTo16BitWav(samples, rate);
  const url = URL.createObjectURL(blob);
  urls.push(url);

  const box = document.createElement("div");
  box.className = "part";
  const title = document.createElement("b");
  title.textContent = `Part ${index}`;
  const audio = document.createElement("audio");
  audio.controls = true; audio.src = url;
  const link = document.createElement("a");
  link.className = "dl"; link.href = url;
  link.download = `narration-part-${index}.wav`;
  link.textContent = `Download part ${index} (WAV)`;
  box.append(title, audio, link);
  partsEl.appendChild(box);
  return { title, link };
}

async function keepScreenOn() {
  try { if ("wakeLock" in navigator) wakeLock = await navigator.wakeLock.request("screen"); } catch (e) { /* optional */ }
}
function releaseScreen() {
  try { if (wakeLock) wakeLock.release(); } catch (e) { /* optional */ }
  wakeLock = null;
}

async function run(previewOnly) {
  const text = textEl.value.trim();
  if (!text) { setStatus("Please add some text or a file first."); return; }
  let chunks = chunkText(text);
  if (!chunks.length) { setStatus("Nothing readable in that text."); return; }
  if (previewOnly) chunks = chunks.slice(0, 1);

  cancelled = false; running = true;
  genBtn.disabled = true; prevBtn.disabled = true; stopBtn.hidden = false;
  for (const u of urls) URL.revokeObjectURL(u);
  urls = []; partsEl.textContent = "";
  await keepScreenOn();

  const a = selA.value, b = selB.value, pctA = Number(mix.value);
  const spd = Number(speed.value);
  let voice = a;
  let notice = "";

  try {
    if (!tts) {
      setStatus("Waiting for the voice model to finish loading...");
      await loadModel();
    }
    markBusy();
    setStatus(b ? "Blending voices..." : "Preparing voice...");
    try {
      voice = await resolveVoice(a, b, pctA);
    } catch (e) {
      console.error(e);
      voice = a;
      notice = " (Voice blending failed, so voice A alone was used.)";
    }

    let rate = 24000;
    const gap = new Float32Array(Math.floor(rate * 0.25));
    let pending = [], pendingLen = 0, partCount = 0;
    const made = [];

    const flush = () => {
      if (!pendingLen) return;
      const all = new Float32Array(pendingLen);
      let o = 0;
      for (const p of pending) { all.set(p, o); o += p.length; }
      pending = []; pendingLen = 0;
      partCount++;
      made.push(addPart(all, rate, partCount));
    };

    for (let i = 0; i < chunks.length; i++) {
      if (cancelled) break;
      setStatus(`Generating ${i + 1} of ${chunks.length} (part ${partCount + 1})...`);
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
      pending.push(audio.audio, gap);
      pendingLen += audio.audio.length + gap.length;
      if (pendingLen >= rate * PART_SECONDS) flush();
    }
    flush();

    if (made.length === 1) {
      made[0].title.textContent = previewOnly ? "Preview" : "Your narration";
      made[0].link.textContent = "Download (WAV)";
      made[0].link.download = previewOnly ? "preview.wav" : "narration.wav";
    }
    setStatus(
      (cancelled ? "Stopped. Parts finished so far are below." :
        previewOnly ? "Preview ready." : "Done. Play or download below.") + notice
    );
  } catch (err) {
    console.error(err);
    setStatus("Something went wrong: " + (err && err.message ? err.message : err));
  } finally {
    releaseScreen(); running = false; clearBusy();
    genBtn.disabled = false; prevBtn.disabled = false; stopBtn.hidden = true;
  }
}

genBtn.addEventListener("click", () => run(false));
prevBtn.addEventListener("click", () => run(true));
stopBtn.addEventListener("click", () => { cancelled = true; setStatus("Stopping after this sentence..."); });

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").then(async () => {
    if (window.crossOriginIsolated || SAFE) return;
    // The speed boost only applies once the worker controls the page, so reload once.
    await navigator.serviceWorker.ready;
    let canReload = false;
    try {
      if (sessionStorage.getItem("boostReload") !== "1") {
        sessionStorage.setItem("boostReload", "1");
        canReload = sessionStorage.getItem("boostReload") === "1";
      }
    } catch (e) { canReload = false; }
    if (canReload && !modelPromise && !running) location.reload();
  }).catch(() => {});
}

// Start loading the model as soon as the app opens. Wait a moment first in case the
// page reloads once to turn on the multi-core speed boost.
function sessionStorageGet(k) { try { return sessionStorage.getItem(k); } catch (e) { return "1"; } }
const willReload = "serviceWorker" in navigator && !window.crossOriginIsolated && !SAFE &&
  sessionStorageGet("boostReload") !== "1";
setTimeout(() => { loadModel().catch(() => {}); }, willReload ? 2500 : 0);
