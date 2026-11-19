import JSZip from "jszip";
import type opentype from "opentype.js";
import { drawOrder } from "./compose";
import { layoutInBox, textParts, type WriteStyle } from "./textanim";
import type { Layer, Project } from "./types";

/* ------------------------------------------------------------------ timing file (JSON) ------------------------------------------------------------------ */

/** What the timing file says about one image. All times are seconds on the video clock. */
/** One panel of a v2 canvas (docs/JSON_FORMAT.md, "Version 2"). The array order is the reading order. */
export interface PanelSpec {
  /** "circle", "rectangle" or "parchment" (a verse panel that carries its own text). Anything else is kept but noted. */
  shape?: string;
  /** The yellow label under the panel (1 to 4 words). Written in handwriting like a v1 caption. */
  label: string;
  /** Text of the small narration box inside the panel's corner (comic layouts), if the panel has one. */
  caption?: string;
  /** Exact words of a verse. Only for shape "parchment"; the picture keeps its own lettering there. */
  verse?: string;
  /** Speech balloon text, if any. */
  balloon?: string;
  /** The script words (the caption cue) this panel shows. Informational. */
  cue?: string;
  /** Second on the video clock when the panel begins to appear. */
  start: number;
}

export const PANEL_SHAPES = ["circle", "rectangle", "parchment"];
export const LAYOUT_SYSTEMS = ["rows", "comic"];
export const MAX_PANELS = 8;

export interface SlideSpec {
  n: number;                 // image number (1.jpg = 1)
  start: number; end: number;
  heading?: string;
  /** v2: the layout system ("rows" = circles and rectangles in rows, "comic" = comic layouts) and the layout's name (for example "CL-3"). Informational. */
  system?: string; layout?: string;
  /** v2: the script paragraph this canvas shows (never drawn; shown in the image list to help reorder images). */
  text?: string;
  /** v2: the panels in reading order. When present, `elements` and `captions` below are made from it, so the rest of the app works unchanged. */
  panels?: PanelSpec[];
  /** Start time of each element, in narration order (optional: without it the elements are spread evenly). */
  elements: number[];
  /** How many elements the prompt asked for (only known from a prompts .txt), used to warn when the detector finds another number. */
  expected?: number;
  /** Caption of each element, in narration order (informational; the app times by order). */
  captions?: string[];
  /** true when the element times are estimates, not read from the SRT. */
  estimated?: boolean;
}

const pick = (o: any, keys: string[]): any => { for (const k of keys) if (o && o[k] !== undefined && o[k] !== null) return o[k]; return undefined; };
const toNum = (v: any): number | undefined => (typeof v === "number" && isFinite(v) ? v : typeof v === "string" && v.trim() !== "" && isFinite(+v) ? +v : undefined);

/** Reads the slide-timing file. Accepts the fixed format (see docs/JSON_FORMAT.md), looser slides JSON spellings, or a prompts .txt with "=== IMAGE 3 (13.41-23.65 s, ...) ===" headers. */
export function parseSlidesFile(text: string, fallbackSeconds = 10): SlideSpec[] {
  return parseSlidesFileChecked(text, fallbackSeconds).specs;
}

/** Same as parseSlidesFile, plus a list of plain-language notes about problems found in the file (shown to the person). */
export function parseSlidesFileChecked(text: string, fallbackSeconds = 10): { specs: SlideSpec[]; notes: string[] } {
  const t = text.replace(/^\uFEFF/, "").trim();
  let raw: SlideSpec[];
  let estimated = false;
  if (t.startsWith("{") || t.startsWith("[")) {
    const j = JSON.parse(t);
    raw = fromJson(j);
    estimated = !Array.isArray(j) && j?.timing_source === "estimated";
  } else raw = fromPrompts(t);
  if (!raw.length) throw new Error("No slides found in this file. It needs a list of images with start and end times.");
  const notes = checkSlides(raw, estimated);
  if (estimated) raw.forEach(s => { s.estimated = true; });
  raw.sort((a, b) => a.n - b.n);
  // fill holes: an image without a start begins where the previous one ended; one without an end stops where the next one starts
  for (let i = 0; i < raw.length; i++) {
    const s = raw[i];
    if (!isFinite(s.start)) s.start = i ? raw[i - 1].end : 0;
    if (!isFinite(s.end)) s.end = i + 1 < raw.length && isFinite(raw[i + 1].start) ? raw[i + 1].start : s.start + fallbackSeconds;
    if (s.end <= s.start) s.end = s.start + fallbackSeconds;
    // element times given relative to the image start (all below its start) are moved onto the video clock
    if (s.elements.length && s.start > 0 && s.elements.every(e => e < s.start - 0.01)) s.elements = s.elements.map(e => e + s.start);
    s.elements = s.elements.map(e => Math.min(Math.max(e, s.start), s.end - 0.3)).sort((a, b) => a - b);
  }
  return { specs: raw, notes };
}

/** The rules of the fixed format, as notes in plain words. An empty list means the file is clean. */
export function checkSlides(raw: SlideSpec[], estimated = false): string[] {
  const notes: string[] = [];
  const list = [...raw].sort((a, b) => a.n - b.n);
  const seen = new Set<number>();
  list.forEach((s, i) => {
    if (seen.has(s.n)) notes.push(`Image number ${s.n} appears twice.`); seen.add(s.n);
    if (i && s.n !== list[i - 1].n + 1 && s.n !== list[i - 1].n) notes.push(`Numbers jump from ${list[i - 1].n} to ${s.n}.`);
    if (!isFinite(s.start) || !isFinite(s.end)) notes.push(`Image ${s.n}: start or end time is missing.`);
    else if (s.end <= s.start) notes.push(`Image ${s.n}: the end (${s.end}) is not after the start (${s.start}).`);
    const next = list[i + 1];
    if (next && isFinite(s.end) && isFinite(next.start) && Math.abs(next.start - s.end) > 0.05) notes.push(`Image ${s.n} ends at ${s.end} s but image ${next.n} starts at ${next.start} s.`);
    if (!s.elements.length) notes.push(`Image ${s.n}: no element times, the elements will be spread evenly.`);
    if (isFinite(s.start) && isFinite(s.end) && s.elements.some(e => e < s.start - 0.01 || e > s.end)) {
      const rel = s.start > 0 && s.elements.every(e => e < s.start - 0.01);
      if (!rel) notes.push(`Image ${s.n}: an element time is outside the image (${s.start} to ${s.end} s).`);
    }
    if (s.elements.some((e, k) => k && e < s.elements[k - 1])) notes.push(`Image ${s.n}: element times are not in order.`);
    if (s.panels) notes.push(...checkPanels(s));
  });
  if (estimated) notes.push("The element times in this file are estimates, not read from the SRT.");
  return notes;
}

