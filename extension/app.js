import { readZip, buildZip } from './zip.js';
import { CompareViewer } from './compare.js';

const $ = (id) => document.getElementById(id);
const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'avif']);
const extOf = (n) => (n.split('.').pop() || '').toLowerCase();
const baseOf = (path) => path.replace(/\\/g, '/').split('/').pop();
const isImageName = (n) => IMAGE_EXT.has(extOf(n));
const isZipName = (n) => n.toLowerCase().endsWith('.zip');
const stem = (n) => { const b = baseOf(n); const i = b.lastIndexOf('.'); return (i > 0 ? b.slice(0, i) : b) || 'image'; };

let running = false;
let results = [];           // finished pictures: { name, srcGet, srcH, cropRows, png, thumbUrl, w, h }
let viewer = null;
let compareIndex = 0;
let compareToken = 0;

// ------------------------------------------------------------------ worker pool
const workerCount = Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1));
let workers = [];
function getWorkers() {
  if (workers.length === 0) {
    for (let i = 0; i < workerCount; i++) workers.push(new Worker('worker.js'));
  }
  return workers;
}
let jobId = 0;
function runInWorker(worker, msg) {
  return new Promise((resolve, reject) => {
    const id = ++jobId;
    const onMsg = (e) => {
      if (e.data.id !== id) return;
      worker.removeEventListener('message', onMsg);
      worker.removeEventListener('error', onErr);
      e.data.error ? reject(new Error(e.data.error)) : resolve(e.data);
    };
    const onErr = (e) => { worker.removeEventListener('message', onMsg); worker.removeEventListener('error', onErr); reject(new Error(e.message || 'worker error')); };
    worker.addEventListener('message', onMsg);
    worker.addEventListener('error', onErr);
    worker.postMessage({ ...msg, id }, [msg.buffer]);
  });
}

// ------------------------------------------------------------------ collecting inputs
/** Turns picked / dropped files into a flat list of images. A ZIP is opened and its images are used. */
async function collectInputs(files, problems) {
  const inputs = [];
  for (const file of files) {
    const name = file.name;
    if (isZipName(name)) {
      try {
        const entries = await readZip(file);
        for (const e of entries) {
          const base = baseOf(e.name);
          if (e.isDir || !isImageName(e.name) || e.name.startsWith('__MACOSX') || base.startsWith('.')) continue;
          inputs.push({ name: base, getBlob: () => e.blob() });
        }
      } catch (err) {
        problems.push(`${name} (${err.message})`);
      }
    } else if (file.type.startsWith('image/') || isImageName(name)) {
      if (name.startsWith('.')) continue;
      inputs.push({ name, getBlob: async () => file });
    }
  }
  return inputs;
}

async function filesFromDrop(dt) {
  const files = [];
  const walk = async (entry) => {
    if (entry.isFile) {
      await new Promise((res) => entry.file((f) => { files.push(f); res(); }, res));
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise((res) => reader.readEntries(res, () => res([])));
        if (!batch.length) break;
        for (const child of batch) await walk(child);
      }
    }
  };
  const entries = [];
  for (const item of dt.items || []) {
    const en = item.webkitGetAsEntry && item.webkitGetAsEntry();
    if (en) entries.push(en);
  }
  if (entries.length) for (const en of entries) await walk(en);
  else files.push(...dt.files);
  return files;
}

// ------------------------------------------------------------------ processing
function setStatus(text) { $('status').textContent = text; }

async function processOne(worker, input, opts) {
  const blob = await input.getBlob();
  const bmp = await createImageBitmap(blob);
  const w = bmp.width, h = bmp.height;
  const cv = new OffscreenCanvas(w, h);
  const cx = cv.getContext('2d', { willReadFrequently: true });
  cx.drawImage(bmp, 0, 0);
  bmp.close();
  const data = cx.getImageData(0, 0, w, h);
  const r = await runInWorker(worker, { buffer: data.data.buffer, w, h, removeMark: opts.removeMark, crop: opts.crop });

  const out = new ImageData(new Uint8ClampedArray(r.buffer), r.w, r.h);
  const outCanvas = new OffscreenCanvas(r.w, r.h);
  outCanvas.getContext('2d').putImageData(out, 0, 0);
  const png = await outCanvas.convertToBlob({ type: 'image/png' });

  // small preview for the grid
  const tw = 380, th = Math.max(1, Math.round(r.h * tw / r.w));
  const thumbCanvas = new OffscreenCanvas(tw, th);
  const tctx = thumbCanvas.getContext('2d');
  tctx.imageSmoothingQuality = 'high';
  tctx.drawImage(outCanvas, 0, 0, tw, th);
  const thumb = await thumbCanvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });

  return { srcGet: input.getBlob, srcH: h, cropRows: r.cropRows, png, thumbUrl: URL.createObjectURL(thumb), w: r.w, h: r.h, factor: r.factor };
}

