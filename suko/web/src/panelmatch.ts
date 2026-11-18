/** v2 step 6: match the panels the backend found (/api/panels) to the panels in the JSON contract, by ORDER.
 *  The JSON lists the panels in reading order, so any comic layout works: the detected panels are put in the reading order that best fits
 *  the contract (shape, label and caption box of each panel), then panel k of the file is detected panel order[k].
 *  Without a contract the panels are read in row bands (left to right, row by row). Pure functions: no DOM, tested in tests/units.test.mjs. */

import type { Layer, Reveal } from "./types";

export type FoundShape = "circle" | "rectangle" | "polygon";
type Box4 = [number, number, number, number];
/** Original pixels cut from the upload (v2 step 7): PNG data URL, where it sits on the canvas, and its pixel size. */
export interface RasterCrop { href: string; box: Box4; size: [number, number]; fill?: string }
export interface FoundPanel {
  id: number; shape: FoundShape; bbox: Box4; inner: Box4; thickness: number; color: string;
  inset: boolean; parent: number | null;                       // parent = id of the panel this one sits in
  label: { bbox: Box4; raster?: RasterCrop } | null; caption: { bbox: Box4; corner: string; raster?: RasterCrop } | null;
  raster?: RasterCrop;                                          // absent from backends older than step 7, or with raster=0
  inkRaster?: RasterCrop;                                       // 0.16: line-art-only cut (absent for painted panels)
  reveal?: Reveal | null;                                       // 0.16: pen-stroke plan from the backend (absent/null = plain sweep)
}
export interface FoundArrow { id: number; bbox: Box4; from: number | null; to: number | null; headKnown: boolean; raster?: RasterCrop; inkRaster?: RasterCrop; reveal?: Reveal | null }
export interface FoundPanels {
  width: number; height: number; frame: Box4 | null; frameRaster?: RasterCrop | null; panels: FoundPanel[];
  ribbon: { bbox: Box4; color: string } | null; arrows: FoundArrow[]; unassigned: Box4[];
}
/** What the matcher needs from a contract panel (a PanelSpec fits). */
export interface ContractPanel { shape?: string; label?: string; caption?: string }

export type OrderMethod = "xycut" | "rows" | "topleft" | "cols";
export interface PanelMatch {
  /** order[k] = index in found.panels of the panel that is number k+1 in the file. Shorter than found.panels when the file lists fewer. */
  order: number[];
  /** The reading order used. Without a contract this is always "rows". */
  method: OrderMethod;
  found: number; expected: number;
  /** 1-based numbers (in the file) of panels whose shape does not fit the detected panel at that position. */
  shapeMismatch: number[];
  /** arrowOut[k] = the arrow that leaves file panel k+1 (or null). */
  arrowOut: (FoundArrow | null)[];
  /** Plain-language notes for the person (empty when everything lines up). */
  notes: string[];
}

interface Item { i: number; x: number; y: number; w: number; h: number }

const byTopLeft = (tol: number) => (a: Item, b: Item) => Math.abs(a.y - b.y) > tol ? a.y - b.y : a.x - b.x;

/** Row bands: panels whose vertical centres lie within half a panel height of each other share a row; rows top to bottom, panels left to right. */
function rowsOrder(items: Item[]): number[] {
  const s = [...items].sort((a, b) => (a.y + a.h / 2) - (b.y + b.h / 2));
  const bands: { cy: number; h: number; m: Item[] }[] = [];
  for (const b of s) {
    const cy = b.y + b.h / 2, last = bands[bands.length - 1];
    if (last && Math.abs(cy - last.cy) <= 0.5 * Math.min(b.h, last.h)) { last.m.push(b); last.cy = last.m.reduce((t, q) => t + q.y + q.h / 2, 0) / last.m.length; }
    else bands.push({ cy, h: b.h, m: [b] });
  }
  return bands.flatMap(r => r.m.sort((a, b) => a.x - b.x).map(q => q.i));
}
/** The same with columns: left to right, panels top to bottom inside a column. */
function colsOrder(items: Item[]): number[] {
  const s = [...items].sort((a, b) => (a.x + a.w / 2) - (b.x + b.w / 2));
  const bands: { cx: number; w: number; m: Item[] }[] = [];
  for (const b of s) {
    const cx = b.x + b.w / 2, last = bands[bands.length - 1];
    if (last && Math.abs(cx - last.cx) <= 0.5 * Math.min(b.w, last.w)) { last.m.push(b); last.cx = last.m.reduce((t, q) => t + q.x + q.w / 2, 0) / last.m.length; }
    else bands.push({ cx, w: b.w, m: [b] });
  }
  return bands.flatMap(c => c.m.sort((a, b) => a.y - b.y).map(q => q.i));
}
/** XY-cut: split the page by a gap that no panel crosses, top part first (a horizontal cut is tried before a vertical one), and repeat in each part.
 *  Reads "tall panel on the left, two stacked on the right" as left, right-top, right-bottom. Falls back to top-then-left when no gap exists. */
