import {
  VOICES, MODELS, parseDialogue, splitNarration, splitDialogue,
  buildPrompt, buildRequest, decodeAudio, joinToWav, retryDelaySec,
} from "./studio-lib.js";
import { fileToText } from "./files.js";

const API = "https://generativelanguage.googleapis.com/v1beta/models/";
const $ = (id) => document.getElementById(id);
const textEl = $("text"), voiceEl = $("voice"), voice2El = $("voice2"), styleEl = $("style");
const genBtn = $("genBtn"), prevBtn = $("prevBtn"), stopBtn = $("stopBtn");
const statusEl = $("status"), prog = $("prog");
const setStatus = (m) => (statusEl.textContent = m);

const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* optional */ } },
  del(k) { try { localStorage.removeItem(k); } catch (e) { /* optional */ } },
};

// ---------- key ----------
function showKeyCard(show) {
  $("keyCard").hidden = !show;
  $("changeKey").hidden = show;
}
showKeyCard(!store.get("geminiKey"));
$("saveKey").addEventListener("click", () => {
  const k = $("key").value.trim();
  if (k.length < 20) { alert("That doesn't look like a full key. Copy the whole key and paste it again."); return; }
  store.set("geminiKey", k);
  $("key").value = "";
  showKeyCard(false);
  setStatus("Key saved. Add your script and tap Make narration.");
});
$("changeKey").addEventListener("click", () => { showKeyCard(true); window.scrollTo(0, 0); });

// ---------- voices ----------
for (const v of VOICES) { voiceEl.add(new Option(v.label, v.id)); voice2El.add(new Option(v.label, v.id)); }
voiceEl.value = store.get("voice") || "Puck";
voice2El.value = store.get("voice2") || "Sulafat";
styleEl.value = store.get("style") || "";
voiceEl.addEventListener("change", () => store.set("voice", voiceEl.value));
voice2El.addEventListener("change", () => store.set("voice2", voice2El.value));
styleEl.addEventListener("change", () => store.set("style", styleEl.value));

// ---------- script ----------
function update() {
  const t = textEl.value;
  $("count").textContent = `${t.length.toLocaleString()} characters`;
  const mins = Math.round(t.length / 900);
  $("est").textContent = t.trim() ? `about ${mins < 1 ? "under 1" : mins} min of audio` : "";
  const d = parseDialogue(t);
  $("voice2Box").hidden = !d;
  if (d) {
    $("detect").textContent = `Conversation found: ${d.speakers[0]} and ${d.speakers[1]}.`;
    $("voiceLabel").textContent = `Voice for ${d.speakers[0]}`;
    $("voice2Label").textContent = `Voice for ${d.speakers[1]}`;
  } else {
    $("detect").textContent = "";
    $("voiceLabel").textContent = "Voice";
  }
  store.set("script", t);
}
textEl.value = store.get("script") || "";
textEl.addEventListener("input", update);
update();

$("files").addEventListener("change", async (e) => {
  const files = Array.from(e.target.files || []);
  e.target.value = "";
  const problems = [];
  for (let i = 0; i < files.length; i++) {
    setStatus(`Reading ${files[i].name}...`);
    try {
      const t = await fileToText(files[i]);
      textEl.value = (textEl.value.trim() ? textEl.value.trim() + "\n\n" : "") + t;
    } catch (err) {
      problems.push(`${files[i].name}: ${err && err.message ? err.message : err}`);
    }
  }
  update();
  setStatus(problems.length ? problems.join("\n") : "File added. Check the text, then tap Make narration.");
});

// ---------- generation ----------
let cancelled = false;
let lastUrl = null;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function b64ToBytes(b64) {
  const s = atob(b64);
  const u = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
  return u;
}

class StopError extends Error {}

