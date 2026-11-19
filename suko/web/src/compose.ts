import JSZip from "jszip";
import { hasHost, saveFile } from "./bridge";
import { DEFAULT_SKETCH } from "./types";
import type { Background, DrawStyle, Entrance, Dir, Layer, LinesMode, Project, Reveal, SketchSettings, Stroke } from "./types";

const center = (l: Layer) => ({ cx: l.bbox.x + l.bbox.w / 2, cy: l.bbox.y + l.bbox.h / 2 });
const n = (v: number) => +v.toFixed(3);

/** Move, scale and rotate around the element's own center. With no edits this is the identity transform. */
export function layerTransform(l: Layer): string {
  const { cx, cy } = center(l);
  return `translate(${n(cx + l.tx)} ${n(cy + l.ty)}) rotate(${n(l.rot)}) scale(${n(l.scale)}) translate(${n(-cx)} ${n(-cy)})`;
}

export function bgMarkup(p: Project, bg: Background): string {
  if (bg.kind === "color") return `<rect width="${p.width}" height="${p.height}" fill="${bg.color}"/>`;
  const i = bg.image;
  const s = Math.max(p.width / i.w, p.height / i.h) * i.zoom;
  const w = i.w * s, h = i.h * s;
  const x = (p.width - w) / 2 + i.offX * p.width, y = (p.height - h) / 2 + i.offY * p.height;
  return `<image href="${i.dataUrl}" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="none"/>`;
}

const EASE = `calcMode="spline"`;
/** Wraps a layer in SMIL animation. The animation runs around the element's center, so it works with any move/scale/rotate. */
export function animWrap(l: Layer, inner: string): string {
  const a = l.anim;
  if (a.type === "none") return inner;
  const { cx, cy } = center(l);
  const { w, h } = l.bbox;
  const t = (x: number) => n(x);
  const timing = `dur="${a.dur}s" begin="${a.delay}s" ${a.loop ? `repeatCount="indefinite"` : `repeatCount="1" fill="freeze"`}`;
  const sp = (count: number) => `keySplines="${Array(count).fill("0.45 0 0.55 1").join(";")}"`;
  let init = "";
  let anim = "";
  switch (a.type) {
    case "float":
      anim = `<animateTransform attributeName="transform" type="translate" values="0 0;0 ${t(-h * 0.05)};0 0" keyTimes="0;0.5;1" ${EASE} ${sp(2)} ${timing}/>`; break;
    case "bounce":
      anim = `<animateTransform attributeName="transform" type="translate" values="0 0;0 ${t(-h * 0.14)};0 0;0 ${t(-h * 0.05)};0 0" keyTimes="0;0.3;0.6;0.8;1" ${EASE} ${sp(4)} ${timing}/>`; break;
    case "sway":
      anim = `<animateTransform attributeName="transform" type="rotate" values="-6;6;-6" keyTimes="0;0.5;1" ${EASE} ${sp(2)} ${timing}/>`; break;
    case "spin":
      anim = `<animateTransform attributeName="transform" type="rotate" values="0;360" ${timing}/>`; break;
    case "pulse":
      anim = `<animateTransform attributeName="transform" type="scale" values="1;1.1;1" keyTimes="0;0.5;1" ${EASE} ${sp(2)} ${timing}/>`; break;
    case "fade":
      init = ` opacity="0"`;
      anim = `<animate attributeName="opacity" values="0;1" ${timing}/>`; break;
    case "pop":
      init = ` transform="scale(0)"`;
      anim = `<animateTransform attributeName="transform" type="scale" values="0;1.15;1" keyTimes="0;0.6;1" ${timing}/>`; break;
    case "slide": {
      const x = t(cx + w / 2 + 20);
      init = ` transform="translate(${-x} 0)"`;
      anim = `<animateTransform attributeName="transform" type="translate" values="${-x} 0;0 0" ${timing}/>`; break;
    }
  }
  return `<g transform="translate(${n(cx)} ${n(cy)})"><g${init}>${anim}<g transform="translate(${n(-cx)} ${n(-cy)})">${inner}</g></g></g>`;
}