function xyCut(items: Item[], tol: number): number[] {
  if (items.length <= 1) return items.map(b => b.i);
  const cut = (horizontal: boolean): Item[][] | null => {
    const start = (b: Item) => horizontal ? b.y : b.x, end = (b: Item) => horizontal ? b.y + b.h : b.x + b.w;
    const s = [...items].sort((a, b) => start(a) - start(b));
    const groups: Item[][] = [[s[0]]]; let reach = end(s[0]);
    for (let k = 1; k < s.length; k++) {
      if (start(s[k]) >= reach - tol) groups.push([s[k]]); else groups[groups.length - 1].push(s[k]);
      reach = Math.max(reach, end(s[k]));
    }
    return groups.length > 1 ? groups : null;
  };
  const g = cut(true) ?? cut(false);
  return g ? g.flatMap(grp => xyCut(grp, tol)) : [...items].sort(byTopLeft(tol)).map(b => b.i);
}

/** Insets are not read on their own: each one follows the panel it sits in. `top` is the order of the other panels. */
function withInsets(found: FoundPanels, orderTop: (items: Item[]) => number[]): number[] {
  const P = found.panels, idx = new Map(P.map((p, i) => [p.id, i]));
  const item = (i: number): Item => ({ i, x: P[i].bbox[0], y: P[i].bbox[1], w: P[i].bbox[2], h: P[i].bbox[3] });
  const kids = new Map<number, number[]>(); const tops: number[] = [];
  P.forEach((p, i) => { const par = p.inset && p.parent != null ? idx.get(p.parent) : undefined; if (par === undefined) tops.push(i); else kids.set(par, [...(kids.get(par) ?? []), i]); });
  const tol = 0.012 * Math.max(found.width, found.height);
  const out: number[] = [];
  const emit = (i: number) => { out.push(i); (kids.get(i) ?? []).map(item).sort(byTopLeft(tol)).forEach(k => emit(k.i)); };
  orderTop(tops.map(item)).forEach(emit);
  return out;
}

export function readingOrder(found: FoundPanels, method: OrderMethod): number[] {
  const tol = 0.012 * Math.max(found.width, found.height);
  const fn = (items: Item[]) => method === "xycut" ? xyCut(items, tol) : method === "rows" ? rowsOrder(items) : method === "cols" ? colsOrder(items) : [...items].sort(byTopLeft(tol)).map(b => b.i);
  return withInsets(found, fn);
}

const shapeFits = (spec: string | undefined, f: FoundShape) => !spec || (spec === "circle" ? f === "circle" : (spec === "rectangle" || spec === "parchment") ? f !== "circle" : true);

/** The existing short note when the number of things found and the number in the file differ ("" when they agree). */
export function countNote(found: number, want: number | undefined, noun = "elements"): string {
  if (!want) return "";
  if (found > want) return `Found ${found} ${noun}, the file lists ${want}: matched in order, the last ${found - want} keep the picture's lettering and follow the last script time.`;
  if (found < want) return `Found ${found} ${noun}, the file lists ${want}: matched in order, the last ${want - found} are not used.`;
  return "";
}

/** Match the found panels to the contract. contract = the file's panels in reading order; undefined or empty = no contract (row bands). */
export function matchPanels(found: FoundPanels, contract?: ContractPanel[]): PanelMatch {
  const P = found.panels, want = contract?.length ?? 0;
  const empty: PanelMatch = { order: [], method: "rows", found: P.length, expected: want, shapeMismatch: [], arrowOut: [], notes: [] };
  if (!P.length) return { ...empty, notes: want ? [countNote(0, want, "panels")] : [] };
  let method: OrderMethod = "rows", order = readingOrder(found, "rows");
  if (want) {
    // choose the reading order that fits the contract best: shape counts most, then "has a label", then "has a caption box"
    let best = -1;
    for (const m of ["xycut", "rows", "topleft", "cols"] as OrderMethod[]) {
      const o = readingOrder(found, m);
      let score = 0;
      for (let k = 0; k < Math.min(o.length, want); k++) {
        const f = P[o[k]], c = contract![k];
        score += (shapeFits(c.shape, f.shape) ? 3 : 0) + (!!c.label?.trim() === !!f.label ? 1 : 0) + (!!c.caption?.trim() === !!f.caption ? 1 : 0);
      }
      if (score > best) { best = score; method = m; order = o; }
    }
  }
  const order2 = want ? order.slice(0, want) : order;
  const shapeMismatch = want ? order2.map((pi, k) => shapeFits(contract![k].shape, P[pi].shape) ? 0 : k + 1).filter(Boolean) : [];
  const arrowOut = order2.map((pi, k) => {
    const mine = found.arrows.filter(a => a.from === P[pi].id);
    const nextId = order2[k + 1] !== undefined ? P[order2[k + 1]].id : null;
    return mine.find(a => a.to === nextId) ?? mine[0] ?? null;
  });
  const notes: string[] = [];
  const cn = countNote(P.length, want || undefined, "panels"); if (cn) notes.push(cn);
  for (const k of shapeMismatch) notes.push(`Panel ${k}: the file says ${contract![k - 1].shape}, but a ${P[order2[k - 1]].shape} was found at that position.`);
  return { order: order2, method, found: P.length, expected: want, shapeMismatch, arrowOut, notes };
}

