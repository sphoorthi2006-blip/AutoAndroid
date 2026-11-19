// Run: node tests/timing.test.mjs   (FIX PLAN steps 2 and 6: a panel starts exactly at its cue; no drawing runs past the next cue)
import { build } from "esbuild";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default ''", loader: "js" })); } };
const r = await build({ stdin: { contents: 'export { planSlide, layerTimes, panelParts } from "./src/slides.ts"; export { revealGroup, linesColourGroup } from "./src/compose.ts";', resolveDir: process.cwd(), loader: "ts" },
  bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const m = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
let fails = 0; const ok = (c, msg) => { console.log((c ? "PASS " : "FAIL ") + msg); if (!c) fails++; };

// step 2: panels with no heading start EXACTLY at their cues (before: the first one was pushed 0.05 s late)
const unit = (k, x, y) => ({ kind: "item", panel: k, layerIds: [`panel-${k}`], bbox: { x, y, w: 400, h: 300 } });
const units = [unit(1, 0, 0), unit(2, 450, 0), unit(3, 0, 350), unit(4, 450, 350)];
const cues = [0, 4.51, 8.08, 8.83];   // the last gap is 0.75 s, the shortest in the Genesis 1 file
const plan = m.planSlide(units, 1376, 768, 14, cues, { writeHeading: true, maxIdle: 5 });
const items = plan.items.filter(i => i.unit.kind === "item");
ok(items.length === 4, "four panels planned");
items.forEach((it, i) => ok(Math.abs(it.begin - cues[i]) < 1e-9, `panel ${i + 1} begins exactly at its cue (${it.begin.toFixed(3)} vs ${cues[i]})`));
// step 6: every drawing is finished before the next cue and the windows never overlap
items.forEach((it, i) => { const next = i + 1 < items.length ? cues[i + 1] : 14; ok(it.begin + it.dur <= next + 1e-9, `panel ${i + 1} is finished before the next cue (${(it.begin + it.dur).toFixed(2)} <= ${next})`); });

// step 6: a drawing with very many strokes in a short window still ends inside the window (before: 0.01 s per stroke minimum ran past the end)
const strokes = Array.from({ length: 160 }, (_, i) => ({ d: `M0 ${i} L100 ${i}`, len: 100 }));
const layer = { id: "a", name: "a", group: "<g/>", visible: true, kind: "icon", bbox: { x: 0, y: 0, w: 100, h: 160 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true }, reveal: { v: 2, w: 2, cw: 8, paths: strokes, cpaths: [] } };
const EV = /begin="([\d.]+)s" dur="([\d.]+)s"/g;
for (const dur of [0.4, 0.8, 3]) {
  const svg = m.revealGroup(layer, { width: 100, height: 160, backgroundColor: "#fff", layers: [layer] }, 10, dur, 0.5);
  const ends = [...svg.matchAll(EV)].map(x => +x[1] + +x[2]);
  ok(Math.max(...ends) <= 10 + dur + 0.06, `160 strokes in ${dur} s end inside the window (last end ${(Math.max(...ends) - 10).toFixed(2)} s, pop-in 0.05 s allowed)`);
}
// step 3: a painted panel (raster, edge-only reveal, NO inkRasterHref) shows its first outline stroke at its cue, not after the lines share of its window
{
  const ring = [{ d: "M0 0 L400 0 L400 300 L0 300 Z", len: 1400 }];
  const sweep = [{ d: "M0 10 L400 10 L0 30 L400 30", len: 1600 }];
  const pl = { id: "panel-1", name: "Panel 1", group: "", visible: true, kind: "icon", bbox: { x: 0, y: 0, w: 400, h: 300 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true },
    rasterHref: "data:image/png;base64,AA==", rasterBox: [0, 0, 400, 300], reveal: { v: 2, w: 8, cw: 14, edgeFirst: true, paths: ring, cpaths: sweep } };
  const pr = { width: 400, height: 300, backgroundColor: "#fff", layers: [pl] };
  for (const [begin, dur] of [[5, 3], [5, 1.2], [5, 0.75]]) {
    const svg = m.linesColourGroup(pl, pr, begin, dur, begin, begin + dur, 0.5);
    const starts = [...svg.matchAll(EV)].map(x => +x[1]);
    const first = Math.min(...starts), last = Math.max(...[...svg.matchAll(EV)].map(x => +x[1] + +x[2]));
    ok(first - begin <= 0.15, `painted panel (${dur} s window): first stroke ${(first - begin).toFixed(2)} s after the cue (<= 0.15)`);
    ok(last <= begin + dur + 0.06, `painted panel (${dur} s window): all strokes end inside the window (pop-in 0.05 s allowed)`);
  }
}
// step 4: a panel with NO colour sweeps from the backend (nearly all dark ink, or no colour of its own) used to appear in ONE 0.05 s step at the end of its colour slot.
// Now the colour pass always has real strokes: the colour wipe fills the whole colour slot (>= 0.6 s once the window is 1.2 s or longer; shorter only in a shorter window).
{
  const ring = [{ d: "M0 0 L400 0 L400 300 L0 300 Z", len: 1400 }];
  const mk = (cpaths, ink) => ({ id: "panel-2", name: "Panel 2", group: "", visible: true, kind: "icon", bbox: { x: 0, y: 0, w: 400, h: 300 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true },
    rasterHref: "data:image/png;base64,AA==", rasterBox: [0, 0, 400, 300], ...(ink ? { inkRasterHref: "data:image/png;base64,AA==" } : {}), reveal: { v: 2, w: 8, cw: 14, paths: ring, cpaths } });
  for (const [what, ink] of [["painted panel", false], ["line-art panel", true]]) for (const dur of [3.18, 1.5, 1.2, 0.75]) {
    const pl = mk([], ink), pr = { width: 400, height: 300, backgroundColor: "#fff", layers: [pl] };
    const svg = m.linesColourGroup(pl, pr, 20, dur, 20, 20 + dur, 0.5);
    const ev = [...svg.matchAll(EV)].map(x => ({ b: +x[1], e: +x[1] + +x[2], d: +x[2] }));
    const cb = 20 + dur * 0.5, col = ev.filter(e => e.b >= cb - 0.011 && !(Math.abs(e.d - 0.05) < 1e-9 && e.b >= 20 + dur - 0.011));   // strokes of the colour slot (the end pop: 0.05 s at the very end, not counted)
    const covered = col.length ? Math.max(...col.map(e => e.e)) - Math.min(...col.map(e => e.b)) : 0;
    const want = Math.min(0.6, dur * 0.5) - 0.02;
    ok(covered >= want && col.length >= 2, `${what}, ${dur} s window, no backend sweeps: colour wipe has ${col.length} strokes over ${covered.toFixed(2)} s (>= ${want.toFixed(2)} s)`);
  }
  // sweeps the backend DID give are still used as they are (one snake of length 1600 here)
  const pl2 = mk([{ d: "M0 10 L400 10 L0 30", len: 900 }], false), pr2 = { width: 400, height: 300, backgroundColor: "#fff", layers: [pl2] };
  ok(m.linesColourGroup(pl2, pr2, 0, 3, 0, 3, 0.5).includes("M0 10 L400 10 L0 30"), "backend colour sweeps are kept when there are some");
}

// step 5: the label (and caption) of a panel start only after the picture is drawn (never before picture start + 40% of its draw time) and end inside the panel's window
{
  const mkL = (id, role, y) => ({ id, name: id, group: "", visible: true, kind: role === "panel" ? "icon" : "text", bbox: { x: 0, y, w: 400, h: role === "panel" ? 300 : 60 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true }, rasterHref: "x", panel: { k: 1, role } });
  // the order of layerIds must NOT matter: a label listed before its picture still comes after it
  for (const order of [["panel-1", "panel-1-label", "panel-1-caption"], ["panel-1-caption", "panel-1-label", "panel-1"]]) {
    const ls = [mkL("panel-1", "panel", 0), mkL("panel-1-label", "label", 305), mkL("panel-1-caption", "caption", 370)];
    const pr = { width: 600, height: 500, backgroundColor: "#fff", layers: ls };
    for (const win of [3.18, 1.5, 0.75]) {
      const pl = { items: [{ unit: { id: "u", kind: "item", panel: 1, layerIds: order, bbox: { x: 0, y: 0, w: 400, h: 430 } }, begin: 10, dur: win }], main: 1, fromJson: true };
      const tm = m.layerTimes(pl, pr), pic = tm.get("panel-1"), lab = tm.get("panel-1-label"), cap = tm.get("panel-1-caption");
      const parts = m.panelParts(pl, pr, tm, [{ label: "A", caption: "b", shape: "rect" }], 0);
      const lp = parts.find(q => q.role === "label");
      ok(lab.begin >= pic.begin + 0.4 * pic.dur - 1e-9, `${win} s, order ${order[0]} first: label starts at ${(lab.begin - pic.begin).toFixed(2)} s, picture draws ${pic.dur.toFixed(2)} s (>= 40% of it)`);
      ok(lab.begin + lab.dur <= 10 + win + 1e-9 && cap.begin + cap.dur <= 10 + win + 1e-9 && lp && lp.begin === lab.begin && lp.end <= 10 + win + 1e-9, `${win} s: label and caption end inside the window (report part matches)`);
      ok(lab.begin >= cap.begin + cap.dur ? false : lab.begin >= pic.begin + pic.dur - 1e-9, `${win} s: the label comes after the whole picture`);
    }
  }
}
// step 9: a dense pen plan (1000 short strokes, like a line-art panel) used to be written with 2-decimal durations: every stroke under 5 ms became dur="0s", which SMIL
// ignores, so it never animated (84% of slide 23 panel 1) and its line art only appeared at the pass-end pop. Now every stroke has a real duration and the lines
// appear evenly over the lines slot.
{
  const many = Array.from({ length: 1000 }, (_, i) => ({ d: `M${i % 100} ${Math.floor(i / 100) * 10} L${(i % 100) + 8} ${Math.floor(i / 100) * 10}`, len: 8 }));
  const dl = { id: "panel-9", name: "Panel 9", group: "", visible: true, kind: "icon", bbox: { x: 0, y: 0, w: 110, h: 100 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true },
    rasterHref: "data:image/png;base64,AA==", inkRasterHref: "data:image/png;base64,AA==", rasterBox: [0, 0, 110, 100], reveal: { v: 2, w: 2, cw: 8, paths: many, cpaths: [{ d: "M0 5 L110 5", len: 110 }] } };
  const dp = { width: 110, height: 100, backgroundColor: "#fff", layers: [dl] };
  for (const win of [3, 1.2]) {
    const svg = m.linesColourGroup(dl, dp, 10, win, 10, 10 + win, 0.5);
    const lnk = svg.slice(svg.indexOf('<mask id="lnk-'), svg.indexOf("</mask>"));
    const ev = [...lnk.matchAll(/begin="([\d.]+)s" dur="([\d.]+)s"/g)].map(x => [+x[1], +x[2]]).slice(0, 1000);
    ok(ev.length === 1000 && ev.every(e => e[1] > 0), `1000 strokes, ${win} s window: every stroke has a real duration (none is dur="0s")`);
    const half = ev.filter(e => e[0] + e[1] <= 10 + (win * 0.5) / 2 + 1e-6).length;
    ok(half >= 450 && half <= 550, `${win} s window: about half of the strokes are drawn by half of the lines slot (${half} of 1000)`);
    ok(Math.max(...ev.map(e => e[0] + e[1])) <= 10 + win * 0.5 + 0.01, `${win} s window: the lines pass still ends inside its slot`);
  }
}
// step 9b (D1): a line-art panel with very little line art starts its colour wipe early (first visible mark within 0.15 s of the cue); a normal panel is unchanged;
// everything stays inside the panel's own window, so the panels still come one after another
{
  const lines = Array.from({ length: 40 }, (_, i) => ({ d: `M${i * 5} 0 L${i * 5 + 4} 90`, len: 90 }));
  const sweep = [{ d: "M0 10 L400 10 L0 30 L400 30", len: 1600 }, { d: "M0 50 L400 50 L0 70 L400 70", len: 1600 }];
  const mk = (ink) => { const l = { id: "panel-5", name: "Panel 5", group: "", visible: true, kind: "icon", bbox: { x: 0, y: 0, w: 400, h: 300 }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true },
    rasterHref: "data:image/png;base64,AA==", inkRasterHref: "data:image/png;base64,AA==", rasterBox: [0, 0, 400, 300], reveal: { v: 2, w: 6, cw: 14, paths: lines, cpaths: sweep } };
    if (ink !== undefined) l.reveal.ink = ink; return l; };
  const maskEv = (svg, id) => { const i = svg.indexOf(`<mask id="${id}-`); const body = svg.slice(i, svg.indexOf("</mask>", i)); return [...body.matchAll(/begin="([\d.]+)s" dur="([\d.]+)s"/g)].map(x => [+x[1], +x[2]]); };
  for (const win of [3, 1.2, 0.75]) {
    const begin = 20, pr = (l) => ({ width: 400, height: 300, backgroundColor: "#fff", layers: [l] });
    // sparse line art (2.7%): the colour wipe starts almost at the cue, ends inside the window, lines still start at the cue
    { const l = mk(0.027), svg = m.linesColourGroup(l, pr(l), begin, win, begin, begin + win, 0.5), col = maskEv(svg, "col"), lnk = maskEv(svg, "lnk");
      ok(Math.min(...col.map(e => e[0])) - begin <= 0.15 + 1e-9, `${win} s window, 2.7% line art: first colour stroke ${(Math.min(...col.map(e => e[0])) - begin).toFixed(2)} s after the cue (<= 0.15)`);
      ok(Math.max(...col.map(e => e[0] + e[1])) <= begin + win + 0.06, `${win} s window, 2.7% line art: the colour wipe ends inside the window (0.05 s pop allowed)`);
      ok(Math.min(...lnk.map(e => e[0])) >= begin - 1e-9 && Math.min(...col.map(e => e[0])) >= begin - 1e-9, `${win} s window: nothing starts before the cue`);
      ok(Math.max(...col.map(e => e[0] + e[1])) - Math.min(...col.map(e => e[0])) >= 0.6 * win - 1e-9 || win < 1, `${win} s window: the colour wipe is a long visible wipe (${(Math.max(...col.map(e => e[0] + e[1])) - Math.min(...col.map(e => e[0]))).toFixed(2)} s)`); }
    // no line art at all (ink 0): colour wipe at the cue, no NaN
    { const l = mk(0), svg = m.linesColourGroup(l, pr(l), begin, win, begin, begin + win, 0.5), col = maskEv(svg, "col");
      ok(!svg.includes("NaN") && Math.abs(Math.min(...col.map(e => e[0])) - begin) < 1e-6, `${win} s window, ink 0: colour wipe starts at the cue`); }
    // normal line art (12%) and a panel with no ink figure: EXACTLY the old times (colour wipe after the 50% lines share)
    for (const ink of [0.12, undefined]) {
      const l = mk(ink), svg = m.linesColourGroup(l, pr(l), begin, win, begin, begin + win, 0.5), col = maskEv(svg, "col");
      ok(Math.abs(Math.min(...col.map(e => e[0])) - (begin + win * 0.5)) < 1e-3 && Math.max(...col.map(e => e[0] + e[1])) <= begin + win + 0.06, `${win} s window, ink ${ink === undefined ? "missing" : "12%"}: colour wipe starts at 50% of the window as before`);
    }
  }
  // two panels one after the other (3 s each): the second panel's marks all come after the first panel's window, the first's all before
  const a = mk(0.027), b = mk(0.027), prj = { width: 400, height: 300, backgroundColor: "#fff", layers: [a] };
  const s1 = m.linesColourGroup(a, prj, 0, 3, 0, 3, 0.5), s2 = m.linesColourGroup(b, prj, 3, 3, 3, 6, 0.5);
  const all1 = [...maskEv(s1, "col"), ...maskEv(s1, "lnk")], all2 = [...maskEv(s2, "col"), ...maskEv(s2, "lnk")];
  ok(Math.max(...all1.map(e => e[0] + e[1])) <= 3 + 0.06 && Math.min(...all2.map(e => e[0])) >= 3 - 1e-9, "two sparse panels in a row: panel 1 is done by 3.0 s, panel 2 starts at 3.0 s (one after another)");
}
console.log(fails ? `\n${fails} FAILED` : "\nall passed"); process.exit(fails ? 1 : 0);