/** Sketch effect: every path is drawn as an outline (like a pen), then its color fills in. */
export function sketchGroup(inner: string, begin: number, dur: number, sw: number): string {
  const f = (x: number) => +x.toFixed(2);
  return inner.replace(/<path\b([^>]*?)\/>/g, (_m, attrs: string) => {
    const fill = /\sfill="([^"]*)"/.exec(attrs)?.[1] ?? "#000000";
    const rest = attrs.replace(/\s(fill|stroke|opacity)="[^"]*"/g, "");
    return `<path${rest} pathLength="1" fill="${fill}" fill-opacity="0" stroke="#222" stroke-width="${sw}" stroke-linejoin="round" stroke-linecap="round" stroke-dasharray="1" stroke-dashoffset="1">`
      + `<animate attributeName="stroke-dashoffset" from="1" to="0" begin="${f(begin)}s" dur="${f(dur)}s" fill="freeze"/>`
      + `<animate attributeName="fill-opacity" from="0" to="1" begin="${f(begin + dur * 0.7)}s" dur="${f(Math.max(0.3, dur * 0.5))}s" fill="freeze"/>`
      + `<animate attributeName="stroke-opacity" from="1" to="0" begin="${f(begin + dur * 1.1)}s" dur="0.4s" fill="freeze"/>`
      + `</path>`;
  });
}


export const BACKEND_VERSION = "0.15.7";
/** The entrance and draw style an element really uses: its own choice, else the global default. */
export const effEntrance = (l: Layer, s: SketchSettings): Entrance => l.entrance ?? s.entrance;
export const effStyle = (l: Layer, s: SketchSettings): DrawStyle => l.style ?? s.style;
export const effDir = (l: Layer, s: SketchSettings): Dir => l.dir ?? s.dir;
/** True when every element that is drawn along the artwork's lines carries the v0.10 stroke plan
 *  (otherwise the old outline look would be used for it). Reveal, Fade, Pop, Move in and None need no stroke plan. */
export const sketchReady = (p: Project, s: SketchSettings = DEFAULT_SKETCH) =>
  p.layers.every(l => !l.visible || effEntrance(l, s) !== "draw" || effStyle(l, s) === "reveal" || l.reveal?.v === 2 || !!l.rasterHref);

const f2 = (x: number) => +x.toFixed(2);
const f4 = (x: number) => +x.toFixed(4);
/** Invisible strokes that grow one after another, like the hand following VideoScribe's transparent paths. Time is shared by stroke length. */
function strokeSet(paths: Stroke[], width: number, begin: number, dur: number): { svg: string; end: number } {
  const total = paths.reduce((s, q) => s + q.len, 0) || 1;
  // FIX PLAN step 6: every stroke gets a tiny minimum time, but ALL strokes together take exactly `dur` (before, 100+ strokes of 0.01 s each could run
  // past the end of a short window and into the next panel's cue)
  const floor = Math.min(0.01, dur / (2 * Math.max(1, paths.length)));
  let t = begin;
  const svg = paths.map(q => {
    const d = Math.max(0.0001, floor + (dur - floor * paths.length) * (q.len / total));
    // FIX PLAN step 9: begin and dur need 4 decimals. With f2 a stroke shorter than 5 ms was written as dur="0s", which SMIL ignores, so it NEVER animated
    // (84% of the strokes of a 1000-stroke panel) and its line art only appeared at the pass-end pop.
    const out = `<path d="${q.d}" fill="none" stroke="#fff" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round" pathLength="1" stroke-dasharray="1 2" stroke-dashoffset="1.02"><animate attributeName="stroke-dashoffset" from="1.02" to="0" begin="${f4(t)}s" dur="${f4(d)}s" fill="freeze"/></path>`;
    t += d;
    return out;
  }).join("");
  return { svg, end: begin + dur };
}
/** Anything the strokes missed (tiny pieces, edges) pops in when the pass ends, as it does in VideoScribe. */
const popRect = (b: { x: number; y: number; w: number; h: number }, at: number) => `<rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" fill="#fff" opacity="0"><animate attributeName="opacity" from="0" to="1" begin="${f2(at)}s" dur="0.05s" fill="freeze"/></rect>`;
/** Each mask only covers its own element's box (not the whole picture), so many elements stay light to render. */
const boxOf = (l: Layer, rv: Reveal) => { const pad = Math.ceil(Math.max(rv.w, rv.cw ?? 0)) + 6; return { x: l.bbox.x - pad, y: l.bbox.y - pad, w: l.bbox.w + 2 * pad, h: l.bbox.h + 2 * pad }; };
const maskOf = (id: string, b: { x: number; y: number; w: number; h: number }, inner: string) => `<mask id="${id}" maskUnits="userSpaceOnUse" x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}">${inner}</mask>`;

