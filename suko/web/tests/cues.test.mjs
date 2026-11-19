// Run: node tests/cues.test.mjs <timing.json> <folder with N.json panel analyses> [slide numbers, e.g. 1,2,3] [--strict] [--csv out.csv]
// FIX PLAN step 1 (measure), 2 (start at the cue), 6 (hold until the next cue), 9 (full check). It runs the REAL planner (planSlide, layerTimes) and the REAL
// drawing code (composeSvg) on the picture analyses made by `app/src/test/python/analyse_folder.py`, then reads every animation time out of the SVG.
import { build } from "esbuild";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
const args = process.argv.slice(2).filter(a => !a.startsWith("--"));
const strict = process.argv.includes("--strict");
const csvAt = process.argv.indexOf("--csv"), csvOut = csvAt > 0 ? process.argv[csvAt + 1] : "";
const svgAt = process.argv.indexOf("--svg"), svgDir = svgAt > 0 ? process.argv[svgAt + 1] : "";   // also save slide-N.svg + slide-N.json for tests/frames.py
const [timingFile, foundDir, only] = args.filter(a => a !== csvOut && a !== svgDir);
if (!timingFile || !foundDir) { console.log("usage: node tests/cues.test.mjs <timing.json> <found folder> [slides] [--strict] [--csv out.csv]"); process.exit(0); }
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default ''", loader: "js" })); } };
const r = await build({ stdin: { contents: 'export * from "./src/slides.ts"; export * from "./src/panelmatch.ts"; export { composeSvg } from "./src/compose.ts"; export { DEFAULT_SKETCH } from "./src/types.ts";', resolveDir: process.cwd(), loader: "ts" },
  bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const m = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));

// the same settings the video screen uses (VideoMode.tsx): draw style, pen 0.5, lines auto, max idle 5 s
const SK = { ...m.DEFAULT_SKETCH, entrance: "draw", pen: 0.5, lines: "auto", dir: "left", enterTime: 0.6, min: 0.02, max: 600, fit: false, target: 0 };
const { specs } = m.parseSlidesFileChecked(readFileSync(timingFile, "utf8"));
const want = only ? new Set(only.split(",").map(Number)) : null;
const TOL = 0.15;   // a panel's first visible stroke must come within this many seconds of its cue
const EV = /<animate attributeName="([^"]+)" from="([^"]+)" to="([^"]+)" begin="([\d.]+)s" dur="([\d.]+)s"/g;
const rows = []; let flags = 0, panelsSeen = 0, slidesSeen = 0;
const f2 = v => (v === null || v === undefined || !isFinite(v) ? "  -  " : v.toFixed(2).padStart(5));