/** The version 2 rules for one slide (panels), as notes in plain words. */
export function checkPanels(s: SlideSpec): string[] {
  const notes: string[] = [];
  const ps = s.panels ?? [];
  const tag = `Image ${s.n}`;
  if (!ps.length) { notes.push(`${tag}: "panels" is empty. A canvas needs at least 1 panel.`); return notes; }
  if (ps.length > MAX_PANELS) notes.push(`${tag}: ${ps.length} panels. A canvas holds at most ${MAX_PANELS}; split the paragraph into two canvases.`);
  if (s.system && !LAYOUT_SYSTEMS.includes(s.system)) notes.push(`${tag}: layout system "${s.system}" is not "rows" or "comic".`);
  if (!s.text) notes.push(`${tag}: no "text" (the script paragraph). It is not needed to play, but it is how you check the picture order.`);
  ps.forEach((pn, k) => {
    const t = `${tag} panel ${k + 1}`;
    if (!isFinite(pn.start)) notes.push(`${t}: start time is missing.`);
    if (pn.shape && !PANEL_SHAPES.includes(pn.shape)) notes.push(`${t}: shape "${pn.shape}" is not circle, rectangle or parchment.`);
    if (pn.shape === "parchment" && !pn.verse) notes.push(`${t}: a parchment panel needs its "verse" text.`);
    if (pn.verse && pn.shape !== "parchment") notes.push(`${t}: "verse" is only for parchment panels.`);
    const words = pn.label.split(/\s+/).filter(Boolean).length;
    if (!words) notes.push(`${t}: no label (use "" only if the picture really has none).`);
    else if (words > 4) notes.push(`${t}: the label has ${words} words; labels are 1 to 4 words.`);
  });
  return notes;
}

/** "1, 2, 3, 7" -> "1-3, 7" (for short notes about batches). */
export function ranges(ns: number[]): string {
  const a = [...new Set(ns)].sort((x, y) => x - y), out: string[] = [];
  for (let i = 0; i < a.length;) { let j = i; while (j + 1 < a.length && a[j + 1] === a[j] + 1) j++; out.push(j > i ? `${a[i]}-${a[j]}` : `${a[i]}`); i = j + 1; }
  return out.join(", ");
}

/** Batches: pictures and the timing file are matched by number, never by position, so images 21-40 work with a file that holds slides 21-40.
 *  This lists what does not line up. An empty list means every picture has its slide and every slide has its picture. */
export function matchNotes(picNs: number[], specs: SlideSpec[]): string[] {
  if (!picNs.length || !specs.length) return [];
  const sp = new Set(specs.map(s => s.n)), pc = new Set(picNs);
  const noSlide = [...pc].filter(n => !sp.has(n)), noPic = [...sp].filter(n => !pc.has(n));
  if (noSlide.length === pc.size) return [`None of the picture numbers match the timing file (pictures ${ranges([...pc])}, file ${ranges([...sp])}). Check that the pictures and the file are the same batch.`];
  const notes: string[] = [];
  if (noSlide.length) notes.push(`No timing for picture ${ranges(noSlide)}.`);
  if (noPic.length) notes.push(`The timing file has image ${ranges(noPic)} but no picture with that number was added.`);
  return notes;
}

function fromJson(j: any): SlideSpec[] {
  const list: any[] = Array.isArray(j) ? j : pick(j, ["slides", "images", "items", "cues"]) ?? [];
  return list.map((it, i): SlideSpec => {
    const n = toNum(pick(it, ["n", "image", "index", "id", "number", "slide"])) ?? i + 1;
    const start = toNum(pick(it, ["start", "t0", "begin", "start_time", "from"])) ?? NaN;
    let end = toNum(pick(it, ["end", "t1", "stop", "end_time", "to"])) ?? NaN;
    const dur = toNum(pick(it, ["duration", "dur", "length"]));
    if (!isFinite(end) && dur !== undefined && isFinite(start)) end = start + dur;
    const heading = pick(it, ["heading", "title", "section"]);
    const str = (v: any) => (typeof v === "string" && v.trim() ? v.trim() : undefined);
    const extra = { system: str(it?.system), layout: str(it?.layout), text: str(it?.text) };
    // version 2: panels in reading order. Elements (start times) and captions (labels) are made from them.
    if (it && Array.isArray(it.panels)) {
      const panels: PanelSpec[] = it.panels.map((e: any): PanelSpec => ({
        shape: str(e?.shape), label: typeof e?.label === "string" ? e.label.trim() : "", caption: str(e?.caption), verse: str(e?.verse),
        balloon: str(e?.balloon), cue: str(e?.cue), start: toNum(pick(e, ["start", "at", "t", "time", "begin", "t0"])) ?? NaN,
      }));
      const elements = panels.map(pn => pn.start).filter(x => isFinite(x));
      const captions = panels.map(pn => pn.label);
      return { n, start, end, heading: typeof heading === "string" ? heading : undefined, ...extra, panels, elements, expected: panels.length || undefined, captions: captions.some(Boolean) ? captions : undefined };
    }
    const els: any[] = pick(it, ["elements", "reveals", "element_times", "cues"]) ?? [];
    const elements = els.map(e => (typeof e === "object" && e ? toNum(pick(e, ["start", "at", "t", "time", "begin", "t0"])) : toNum(e))).filter((x): x is number => x !== undefined);
    // one entry per element, in order; an element without a caption keeps "" so the positions still line up (it is simply not rewritten)
    const captions = els.map(e => (typeof e === "object" && e && typeof (e.caption ?? e.label) === "string" ? String(e.caption ?? e.label).trim() : ""));
    return { n, start, end, heading: typeof heading === "string" ? heading : undefined, ...extra, elements, expected: els.length || undefined, captions: captions.some(Boolean) ? captions : undefined };
  });
}

function fromPrompts(t: string): SlideSpec[] {
  const out: SlideSpec[] = [];
  for (const b of t.split(/(?==== IMAGE )/)) {
    const m = /^=== IMAGE (\d+)\s*\(\s*([\d.]+)\s*-\s*([\d.]+)\s*s/.exec(b);
    if (!m) continue;
    const h = /exactly this text\):\s*"(.*?)"/.exec(b)?.[1];
    const expected = (b.match(/^\s*\d+\.\s*ICON:/gm) ?? []).length || undefined;
    out.push({ n: +m[1], start: +m[2], end: +m[3], heading: h, elements: [], expected });
  }
  return out;
}

/* ------------------------------------------------------------------ images (ZIP or loose files) ------------------------------------------------------------------ */

export interface Pic { name: string; n: number | null; file: File }
const IMG = /\.(png|jpe?g|webp|svg)$/i;
const MIME: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", svg: "image/svg+xml" };
const numOf = (name: string): number | null => { const m = /(\d+)/.exec(name.replace(/\.[^.]+$/, "").split("/").pop() ?? ""); return m ? +m[1] : null; };

/** Unpacks ZIPs (any folders inside) and loose picture files into one list, ordered by the number in the file name. */
export async function readPics(files: File[]): Promise<Pic[]> {
  const pics: Pic[] = [];
  for (const f of files) {
    if (/\.zip$/i.test(f.name) || f.type === "application/zip" || f.type === "application/x-zip-compressed") {
      const zip = await JSZip.loadAsync(f);
      for (const [path, entry] of Object.entries(zip.files)) {
        if (entry.dir || /(^|\/)(__MACOSX|\.)/.test(path) || !IMG.test(path)) continue;
        const ext = path.split(".").pop()!.toLowerCase();
        const base = path.split("/").pop()!;
        const blob = await entry.async("blob");
        pics.push({ name: base, n: numOf(base), file: new File([blob], base, { type: MIME[ext] ?? "image/png" }) });
      }
    } else if (IMG.test(f.name) || f.type.startsWith("image/")) {
      pics.push({ name: f.name, n: numOf(f.name), file: f });
    }
  }
  pics.sort((a, b) => (a.n ?? 1e9) - (b.n ?? 1e9) || a.name.localeCompare(b.name, undefined, { numeric: true }));
  return pics;
}

