// Run: node tests/units.test.mjs   (bundles src/slides.ts with esbuild, stubs fonts/zip)
import { build } from "esbuild";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$|^jszip$|^mp4-muxer$|^opentype\.js$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default {}", loader: "js" })); } };
const r = await build({ entryPoints: ["src/slides.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
const W = 1600, H = 900;
const L = (id, kind, x, y, w, h) => ({ id, kind, bbox: { x, y, w, h }, visible: true, name: id, group: "", tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none" } });
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
// 3 icons each with a caption, plus loose bits: a dot near icon 2, a stray name far right of icon 3
const layers = [
  L("a", "icon", 100, 250, 260, 260), L("ac", "text", 110, 540, 240, 40),
  L("b", "icon", 650, 250, 260, 260), L("bc", "text", 660, 540, 240, 40), L("bdot", "icon", 930, 300, 14, 14),
  L("c", "icon", 1200, 250, 260, 260), L("cc", "text", 1210, 540, 240, 40), L("cname", "text", 1480, 380, 90, 30),
];
const units = mod.buildUnits({ width: W, height: H, layers });
const items = units.filter(u => u.kind === "item");
console.log(items.map(u => u.layerIds.join("+")));
ok(items.length === 3, "3 elements, no leftover units");
const ids = units.flatMap(u => u.layerIds);
ok(layers.every(l => ids.includes(l.id)), "every layer belongs to a unit");
ok(items.some(u => u.layerIds.includes("bdot") && u.layerIds.includes("b")), "dot joins its nearest icon");
ok(items.some(u => u.layerIds.includes("cname") && u.layerIds.includes("c")), "stray name joins its nearest icon");


// ---- step 2: strict order. Many random slides, tight and loose timings, with arrows and dots.
let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
let bad = 0, checked = 0, lateStarts = 0;
for (let t = 0; t < 400; t++) {
  const n = 1 + Math.floor(rnd() * 9), D = 4 + rnd() * 20, ls = [];
  if (rnd() < 0.7) ls.push(L("h1", "text", 300, 40, 1000, 90));
  for (let i = 0; i < n; i++) {
    const x = 60 + (i % 4) * 380, y = 200 + Math.floor(i / 4) * 330;
    ls.push(L("i" + i, "icon", x, y, 220, 200), L("c" + i, "text", x, y + 215, 220, 40));
    if (rnd() < 0.4) ls.push(L("d" + i, "icon", x + 230, y + 20, 12, 12));
    if (i % 4 < 3 && rnd() < 0.5) ls.push(L("a" + i, "connector", x + 235, y + 90, 120, 14));
  }
  const p = { width: W, height: H, layers: ls };
  const us = mod.buildUnits(p), nm = us.filter(u => u.kind === "item").length;
  const tight = rnd() < 0.4, times = []; let tt = 0.5 + rnd();
  for (let i = 0; i < nm; i++) { times.push(tt); tt += tight ? 0.2 + rnd() * 0.5 : (D - 1.5) / nm * (0.7 + rnd() * 0.6); }
  const plan = mod.planSlide(us, W, H, D, rnd() < 0.8 ? times.map(v => Math.min(v, D - 0.4)) : null, { writeHeading: true, maxIdle: 1 });
  const lt = mod.layerTimes(plan, p), it = plan.items; checked++;
  let ok2 = true; const why = [];
  for (let i = 0; i < it.length; i++) {
    const a = it[i], e = a.begin + a.dur;
    if (a.begin < -1e-9 || e > D + 1e-9) { ok2 = false; why.push("outside slide"); }
    if (i && a.begin < it[i - 1].begin + it[i - 1].dur - 1e-6) { ok2 = false; why.push(`unit ${i} starts before unit ${i - 1} ends`); }
    // every piece of the unit sits inside the unit window
    let pieceEnd = a.begin;
    for (const id of a.unit.layerIds) { const q = lt.get(id); if (!q) { ok2 = false; why.push("piece without time"); continue; }
      if (q.begin < a.begin - 1e-6 || q.begin + q.dur > e + 1e-6) { ok2 = false; why.push("piece outside its unit window"); }
      pieceEnd = Math.max(pieceEnd, q.begin + q.dur); }
    if (a.dur > 0 && Math.abs(pieceEnd - e) > 1e-6) { ok2 = false; why.push("unit not filled to its end"); }
  }
  const items = it.filter(u => u.unit.kind === "item");
  if (items.length !== nm) { ok2 = false; why.push("lost an element"); }
  if (!ok2) { bad++; if (bad < 4) console.log("  case", t, [...new Set(why)].join("; ")); }
}
ok(bad === 0, `strict order holds in ${checked} random slides (${bad} broken)`);
// comfortable script times are kept exactly
{
  const ls = [L("i0", "icon", 100, 250, 260, 260), L("c0", "text", 110, 540, 240, 40), L("i1", "icon", 650, 250, 260, 260), L("c1", "text", 660, 540, 240, 40), L("a", "connector", 380, 350, 250, 16), L("i2", "icon", 1200, 250, 260, 260), L("c2", "text", 1210, 540, 240, 40)];
  const us = mod.buildUnits({ width: W, height: H, layers: ls });
  const plan = mod.planSlide(us, W, H, 20, [2, 8, 14], { writeHeading: true, maxIdle: 1 });
  const b = plan.items.filter(u => u.unit.kind === "item").map(u => +u.begin.toFixed(2));
  ok(JSON.stringify(b) === "[2,8,14]", "script start times kept when there is room: " + b);
  const arr = plan.items.find(u => u.unit.kind === "arrow"), e0 = plan.items.find(u => u.unit.kind === "item");
  ok(arr && arr.begin >= e0.begin + e0.dur - 1e-6, "arrow comes after the first element is complete");
}

// ---- step 3: no limits. 1 to 40 elements, script with fewer, equal or more times than elements, tight and loose.
{
  let seed3 = 11; const r3 = () => (seed3 = (seed3 * 16807) % 2147483647) / 2147483647;
  let bad3 = 0, total3 = 0, maxEl = 0;
  for (let t = 0; t < 300; t++) {
    const n = 1 + Math.floor(r3() * 40), D = 3 + r3() * 40, ls = [];
    for (let i = 0; i < n; i++) {
      const x = 20 + (i % 10) * 150, y = 100 + Math.floor(i / 10) * 200;
      ls.push(L("i" + i, "icon", x, y, 110, 100), L("c" + i, "text", x, y + 108, 110, 24));
      if (r3() < 0.3 && i % 10 < 9) ls.push(L("a" + i, "connector", x + 118, y + 40, 28, 10));
    }
    const p = { width: W, height: H, layers: ls }, us = mod.buildUnits(p), nm = us.filter(u => u.kind === "item").length;
    maxEl = Math.max(maxEl, nm);
    const k = r3() < 0.15 ? 0 : Math.max(1, nm + Math.floor((r3() - 0.5) * 12));   // fewer, equal or more times than elements
    const times = []; let tt = 0.5 + r3(); for (let i = 0; i < k; i++) { times.push(tt); tt += 0.1 + r3() * (D / Math.max(1, k)) * 1.5; }
    const plan = mod.planSlide(us, W, H, D, k ? times : null, { writeHeading: true, maxIdle: 1 }), it = plan.items; total3++;
    let good = it.filter(u => u.unit.kind === "item").length === nm;
    for (let i = 0; i < it.length; i++) {
      const a = it[i];
      if (a.begin < -1e-9 || a.begin + a.dur > D + 1e-9 || a.dur < 0) good = false;
      if (i && a.begin < it[i - 1].begin + it[i - 1].dur - 1e-6) good = false;
    }
    // elements come out in the same order as the script times, and keep them when there is room
    if (!good) { bad3++; if (bad3 < 4) console.log("  case", t, "n=" + nm, "k=" + k, "D=" + D.toFixed(1)); }
  }
  ok(bad3 === 0 && maxEl > 12, `no element limit: ${total3} slides, up to ${maxEl} elements, any script count, strict order holds (${bad3} broken)`);
  // 12 elements with room: every script time is kept
  const ls = [], ts = [];
  for (let i = 0; i < 12; i++) { const x = 20 + (i % 6) * 250, y = 150 + Math.floor(i / 6) * 330; ls.push(L("i" + i, "icon", x, y, 200, 200), L("c" + i, "text", x, y + 212, 200, 36)); ts.push(2 + i * 4); }
  const us = mod.buildUnits({ width: W, height: H, layers: ls });
  const pl = mod.planSlide(us, W, H, 54, ts, { writeHeading: false, maxIdle: 1 });
  const bs = pl.items.filter(u => u.unit.kind === "item").map(u => +u.begin.toFixed(2));
  ok(JSON.stringify(bs) === JSON.stringify(ts), "12 elements keep their script times: " + bs.join(","));
  // fewer script times than elements: the first ones keep their times, the rest follow, in order
  const pl2 = mod.planSlide(us, W, H, 54, ts.slice(0, 8), { writeHeading: false, maxIdle: 1 });
  const b2 = pl2.items.filter(u => u.unit.kind === "item").map(u => u.begin);
  ok(b2.slice(0, 8).every((v, i) => Math.abs(v - ts[i]) < 1e-6) && b2.every((v, i) => !i || v > b2[i - 1]), "8 script times for 12 elements: first 8 kept, last 4 follow");
  // more script times than elements: the extra times are ignored
  const pl3 = mod.planSlide(us, W, H, 80, [...ts, 60, 70], { writeHeading: false, maxIdle: 1 });
  ok(pl3.items.filter(u => u.unit.kind === "item").length === 12, "14 script times for 12 elements: nothing lost");
  // no cap note any more
  const notes = mod.checkSlides([{ n: 1, start: 0, end: 60, elements: ts }]);
  ok(!notes.some(m => /lot|8 or fewer/.test(m)), "checkSlides has no element-count note for 12 elements");
}

// ---- step 4: timing from the script. Every element begins at its script time when the gaps are at least 0.3 s (closer than that, with an arrow in between, the slot cannot hold both, so the start moves by a fraction of a second); nothing overlaps.
for (const g of [0.3, 0.6, 1.5, 4]) {
  let seed4 = 3 + Math.round(g * 10); const r4 = () => (seed4 = (seed4 * 16807) % 2147483647) / 2147483647;
  let off = 0, tot = 0, ov = 0, short = 0;
  for (let t = 0; t < 300; t++) {
    const m = 1 + Math.floor(r4() * 12), us = [{ id: "h", kind: "heading", layerIds: ["h"], bbox: { x: 0, y: 0, w: 1000, h: 90 } }];
    for (let i = 0; i < m; i++) { if (i && r4() < .4) us.push({ id: "a" + i, kind: "arrow", layerIds: ["a" + i], bbox: { x: i * 300 - 60, y: 90, w: 50, h: 10 } }); us.push({ id: "i" + i, kind: "item", layerIds: ["i" + i], bbox: { x: i * 300, y: 0, w: 200, h: 200 } }); }
    const ts = []; let tt = 1.6; for (let i = 0; i < m; i++) { ts.push(tt); tt += g * (0.9 + r4() * .2); }
    const pl = mod.planSlide(us, W, H, tt + 2, ts, { writeHeading: true, maxIdle: 1 });
    pl.items.filter(u => u.unit.kind === "item").forEach((u, i) => { tot++; if (Math.abs(u.begin - ts[i]) > 1e-6) off++; });
    if (pl.items.some((a, i) => i && a.begin < pl.items[i - 1].begin + pl.items[i - 1].dur - 1e-6)) ov++;
    // the drawing is fitted before the next element starts
    const its = pl.items.filter(u => u.unit.kind === "item");
    its.forEach((u, i) => { if (i + 1 < its.length && u.begin + u.dur > its[i + 1].begin + 1e-6) short++; });
  }
  ok(off === 0 && ov === 0 && short === 0, `script gap ${g} s: ${tot} elements start exactly at their script time, ${off} late, ${ov} overlaps, ${short} not fitted`);
}

// ---- step 5: arrows and dashes are whole units, placed between their two elements.
{
  const base = (extra) => [L("a", "icon", 100, 250, 260, 260), L("ac", "text", 110, 540, 240, 40), L("b", "icon", 650, 250, 260, 260), L("bc", "text", 660, 540, 240, 40), L("c", "icon", 1200, 250, 260, 260), L("cc", "text", 1210, 540, 240, 40), ...extra];
  // dashed line a -> b: 6 short dashes (squarish, so they are not "thin"), plus an arrowhead at the tip
  const dashes = [0, 1, 2, 3, 4, 5].map(i => L("d" + i, "connector", 385 + i * 42, 376, 22, 14));
  const head = L("hd", "icon", 600, 368, 24, 30);
  let us = mod.buildUnits({ width: W, height: H, layers: base([...dashes, head]) });
  let ar = us.filter(u => u.kind === "arrow");
  ok(ar.length === 1 && ar[0].layerIds.length === 7, `dashed line + head = one arrow unit (${ar.length} arrows, ${ar[0]?.layerIds.length} pieces)`);
  ok(us.filter(u => u.kind === "item").length === 3, "the dashes did not become elements");
  // upright dashes (a vertical dashed line between two rows)
  const rows = [L("p", "icon", 100, 100, 200, 160), L("pc", "text", 100, 270, 200, 30), L("q", "icon", 100, 600, 200, 160), L("qc", "text", 100, 770, 200, 30),
    ...[0, 1, 2, 3, 4].map(i => L("v" + i, "connector", 196, 330 + i * 50, 10, 24))];
  us = mod.buildUnits({ width: W, height: H, layers: rows });
  ar = us.filter(u => u.kind === "arrow");
  ok(ar.length === 1 && ar[0].layerIds.length === 5, `upright dashes = one arrow unit (${ar.length} arrows)`);
  // timing: the line is drawn whole after the first element and before the second; pieces run from the far end to the target
  us = mod.buildUnits({ width: W, height: H, layers: base([...dashes, head]) });
  const p = { width: W, height: H, layers: base([...dashes, head]) };
  const plan = mod.planSlide(us, W, H, 20, [2, 8, 14], { writeHeading: true, maxIdle: 1 });
  const seq = plan.items.map(u => u.unit.kind[0]).join("");
  ok(seq === "iaii", "order: element, whole arrow, element, element (" + seq + ")");
  const ai = plan.items.find(u => u.unit.kind === "arrow"), lt = mod.layerTimes(plan, p);
  const mem = ai.unit.layerIds.map(id => ({ id, ...lt.get(id) })).sort((x, y) => x.begin - y.begin);
  ok(mem.every(q => q.begin >= ai.begin - 1e-6 && q.begin + q.dur <= ai.begin + ai.dur + 1e-6), "every piece of the arrow is inside the arrow's own window");
  ok(mem[0].id === "d0" && mem[mem.length - 1].id === "hd", "the line is drawn from its far end, the head last: " + mem.map(q => q.id).join(" "));
  // an arrow down to the next row comes before the first element of that row, not after the whole first row
  const grid = [];
  for (let i = 0; i < 6; i++) { const x = 100 + (i % 3) * 500, y = 150 + Math.floor(i / 3) * 400; grid.push(L("g" + i, "icon", x, y, 220, 160), L("gc" + i, "text", x, y + 170, 220, 30)); }
  grid.push(...[0, 1, 2].map(i => L("w" + i, "connector", 700 - 0 + 40 * 0 + 0, 0, 0, 0)).slice(0, 0));
  grid.push(L("vt", "connector", 195, 360, 14, 120));    // from g0 straight down to g3
  const gu = mod.buildUnits({ width: W, height: H, layers: grid });
  const gp = mod.planSlide(gu, W, H, 30, [2, 5, 8, 11, 14, 17], { writeHeading: false, maxIdle: 1 });
  const names = gp.items.map(u => u.unit.kind === "arrow" ? "ARROW" : u.unit.layerIds[0]);
  const ia = names.indexOf("ARROW"), i3 = names.indexOf("g3");
  ok(ia === i3 - 1, "arrow g0 -> g3 comes right before g3: " + names.join(" "));
}

// ---- step 6: per-slide report.
{
  const ls = [L("i0", "icon", 100, 250, 260, 260), L("c0", "text", 110, 540, 240, 40), L("i1", "icon", 650, 250, 260, 260), L("c1", "text", 660, 540, 240, 40), L("a", "connector", 380, 350, 250, 16), L("i2", "icon", 1200, 250, 260, 260), L("c2", "text", 1210, 540, 240, 40), L("h", "text", 300, 40, 1000, 90)];
  const us = mod.buildUnits({ width: W, height: H, layers: ls });
  const plan = mod.planSlide(us, W, H, 20, [2, 8, 14], { writeHeading: true, maxIdle: 1 });
  const row = { n: 3, start: 100, end: 120, heading: "HEAD", status: "ok", elements: [102, 108, 114], captions: ["ONE", "TWO", "THREE"], plan };
  const { lines, problems } = mod.slideLines(row);
  ok(lines.map(l => l.step).join(",") === "H,1,-,2,3" && problems.length === 0, "report lists heading, elements and the arrow in drawn order: " + lines.map(l => l.step).join(","));
  ok(lines.filter(l => l.kind === "element").every((l, i) => Math.abs(l.begin - [102, 108, 114][i]) < 1e-6 && l.script !== null && Math.abs(l.diff) < 1e-6), "report shows each element at its script time, diff 0");
  ok(/OK: every element/.test(mod.slideReportText(row)) && /"?TWO/.test(mod.slideReportText(row)), "text report says OK and shows the captions");
  const late = mod.slideLines({ ...row, elements: [101, 108, 114] });
  ok(late.problems.some(m => /after its script time/.test(m)), "an element later than its script time is flagged");
  const cnt = mod.slideLines({ ...row, elements: [102, 108] });
  ok(cnt.problems.some(m => /lists 2 elements, 3 found/.test(m)), "a count difference is noted");
  const csv = mod.reportCsv([row, { n: 4, start: 120, end: 130, status: "error" }]).trim().split("\r\n");
  ok(csv.length === 1 + lines.length && csv[0].startsWith("image,") && csv[2].includes('"ONE"'), "CSV has one row per unit: " + csv.length);
  const all = mod.reportText([row, { n: 4, start: 120, end: 130, status: "error" }]);
  ok(/2 images, 1 to look at/.test(all) && /Image 4: CHECK, not analysed/.test(all), "summary on top counts the images to look at");
}


// ---- v2 steps 1-3: panels, checks, batches
{
  const v2 = JSON.stringify({ format: "ils-slides", version: 2, timing_source: "srt", slides: [
    { n: 21, start: 100, end: 110, system: "rows", layout: "R-2", heading: "1. EGGS", text: "Eggs are complete.", panels: [
      { shape: "circle", label: "COMPLETE FOOD", start: 101.2 }, { shape: "rectangle", label: "PROTEIN", caption: "NOTE", start: 105 }] },
    { n: 22, start: 110, end: 120, system: "comic", layout: "CL-3", text: "He said:", panels: [
      { shape: "rectangle", label: "HE SPOKE", start: 110 }, { shape: "parchment", label: "THE WORD", verse: "Blessed are the poor in spirit.", start: 114 }] },
  ] });
  const r = mod.parseSlidesFileChecked(v2);
  ok(r.specs.length === 2 && r.specs[0].n === 21, "v2: a batch starting at image 21 is read");
  ok(JSON.stringify(r.specs[0].elements) === "[101.2,105]", "v2: panel starts become element times");
  ok(JSON.stringify(r.specs[0].captions) === '["COMPLETE FOOD","PROTEIN"]', "v2: panel labels become captions (label, not the narration box)");
  ok(r.specs[0].heading === "1. EGGS" && r.specs[1].heading === undefined, "v2: heading only where given (no heading = no ribbon)");
  ok(r.specs[0].text === "Eggs are complete." && r.specs[1].layout === "CL-3" && r.specs[1].system === "comic", "v2: text, system and layout are kept");
  ok(r.specs[1].panels[1].verse === "Blessed are the poor in spirit." && r.specs[0].panels[1].caption === "NOTE", "v2: verse and caption box text are kept");
  ok(r.notes.length === 0, "v2: a clean file has no notes " + JSON.stringify(r.notes));

  const bad = (f) => { const j = JSON.parse(v2); f(j); return mod.parseSlidesFileChecked(JSON.stringify(j)).notes.join(" | "); };
  ok(/at most 8/.test(bad(j => { j.slides[0].panels = Array.from({ length: 9 }, (_, i) => ({ shape: "circle", label: "A", start: 101 + i * 0.5 })); })), "v2: 9 panels is flagged");
  ok(/needs its "verse"/.test(bad(j => { delete j.slides[1].panels[1].verse; })), "v2: parchment without verse is flagged");
  ok(/only for parchment/.test(bad(j => { j.slides[1].panels[0].verse = "x"; })), "v2: verse on a rectangle is flagged");
  ok(/not circle, rectangle or parchment/.test(bad(j => { j.slides[0].panels[0].shape = "star"; })), "v2: unknown shape is flagged");
  ok(/5 words/.test(bad(j => { j.slides[0].panels[0].label = "ONE TWO THREE FOUR FIVE"; })), "v2: a 5-word label is flagged");
  ok(/not "rows" or "comic"/.test(bad(j => { j.slides[0].system = "grid"; })), "v2: unknown layout system is flagged");
  ok(/no "text"/.test(bad(j => { delete j.slides[0].text; })), "v2: missing script line is noted");
  ok(/not in order/.test(bad(j => { j.slides[0].panels[1].start = 100.5; })), "v2: panel times out of order are flagged by the shared rules");

  // v1 files still parse exactly as before
  const v1 = mod.parseSlidesFileChecked(JSON.stringify({ format: "ils-slides", version: 1, timing_source: "srt", slides: [{ n: 1, start: 0, end: 7, heading: "H", elements: [{ caption: "A", start: 1.6 }, { caption: "B", start: 3.1 }] }] }));
  ok(v1.specs[0].panels === undefined && v1.specs[0].captions.join() === "A,B" && v1.notes.length === 0, "v1 file still reads the same");

  // batches: match by number
  ok(mod.matchNotes([21, 22], r.specs).length === 0, "batch: pictures 21-22 match slides 21-22");
  ok(/None of the picture numbers match/.test(mod.matchNotes([1, 2], r.specs)[0] ?? ""), "batch: pictures 1-2 with a file for 21-22 is called out");
  const part = mod.matchNotes([21, 22, 23], r.specs).join(" | ");
  ok(/No timing for picture 23/.test(part), "batch: a picture without a slide is named");
  const part2 = mod.matchNotes([21], r.specs).join(" | ");
  ok(/image 22 but no picture/.test(part2), "batch: a slide without a picture is named");
  ok(mod.matchNotes([], r.specs).length === 0 && mod.matchNotes([1], []).length === 0, "batch: nothing to compare gives no notes");
  ok(mod.ranges([1, 2, 3, 7, 9, 10]) === "1-3, 7, 9-10", "ranges() groups numbers");
}
// ---- v2 step 6: match detected panels to the contract by order
{
  const pr = await build({ entryPoints: ["src/panelmatch.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  const pm = await import("data:text/javascript;base64," + Buffer.from(pr.outputFiles[0].text).toString("base64"));
  const FP = (id, shape, x, y, w, h, o = {}) => ({ id, shape, bbox: [x, y, w, h], inner: [x, y, w, h], thickness: 10, color: "#fff", inset: false, parent: null, label: null, caption: null, ...o });
  const F = (panels, arrows = []) => ({ width: 1600, height: 900, frame: null, panels, ribbon: null, arrows, unassigned: [] });
  const ids = (m, f) => m.order.map(i => f.panels[i].id).join(",");
  // rows, the found list is in a scrambled order
  let f = F([FP(1, "circle", 1100, 280, 300, 300), FP(2, "circle", 180, 280, 300, 300), FP(3, "rectangle", 650, 280, 300, 300)]);
  let m = pm.matchPanels(f, [{ shape: "circle" }, { shape: "rectangle" }, { shape: "circle" }]);
  ok(ids(m, f) === "2,3,1" && m.notes.length === 0, "step 6: rows are matched left to right whatever order the backend listed them");
  // comic: tall panel on the left, two stacked on the right (row bands would read right-top, left, right-bottom)
  f = F([FP(1, "rectangle", 800, 470, 740, 370), FP(2, "rectangle", 60, 60, 700, 780), FP(3, "rectangle", 800, 60, 740, 370)]);
  m = pm.matchPanels(f, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]);
  ok(ids(m, f) === "2,3,1" && m.method === "xycut", "step 6: tall-left + two stacked is read left, right-top, right-bottom");
  ok(pm.matchPanels(f).method === "rows" && ids(pm.matchPanels(f), f) === "3,2,1", "step 6: without a contract the panels fall back to row bands");
  // the contract's shapes choose between reading orders: 2x2 grid read down the columns
  f = F([FP(1, "circle", 60, 60, 400, 380), FP(2, "rectangle", 800, 60, 700, 380), FP(3, "circle", 60, 480, 400, 380), FP(4, "rectangle", 800, 480, 700, 380)]);
  m = pm.matchPanels(f, [{ shape: "circle" }, { shape: "circle" }, { shape: "rectangle" }, { shape: "rectangle" }]);
  ok(m.method === "cols" && ids(m, f) === "1,3,2,4", "step 6: the shapes in the file pick column order when row order does not fit");
  m = pm.matchPanels(f, [{ shape: "circle" }, { shape: "rectangle" }, { shape: "circle" }, { shape: "rectangle" }]);
  ok(ids(m, f) === "1,2,3,4" && m.shapeMismatch.length === 0, "step 6: the same grid read in rows when the shapes say so");
  // an inset follows its parent
  f = F([FP(1, "rectangle", 1060, 60, 480, 780), FP(2, "rectangle", 700, 100, 260, 200, { inset: true, parent: 3 }), FP(3, "rectangle", 60, 60, 940, 780)]);
  m = pm.matchPanels(f, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]);
  ok(ids(m, f) === "3,2,1", "step 6: an inset panel is read right after the panel it sits in");
  // counts: the existing short notes
  f = F([FP(1, "rectangle", 60, 60, 400, 400), FP(2, "rectangle", 500, 60, 400, 400), FP(3, "rectangle", 940, 60, 400, 400)]);
  m = pm.matchPanels(f, [{ shape: "rectangle" }, { shape: "rectangle" }]);
  ok(m.order.length === 2 && m.notes[0] === "Found 3 panels, the file lists 2: matched in order, the last 1 keep the picture's lettering and follow the last script time.", "step 6: more panels than the file lists gives the existing note");
  m = pm.matchPanels(f, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]);
  ok(m.notes[0] === "Found 3 panels, the file lists 4: matched in order, the last 1 are not used.", "step 6: fewer panels than the file lists gives the existing note");
  ok(pm.countNote(2, 3) === "Found 2 elements, the file lists 3: matched in order, the last 1 are not used." && pm.countNote(3, 3) === "" && pm.countNote(3, undefined) === "", "step 6: the note text for version 1 elements is unchanged");
  // shape mismatch and the arrow leaving each panel
  f = F([FP(1, "rectangle", 60, 60, 400, 400), FP(2, "rectangle", 500, 60, 400, 400), FP(3, "circle", 940, 60, 400, 400)], [{ id: 1, bbox: [460, 200, 40, 40], from: 1, to: 2, headKnown: true }, { id: 2, bbox: [900, 200, 40, 40], from: 2, to: 3, headKnown: true }]);
  m = pm.matchPanels(f, [{ shape: "rectangle" }, { shape: "circle" }, { shape: "circle" }]);
  ok(JSON.stringify(m.shapeMismatch) === "[2]" && /Panel 2: the file says circle, but a rectangle was found/.test(m.notes.join(" ")), "step 6: a shape that does not fit is named");
  ok(m.arrowOut[0]?.to === 2 && m.arrowOut[1]?.to === 3 && m.arrowOut[2] === null, "step 6: each panel gets the arrow that leads out of it (the last one has none)");
  ok(pm.matchPanels(F([]), [{ shape: "circle" }]).notes[0] === "Found 0 panels, the file lists 1: matched in order, the last 1 are not used.", "step 6: no panels found gives the note, not a crash");
}

// ---- v2 step 8: the original pixels (rasterHref) are revealed instead of the trace
{
  const rc = await build({ entryPoints: ["src/compose.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
  const cm = await import("data:text/javascript;base64," + Buffer.from(rc.outputFiles[0].text).toString("base64"));
  const px = "data:image/png;base64,AAAA";
  const RL = (id, extra = {}) => ({ ...L(id, "icon", 100, 100, 300, 200), group: "<g>OLDGROUP</g>", ...extra });
  const raster = RL("r", { rasterHref: px, rasterBox: [99.5, 99.5, 301, 201] });
  const art = cm.finalArt(raster);
  ok(art.includes("<image") && art.includes(px) && art.includes('x="99.5"') && art.includes('width="301"') && !art.includes("OLDGROUP"), "step 8: finalArt of a raster element is the original pixels at the cut box, not the trace");
  const old = RL("o", { inkGroup: "<g>INK</g>", colorGroup: "<g>COL</g>" });
  ok(cm.finalArt(old) === "<g>COL</g><g>INK</g>" && cm.finalArt(RL("p")) === "<g>OLDGROUP</g>", "step 8: elements without a raster are unchanged");
  const P = (layers) => ({ width: 1600, height: 900, backgroundColor: "#fff", layers });
  const bg = { kind: "color", color: "#fff" };
  const S = { draw: 3, pause: 0.5, min: 0.5, max: 10, fit: false, target: 30, entrance: "draw", style: "illustrate", enterTime: 0.6, dir: "left" };   // = DEFAULT_SKETCH (types.ts)
  ok(cm.sketchReady(P([raster]), S), "step 8: a raster element needs no traced stroke plan");
  const svgNoPlan = cm.composeSvg(P([raster]), bg, { sketch: S });
  ok(svgNoPlan.includes(px) && svgNoPlan.includes('mask id="rvr-r"') && !svgNoPlan.includes("OLDGROUP"), "step 8: with no traced plan the raster is uncovered by the sweep (reveal style)");
  const withPen = RL("s", { rasterHref: px, rasterBox: [100, 100, 300, 200], inkGroup: "<g>INK</g>", colorGroup: "<g>COL</g>", reveal: { v: 2, w: 6, paths: [{ d: "M100 100 L400 100", len: 300 }], cw: 20, cpaths: [{ d: "M100 150 L400 150", len: 300 }] } });
  const svgPen = cm.composeSvg(P([withPen]), bg, { sketch: S });
  ok(svgPen.includes('mask id="rvi-s"') && svgPen.includes("M100 100 L400 100") && svgPen.includes("M100 150 L400 150") && svgPen.includes(px) && !svgPen.includes("INK") && !svgPen.includes("COL<") && !svgPen.includes('id="rvc-s"'), "step 8: the traced strokes are only the pen path over the raster (ink and colour groups unused)");
  const svgOld = cm.composeSvg(P([{ ...old, reveal: withPen.reveal }]), bg, { sketch: S });
  ok(svgOld.includes('id="rvc-o"') && svgOld.includes('id="rvi-o"') && svgOld.includes("COL") && svgOld.includes("INK"), "step 8: the old ink + colour passes still run for elements without a raster");
  for (const ent of ["fade", "pop", "slide", "none"]) ok(cm.composeSvg(P([raster]), bg, { sketch: { ...S, entrance: ent } }).includes(px) && !cm.composeSvg(P([raster]), bg, { sketch: { ...S, entrance: ent } }).includes("OLDGROUP"), "step 8: entrance " + ent + " shows the raster");
  ok(cm.composeSvg(P([raster]), bg, {}).includes(px), "step 8: the still picture shows the raster");
  ok(cm.composeSvg(P([raster]), bg, { sketch: S, skip: new Set(["r"]) }).includes(px) === false, "step 8: a skipped raster element is not drawn");
}

// ---- v2 step 9: 1080p 30 fps only, and the last-frame check
{
  const q = await build({ entryPoints: ["src/quality.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  const qm = await import("data:text/javascript;base64," + Buffer.from(q.outputFiles[0].text).toString("base64"));
  const stub2 = { name: "stub2", setup(b) { b.onResolve({ filter: /^mp4-muxer$/ }, a => ({ path: a.path, namespace: "stub2" })); b.onLoad({ filter: /.*/, namespace: "stub2" }, () => ({ contents: "export class ArrayBufferTarget {} export class Muxer {}", loader: "js" })); } };
  const vb = await build({ entryPoints: ["src/video.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub2], logLevel: "silent" });
  const vm = await import("data:text/javascript;base64," + Buffer.from(vb.outputFiles[0].text).toString("base64"));
  ok(vm.EXPORT_W === 1920 && vm.EXPORT_H === 1080 && vm.EXPORT_FPS === 30, "step 9: the export is 1920x1080 at 30 fps");
  const f1 = vm.fitInto(1376, 768), f2 = vm.fitInto(2752, 1536), f3 = vm.fitInto(1000, 1000), f4 = vm.fitInto(960, 540);
  ok(f1.w <= 1920 + 1e-6 && f1.h <= 1080 + 1e-6 && Math.abs(f1.w / f1.h - 1376 / 768) < 1e-9 && Math.abs(f1.x * 2 + f1.w - 1920) < 1e-6 && Math.abs(f1.y * 2 + f1.h - 1080) < 1e-6, "step 9: a 1376x768 picture is fitted and centred in 1080p, not stretched");
  ok(Math.abs(f2.w / f2.h - 2752 / 1536) < 1e-9 && f2.w <= 1920 + 1e-6, "step 9: a 4K-size picture is scaled down into 1080p");
  ok(f3.w === 1080 && f3.h === 1080 && f3.x === 420 && f3.y === 0, "step 9: a square picture gets bars left and right");
  ok(f4.w === 1920 && f4.h === 1080 && f4.x === 0 && f4.y === 0, "step 9: a small 16:9 picture is scaled up to fill 1080p");
  ok(vm.svgBackground('<svg><defs/><g><rect width="10" height="10" fill="#fdf6e3"/></g></svg>') === "#fdf6e3" && vm.svgBackground("<svg/>") === "#ffffff", "step 9: the bars take the picture's background colour");
  const W = 20, H = 10, a = new Uint8ClampedArray(W * H * 4).fill(255), b = new Uint8ClampedArray(a);
  ok(qm.compareFrames(a, b, W, H).mean === 0 && qm.qualityVerdict(qm.compareFrames(a, b, W, H)).ok, "step 9: identical frames are OK");
  for (let x = 0; x < 10; x++) for (let y = 0; y < H; y++) { const i = (y * W + x) * 4; b[i] = b[i + 1] = b[i + 2] = 0; }   // left half black
  const dAll = qm.compareFrames(a, b, W, H), dRight = qm.compareFrames(a, b, W, H, [{ x: 10, y: 0, w: 10, h: 10 }]), dLeft = qm.compareFrames(a, b, W, H, [{ x: 0, y: 0, w: 10, h: 10 }]);
  ok(Math.abs(dAll.bad - 0.5) < 1e-9 && !qm.qualityVerdict(dAll).ok && /CHECK/.test(qm.qualityVerdict(dAll).text), "step 9: half the picture wrong is flagged CHECK");
  ok(dRight.bad === 0 && qm.qualityVerdict(dRight).ok && dLeft.bad === 1, "step 9: only the chosen regions count (panels, not lettering)");
  b.set(a); b[0] = 0; ok(qm.qualityVerdict(qm.compareFrames(a, b, W, H)).ok, "step 9: a few edge pixels may differ");
}
// ---- v2 step 10: one unit per panel (panel, label, caption, arrow out); old logic as fallback
{
  const pr = await build({ entryPoints: ["src/panelmatch.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  const pm = await import("data:text/javascript;base64," + Buffer.from(pr.outputFiles[0].text).toString("base64"));
  const crop = (x, y, w, h) => ({ href: "data:image/png;base64,AAAA", box: [x, y, w, h], size: [w, h] });
  // 3 panels in a row; panel 1 has a caption box in its top-left corner (drawn AFTER the panel although it is higher), arrows 1->2 and 2->3, none out of 3
  const P = (id, x, o = {}) => ({ id, shape: "rectangle", bbox: [x, 100, 400, 300], inner: [x, 100, 400, 300], thickness: 4, color: "#000", inset: false, parent: null, raster: crop(x, 100, 400, 300),
    label: { bbox: [x + 50, 380, 300, 60], raster: crop(x + 50, 380, 300, 60) }, caption: null, ...o });
  const found = { width: 1600, height: 900, frame: null, ribbon: null, unassigned: [],
    panels: [P(1, 50, { caption: { bbox: [50, 100, 120, 40], corner: "tl", raster: crop(50, 100, 120, 40) } }), P(2, 550), P(3, 1050)],
    arrows: [{ id: 1, bbox: [470, 230, 70, 40], from: 1, to: 2, headKnown: true, raster: crop(470, 230, 70, 40) }, { id: 2, bbox: [970, 230, 70, 40], from: 2, to: 3, headKnown: true, raster: crop(970, 230, 70, 40) }] };
  const m = pm.matchPanels(found, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]);
  const layers = pm.panelLayers(found, m);
  ok(layers && layers.length === 3 + 3 + 1 + 2 && layers.every(l => l.rasterHref && l.panel), "step 10: panelLayers makes a raster layer for panels, labels, the caption box and arrows, all tagged");
  ok(!pm.panelLayers({ ...found, panels: [{ ...found.panels[0], raster: undefined }, ...found.panels.slice(1)] }, m), "step 10: a panel without raster gives null (old logic stays)");
  const units = mod.buildUnits({ width: 1600, height: 900, layers });
  const items = units.filter(u => u.kind === "item"), arrows = units.filter(u => u.kind === "arrow");
  ok(items.length === 3 && arrows.length === 2 && units.length === 5, "step 10: one item unit per panel and one arrow unit per arrow");
  ok(items.map(u => u.panel).join() === "1,2,3", "step 10: panel units are in the file's order");
  ok(items[0].layerIds.join() === "panel-1,panel-1-label,panel-1-caption", "step 10: a unit holds panel, then label, then caption box");
  ok(arrows.map(u => u.from).join() === "1,2", "step 10: an arrow unit knows the panel it leaves");
  const plan = mod.planSlide(units, 1600, 900, 30, [2, 10, 20], { writeHeading: false, maxIdle: 3 });
  const seq = plan.items.map(i => i.unit.id);
  ok(seq.join() === "u-panel-1,u-arrow-1,u-panel-2,u-arrow-2,u-panel-3", "step 10: order is panel 1, arrow out of 1, panel 2, arrow out of 2, panel 3");
  let prev = 0, strict = true; for (const i of plan.items) { if (i.begin < prev - 1e-9) strict = false; prev = i.begin + i.dur; }
  ok(strict && prev <= 30, "step 10: no unit starts before the one before it has ended; all end inside the slide");
  ok(plan.items.find(i => i.unit.id === "u-panel-2").begin >= 10 - 1e-9 - 0 || Math.abs(plan.items.find(i => i.unit.id === "u-panel-2").begin - 10) < 1e-6, "step 10: each panel starts at its script time");
  const times = mod.layerTimes(plan, { width: 1600, height: 900, layers });
  const t1 = times.get("panel-1"), tl = times.get("panel-1-label"), tc = times.get("panel-1-caption");
  ok(t1.begin <= tl.begin && tl.begin <= tc.begin, "step 10: inside a panel unit the panel is drawn first, then label, then caption (not top to bottom)");
  ok(mod.buildUnits({ width: 1600, height: 900, layers: [...layers, L("x", "icon", 10, 10, 50, 50)] }).every(u => u.panel === undefined && u.from === undefined), "step 10: one untagged layer: the old logic decides for the whole picture");
  const last = mod.planSlide(mod.buildUnits({ width: 1600, height: 900, layers: layers.filter(l => l.panel.k !== 3 || l.panel.role === "panel") }), 1600, 900, 30, null, { writeHeading: false, maxIdle: 3 });
  ok(last.items.length === 5 && last.main === 3, "step 10: panels without a script time are still all planned");
  const noArr = { ...found, arrows: [] }, a = pm.panelLayers(noArr, pm.matchPanels(noArr, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]));
  ok(a.length === 7 && mod.buildUnits({ width: 1600, height: 900, layers: a }).filter(u => u.kind === "arrow").length === 0, "step 10: no arrows found: no arrow units");
  ok(pm.panelLayers(found, pm.matchPanels(found, [{ shape: "rectangle" }])).filter(l => l.panel.role === "panel").length === 3, "step 10: panels the file does not list are kept, after the listed ones");
}
// ---- v2 step 11: handwriting on labels and caption boxes (real Patrick Hand font), parchment untouched, no invented heading
{
  const { readFileSync } = await import("node:fs");
  const opentype = (await import("opentype.js")).default;
  const stubU = { name: "stubU", setup(b) { b.onResolve({ filter: /\?url$|^jszip$|^mp4-muxer$/ }, a => ({ path: a.path, namespace: "stubU" })); b.onLoad({ filter: /.*/, namespace: "stubU" }, () => ({ contents: "export default {}", loader: "js" })); } };
  const sb = await build({ entryPoints: ["src/slides.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stubU], logLevel: "silent" });
  const { writeFileSync, mkdtempSync } = await import("node:fs"), { tmpdir } = await import("node:os"), { join } = await import("node:path"), { pathToFileURL } = await import("node:url");
  const tmpFile = join(mkdtempSync(join(tmpdir(), "ils-")), "slides-real.mjs");
  writeFileSync(tmpFile, 'import { createRequire } from "node:module"; const require = createRequire(import.meta.url);\n' + sb.outputFiles[0].text);
  const sm = await import(pathToFileURL(tmpFile).href);
  const load = f => { const b = readFileSync(f.endsWith(".ttf") ? f : "node_modules/@fontsource/" + f); return opentype.parse(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)); };
  const fonts = { main: load("src/fonts/PatrickHand-Regular.ttf"), math: load("noto-sans-math/files/noto-sans-math-latin-400-normal.woff") };
  const PL = (id, k, role, x, y, w, h, fill) => ({ ...L(id, role === "panel" ? "icon" : role === "arrow" ? "connector" : "text", x, y, w, h), panel: { k, role }, rasterHref: "data:image/png;base64,AAAA", ...(fill ? { fill } : {}) });
  const layers = [PL("p1", 1, "panel", 50, 100, 400, 300), PL("p1l", 1, "label", 100, 380, 300, 60, "#ffee99"), PL("p1c", 1, "caption", 50, 100, 120, 40, "#ffee88"),
    PL("p2", 2, "panel", 550, 100, 400, 300), PL("p2l", 2, "label", 600, 380, 300, 60, "#ffee99"),
    PL("p3", 3, "panel", 1050, 100, 400, 300), PL("p3l", 3, "label", 1100, 380, 300, 60, "#ffee99")];
  const proj = { width: 1600, height: 900, layers };
  const units = sm.buildUnits(proj), plan = sm.planSlide(units, 1600, 900, 30, [2, 10, 20], { writeHeading: false, maxIdle: 3 }), times = sm.layerTimes(plan, proj);
  const panels = [{ shape: "rectangle", label: "Lost Son", caption: "LOOK", start: 2 }, { shape: "parchment", label: "Verse", start: 10 }, { shape: "rectangle", label: "", start: 20 }];
  const tp = sm.planTexts(plan, proj, times, { heading: "SHOULD NOT SHOW", captions: ["x"], panels }, 30, fonts, "#1b2a41", "trace");
  ok(tp.skip.has("p1l") && tp.skip.has("p1c"), "step 11: the label and the caption box of a listed panel are not drawn as picture (their words are rewritten)");
  ok(!tp.skip.has("p2l") && !tp.skip.has("p2"), "step 11: a parchment panel keeps the picture's own lettering");
  ok(!tp.skip.has("p3l"), "step 11: a panel with no words in the file keeps its lettering");
  ok(!tp.skip.has("p1") && !tp.heading, "step 11: the panel art is never skipped and no heading is written in panel mode");
  ok((tp.markup.match(/<rect /g) || []).length === 2 && tp.markup.includes("#ffee99") && tp.markup.includes("#ffee88"), "step 11: each rewritten box is redrawn in its own colour");
  ok(tp.markup.includes('pathLength="1"') && tp.captions === 1 && tp.wanted === 1, "step 11: the words are drawn in Patrick Hand (1 label written, 1 wanted; trace style here, the write style needs a browser canvas)");
  const t = times.get("p1l"); ok(tp.markup.includes(`begin="${+t.begin.toFixed(3)}s"`), "step 11: the label is written at its own time in the unit's window");
  const none = sm.planTexts(plan, proj, times, undefined, 30, fonts, "#000", "trace");
  ok(none.skip.size === 0 && none.markup === "", "step 11: no texts in the file: nothing is replaced");
}
// ---- v2 step 12: timing per panel and report rows for panel / label / arrow
{
  const PL = (id, k, role, x, y, w, h) => ({ ...L(id, role === "panel" ? "icon" : role === "arrow" ? "connector" : "text", x, y, w, h), panel: { k, role }, rasterHref: "data:image/png;base64,AAAA" });
  const layers = [PL("p1", 1, "panel", 50, 100, 400, 300), PL("p1l", 1, "label", 100, 380, 300, 60), PL("p1c", 1, "caption", 50, 100, 120, 40), PL("a1", 1, "arrow", 470, 230, 70, 40),
    PL("p2", 2, "panel", 550, 100, 400, 300), PL("p2l", 2, "label", 600, 380, 300, 60), PL("a2", 2, "arrow", 970, 230, 70, 40),
    PL("p3", 3, "panel", 1050, 100, 400, 300), PL("p3l", 3, "label", 1100, 380, 300, 60)];
  const proj = { width: 1600, height: 900, layers };
  const units = mod.buildUnits(proj);
  const OPT = { writeHeading: false, maxIdle: 3 };
  // cues with room: each panel starts exactly at its cue
  const plan = mod.planSlide(units, 1600, 900, 30, [2, 10, 20], OPT);
  const at = id => plan.items.find(i => i.unit.id === id);
  ok([["u-panel-1", 2], ["u-panel-2", 10], ["u-panel-3", 20]].every(([id, t]) => Math.abs(at(id).begin - t) < 1e-6), "step 12: every panel starts exactly at its cue when there is room");
  const pItems = plan.items.filter(i => i.unit.kind === "item");
  ok(pItems.every((i, k) => k + 1 >= pItems.length || i.begin + i.dur <= pItems[k + 1].begin + 1e-9), "step 12: each panel is finished (with label and caption) before the next panel starts");
  // tight cues: strict order, nothing overlaps, everything inside the slide
  for (const cues of [[1, 1.3, 1.6], [0, 0.1, 0.2], [5, 5.5, 29.9], [28, 29, 30]]) {
    const pl = mod.planSlide(units, 1600, 900, 30, cues, OPT);
    let prev = 0, good = true; for (const i of pl.items) { if (i.begin < prev - 1e-9) good = false; prev = i.begin + i.dur; }
    ok(good && prev <= 30 && pl.items.length === 5, "step 12: tight cues " + cues.join("/") + ": strict order, all inside the slide");
    const ps = pl.items.filter(i => i.unit.kind === "item").map(i => i.begin);
    ok(ps.every((b, k) => !k || b >= ps[k - 1]), "step 12: tight cues " + cues.join("/") + ": panels keep the file's order");
  }
  // no cues at all: panels are spread over the slide, in order
  const even = mod.planSlide(units, 1600, 900, 30, null, OPT), eb = even.items.filter(i => i.unit.kind === "item").map(i => i.begin);
  ok(eb[0] >= 0.2 && eb[1] > eb[0] && eb[2] > eb[1] && eb[2] < 30, "step 12: no cues: panels are spread evenly over the slide");
  // inside a unit: panel 60%, label 25%, caption 15%
  const tm = mod.layerTimes(plan, proj), u1 = at("u-panel-1"), tp = tm.get("p1"), tl = tm.get("p1l"), tc = tm.get("p1c");
  ok(Math.abs(tp.dur / u1.dur - 0.6) < 1e-6 && Math.abs(tl.dur / u1.dur - 0.25) < 1e-6 && Math.abs(tc.dur / u1.dur - 0.15) < 1e-6, "step 12: a panel unit gives 60% to the panel, 25% to the label, 15% to the caption box");
  ok(Math.abs(tc.begin + tc.dur - (u1.begin + u1.dur)) < 1e-6, "step 12: the caption box ends exactly when its panel unit ends");
  // report
  const panelsSpec = [{ shape: "rectangle", label: "Lost Son", caption: "LOOK", start: 2 }, { shape: "rectangle", label: "", start: 10 }, { shape: "parchment", label: "Verse", start: 20 }];
  const parts = mod.panelParts(plan, proj, tm, panelsSpec, 100);
  ok(parts.length === 4 && parts.filter(x => x.role === "label").length === 3 && parts.filter(x => x.role === "caption").length === 1, "step 12: panelParts lists every label and caption box");
  ok(parts.find(x => x.k === 1 && x.role === "label").text === "Lost Son" && !parts.find(x => x.k === 1 && x.role === "label").kept, "step 12: a label with words from the file is not 'kept'");
  ok(parts.find(x => x.k === 2 && x.role === "label").kept && parts.find(x => x.k === 3 && x.role === "label").kept, "step 12: an empty label and a parchment panel keep the picture's lettering");
  const row = { n: 1, start: 100, end: 130, status: "ok", elements: [102, 110, 120], captions: ["Lost Son", "", "Verse"], plan, parts };
  const { lines, problems } = mod.slideLines(row);
  ok(lines.map(l => l.kind).join() === "element,label,caption,arrow,element,label,arrow,element,label", "step 12: report rows: panel, its label, its caption box, the arrow out, next panel ...");
  ok(lines.find(l => l.kind === "arrow").text === "arrow out of panel 1" && lines.filter(l => l.kind === "arrow").length === 2, "step 12: report rows name the panel an arrow leaves");
  ok(lines.find(l => l.step === "2.label").flag.includes("picture's lettering stays") && problems.length === 0, "step 12: a kept label is noted in its row but is not a problem");
  ok(mod.slideReportText(row).includes("1.label") && mod.reportCsv([row]).includes("label"), "step 12: text report and CSV show the label rows");
  ok(mod.slideLines({ ...row, parts: [{ ...parts[0], begin: 100 }] }).problems.some(x => /starts before its panel/.test(x)), "step 12: a label that starts before its panel is flagged");
}
{ // v2 step 13: panelCheck (numbers, layout name, found vs file)
  const r2 = await build({ entryPoints: ["src/panelmatch.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  const pm = await import("data:text/javascript;base64," + Buffer.from(r2.outputFiles[0].text).toString("base64"));
  const mk = (id, x, y, shape = "rectangle") => ({ id, shape, bbox: [x, y, 300, 200], inner: [x, y, 300, 200], thickness: 4, color: "#000", inset: false, parent: null, label: null, caption: null });
  const found = { width: 1000, height: 500, frame: null, panels: [mk(1, 0, 0), mk(2, 350, 0), mk(3, 0, 250), mk(4, 350, 250)], ribbon: null, arrows: [{ id: 1, bbox: [310, 80, 30, 20], from: 1, to: 2, headKnown: true }] };
  const c1 = pm.panelCheck(found, pm.matchPanels(found, [{ shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]), { system: "comic", layout: "CL-4", panels: [1, 2, 3, 4] });
  ok(c1.outlines.map(o => o.k).join() === "1,2,3,4" && c1.level === "ok" && c1.layoutText === "comic CL-4, 4 panels in the file", "step 13: numbers 1..4, level ok, layout name shown");
  ok(c1.countText.includes("they agree") && c1.arrows[0].from === 1, "step 13: counts agree, arrow tied to panel 1");
  const c2 = pm.panelCheck(found, pm.matchPanels(found, [{ shape: "rectangle" }, { shape: "rectangle" }]), undefined);
  ok(c2.level === "warn" && c2.outlines.filter(o => !o.listed).map(o => o.k).join() === "3,4" && c2.countText === "Found 4 panels, the file lists 2.", "step 13: panels beyond the file are numbered 3,4 and flagged, count text says 4 vs 2");
  const c3 = pm.panelCheck(found, pm.matchPanels(found, undefined));
  ok(c3.level === "none" && c3.layoutText === "" && c3.outlines.length === 4, "step 13: no panels list = level none, no layout line");
  const c4 = pm.panelCheck(found, pm.matchPanels(found, [{ shape: "circle" }, { shape: "rectangle" }, { shape: "rectangle" }, { shape: "rectangle" }]));
  ok(c4.outlines.some(o => o.mismatch) && c4.level === "warn", "step 13: a shape that differs from the file is flagged");
}
{ // v2 step 14: a version 1 file and untagged layers keep the old element path
  const { readFileSync } = await import("node:fs");
  const txt = readFileSync("tests/fixtures/example_baking_soda_slides.json", "utf8");
  const { specs, notes } = mod.parseSlidesFileChecked(txt);
  ok(specs.length === 11 && notes.every(n => /estimates/.test(n)), `step 14: v1 example parses (${specs.length} slides; only the "times are estimates" note)`);
  ok(specs.every(s => !s.panels && s.elements.length > 0 && s.elements.every(Number.isFinite)), "step 14: v1 slides have elements with start times and no panels");
  ok(specs[0].heading && specs[0].captions?.[0] === "KITCHEN CUPBOARD" && specs[0].elements[0] === 1.6, "step 14: v1 heading, caption and start time read as before");
  const v2 = mod.parseSlidesFileChecked(readFileSync("tests/fixtures/example_v2_panels.json", "utf8"));
  ok(v2.specs.length > 0 && v2.specs.every(s => s.panels && s.elements.length === s.panels.length), "step 14: v2 example still parses, elements made from panels");
  // untagged layers (the segment path) give element units and no panel unit
  const ls = [L("i0", "icon", 100, 250, 260, 260), L("c0", "text", 110, 540, 240, 40), L("i1", "icon", 650, 250, 260, 260), L("c1", "text", 660, 540, 240, 40)];
  const us = mod.buildUnits({ width: W, height: H, layers: ls });
  ok(us.filter(u => u.kind === "item").length === 2 && us.every(u => u.panel === undefined), "step 14: untagged layers = old element units, no panel tag");
  const plan = mod.planSlide(us, W, H, 12, specs[0].elements.slice(0, 2).map(v => v + 1), { writeHeading: true, maxIdle: 1 });
  ok(plan.items.every(i => i.unit.panel === undefined) && plan.items.filter(i => i.unit.kind === "item").length === 2, "step 14: v1 plan has no panel units");
}
console.log(fails ? fails + " FAILED" : "ALL PASSED"); process.exit(fails ? 1 : 0);