async function runBatch(inputs, problems) {
  const opts = { removeMark: $('optMark').checked, crop: $('optCrop').checked };
  for (const r of results) URL.revokeObjectURL(r.thumbUrl);
  results = [];
  renderResults();
  const slots = new Array(inputs.length).fill(null);
  const names = new Map();
  const failures = [];
  let next = 0, done = 0;
  $('bar').max = inputs.length; $('bar').value = 0;

  const pool = getWorkers();
  await Promise.all(pool.map(async (worker) => {
    while (next < inputs.length) {
      const i = next++;
      const input = inputs[i];
      setStatus(`Processing ${Math.min(done + 1, inputs.length)} of ${inputs.length}: ${input.name}`);
      try {
        const r = await processOne(worker, input, opts);
        slots[i] = { ...r, stem: stem(input.name) };
      } catch (err) {
        failures.push(`${input.name} (${err.message})`);
      }
      done++;
      $('bar').value = done;
    }
  }));

  // unique output names, in input order
  for (const s of slots) {
    if (!s) continue;
    const n = (names.get(s.stem) || 0) + 1;
    names.set(s.stem, n);
    s.name = n === 1 ? s.stem : `${s.stem}_${n}`;
    results.push(s);
  }
  const notes = [...problems.map((p) => 'Skipped: ' + p), ...failures.map((f) => 'Failed: ' + f)];
  setStatus(`Done: ${results.length} of ${inputs.length} processed.` + (notes.length ? '\n' + notes.join('\n') : ''));
  renderResults();
}

async function start(files) {
  if (running) { setStatus('Still working on the previous batch. Please wait.'); return; }
  running = true;
  setButtons(false);
  $('progressCard').hidden = false;
  $('bar').removeAttribute('value');
  setStatus('Reading files ...');
  try {
    const problems = [];
    const inputs = await collectInputs(files, problems);
    if (!inputs.length) {
      setStatus('No supported images found (pick images, ZIPs, or a folder that has them).' + (problems.length ? '\nSkipped: ' + problems.join('; ') : ''));
    } else {
      await runBatch(inputs, problems);
    }
  } catch (err) {
    setStatus('Something went wrong: ' + err.message);
  } finally {
    running = false;
    setButtons(true);
  }
}

function setButtons(on) {
  for (const id of ['pickFiles', 'pickFolder']) $(id).disabled = !on;
}