/* ------------------------------------------------------------------ elements: icon + its caption become one unit ------------------------------------------------------------------ */

/** `panel` (v2 step 10): this item is panel k with its label and caption box (layerIds are in drawing order). `from`: this arrow leaves panel k. */
export interface Unit { id: string; kind: "heading" | "arrow" | "item"; layerIds: string[]; bbox: { x: number; y: number; w: number; h: number }; panel?: number; from?: number }
type Box = { x: number; y: number; w: number; h: number };
const unionBox = (bs: Box[]): Box => { const x = Math.min(...bs.map(b => b.x)), y = Math.min(...bs.map(b => b.y)), r = Math.max(...bs.map(b => b.x + b.w)), d = Math.max(...bs.map(b => b.y + b.h)); return { x, y, w: r - x, h: d - y }; };

/** v2 step 10: when EVERY visible layer comes from a detected panel (`layer.panel`), there is one unit per panel (the panel, then its label, then its caption box)
 *  in the file's order, and one arrow unit per arrow that leaves a panel. Returns null when any layer is untagged (then the old logic below decides). */
export function panelUnits(all: Layer[]): Unit[] | null {
  if (!all.length || !all.every(l => l.panel)) return null;
  const ks = [...new Set(all.filter(l => l.panel!.role === "panel").map(l => l.panel!.k))].sort((a, b) => a - b);
  if (!ks.length || all.some(l => !ks.includes(l.panel!.k))) return null;     // a label or arrow whose panel is missing: not a clean panel set
  const rank = { panel: 0, label: 1, caption: 2, arrow: 3 } as const;
  const units: Unit[] = [];
  for (const k of ks) {
    const ls = all.filter(l => l.panel!.k === k && l.panel!.role !== "arrow").sort((a, b) => rank[a.panel!.role] - rank[b.panel!.role]);
    units.push({ id: "u-panel-" + k, kind: "item", layerIds: ls.map(l => l.id), bbox: unionBox(ls.map(l => l.bbox)), panel: k });
  }
  for (const k of ks) {
    const ls = all.filter(l => l.panel!.k === k && l.panel!.role === "arrow");
    if (ls.length) units.push({ id: "u-arrow-" + k, kind: "arrow", layerIds: ls.map(l => l.id), bbox: unionBox(ls.map(l => l.bbox)), from: k });
  }
  return units;
}