/** VideoScribe-style drawing (v0.10). Pass 1: the ink line art is uncovered along the outline strokes (colour not visible yet).
 *  Pass 2: the colour layer is uncovered by back-and-forth colour sweeps. Elements without dark line art use their own edge as pen path first. */
export function revealGroup(l: Layer, p: Project, begin: number, dur: number, pen = 0.3): string {
  const rv = l.reveal!;
  const split = !l.rasterHref && !!(l.inkGroup && l.colorGroup);   // v2 step 8: a raster element is one picture of the original pixels; the traced strokes are only the pen path
  const sweeps = rv.cpaths ?? [];
  const cw = rv.cw ?? 14;
  const box = boxOf(l, rv);
  if (split) {
    const colDur = sweeps.length ? dur * 0.25 : 0;      // an ink-only element (black text) uses its whole time for the pen
    const ink = strokeSet(rv.paths, rv.w, begin, dur - colDur);
    const col = strokeSet(sweeps, cw, ink.end, colDur);
    return maskOf(`rvc-${l.id}`, box, col.svg + popRect(box, col.end))
      + maskOf(`rvi-${l.id}`, box, ink.svg + popRect(box, ink.end))
      + `<g mask="url(#rvc-${l.id})">${l.colorGroup}</g><g mask="url(#rvi-${l.id})">${l.inkGroup}</g>`;
  }
  // No dark line art: edge strokes first, then colour sweeps, all uncovering the plain element.
  const pf = clampN(pen, 0.1, 0.9);   // share of the time for the pen outline; the rest is the colour fill
  const edge = strokeSet(rv.paths, rv.w, begin, dur * pf);
  const col = strokeSet(sweeps, cw, edge.end, dur * (1 - pf));
  return maskOf(`rvi-${l.id}`, box, edge.svg + col.svg + popRect(box, col.end)) + `<g mask="url(#rvi-${l.id})">${plainArt(l)}</g>`;
}

/** The finished artwork exactly as the Illustrate style ends: colour layer with the clean ink line art on top (no grey edge halos).
 *  Elements without dark line art use their plain layer. */
export const finalArt = (l: Layer) => (l.rasterHref ? rasterArt(l) : l.inkGroup && l.colorGroup ? l.colorGroup + l.inkGroup : l.group);
/** v2 step 8: the ORIGINAL pixels of an element (cut from the upload by the backend), drawn exactly where they were cut. The traced ink/colour groups are not used for it. */
export const rasterArt = (l: Layer) => { const b = l.rasterBox ?? [l.bbox.x, l.bbox.y, l.bbox.w, l.bbox.h]; return `<image href="${l.rasterHref}" x="${n(b[0])}" y="${n(b[1])}" width="${n(b[2])}" height="${n(b[3])}" preserveAspectRatio="none"/>`; };
/** The plain (not ink/colour split) artwork of an element: its raster when it has one, else its traced group. */
export const plainArt = (l: Layer) => (l.rasterHref ? rasterArt(l) : l.group);

/** 0.16 "lines first, then colour": every element's LINES are drawn first (the first `pf` share of the span T0..T1, in the same order and with the same
 *  proportions as before), then the colour of every element is filled in (the rest of the span). Elements with no line art (painted panels) are drawn in the colour pass only. */
/** FIX PLAN step 4: the colour sweeps of an element. The backend gives none when the element is nearly all dark line art or has no colour of its own
 *  (`cpaths` empty); the colour pass then had NO strokes and the whole element appeared in ONE 0.05 s step (`popRect`) at the end of its colour slot.
 *  Such an element now gets the same back-and-forth sweeps the REVEAL style uses (made from its box), so the colour is always a visible wipe over the whole slot. */