// One request. Moves to the next model when one is unavailable or out of free quota.
async function speak(body, label) {
  const key = store.get("geminiKey");
  const saved = store.get("model");
  let order = MODELS.slice();
  if (saved && order.includes(saved)) order = [saved, ...order.filter((m) => m !== saved)];
  let lastMsg = "";
  for (const model of order) {
    for (let attempt = 0; attempt < 3; attempt++) {
      if (cancelled) throw new StopError();
      let res, data = null;
      try {
        res = await fetch(`${API}${model}:generateContent`, {
          method: "POST",
          headers: { "Content-Type": "application/json", "x-goog-api-key": key },
          body: JSON.stringify(body),
        });
        data = await res.json().catch(() => null);
      } catch (e) {
        lastMsg = "No internet connection.";
        await sleep(3000);
        continue;
      }
      if (res.ok) {
        const part = data && data.candidates && data.candidates[0] && data.candidates[0].content &&
          (data.candidates[0].content.parts || []).find((p) => p.inlineData && p.inlineData.data);
        if (part) {
          store.set("model", model);
          return decodeAudio(b64ToBytes(part.inlineData.data), part.inlineData.mimeType);
        }
        lastMsg = "Google returned no audio for this part.";
        break; // try the next model
      }
      const msg = (data && data.error && data.error.message) || `HTTP ${res.status}`;
      lastMsg = msg;
      if (res.status === 400 && /api key|API_KEY/i.test(msg)) throw new Error("KEY");
      if (res.status === 401 || res.status === 403) throw new Error("KEY");
      if (res.status === 429) {
        const wait = retryDelaySec(data);
        if (wait <= 60 && attempt < 2) {
          for (let s = Math.ceil(wait); s > 0; s--) {
            if (cancelled) throw new StopError();
            setStatus(`${label}\nGoogle's free limit is busy. Waiting ${s} s...`);
            await sleep(1000);
          }
          continue;
        }
        break; // this model's free quota is used up: try the next one
      }
      if (res.status >= 500 && attempt < 2) { await sleep(3000); continue; }
      break; // 404 (model not available) or another problem: next model
    }
  }
  throw new Error(lastMsg || "Could not make audio.");
}

async function run(previewOnly) {
  if (!store.get("geminiKey")) { showKeyCard(true); window.scrollTo(0, 0); setStatus("First, do the one-time setup above."); return; }
  const text = textEl.value.trim();
  if (!text) { setStatus("Please add your script first."); return; }

  const dialogue = parseDialogue(text);
  let pieces = dialogue ? splitDialogue(dialogue.lines) : splitNarration(text);
  if (previewOnly) {
    pieces = dialogue
      ? [dialogue.lines.slice(0, 2).map((l) => `${l.speaker}: ${l.text}`).join("\n")]
      : splitNarration(text, 300).slice(0, 1);
  }
  if (!pieces.length) { setStatus("Nothing readable in the script."); return; }

  cancelled = false;
  genBtn.disabled = true; prevBtn.disabled = true; stopBtn.hidden = false;
  $("result").hidden = true;
  prog.hidden = false; prog.value = 0;
  const audio = [];
  let rate = 24000;
  try {
    for (let i = 0; i < pieces.length; i++) {
      const label = pieces.length > 1 ? `Making part ${i + 1} of ${pieces.length}...` : "Making audio...";
      setStatus(label);
      const body = buildRequest(buildPrompt(pieces[i], styleEl.value, dialogue), voiceEl.value, dialogue, voice2El.value);
      const out = await speak(body, label);
      rate = out.rate;
      audio.push(out.pcm);
      prog.value = Math.round(((i + 1) / pieces.length) * 100);
    }
    finish(audio, rate, previewOnly ? "Preview ready." : "Done. Play it or download it below.");
  } catch (err) {
    if (err instanceof StopError) {
      if (audio.length) finish(audio, rate, "Stopped. The parts made so far are below.");
      else setStatus("Stopped.");
    } else if (err.message === "KEY") {
      setStatus("Google didn't accept your key. Do the setup again with a new key.");
      showKeyCard(true); window.scrollTo(0, 0);
    } else {
      if (audio.length) finish(audio, rate, "");
      const limit = /quota|exhausted|rate/i.test(err.message);
      setStatus((audio.length ? `Made ${audio.length} of ${pieces.length} parts (download below). ` : "") +
        (limit ? "Google's free daily limit is used up. Try again later or tomorrow." : "Problem: " + err.message));
    }
  } finally {
    genBtn.disabled = false; prevBtn.disabled = false; stopBtn.hidden = true; prog.hidden = true;
  }
}

function finish(audio, rate, msg) {
  if (lastUrl) URL.revokeObjectURL(lastUrl);
  lastUrl = URL.createObjectURL(new Blob([joinToWav(audio, rate)], { type: "audio/wav" }));
  $("player").src = lastUrl;
  $("download").href = lastUrl;
  $("result").hidden = false;
  if (msg) setStatus(msg);
}

genBtn.addEventListener("click", () => run(false));
prevBtn.addEventListener("click", () => run(true));
stopBtn.addEventListener("click", () => { cancelled = true; setStatus("Stopping..."); });

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