/** Finds the heading (top row of text), the arrows, and joins every caption to the icon right above it, so an icon and its words appear together. */
export function buildUnits(p: Project): Unit[] {
  const W = p.width, H = p.height;
  const all = p.layers.filter(l => l.visible);
  const pu = panelUnits(all);                                                  // v2 step 10: layers made from detected panels are one unit per panel
  if (pu) return pu;
  const byId = new Map(all.map(l => [l.id, l]));
  const kindOf = (l: Layer) => (l as any).kind as string | undefined;

  // arrows: thin connectors, and small flat pieces that sit between two neighbours on the same row (the detector often calls those "text")
  const thinConnector = (l: Layer) => kindOf(l) === "connector" && Math.min(l.bbox.w, l.bbox.h) <= 0.12 * H && Math.max(l.bbox.w, l.bbox.h) / Math.max(1, Math.min(l.bbox.w, l.bbox.h)) >= 1.5;
  const flat = (l: Layer) => kindOf(l) !== "connector" && l.bbox.h <= 0.065 * H && l.bbox.w <= 0.2 * W && l.bbox.w / Math.max(1, l.bbox.h) >= 1.8;
  const sideNeighbour = (c: Layer, dir: -1 | 1) => all.some(a => {
    if (a.id === c.id) return false;
    const cy = c.bbox.y + c.bbox.h / 2;
    if (cy < a.bbox.y - 0.1 * H || cy > a.bbox.y + a.bbox.h + 0.1 * H) return false;
    return dir < 0 ? a.bbox.x + a.bbox.w <= c.bbox.x + 0.25 * c.bbox.w && a.bbox.x + a.bbox.w >= c.bbox.x - 0.12 * W
                   : a.bbox.x >= c.bbox.x + 0.75 * c.bbox.w && a.bbox.x <= c.bbox.x + c.bbox.w + 0.12 * W;
  });
  // a flat piece right under another piece, inside its width, is a caption, never an arrow
  const underOther = (c: Layer) => all.some(a => {
    if (a.id === c.id || a.bbox.y + a.bbox.h > c.bbox.y + 0.3 * c.bbox.h) return false;
    const gap = c.bbox.y - (a.bbox.y + a.bbox.h), cx = c.bbox.x + c.bbox.w / 2;
    return gap <= Math.max(0.075 * H, 0.45 * Math.max(a.bbox.w, a.bbox.h)) && cx >= a.bbox.x && cx <= a.bbox.x + a.bbox.w;
  });
  const isArrow = (l: Layer) => thinConnector(l) || (flat(l) && !underOther(l) && sideNeighbour(l, -1) && sideNeighbour(l, 1));
  const M = Math.max(W, H);
  const boxDist = (a: Box, b: Box) => Math.hypot(Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.w, b.x + b.w)), Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.h, b.y + b.h)));
  // v0.14.11 arrows and dashes are whole units: every dash of a dashed line (also short or upright ones) that touches the line joins it,
  // and the pieces of one line (dashes, shaft, head) become ONE arrow unit that is drawn as a whole between its two elements
  const arrowSet = new Set(all.filter(isArrow).map(l => l.id));
  const smallConn = (l: Layer) => kindOf(l) === "connector" && Math.max(l.bbox.w, l.bbox.h) <= 0.08 * M;
  for (let guard = 0; guard < 100; guard++) {
    const add = all.filter(l => !arrowSet.has(l.id) && smallConn(l) && all.some(o => arrowSet.has(o.id) && boxDist(l.bbox, o.bbox) <= 0.05 * M));
    if (!add.length) break;
    add.forEach(l => arrowSet.add(l.id));
  }
  const arrows = all.filter(l => arrowSet.has(l.id));
  const rest = all.filter(l => !arrowSet.has(l.id));
  const lineGroups: Layer[][] = [];                                             // pieces that touch each other (gap up to 4% of the picture) are one line
  { const par = arrows.map((_, i) => i); const f = (i: number): number => (par[i] === i ? i : (par[i] = f(par[i])));
    for (let i = 0; i < arrows.length; i++) for (let j = i + 1; j < arrows.length; j++) if (boxDist(arrows[i].bbox, arrows[j].bbox) <= 0.04 * M) par[f(j)] = f(i);
    const by = new Map<number, Layer[]>(); arrows.forEach((l, i) => { const r = f(i); by.set(r, [...(by.get(r) ?? []), l]); });
    by.forEach(v => lineGroups.push(v)); }

  // heading: the top cluster of layers, if it sits high and is wide enough to be a title
  const sorted = [...rest].sort((a, b) => a.bbox.y - b.bbox.y);
  let headIds: string[] = [];
  if (sorted.length) {
    const cl = [sorted[0]]; let bottom = sorted[0].bbox.y + sorted[0].bbox.h;
    for (const l of sorted.slice(1)) { if (l.bbox.y - bottom <= 0.035 * H && l.bbox.y < 0.3 * H) { cl.push(l); bottom = Math.max(bottom, l.bbox.y + l.bbox.h); } else break; }
    const b = unionBox(cl.map(l => l.bbox));
    if (b.y + b.h <= 0.3 * H && b.h <= 0.22 * H && b.w >= 0.22 * W && b.y < 0.2 * H) headIds = cl.map(l => l.id);
  }
  // heading slide: a picture with nothing but lettering (no icon, no arrow) is a title slide, wherever the text sits (e.g. centred)
  if (!headIds.length && !arrows.length && rest.length && rest.every(l => kindOf(l) === "text")) {
    const b = unionBox(rest.map(l => l.bbox));
    if (b.w >= 0.15 * W && b.h <= 0.5 * H) headIds = rest.map(l => l.id);
  }
  const body = rest.filter(l => !headIds.includes(l.id));

  // captions: a short piece directly under another piece joins it
  const parent = new Map(body.map(l => [l.id, l.id]));
  const find = (id: string): string => { while (parent.get(id) !== id) { parent.set(id, parent.get(parent.get(id)!)!); id = parent.get(id)!; } return id; };
  // pieces that overlap each other belong to one drawing (a siren on a van, wheels under a body)
  for (let i = 0; i < body.length; i++) for (let j = i + 1; j < body.length; j++) {
    const a = body[i].bbox, c = body[j].bbox;
    const ix = Math.min(a.x + a.w, c.x + c.w) - Math.max(a.x, c.x), iy = Math.min(a.y + a.h, c.y + c.h) - Math.max(a.y, c.y);
    if (ix > 0 && iy > 0 && ix * iy >= 0.2 * Math.min(a.w * a.h, c.w * c.h)) parent.set(find(body[j].id), find(body[i].id));
  }
  for (const b of body) {
    if (b.bbox.h > 0.14 * H && kindOf(b) !== "text") continue;
    let best: Layer | null = null, bestGap = Infinity;
    for (const a of body) {
      if (a.id === b.id || a.bbox.y >= b.bbox.y) continue;
      const gap = b.bbox.y - (a.bbox.y + a.bbox.h);
      const reach = kindOf(b) === "text" && kindOf(a) !== "text" ? Math.max(0.075 * H, 0.45 * Math.max(a.bbox.w, a.bbox.h)) : 0.075 * H;   // a caption may sit a bit further below a big icon
      if (gap > reach || gap < -0.25 * b.bbox.h) continue;
      const cb = b.bbox.x + b.bbox.w / 2;
      const overlap = Math.min(a.bbox.x + a.bbox.w, b.bbox.x + b.bbox.w) - Math.max(a.bbox.x, b.bbox.x);
      if (!(cb >= a.bbox.x - 0.15 * a.bbox.w && cb <= a.bbox.x + 1.15 * a.bbox.w) && overlap < 0.3 * Math.min(a.bbox.w, b.bbox.w)) continue;
      if (gap < bestGap) { bestGap = gap; best = a; }
    }
    if (best) parent.set(find(b.id), find(best.id));
  }
  const groups = new Map<string, Layer[]>();
  for (const l of body) { const r = find(l.id); groups.set(r, [...(groups.get(r) ?? []), l]); }

  // v0.14.7 no leftovers: a small loose group (a dot, a stray name, a few letters) that found no parent above it still belongs to the element
  // it sits closest to. It is never an element of its own, so an element is only finished when all of its pieces are drawn.
  const isFragment = (ls: Layer[]) => {
    const b = unionBox(ls.map(l => l.bbox));
    return ls.every(l => kindOf(l) === "text" || Math.max(l.bbox.w, l.bbox.h) <= 0.05 * M) && (b.h <= 0.07 * H || Math.max(b.w, b.h) <= 0.06 * M);
  };
  // an arrowhead (a small loose piece at the tip of a line) belongs to that line, when the line is closer to it than any element is
  if (lineGroups.length) for (const k of [...groups.keys()]) {
    const g = groups.get(k)!, gb = unionBox(g.map(l => l.bbox));
    if (!g.every(l => kindOf(l) !== "text" && Math.max(l.bbox.w, l.bbox.h) <= 0.06 * M)) continue;
    let bl = -1, bd = Infinity;
    lineGroups.forEach((lg, i) => { const d = boxDist(gb, unionBox(lg.map(l => l.bbox))); if (d < bd) { bd = d; bl = i; } });
    const other = Math.min(Infinity, ...[...groups.keys()].filter(o => o !== k).map(o => boxDist(gb, unionBox(groups.get(o)!.map(l => l.bbox)))));
    if (bl >= 0 && bd <= 0.03 * M && bd <= other) { lineGroups[bl].push(...g); groups.delete(k); }
  }
  for (let guard = 0; guard < 200; guard++) {
    const keys = [...groups.keys()];
    if (keys.length < 2) break;
    const frag = keys.filter(k => isFragment(groups.get(k)!));
    let best: { from: string; to: string; d: number } | null = null;
    for (const f of frag) {
      const fb = unionBox(groups.get(f)!.map(l => l.bbox));
      for (const k of keys) {
        if (k === f) continue;
        const tl = groups.get(k)!;
        if (frag.includes(k) && isFragment(tl) && frag.length === keys.length) continue;   // only fragments left: nothing to join
        const d = boxDist(fb, unionBox(tl.map(l => l.bbox))) + (isFragment(tl) ? 0.5 * M : 0);   // prefer a real element over another fragment
        if (!best || d < best.d) best = { from: f, to: k, d };
      }
    }
    if (!best) break;
    groups.set(best.to, [...groups.get(best.to)!, ...groups.get(best.from)!]);
    groups.delete(best.from);
  }

  const units: Unit[] = [];
  if (headIds.length) units.push({ id: "u-heading", kind: "heading", layerIds: headIds, bbox: unionBox(headIds.map(i => byId.get(i)!.bbox)) });
  for (const [r, ls] of groups) units.push({ id: "u-" + r, kind: "item", layerIds: ls.map(l => l.id), bbox: unionBox(ls.map(l => l.bbox)) });
  for (const lg of lineGroups) units.push({ id: "u-" + lg[0].id, kind: "arrow", layerIds: lg.map(l => l.id), bbox: unionBox(lg.map(l => l.bbox)) });

  // reading order of the body (heading always first)
  const fake = units.filter(u => u.kind !== "heading").map(u => ({ id: u.id, bbox: u.bbox } as unknown as Layer));
  const order = drawOrder(fake).map(l => l.id);
  const head = units.filter(u => u.kind === "heading");
  return [...head, ...order.map(id => units.find(u => u.id === id)!)];
}

/* ------------------------------------------------------------------ the timing plan of one image ------------------------------------------------------------------ */