function colourSweepsOf(l: Layer, rv: Reveal): { w: number; paths: Stroke[] } {
  return rv.cpaths && rv.cpaths.length ? { w: rv.cw ?? 14, paths: rv.cpaths } : revealStrokes(l);
}
export function linesColourGroup(l: Layer, p: Project, begin: number, dur: number, T0: number, T1: number, pf: number): string {
  const f = clampN(pf, 0.1, 0.9), span = T1 - T0;
  const lb = T0 + (begin - T0) * f, ld = dur * f;
  const cb = T0 + f * span + (begin - T0) * (1 - f), cd = dur * (1 - f);
  if (!l.inkRasterHref) {
    // FIX PLAN step 3: a painted panel (no real line art) has no lines pass, so before it sat blank for the whole lines share (`ld`) and only began at `cb`.
    // Now its OUTLINE strokes (the panel's own edge) are drawn in the lines slot (starting exactly at `lb`, the panel's cue), then the colour sweeps in the colour slot.
    const rv = l.reveal!, box = boxOf(l, rv);
    const edge = strokeSet(rv.paths, rv.w, lb, ld);
    const cs = colourSweepsOf(l, rv);
    const col = strokeSet(cs.paths, cs.w, cb, cd);
    return maskOf(`rvi-${l.id}`, box, edge.svg + col.svg + popRect(box, col.end)) + `<g mask="url(#rvi-${l.id})">${plainArt(l)}</g>`;
  }
  const rv = l.reveal!, box = boxOf(l, rv), b = l.rasterBox ?? [l.bbox.x, l.bbox.y, l.bbox.w, l.bbox.h];
  const lines = strokeSet(rv.paths, rv.w, lb, ld);
  const cs = colourSweepsOf(l, rv);
  // FIX PLAN step 9b (D1): a panel with very little line art (2-3% of the picture) showed almost nothing during the lines share (50% of its window), so its first
  // visible mark came up to 1.2 s after the cue. Its colour wipe now starts earlier, the sparser the line art: line art at or above INK_FULL (10%) is unchanged (k = 1),
  // below it the start moves toward the cue with the square of the ratio (2.7% -> about 7% of the usual lines share). The wipe still ends where it ended before, so it is
  // always inside the panel's own window and the panels still come one after another.
  const dens = rv.ink == null ? 1 : clampN(rv.ink / INK_FULL, 0, 1), k = dens * dens;
  const cbS = T0 + f * span * k + (begin - T0) * (1 - f), cdS = dur * (1 - f) + f * span * (1 - k);
  const col = strokeSet(cs.paths, cs.w, cbS, cdS);
  const ink = `<image href="${l.inkRasterHref}" x="${n(b[0])}" y="${n(b[1])}" width="${n(b[2])}" height="${n(b[3])}" preserveAspectRatio="none"/>`;
  return maskOf(`lnk-${l.id}`, box, lines.svg + popRect(box, lines.end)) + maskOf(`col-${l.id}`, box, col.svg + popRect(box, col.end))
    + `<g mask="url(#col-${l.id})">${rasterArt(l)}</g><g mask="url(#lnk-${l.id})">${ink}</g>`;
}

const clampN = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
/** Line art share (of the panel) at or above which a panel is drawn "lines first, then colour" with the full lines share (FIX PLAN step 9b). Measured on Genesis 1: late panels median 2.7%, good ones 10.6%. */
const INK_FULL = 0.10;

/** REVEAL draw style (VideoScribe): the artwork is uncovered top to bottom by slanted back-and-forth strokes.
 *  No stroke plan from the backend is needed, the strokes come from the element's box. Every pass is a straight slanted line, all passes are
 *  parallel and overlap, so the uncovered edge is slanted (lower on the right), like the reveal frames in the reference. */