// ------------------------------------------------------------------ results grid + downloads
function download(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

function renderResults() {
  const grid = $('grid');
  grid.textContent = '';
  $('resultsCard').hidden = results.length === 0;
  $('resultsTitle').textContent = `Results (${results.length})`;
  results.forEach((r, i) => {
    const item = document.createElement('div');
    item.className = 'item';
    const img = document.createElement('img');
    img.src = r.thumbUrl; img.alt = r.name; img.loading = 'lazy'; img.title = 'Open before / after slider';
    img.addEventListener('click', () => openCompare(i));
    const name = document.createElement('div'); name.className = 'name'; name.textContent = r.name + '.png'; name.title = r.name + '.png';
    const meta = document.createElement('div'); meta.className = 'meta'; meta.textContent = `${r.w} x ${r.h}`;
    const row = document.createElement('div'); row.className = 'row';
    const cmp = document.createElement('button'); cmp.textContent = 'Compare'; cmp.addEventListener('click', () => openCompare(i));
    const save = document.createElement('button'); save.textContent = 'Save PNG'; save.addEventListener('click', () => download(r.png, r.name + '.png'));
    row.append(cmp, save);
    item.append(img, name, meta, row);
    grid.appendChild(item);
  });
}

async function zipAll() {
  const btn = $('zipAll'); btn.disabled = true;
  try {
    setStatus('Building ZIP ...');
    const zip = await buildZip(results.map((r) => ({ name: r.name + '.png', blob: r.png })));
    download(zip, 'Upscaled.zip');
    setStatus(`ZIP ready: ${results.length} pictures.`);
  } catch (err) {
    setStatus('Could not build the ZIP: ' + err.message);
  } finally { btn.disabled = false; }
  $('progressCard').hidden = false;
}

async function saveToFolder() {
  try {
    const dir = await window.showDirectoryPicker({ mode: 'readwrite' });
    $('progressCard').hidden = false;
    let n = 0;
    for (const r of results) {
      const fh = await dir.getFileHandle(r.name + '.png', { create: true });
      const w = await fh.createWritable();
      await w.write(r.png); await w.close();
      setStatus(`Saving ${++n} of ${results.length} ...`);
    }
    setStatus(`Saved ${n} pictures to the folder.`);
  } catch (err) {
    if (err.name !== 'AbortError') setStatus('Could not save to the folder: ' + err.message);
  }
}

// ------------------------------------------------------------------ before / after viewer
function openCompare(index) {
  if (!results.length) return;
  $('overlay').hidden = false;
  document.body.style.overflow = 'hidden';
  if (!viewer) viewer = new CompareViewer($('compareCanvas'));
  else viewer.resize();
  showCompare(index);
}

function closeCompare() {
  compareToken++;
  $('overlay').hidden = true;
  document.body.style.overflow = '';
  if (viewer) viewer.setImages(null, null);
}

async function showCompare(index) {
  compareIndex = Math.min(results.length - 1, Math.max(0, index));
  const r = results[compareIndex];
  $('compareTitle').textContent = `${compareIndex + 1} / ${results.length}   ${r.name}  (${r.w} x ${r.h})`;
  $('prev').disabled = compareIndex === 0;
  $('next').disabled = compareIndex === results.length - 1;
  viewer.setImages(null, null);
  const token = ++compareToken;
  try {
    const after = await createImageBitmap(r.png);
    let before = await createImageBitmap(await r.srcGet());
    if (r.cropRows > 0 && r.cropRows < before.height) {
      // the black strip was cropped from the result, so crop the same strip from the original
      const cropped = await createImageBitmap(before, 0, r.cropRows, before.width, before.height - r.cropRows);
      before.close(); before = cropped;
    }
    if (token !== compareToken) { after.close(); before.close(); return; }
    viewer.setImages(before, after);
  } catch (err) {
    if (token === compareToken) viewer.setImages(null, null, 'Could not load this picture: ' + err.message);
  }
}

// ------------------------------------------------------------------ wiring
$('pickFiles').addEventListener('click', () => $('fileInput').click());
$('pickFolder').addEventListener('click', () => $('folderInput').click());
$('fileInput').addEventListener('change', (e) => { const f = [...e.target.files]; e.target.value = ''; if (f.length) start(f); });
$('folderInput').addEventListener('change', (e) => { const f = [...e.target.files]; e.target.value = ''; if (f.length) start(f); });
$('compareAll').addEventListener('click', () => openCompare(0));
$('zipAll').addEventListener('click', zipAll);
$('saveFolder').addEventListener('click', saveToFolder);
if ('showDirectoryPicker' in window) $('saveFolder').hidden = false;
$('closeCompare').addEventListener('click', closeCompare);
$('prev').addEventListener('click', () => showCompare(compareIndex - 1));
$('next').addEventListener('click', () => showCompare(compareIndex + 1));
document.addEventListener('keydown', (e) => {
  if ($('overlay').hidden) return;
  if (e.key === 'Escape') closeCompare();
  else if (e.key === 'ArrowLeft') showCompare(compareIndex - 1);
  else if (e.key === 'ArrowRight') showCompare(compareIndex + 1);
});

const drop = $('drop');
['dragenter', 'dragover'].forEach((t) => document.addEventListener(t, (e) => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach((t) => document.addEventListener(t, (e) => { e.preventDefault(); if (t === 'drop' || e.target === document.documentElement) drop.classList.remove('over'); }));
document.addEventListener('drop', async (e) => {
  const files = await filesFromDrop(e.dataTransfer);
  if (files.length) start(files);
});