/** aim: for an arrow, the centre of the element it points to (the pieces of the line are drawn from the far end towards it). */
export interface TimedUnit { unit: Unit; begin: number; dur: number; aim?: { x: number; y: number } }
export interface SlidePlan { items: TimedUnit[]; main: number; fromJson: boolean }
export interface PlanOpts { writeHeading: boolean; maxIdle: number }

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Times (seconds from the start of the image) for every unit, in strict order (v0.14.8):
 *  heading, then for each element its arrows (if any) and the element itself. A unit begins only after the one before it has ENDED completely,
 *  and every unit ends before the slide does. Elements start at their script time (element i takes time i); a drawing is shortened (never overlapped) when the slot is short.
 *  Any number of elements is fine (v0.14.9). */
export function planSlide(units: Unit[], W: number, H: number, D: number, elementTimes: number[] | null, opts: PlanOpts): SlidePlan {
  const head = units.find(u => u.kind === "heading");
  const main = units.filter(u => u.kind === "item");
  const order = units.filter(u => u.kind !== "heading");
  let headEnd = 0;
  const out: TimedUnit[] = [];
  if (head) {
    const t0 = elementTimes?.length ? elementTimes[0] : 1.6;
    const dur = opts.writeHeading ? clamp(t0 - 0.05, 0.4, 1.6) : 0;
    out.push({ unit: head, begin: 0, dur });
    headEnd = dur;
  }
  // wanted start time of every main element
  const k = elementTimes?.length ?? 0, m = main.length;
  const start: number[] = [];
  for (let i = 0; i < m; i++) {
    let t: number;
    if (i < k) t = elementTimes![i];                                   // v0.14.9: element i takes script time i, whatever the two counts are
    else if (k > 0) { const last = elementTimes![k - 1]; t = last + (i - k + 1) * Math.max(0.3, (D - 0.4 - last) / (m - k + 1)); }   // more elements than script times: the rest follow the last script time
    else { const s0 = Math.max(headEnd + 0.15, 0.2), slot = Math.max(0.5, (D - 0.4 - s0) / Math.max(1, m)); t = s0 + i * slot; }
    start.push(Math.max(t, i ? start[i - 1] + 0.05 : headEnd + 0.05));   // v0.14.10: script times closer than 0.3 s are kept as they are (only never backwards)
  }
  // v0.14.9 no element limit: when the starts run past the end of the slide (many elements, or times late in the file), the whole row of starts is
  // squeezed evenly into the slide, so every element still gets its own slot, in order
  if (m > 1) {
    const b0 = headEnd + 0.05, lastMax = D - 0.4;
    if (start[m - 1] > lastMax && start[m - 1] > b0) { const f = Math.max(0, lastMax - b0) / (start[m - 1] - b0); for (let i = 0; i < m; i++) start[i] = b0 + (start[i] - b0) * f; }
  }
  // arrows belong to the gap in front of the element they lead to (gap m = after the last element)
  // v0.14.11 an arrow belongs to the gap in front of the later of the two elements it sits between (the two nearest elements), wherever it lies on the page
  const gap: Unit[][] = Array.from({ length: m + 1 }, () => []);
  const aimOf = new Map<Unit, { x: number; y: number }>();
  {
    const dist = (a: Unit["bbox"], b: Unit["bbox"]) => Math.hypot(Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.w, b.x + b.w)), Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.h, b.y + b.h)));
    for (const u of order) {
      if (u.kind !== "arrow") continue;
      if (!m) { gap[0].push(u); continue; }
      if (u.from !== undefined && main.some(e => e.panel === u.from)) {      // v2 step 10: the arrow out of panel k sits in the gap in front of panel k+1 (after the last panel when there is none)
        const t = main.findIndex(e => e.panel === u.from! + 1) >= 0 ? main.findIndex(e => e.panel === u.from! + 1) : (() => { const i = main.findIndex(e => e.panel! > u.from!); return i >= 0 ? i : m; })();
        gap[t].push(u);
        if (t < m) { const tb = main[t].bbox; aimOf.set(u, { x: tb.x + tb.w / 2, y: tb.y + tb.h / 2 }); }
        continue;
      }
      const near = main.map((e, i) => ({ i, d: dist(u.bbox, e.bbox) })).sort((x, y) => x.d - y.d).slice(0, 2);
      const t = Math.max(...near.map(x => x.i));
      gap[t].push(u);
      const tb = main[t].bbox; aimOf.set(u, { x: tb.x + tb.w / 2, y: tb.y + tb.h / 2 });
    }
  }
  const END = D - 0.05;
  const gapStart = (j: number) => (j === 0 ? headEnd : start[j - 1]);
  const gapEnd = (j: number) => (j < m ? start[j] : D - 0.1);
  const aDur = (j: number) => (gap[j].length ? clamp((0.3 * Math.max(0.3, gapEnd(j) - gapStart(j))) / gap[j].length, 0.05, 0.7) : 0);
  let cursor = headEnd;                                     // nothing may begin before this: the end of the unit drawn last
  const place = (unit: Unit, want: number, dur: number) => {
    const begin = Math.min(Math.max(want, cursor), Math.max(cursor, END - 0.05));
    const d = Math.max(0.05, Math.min(dur, END - begin));
    out.push({ unit, begin, dur: d, aim: aimOf.get(unit) }); cursor = begin + d;
  };
  for (let j = 0; j <= m; j++) {
    const ad = aDur(j), n = gap[j].length;
    const first = j < m ? gapEnd(j) - 0.05 - n * ad : cursor + 0.1;       // arrows end just before their element (or follow the last one)
    gap[j].forEach((a, q) => place(a, first + q * ad, ad));
    if (j === m) break;
    // the drawing may use the time until the next element starts, minus the time its arrows need
    const next = j + 1 < m ? start[j + 1] : D - 0.1;
    const slot = next - start[j], avail = Math.max(0.03, slot - Math.min(0.1, 0.15 * slot) - aDur(j + 1) * gap[j + 1].length);   // the gap to the next element is kept, but never more than 15% of a short slot
    const frac = (main[j].bbox.w * main[j].bbox.h) / (W * H);
    const base = clamp(1.2 + 6 * Math.sqrt(frac), 1, 3.5);
    place(main[j], start[j], Math.min(avail, Math.max(base, avail - Math.max(0.5, opts.maxIdle))));
  }
  // more units than the slide has time for: squeeze the whole timeline evenly, so the order and the no-overlap rule still hold
  const over = out.length ? Math.max(...out.map(u => u.begin + u.dur)) : 0;
  if (over > END) { const f = END / over; out.forEach(u => { u.begin *= f; u.dur *= f; }); }
  return { items: out, main: m, fromJson: k > 0 };
}

/** Per-layer times from a unit plan. The members of a unit (icon, dots, caption) are drawn one after another and fill exactly the unit's own
 *  window, so the unit is finished, with every piece, when its window ends and the next unit can start. */