export function revealStrokes(l: Layer): { w: number; paths: Stroke[] } {
  const { x, y, w, h } = l.bbox;
  const pw = clampN(Math.sqrt(Math.max(1, w * h)) / 4, 8, 48);   // stroke width
  const step = pw * 0.8;                                          // passes overlap by 20%
  const slant = Math.min(w * 0.36, Math.max(h, pw));              // how much lower the right end of a pass is
  const slope = slant / Math.max(1, w);
  const pad = pw * 0.6, xl = x - pad, xr = x + w + pad, span = xr - xl;
  const c0 = y - slope * (w + pad) + pw / 2 - 1;                 // first pass still covers the top edge at the right
  const cLast = y + h - pw / 2 - slope * pad + 1;                 // last pass still covers the bottom edge at the left
  const rows = Math.max(1, Math.ceil((cLast - c0) / step) + 1);
  const rise = slope * span, len = Math.hypot(span, rise);
  const paths: Stroke[] = [];
  for (let k = 0; k < rows; k++) {
    const c = c0 + k * step;
    const a = `${f2(xl)} ${f2(c)}`, b = `${f2(xr)} ${f2(c + rise)}`;
    paths.push({ d: k % 2 === 0 ? `M${a} L${b}` : `M${b} L${a}`, len });
  }
  return { w: pw, paths };
}
export function revealStyleGroup(l: Layer, begin: number, dur: number): string {
  const rs = revealStrokes(l);
  const pad = Math.ceil(rs.w) + 6;
  const box = { x: l.bbox.x - pad, y: l.bbox.y - pad, w: l.bbox.w + 2 * pad, h: l.bbox.h + 2 * pad };
  const st = strokeSet(rs.paths, rs.w, begin, dur);
  return maskOf(`rvr-${l.id}`, box, st.svg + popRect(box, st.end)) + `<g mask="url(#rvr-${l.id})">${finalArt(l)}</g>`;
}

/** Non-drawn entrances (VideoScribe: None, Fade in, Pop in, Move in). The element is hidden until its turn, then appears over `dur` seconds. */
export function entranceGroup(l: Layer, kind: Entrance, begin: number, dur: number, dir: Dir = "left", W = 1920, H = 1080): string {
  const { cx, cy } = center(l);
  const b = f2(begin), d = f2(Math.max(0.05, dur));
  switch (kind) {
    case "fade":
      return `<g opacity="0"><animate attributeName="opacity" from="0" to="1" begin="${b}s" dur="${d}s" fill="freeze"/>${finalArt(l)}</g>`;
    case "pop":
      return `<g transform="translate(${n(cx)} ${n(cy)})"><g transform="scale(0)"><animateTransform attributeName="transform" type="scale" values="0;1.12;1" keyTimes="0;0.65;1" begin="${b}s" dur="${d}s" fill="freeze"/><g transform="translate(${n(-cx)} ${n(-cy)})">${finalArt(l)}</g></g></g>`;
    case "slide": {
      // Starts just outside the picture on the chosen side and glides in with a soft stop (VideoScribe "Move in").
      const m = 20, bb = l.bbox;
      const from = dir === "left" ? `${-n(bb.x + bb.w + m)} 0` : dir === "right" ? `${n(W - bb.x + m)} 0` : dir === "top" ? `0 ${-n(bb.y + bb.h + m)}` : `0 ${n(H - bb.y + m)}`;
      return `<g transform="translate(${from})"><animateTransform attributeName="transform" type="translate" values="${from};0 0" keyTimes="0;1" calcMode="spline" keySplines="0.2 0.7 0.3 1" begin="${b}s" dur="${d}s" fill="freeze"/>${finalArt(l)}</g>`;
    }
    default:   // "none": appears at once
      return `<g opacity="0"><set attributeName="opacity" to="1" begin="${b}s" fill="freeze"/>${finalArt(l)}</g>`;
  }
}

/** Reading order of whole objects: row by row (rows = things that share the same height band), left to right inside a row.
 *  So the title comes first, then the row of icons with their arrows, then the labels, then the chart. */
export function drawOrder(layers: Layer[], custom?: string[]): Layer[] {
  const auto = readingOrder(layers);
  if (!custom || !custom.length) return auto;
  // Your own order first (ids that no longer exist are skipped); elements you never placed (new ones) follow in reading order.
  const byId = new Map(auto.map(l => [l.id, l]));
  const placed = custom.filter((id, i) => byId.has(id) && custom.indexOf(id) === i).map(id => byId.get(id)!);
  const rest = auto.filter(l => !placed.includes(l));
  return [...placed, ...rest];
}
function readingOrder(layers: Layer[]): Layer[] {
  const rows: { top: number; bot: number; items: Layer[] }[] = [];
  for (const l of [...layers].sort((a, b) => a.bbox.y - b.bbox.y)) {
    const t = l.bbox.y, bt = l.bbox.y + l.bbox.h;
    const row = rows.find(r => Math.min(bt, r.bot) - Math.max(t, r.top) >= 0.5 * l.bbox.h);
    if (row) { row.top = Math.min(row.top, t); row.bot = Math.max(row.bot, bt); row.items.push(l); }
    else rows.push({ top: t, bot: bt, items: [l] });
  }
  rows.sort((a, b) => a.top - b.top);
  return rows.flatMap(r => r.items.sort((a, b) => a.bbox.x + a.bbox.w / 2 - (b.bbox.x + b.bbox.w / 2)));
}