for (const spec of specs) {
  if (want && !want.has(spec.n)) continue;
  const fp = `${foundDir}/${spec.n}.json`;
  if (!existsSync(fp) || !spec.panels?.length) continue;
  const found = JSON.parse(readFileSync(fp, "utf8"));
  if (!found.panels?.length || found.panels.some(p => !p.raster)) { console.log(`slide ${spec.n}: no panels found in the picture (skipped)`); continue; }
  const pm = m.matchPanels(found, spec.panels), pl = m.panelLayers(found, pm);
  const p = { width: found.width, height: found.height, backgroundColor: "#ffffff", layers: pl };
  const units = m.buildUnits(p), D = spec.end - spec.start;
  const local = spec.elements.length ? spec.elements.map(e => e - spec.start) : null;
  const plan = m.planSlide(units, p.width, p.height, D, local, { writeHeading: true, maxIdle: 5 });
  const times = m.layerTimes(plan, p);
  const svg = m.composeSvg(p, { kind: "color", color: "#ffffff" }, { sketch: SK, times });
  // events per layer (times are on the slide clock, seconds from the image start)
  const parts = svg.split(/(?=<g id="panel-\d+(?:-\w+)?" transform=)/).filter(s => s.startsWith('<g id="panel-'));
  const ev = new Map();
  for (const part of parts) {
    const id = /^<g id="([^"]+)"/.exec(part)[1]; const list = [];
    for (const mm of part.matchAll(EV)) list.push({ attr: mm[1], b: +mm[4], e: +mm[4] + +mm[5] });
    ev.set(id, list);
  }
  const span = id => { const l = (ev.get(id) ?? []); return l.length ? { first: Math.min(...l.map(x => x.b)), last: Math.max(...l.map(x => x.e)) } : null; };
  const cues = spec.panels.map(q => q.start - spec.start);
  if (svgDir) {   // for tests/frames.py: the SVG and where every panel sits and when it is due
    mkdirSync(svgDir, { recursive: true });
    writeFileSync(`${svgDir}/slide-${spec.n}.svg`, svg);
    writeFileSync(`${svgDir}/slide-${spec.n}.json`, JSON.stringify({ n: spec.n, w: p.width, h: p.height, dur: D, panels: pm.order.map((_, i) => {
      const k = i + 1, L = p.layers.find(l => l.id === `panel-${k}`), B = p.layers.find(l => l.id === `panel-${k}-label`);
      return { k, label: spec.panels[i]?.label ?? "", cue: cues[i], next: i + 1 < pm.order.length ? cues[i + 1] : D, box: L.bbox, labelBox: B?.bbox ?? null };
    }) }));
  }
  const K = pm.order.length; slidesSeen++;
  console.log(`\nSLIDE ${spec.n}  ${spec.layout ?? ""}  ${D.toFixed(2)} s  ${K} panels   (all times in seconds from the slide start)`);
  console.log(" k  label               cue   planBeg planDur  1stStroke  picEnd  lblBeg  lblEnd  next    lag   hold  flags");
  let prevEnd = 0;
  for (let k = 1; k <= K; k++) {
    const sp = spec.panels[k - 1], cue = cues[k - 1] ?? NaN, next = k < K ? (cues[k] ?? D) : D;
    const it = plan.items.find(i => i.unit.kind === "item" && i.unit.panel === k);
    const pic = span(`panel-${k}`), lab = span(`panel-${k}-label`), cap = span(`panel-${k}-caption`);
    const first = pic?.first ?? it?.begin, picEnd = pic?.last, lblB = lab?.first, lblE = lab?.last;
    const doneAt = Math.max(picEnd ?? 0, lblE ?? 0, cap?.last ?? 0);
    const lag = first - cue, hold = next - doneAt;
    const fl = [];
    if (it && Math.abs(it.begin - cue) > 0.02) fl.push("BEGIN!=CUE");            // step 2: the panel's window starts at its cue
    if (lag > TOL) fl.push("LATE");                                              // steps 2/3/4: first visible stroke within TOL of the cue
    if (first < cue - 0.02) fl.push("EARLY");
    if (doneAt > next + 0.001) fl.push("OVERRUN");                               // step 6: finished before the next cue
    if (first < prevEnd - 0.001) fl.push("OVERLAP");                             // step 6: nothing of this panel moves before the one before has finished
    if (lblB !== undefined && picEnd !== undefined && lblB < pic.first + 0.4 * (picEnd - pic.first) - 0.01) fl.push("LABEL-EARLY");   // step 5
    if (lblB !== undefined && lblE > next + 0.001) fl.push("LABEL-OVER");
    const arrow = span(`panel-${k}-arrow`);
    if (arrow && k < K && arrow.last > next + 0.001) fl.push("ARROW-OVER");
    prevEnd = Math.max(prevEnd, doneAt);
    flags += fl.length; panelsSeen++;
    rows.push([spec.n, k, sp.label, cue, it?.begin, it?.dur, first, picEnd, lblB, lblE, next, lag, hold, fl.join(" ")]);
    console.log(` ${k}  ${(sp.label || "(none)").slice(0, 17).padEnd(17)} ${f2(cue)}   ${f2(it?.begin)}  ${f2(it?.dur)}    ${f2(first)}    ${f2(picEnd)}  ${f2(lblB)}  ${f2(lblE)}  ${f2(next)}  ${f2(lag)} ${f2(hold)}  ${fl.join(" ")}`);
  }
}
console.log(`\n${slidesSeen} slides, ${panelsSeen} panels, ${flags} flags`);
if (csvOut) {
  const head = "slide,panel,label,cue,plan_begin,plan_dur,first_stroke,picture_end,label_begin,label_end,next_cue,lag,hold,flags";
  writeFileSync(csvOut, [head, ...rows.map(x => x.map(v => typeof v === "number" ? v.toFixed(2) : `"${v ?? ""}"`).join(","))].join("\n") + "\n");
  console.log("CSV saved: " + csvOut);
}
if (strict && flags) process.exit(1);