export function layerTimes(plan: SlidePlan, p: Project): Map<string, { begin: number; dur: number; pause: number }> {
  const lay = new Map(p.layers.map(l => [l.id, l]));
  const out = new Map<string, { begin: number; dur: number; pause: number }>();
  for (const it of plan.items) {
    const cen = (l: Layer) => ({ x: l.bbox.x + l.bbox.w / 2, y: l.bbox.y + l.bbox.h / 2 });
    // an arrow is drawn whole, from its far end towards the element it points to (so the head comes last); everything else top to bottom
    const ms = it.unit.layerIds.map(id => lay.get(id)!);
    if (it.unit.panel === undefined) ms.sort(it.aim && it.unit.kind === "arrow"
      ? (a, b) => Math.hypot(cen(b).x - it.aim!.x, cen(b).y - it.aim!.y) - Math.hypot(cen(a).x - it.aim!.x, cen(a).y - it.aim!.y)
      : (a, b) => a.bbox.y - b.bbox.y || a.bbox.x - b.bbox.x);
    if (it.dur === 0) { ms.forEach(l => out.set(l.id, { begin: it.begin, dur: 0, pause: 0 })); continue; }
    // v2 step 12: inside a panel unit the panel takes most of the window, then its label, then its caption box (so the lettering is never squeezed to a blink)
    const roleW = { panel: 6, label: 2.5, caption: 1.5, arrow: 1 } as const;
    const wts = it.unit.panel !== undefined ? ms.map(l => roleW[l.panel?.role ?? "panel"]) : ms.map(l => Math.max(40, Math.sqrt(l.bbox.w * l.bbox.h)));
    const tot = wts.reduce((a, b) => a + b, 0);
    let t = it.begin;
    ms.forEach((l, i) => { const d = (it.dur * wts[i]) / tot; out.set(l.id, { begin: t, dur: d, pause: 0 }); t += d; });
  }
  return out;
}

/* ------------------------------------------------------------------ per-slide report (v0.14.12) ------------------------------------------------------------------ */

/** What the report needs to know about one image. All times are seconds on the video clock. */
/** v2 step 12: one label / caption box of a panel, with its own time on the video clock (report rows for panel parts). */
export interface ReportPart { k: number; role: "label" | "caption"; begin: number; end: number; text: string; kept: boolean }
/** New workflow step 9: what a row is. A picture is drawn by hand; a heading or subscribe slide is handwritten words on white; a scene is a video clip. `last` is the last cue a scene covers; `label` the words or the clip name. */
export type RowKind = "picture" | "heading" | "subscribe" | "scene";
export interface ReportRow { n: number; start: number; end: number; heading?: string; status: string; elements?: number[]; captions?: string[]; plan?: SlidePlan; note?: string; parts?: ReportPart[]; kind?: RowKind; last?: number; label?: string }
const rowName = (r: ReportRow): string => (r.last !== undefined && r.last > r.n ? `${r.n}-${r.last}` : String(r.n));
const kindTag = (r: ReportRow): string => (r.kind && r.kind !== "picture" ? ` (${r.kind})` : "");

/** The label and caption box of every panel unit of a plan, with times (absolute: `start` + local) and whether their words come from the file (`kept` = the picture's own lettering stays). */
export function panelParts(plan: SlidePlan, p: Project, times: Map<string, { begin: number; dur: number; pause: number }>, panels: PanelSpec[] | undefined, start: number): ReportPart[] {
  const out: ReportPart[] = [];
  for (const it of plan.items) {
    const k = it.unit.panel; if (k === undefined) continue;
    const spec = panels?.[k - 1];
    for (const id of it.unit.layerIds) {
      const l = p.layers.find(x => x.id === id), role = l?.panel?.role, t = times.get(id);
      if (!l || !t || (role !== "label" && role !== "caption")) continue;
      const words = spec && spec.shape !== "parchment" ? (role === "label" ? spec.label : spec.caption) : "";
      out.push({ k, role, begin: start + t.begin, end: start + t.begin + t.dur, text: words?.trim() ?? "", kept: !words?.trim() });
    }
  }
  return out;
}
export interface ReportLine { slide: number; step: string; kind: string; begin: number; end: number; script: number | null; diff: number | null; text: string; flag: string }

const r2 = (v: number) => (Math.round(v * 100) / 100).toFixed(2);

/** The units of one image in the order they are drawn, with their times, the script time of every element, and a flag where something needs a look:
 *  an element that starts later than its script time, two units that overlap, or a unit that ends after the image does. */
export function slideLines(r: ReportRow): { lines: ReportLine[]; problems: string[] } {
  const lines: ReportLine[] = [], problems: string[] = [];
  if (r.kind && r.kind !== "picture") {   // step 9: no pictures to analyse; one line says what the row is
    lines.push({ slide: r.n, step: r.kind === "scene" ? "V" : r.kind === "subscribe" ? "S" : "H", kind: r.kind, begin: r.start, end: r.end, script: null, diff: null, text: r.label ?? "", flag: "" });
    return { lines, problems };
  }
  if (!r.plan) { if (r.status !== "ok") problems.push(`not analysed (${r.status})`); return { lines, problems }; }
  let k = 0, prevEnd = r.start;
  r.plan.items.forEach((it, i) => {
    const begin = r.start + it.begin, end = begin + it.dur, isItem = it.unit.kind === "item";
    const script = isItem && r.elements && k < r.elements.length ? r.elements[k] : null;
    const diff = script === null ? null : begin - script;
    const flags: string[] = [];
    if (diff !== null && diff > 0.05) flags.push(`starts ${r2(diff)} s after its script time`);
    if (begin < prevEnd - 1e-6) flags.push("starts before the unit before it ended");
    if (end > r.end + 1e-6) flags.push("ends after the image");
    const label = it.unit.kind === "heading" ? (r.heading ?? "") : isItem ? (r.captions?.[k] ?? "") : "";
    lines.push({ slide: r.n, step: it.unit.kind === "heading" ? "H" : it.unit.kind === "arrow" ? "-" : String(k + 1), kind: it.unit.kind === "item" ? "element" : it.unit.kind, begin, end, script, diff, text: label, flag: flags.join("; ") });
    flags.forEach(f => problems.push(`${it.unit.kind === "item" ? "element " + (k + 1) : it.unit.kind} (unit ${i + 1}): ${f}`));
    // v2 step 12: the arrow says which panel it leaves; the label and the caption box of a panel get their own rows right after it
    if (it.unit.kind === "arrow" && it.unit.from !== undefined) lines[lines.length - 1].text = `arrow out of panel ${it.unit.from}`;
    if (isItem && it.unit.panel !== undefined) for (const pt of r.parts?.filter(x => x.k === it.unit.panel) ?? []) {
      const pf: string[] = [];
      if (pt.begin < begin - 1e-6) pf.push("starts before its panel");
      if (pt.end > end + 1e-6) pf.push("ends after its panel");
      if (pt.kept) pf.push("no words in the file: the picture's lettering stays");
      lines.push({ slide: r.n, step: `${k + 1}.${pt.role}`, kind: pt.role, begin: pt.begin, end: pt.end, script: null, diff: null, text: pt.text, flag: pf.join("; ") });
      pf.filter(f => !f.startsWith("no words")).forEach(f => problems.push(`${pt.role} of panel ${k + 1}: ${f}`));
    }
    if (isItem) k++;
    prevEnd = Math.max(prevEnd, end);
  });
  if (r.elements && r.elements.length !== r.plan.main) problems.push(`the file lists ${r.elements.length} elements, ${r.plan.main} found (matched in order)`);
  return { lines, problems };
}