export const hasAnimation = (p: Project) => p.layers.some(l => l.visible && l.anim.type !== "none");

interface Opts { onlyLayerId?: string; transparent?: boolean; animate?: boolean; sketch?: number | SketchSettings /* a number = fit to that many seconds */;
  /** Ready-made times per layer id (begin and draw time on the video clock). When given they replace the automatic plan; dur 0 = appears at once. */
  times?: Map<string, { begin: number; dur: number; pause: number }>;
  /** Layers that are not drawn (their words are written by `extra` instead). */
  skip?: Set<string>;
  /** Extra SVG markup drawn on top of all layers (the handwritten headings and captions of the Video tab). */
  extra?: string }

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
/** How much pen work an element needs (more lines = more time). */
const effort = (l: Layer, s: SketchSettings) => (effStyle(l, s) === "reveal" ? revealStrokes(l).paths.reduce((a, q) => a + q.len, 0)
  : l.reveal ? l.reveal.paths.reduce((a, q) => a + q.len, 0) + 0.5 * (l.reveal.cpaths ?? []).reduce((a, q) => a + q.len, 0) : l.bbox.w + l.bbox.h) + 150;

export interface SketchPlan { order: Layer[]; times: Map<string, { begin: number; dur: number; pause: number }>; total: number; reachable: boolean }
/** Timing plan, like VideoScribe's animate time + pause time per element:
 *  - every element gets a draw time; elements without their own value share the budget by drawing effort, each kept between min and max;
 *  - budget = draw time x number of elements, or (fit to narration) the target length minus all pauses;
 *  - an element's own draw time / pause (set in its panel) always wins. */
export function planSketch(shown: Layer[], s: SketchSettings, custom?: string[]): SketchPlan {
  const order = drawOrder(shown, custom);
  const pauseOf = (l: Layer, i: number) => (i === order.length - 1 ? 0 : clamp(l.timing?.pause ?? s.pause, 0, 60));
  const dur = new Map<string, number>();
  let used = 0;
  for (const l of order) {
    const ent = effEntrance(l, s);
    // Fade / Pop / Move in take their own short time (not shared by drawing effort); None is instant.
    const d = ent === "none" ? 0 : ent !== "draw" ? clamp(l.timing?.draw ?? s.enterTime, 0.1, 60) : l.timing?.draw !== undefined ? clamp(l.timing.draw, 0.1, 60) : undefined;
    if (d !== undefined) { dur.set(l.id, d); used += d; }
  }
  const pauses = order.reduce((a, l, i) => a + pauseOf(l, i), 0);
  let free = order.filter(l => !dur.has(l.id));
  let budget = s.fit ? s.target - pauses - used : s.draw * free.length;
  budget = Math.max(budget, 0.1 * free.length);
  for (let round = 0; round < 12 && free.length; round++) {   // clamp to min/max and hand the leftover to the others
    const sw = free.reduce((a, l) => a + effort(l, s), 0) || 1;
    const hit = free.filter(l => { const d = (budget * effort(l, s)) / sw; return d < s.min || d > s.max; });
    if (!hit.length) { for (const l of free) dur.set(l.id, (budget * effort(l, s)) / sw); free = []; break; }
    for (const l of hit) { const d = clamp((budget * effort(l, s)) / sw, s.min, s.max); dur.set(l.id, d); budget -= d; }
    free = free.filter(l => !hit.includes(l));
    budget = Math.max(budget, 0.1 * free.length);
  }
  const times = new Map<string, { begin: number; dur: number; pause: number }>();
  let t = 0;
  order.forEach((l, i) => { const d = dur.get(l.id) ?? s.min, p = pauseOf(l, i); times.set(l.id, { begin: t, dur: d, pause: p }); t += d + p; });
  return { order, times, total: t, reachable: !s.fit || Math.abs(t - s.target) < 0.1 };
}