/** v2 step 13: what the Video tab draws over the upload so the person can check the detection by eye. */
export interface PanelOutline { k: number; bbox: Box4; shape: FoundShape; listed: boolean; mismatch: boolean; label: Box4 | null; caption: Box4 | null }
export interface PanelCheck {
  width: number; height: number; outlines: PanelOutline[];
  arrows: { from: number | null; bbox: Box4 }[];     // from = panel number (as drawn), null when unknown
  /** Layout line, e.g. "rows CL-3, 6 panels in the file" ("" when the file names no layout). */
  layoutText: string;
  /** "Found 6 panels, the file lists 6." etc. */
  countText: string;
  level: "ok" | "warn" | "none";
  notes: string[];
}
/** Number every found panel as the file does (match.order), then the panels the file does not list continue the numbering in reading order. */
export function panelCheck(found: FoundPanels, match: PanelMatch, spec?: { system?: string; layout?: string; panels?: unknown[] }): PanelCheck {
  const P = found.panels, num = new Map<number, number>();      // index in found.panels -> number shown
  match.order.forEach((pi, k) => num.set(pi, k + 1));
  let next = match.order.length + 1;
  for (const pi of readingOrder(found, match.method)) if (!num.has(pi)) num.set(pi, next++);
  const outlines = P.map((p, pi) => {
    const k = num.get(pi)!;
    return { k, bbox: p.bbox, shape: p.shape, listed: !match.expected || k <= match.expected, mismatch: match.shapeMismatch.includes(k), label: p.label?.bbox ?? null, caption: p.caption?.bbox ?? null };
  }).sort((a, b) => a.k - b.k);
  const byId = new Map(P.map((p, pi) => [p.id, num.get(pi)!]));
  const arrows = found.arrows.map(a => ({ from: a.from === null ? null : byId.get(a.from) ?? null, bbox: a.bbox }));
  const want = match.expected, n = P.length;
  const layoutText = spec?.layout ? `${spec.system ? spec.system + " " : ""}${spec.layout}${want ? `, ${want} panels in the file` : ""}` : "";
  const countText = want ? (n === want ? `Found ${n} panels, the file lists ${want}: they agree.` : `Found ${n} panel${n === 1 ? "" : "s"}, the file lists ${want}.`) : `Found ${n} panel${n === 1 ? "" : "s"}; the file lists none, so they are taken by rows.`;
  const level = !want ? "none" : n === want && !match.shapeMismatch.length ? "ok" : "warn";
  return { width: found.width, height: found.height, outlines, arrows, layoutText, countText, level, notes: match.notes };
}

/** v2 step 10: raster layers for every detected panel, each tagged with its number in the file (`panel.k`). Panels in the file's order first (`match.order`),
 *  panels the file does not list follow in reading order, so nothing found is lost. A panel brings its yellow label and caption box; the arrow that leaves it
 *  (`match.arrowOut`, else any arrow whose `from` is that panel) is one more layer. Returns null when any panel has no raster (older backend, raster=0):
 *  the caller then keeps the old element logic. */
export function panelLayers(found: FoundPanels, match: PanelMatch): Layer[] | null {
  const P = found.panels;
  if (!P.length || P.some(p => !p.raster)) return null;
  const order = [...match.order, ...readingOrder(found, "rows").filter(i => !match.order.includes(i))];
  const mk = (id: string, name: string, kind: string, c: RasterCrop, panel: Layer["panel"], reveal?: Reveal | null, ink?: RasterCrop): Layer => ({
    id, name, group: "", visible: true, kind, bbox: { x: c.box[0], y: c.box[1], w: c.box[2], h: c.box[3] },
    tx: 0, ty: 0, scale: 1, rot: 0, anim: { type: "none", dur: 2, delay: 0, loop: true }, rasterHref: c.href, rasterBox: c.box, panel, ...(c.fill ? { fill: c.fill } : {}), ...(reveal && reveal.v === 2 && reveal.paths.length ? { reveal } : {}), ...(ink ? { inkRasterHref: ink.href } : {}),
  });
  const out: Layer[] = [], used = new Set<number>();
  order.forEach((pi, i) => {
    const k = i + 1, p = P[pi];
    out.push(mk(`panel-${k}`, `Panel ${k}`, "icon", p.raster!, { k, role: "panel" }, p.reveal, p.inkRaster));
    if (p.label?.raster) out.push(mk(`panel-${k}-label`, `Label ${k}`, "text", p.label.raster, { k, role: "label" }));
    if (p.caption?.raster) out.push(mk(`panel-${k}-caption`, `Caption ${k}`, "text", p.caption.raster, { k, role: "caption" }));
    const a = (i < match.arrowOut.length ? match.arrowOut[i] : null) ?? found.arrows.find(x => x.from === p.id && !used.has(x.id)) ?? null;
    if (a && !used.has(a.id) && a.raster) { used.add(a.id); out.push(mk(`panel-${k}-arrow`, `Arrow ${k}`, "connector", a.raster, { k, role: "arrow" }, a.reveal, a.inkRaster)); }
  });
  return out;
}