/** Plain-text report of one image. */
export function slideReportText(r: ReportRow): string {
  const { lines, problems } = slideLines(r);
  const out = [`IMAGE ${rowName(r)}${kindTag(r)}   ${r2(r.start)} to ${r2(r.end)} s   (${r2(r.end - r.start)} s)${r.heading ? `   heading: "${r.heading}"` : ""}`];
  if (!lines.length) out.push("  " + (problems[0] ?? "nothing to draw"));
  else {
    out.push("  step  kind     begin    end   script   diff  text");
    for (const l of lines) out.push(`  ${l.step.padEnd(4)}  ${l.kind.padEnd(7)} ${r2(l.begin).padStart(6)} ${r2(l.end).padStart(6)} ${(l.script === null ? "-" : r2(l.script)).padStart(7)} ${(l.diff === null ? "-" : (l.diff >= 0 ? "+" : "") + r2(l.diff)).padStart(6)}  ${l.text}${l.flag ? `   <-- ${l.flag}` : ""}`);
    out.push(problems.length ? `  CHECK: ${problems.length} thing${problems.length > 1 ? "s" : ""} to look at` : (r.kind && r.kind !== "picture" ? "  OK: not a drawn picture, nothing to check" : "  OK: every element starts at its script time, nothing overlaps"));
  }
  return out.join("\n");
}

/** Plain-text report of all images, with a one-line summary per image on top. */
export function reportText(rows: ReportRow[]): string {
  const all = rows.map(r => ({ r, p: slideLines(r).problems }));
  const head = [`Timing report: ${rows.length} images, ${all.filter(a => a.p.length).length} to look at`, ...all.map(a => `  Image ${rowName(a.r)}${kindTag(a.r)}: ${a.p.length ? "CHECK, " + a.p[0] + (a.p.length > 1 ? ` (+${a.p.length - 1} more)` : "") : "OK"}`), ""];
  return head.join("\n") + "\n" + rows.map(slideReportText).join("\n\n") + "\n";
}

/** One CSV row per drawn unit (open it in a spreadsheet and set it beside the canvases). */
export function reportCsv(rows: ReportRow[]): string {
  const q = (v: string) => `"${v.replace(/"/g, '""')}"`;
  const out = ["image,image_start,image_end,image_kind,step,kind,begin,end,script_time,diff,text,flag"];
  for (const r of rows) for (const l of slideLines(r).lines)
    out.push([rowName(r), r2(r.start), r2(r.end), r.kind ?? "picture", l.step, l.kind, r2(l.begin), r2(l.end), l.script === null ? "" : r2(l.script), l.diff === null ? "" : r2(l.diff), q(l.text), q(l.flag)].join(","));
  return out.join("\r\n") + "\r\n";
}

/* ------------------------------------------------------------------ handwritten headings and captions (font replacement) ------------------------------------------------------------------ */

export interface TextFonts { main: opentype.Font; math: opentype.Font }
export interface TextPlan { skip: Set<string>; markup: string; heading: boolean; captions: number; wanted: number }

/** Replaces the AI's lettering by handwriting. The heading and every caption are found as "text" pieces of the picture; those pieces are not drawn,
 *  and the words from the JSON are written in the chosen font, fitted into the same box, at the same moment.
 *  - Heading: written at the heading's time (or shown at once when the heading is not rewritten).
 *  - Caption: written right after its icon is drawn, inside the element's own time.
 *  Captions are matched to the elements by order. There is no rule about the two counts: an element without a caption in the file keeps the picture's lettering,
 *  and a caption without an element is not used. */
export function planTexts(plan: SlidePlan, p: Project, times: Map<string, { begin: number; dur: number; pause: number }>, texts: { heading?: string; captions?: string[]; panels?: PanelSpec[] } | undefined, D: number,
  fonts: TextFonts, color: string, style: WriteStyle): TextPlan {
  const res: TextPlan = { skip: new Set(), markup: "", heading: false, captions: 0, wanted: 0 };
  if (!texts) return res;
  if (texts.panels && plan.items.some(i => i.unit.panel !== undefined)) return planPanelTexts(plan, p, times, texts.panels, fonts, color, style);
  const lay = new Map(p.layers.map(l => [l.id, l]));
  const isText = (l: Layer) => (l as any).kind === "text";
  const cv = { w: p.width, h: p.height };

  const head = plan.items.find(i => i.unit.kind === "heading");
  if (head && texts.heading) {
    const tl = head.unit.layerIds.map(id => lay.get(id)!).filter(isText);
    if (tl.length) {
      const laid = layoutInBox(fonts.main, fonts.math, texts.heading, unionBox(tl.map(l => l.bbox)), cv);
      tl.forEach(l => res.skip.add(l.id));
      res.markup += textParts(laid, { color, style }, head.dur, head.begin, "h");
      res.heading = true;
    }
  }

  const items = plan.items.filter(i => i.unit.kind === "item");
  const caps = texts.captions;
  res.wanted = caps?.length ?? 0;
  if (caps) {                                                                          // v0.14.9: matched by order, whatever the two counts are
    items.forEach((it, k) => {
      const layers = it.unit.layerIds.map(id => lay.get(id)!);
      const icon = layers.filter(l => !isText(l));
      if (!icon.length) return;
      const ib = unionBox(icon.map(l => l.bbox));
      const capL = layers.filter(l => isText(l) && l.bbox.y + l.bbox.h / 2 >= ib.y + ib.h * 0.75);   // text under the icon; text inside the icon stays as drawn
      if (!capL.length || !caps[k]) return;                                            // no caption in the file for this element: it keeps the picture's lettering
      const tt = capL.map(l => times.get(l.id)).filter((t): t is { begin: number; dur: number; pause: number } => !!t);
      if (!tt.length) return;
      const begin = Math.min(...tt.map(t => t.begin)), end = Math.max(...tt.map(t => t.begin + t.dur));
      const winEnd = it.begin + it.dur;                                                // the caption is written inside its own element's window
      const dur = Math.max(0.1, Math.min(end, winEnd) - begin);
      let box = unionBox(capL.map(l => l.bbox));
      const w2 = Math.max(box.w, Math.min(ib.w * 1.1, box.w * 1.5));                   // a longer caption may use a little more width, centred
      box = { x: box.x + box.w / 2 - w2 / 2, y: box.y, w: w2, h: box.h };
      const laid = layoutInBox(fonts.main, fonts.math, caps[k], box, cv);
      capL.forEach(l => res.skip.add(l.id));
      res.markup += textParts(laid, { color, style }, dur, begin, "c" + k + "_");
      res.captions++;
    });
  }
  return res;
}

/** v2 step 11: handwriting for panel units. The yellow label and the caption box of panel k are redrawn (their own colour + a thin dark outline) and the words
 *  of the file are written on them, in the label's / caption's own time window. The picture's lettering in those boxes is not drawn (`skip`).
 *  Parchment (verse) panels are untouched, and so is any panel the file does not list or gives no words for. No heading is ever written here:
 *  panel units have none, so a heading appears only where the picture has one. */