/** Every layer lives in the original image's coordinate space, so positions, sizes and order are preserved. */
export function composeSvg(p: Project, bg: Background, o: Opts = {}): string {
  const back = o.transparent ? "" : bgMarkup(p, bg);
  const shown = p.layers.filter(l => (o.onlyLayerId ? l.id === o.onlyLayerId : l.visible) && !o.skip?.has(l.id));
  const sw = Math.max(1, p.width / 700);
  // Drawing order is reading order of whole objects (rows, then left to right). Each object is finished before the next one starts.
  const settings: SketchSettings | null = o.sketch === undefined ? null : typeof o.sketch === "number" ? { ...DEFAULT_SKETCH, fit: true, target: o.sketch } : o.sketch;
  const times = o.times ?? (settings ? planSketch(shown, settings, p.order).times : new Map<string, { begin: number; dur: number; pause: number }>());
  const tv = shown.map(l => times.get(l.id)).filter((t): t is { begin: number; dur: number; pause: number } => !!t && t.dur > 0);
  const T0 = tv.length ? Math.min(...tv.map(t => t.begin)) : 0, T1 = tv.length ? Math.max(...tv.map(t => t.begin + t.dur)) : 0;
  const lmode: LinesMode = !settings ? "off" : settings.lines === "auto" ? (p.layers.some(l => l.panel) ? "each" : "all") : settings.lines;   // auto: panels one by one, other pictures all lines first
  const bgLast = !!(lmode !== "off" && p.bgRasterHref && !o.transparent && !o.onlyLayerId && T1 > T0);   // the picture's own background comes in last
  const ts = bgLast ? 0.9 : 1;                                                                                          // lines + colour use the first 90% of the span, the background the rest
  const T1x = T0 + (T1 - T0) * ts;
  const layers = shown
    .map(l => {
      const tm = times.get(l.id);
      const ent = settings ? (o.times && tm && tm.dur === 0 ? "none" : effEntrance(l, settings)) : "draw";
      const inner = settings && tm
        ? ent !== "draw" ? entranceGroup(l, ent, tm.begin, tm.dur, effDir(l, settings), p.width, p.height)
        : effStyle(l, settings) === "reveal" ? revealStyleGroup(l, tm.begin, tm.dur)
        : lmode === "each" && l.rasterHref && l.reveal?.v === 2 ? linesColourGroup(l, p, T0 + (tm.begin - T0) * ts, tm.dur * ts, T0 + (tm.begin - T0) * ts, T0 + (tm.begin - T0) * ts + tm.dur * ts, settings!.pen)
        : lmode === "all" && l.rasterHref && l.reveal?.v === 2 && T1 > T0 ? linesColourGroup(l, p, T0 + (tm.begin - T0) * ts, tm.dur * ts, T0, T1x, settings!.pen)
        : l.reveal?.v === 2 ? revealGroup(l, p, tm.begin, tm.dur, settings.pen) : l.rasterHref ? revealStyleGroup(l, tm.begin, tm.dur) : sketchGroup(l.group, tm.begin, tm.dur, sw)
        : o.animate ? animWrap(l, plainArt(l)) : plainArt(l);
      return `<g id="${l.id}" transform="${layerTransform(l)}">${inner}</g>`;
    })
    .join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${p.width}" height="${p.height}" viewBox="0 0 ${p.width} ${p.height}"><defs><clipPath id="frame"><rect width="${p.width}" height="${p.height}"/></clipPath></defs><g clip-path="url(#frame)">${back}${bgLast ? `<image href="${p.bgRasterHref}" width="${p.width}" height="${p.height}" preserveAspectRatio="none" opacity="0"><animate attributeName="opacity" from="0" to="1" begin="${f2(T1x - 0.02)}s" dur="${f2(Math.max(0.3, T1 - T1x))}s" fill="freeze"/></image>` : ""}${layers}${o.extra ?? ""}</g></svg>`;
}

export function download(name: string, blob: Blob) {
  if (hasHost()) {   // inside the Bezalel app a blob link does nothing: save through Android (Download/Bezalel)
    saveFile(name, blob).catch(e => alert(`Could not save ${name}: ${(e as Error).message}`));
    return;
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob); a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

export async function svgToPngBlob(svg: string, w: number, h: number): Promise<Blob> {
  const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
  const img = new Image();
  await new Promise<void>((ok, err) => { img.onload = () => ok(); img.onerror = () => err(new Error("PNG export failed")); img.src = url; });
  const c = document.createElement("canvas"); c.width = w; c.height = h;
  c.getContext("2d")!.drawImage(img, 0, 0, w, h);
  URL.revokeObjectURL(url);
  return await new Promise<Blob>(ok => c.toBlob(b => ok(b!), "image/png"));
}

export async function zipLayers(p: Project, bg: Background, sk: SketchSettings = DEFAULT_SKETCH): Promise<Blob> {
  const zip = new JSZip();
  zip.file("composition.svg", composeSvg(p, bg));
  if (hasAnimation(p)) zip.file("composition-animated.svg", composeSvg(p, bg, { animate: true }));
  zip.file("composition-sketch.svg", composeSvg(p, bg, { sketch: sk }));
  const folder = zip.folder("layers")!;
  p.layers.forEach((l, i) => folder.file(`${String(i + 1).padStart(2, "0")}-${l.name.replace(/[^\w-]+/g, "_")}.svg`, composeSvg(p, bg, { onlyLayerId: l.id, transparent: true })));
  zip.file("project.json", JSON.stringify({ width: p.width, height: p.height, order: p.order, layers: p.layers.map(({ group, inkGroup, colorGroup, reveal, rasterHref, inkRasterHref, ...m }) => m), background: bg.kind }, null, 2));
  return zip.generateAsync({ type: "blob" });
}

/** Read an SVG file's drawing size. Falls back to viewBox, then 1024x1024, so SVGs without width/height still work. */
export function svgSize(text: string): { w: number; h: number } {
  const doc = new DOMParser().parseFromString(text, "image/svg+xml");
  const root = doc.documentElement;
  const num = (v: string | null) => { const m = v && /^\s*([\d.]+)\s*(px)?\s*$/.exec(v); return m ? parseFloat(m[1]) : 0; };
  let w = num(root.getAttribute("width")), h = num(root.getAttribute("height"));
  const vb = (root.getAttribute("viewBox") || "").trim().split(/[\s,]+/).map(Number);
  if ((!w || !h) && vb.length === 4 && vb[2] > 0 && vb[3] > 0) {
    if (!w && !h) { w = vb[2]; h = vb[3]; }
    else if (!w) w = (h * vb[2]) / vb[3];
    else h = (w * vb[3]) / vb[2];
  }
  if (!w || !h) { w = 1024; h = 1024; }
  const k = Math.min(1, 2000 / Math.max(w, h)) || 1;
  const s = Math.max(1, 1024 / Math.max(w, h));
  return { w: Math.round(w * Math.max(k, s)), h: Math.round(h * Math.max(k, s)) };
}

/** Turn an SVG file into a PNG file (the backend only reads PNG/JPG/WebP). Non-SVG files pass through unchanged. */
export async function rasterizeIfSvg(f: File): Promise<File> {
  if (!(f.type === "image/svg+xml" || /\.svg$/i.test(f.name))) return f;
  const text = await f.text();
  const { w, h } = svgSize(text);
  // Force explicit width/height so the browser can draw it.
  const fixed = text.replace(/<svg\b([^>]*)>/i, (_m, attrs: string) => {
    const a = attrs.replace(/\s(width|height)\s*=\s*("[^"]*"|'[^']*')/gi, "");
    return `<svg${a} width="${w}" height="${h}">`;
  });
  const url = URL.createObjectURL(new Blob([fixed], { type: "image/svg+xml" }));
  try {
    const img = new Image();
    await new Promise<void>((ok, err) => { img.onload = () => ok(); img.onerror = () => err(new Error("Could not read this SVG file.")); img.src = url; });
    const c = document.createElement("canvas"); c.width = w; c.height = h;
    const ctx = c.getContext("2d")!;
    ctx.fillStyle = "#ffffff"; ctx.fillRect(0, 0, w, h);
    ctx.drawImage(img, 0, 0, w, h);
    const blob = await new Promise<Blob>(ok => c.toBlob(b => ok(b!), "image/png"));
    return new File([blob], f.name.replace(/\.svg$/i, "") + ".png", { type: "image/png" });
  } finally { URL.revokeObjectURL(url); }
}