export function planPanelTexts(plan: SlidePlan, p: Project, times: Map<string, { begin: number; dur: number; pause: number }>, panels: PanelSpec[], fonts: TextFonts, color: string, style: WriteStyle): TextPlan {
  const res: TextPlan = { skip: new Set(), markup: "", heading: false, captions: 0, wanted: panels.filter(q => q.shape !== "parchment" && q.label).length };
  const cv = { w: p.width, h: p.height };
  const f3 = (v: number) => +v.toFixed(3);
  for (const it of plan.items) {
    const k = it.unit.panel; if (k === undefined) continue;
    const spec = panels[k - 1];
    if (!spec || spec.shape === "parchment") continue;
    for (const id of it.unit.layerIds) {
      const l = p.layers.find(x => x.id === id);
      const role = l?.panel?.role, words = role === "label" ? spec.label : role === "caption" ? spec.caption : "";
      const t = times.get(id);
      if (!l || !words || !words.trim() || !t) continue;
      const b = l.bbox, pad = Math.min(b.w, b.h) * 0.1;
      const laid = layoutInBox(fonts.main, fonts.math, words.trim(), { x: b.x + pad, y: b.y + pad, w: b.w - 2 * pad, h: b.h - 2 * pad }, cv);
      const r = Math.min(b.w, b.h) * 0.18, sw = Math.max(1.5, Math.min(b.w, b.h) * 0.04);
      const box = (op: string) => `<rect x="${f3(b.x)}" y="${f3(b.y)}" width="${f3(b.w)}" height="${f3(b.h)}" rx="${f3(r)}" fill="${l.fill ?? "#fff4b3"}" stroke="#2b2b2b" stroke-width="${f3(sw)}" ${op}`;
      res.skip.add(id);
      res.markup += (t.dur <= 0 ? box('/>') : box(`opacity="0"><animate attributeName="opacity" from="0" to="1" begin="${f3(t.begin)}s" dur="0.05s" fill="freeze"/></rect>`))
        + textParts(laid, { color, style }, t.dur, t.begin, `p${k}${role![0]}_`);
      if (role === "label") res.captions++;
    }
  }
  return res;
}

/* ------------------------------------------------------------------ scene clips (videos) ------------------------------------------------------------------ */

/** A scene clip: a video that replaces the pictures of cues `first` to `last` (same naming rule as the Auto Editor's CueNames.clipRange). */
export interface Clip { name: string; first: number; last: number; file: File }
const VID = /\.(mp4|mov|mkv|webm|m4v|avi)$/i;
const VMIME: Record<string, string> = { mp4: "video/mp4", mov: "video/quicktime", mkv: "video/x-matroska", webm: "video/webm", m4v: "video/mp4", avi: "video/x-msvideo" };
export const isVideoName = (name: string): boolean => VID.test(name);

/** "53.mp4", "n53.mp4", "scene_53.mp4" -> 53..53; "12-14.mp4" -> 12..14. With several numbers the LAST number or range wins. Leading zeros are ignored.
 *  null when the name has no number, or the range runs backwards (14-12). */
export function clipRange(fileName: string): { first: number; last: number } | null {
  const stem = (fileName.split(/[\\/]/).pop() ?? fileName).replace(/\.[^.]+$/, "");
  const all = [...stem.matchAll(/(\d+)(?:\s*[-\u2013]\s*(\d+))?/g)];
  const m = all[all.length - 1];
  if (!m) return null;
  const a = parseInt(m[1], 10), b = m[2] === undefined ? a : parseInt(m[2], 10);
  return Number.isFinite(a) && Number.isFinite(b) && b >= a ? { first: a, last: b } : null;
}

/** Loose videos and videos inside ZIPs. Videos whose name has no readable number are returned in `unnamed` (shown to the owner, never guessed). macOS junk and hidden files are skipped. */
export async function readClips(files: File[]): Promise<{ clips: Clip[]; unnamed: string[] }> {
  const found: { name: string; file: File }[] = [];
  for (const f of files) {
    if (/\.zip$/i.test(f.name) || f.type === "application/zip" || f.type === "application/x-zip-compressed") {
      const zip = await JSZip.loadAsync(f);
      for (const [path, entry] of Object.entries(zip.files)) {
        if (entry.dir || /(^|\/)(__MACOSX|\.)/.test(path) || !VID.test(path)) continue;
        const base = path.split("/").pop()!, ext = base.split(".").pop()!.toLowerCase();
        found.push({ name: base, file: new File([await entry.async("blob")], base, { type: VMIME[ext] ?? "video/mp4" }) });
      }
    } else if (VID.test(f.name) || f.type.startsWith("video/")) found.push({ name: f.name, file: f });
  }
  const clips: Clip[] = [], unnamed: string[] = [];
  for (const { name, file } of found) { const r = clipRange(name); if (r) clips.push({ name, ...r, file }); else unnamed.push(name); }
  clips.sort((a, b) => a.first - b.first || a.name.localeCompare(b.name, undefined, { numeric: true }));
  return { clips, unnamed };
}

/** Plain-language notes about the clips: overlapping ranges, and clips that cover a number the timing file does not have. */
export function clipNotes(clips: Clip[], specNs?: number[]): string[] {
  const notes: string[] = [];
  const list = [...clips].sort((a, b) => a.first - b.first);
  list.forEach((c, i) => { const p = list[i - 1]; if (p && c.first <= p.last) notes.push(`${p.name} (cues ${p.first}-${p.last}) and ${c.name} (cues ${c.first}-${c.last}) cover the same cue. The first one is used.`); });
  if (specNs?.length) { const have = new Set(specNs); for (const c of list) { const miss: number[] = []; for (let n = c.first; n <= c.last; n++) if (!have.has(n)) miss.push(n); if (miss.length) notes.push(`${c.name}: the timing file has no image ${ranges(miss)}.`); } }
  return notes;
}

/** New workflow step 5: which cues each scene clip takes. A cue belongs to the FIRST clip that names it (clips overlapping an earlier clip lose the shared cues;
 *  a clip left with no cue is dropped). Those cues are shown as the video, so their pictures are never processed. */
export interface SceneSpan { clip: Clip; cues: number[] }
export function sceneSpans(clips: Clip[]): SceneSpan[] {
  const taken = new Set<number>(), out: SceneSpan[] = [];
  for (const clip of [...clips].sort((a, b) => a.first - b.first || a.name.localeCompare(b.name, undefined, { numeric: true }))) {
    const cues: number[] = [];
    for (let n = clip.first; n <= Math.min(clip.last, clip.first + 5000); n++) if (!taken.has(n)) { taken.add(n); cues.push(n); }
    if (cues.length) out.push({ clip, cues });
  }
  return out;
}

/** Start and end (seconds) of a scene row: from the timing file (first covered cue's start to last covered cue's end); with no timing entry the row follows the one before it, `defSec` per cue. */
export function sceneTimes(cues: number[], specs: { n: number; start: number; end: number }[] | null | undefined, prevEnd: number, defSec: number): { start: number; end: number; fromFile: boolean } {
  const hit = (specs ?? []).filter(sp => cues.includes(sp.n));
  if (!hit.length) return { start: prevEnd, end: prevEnd + defSec * cues.length, fromFile: false };
  return { start: Math.min(...hit.map(h => h.start)), end: Math.max(...hit.map(h => h.end)), fromFile: true };
}
