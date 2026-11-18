import opentype from "opentype.js";
import { polyLength, skeletonStrokes, type Pt } from "./skeleton";
import patrick from "./fonts/PatrickHand-Regular.ttf?url";
import math from "@fontsource/noto-sans-math/files/noto-sans-math-latin-400-normal.woff?url";

export interface FontChoice { id: string; label: string; url: string; custom?: boolean }
/** Patrick Hand is the only text font (the owner's own file, bundled inside the app, so everything works offline); Noto Sans Math is only a hidden fallback for symbols. Math symbols (√ ∑ π ≠ ≤ ∞ ∫ Greek) come from the Math font when the chosen font lacks them. */
export const FONTS: FontChoice[] = [
  { id: "patrick", label: "Handwriting (Patrick Hand)", url: patrick },
];
export const MATH_URL = math;

/** Step 24: fonts the owner uploads. They are parsed in memory (no network, no file URL) and listed after the bundled font. */
const customChoices: FontChoice[] = [];
/** Bundled fonts first, then the uploaded ones in the order they were added. */
export const allFonts = (): FontChoice[] => [...FONTS, ...customChoices];
export const findFont = (id: string): FontChoice => allFonts().find(f => f.id === id) ?? FONTS[0];
export const FONT_EXTENSIONS = [".ttf", ".otf", ".woff"];
const slug = (v: string) => v.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "font";

/** Reads font bytes (TTF, OTF or WOFF) and adds the font to the list. The same file name uploaded again replaces the old one. Throws a plain message for a file that is not a font. */
export function addCustomFont(fileName: string, bytes: ArrayBuffer): FontChoice {
  let font: opentype.Font;
  try { font = opentype.parse(bytes); } catch { throw new Error(`${fileName} is not a font this app can read. Use a .ttf, .otf or .woff file.`); }
  if (!font.glyphs || font.glyphs.length < 2) throw new Error(`${fileName} has no letters in it.`);
  const family = font.names?.fontFamily?.en || Object.values(font.names?.fontFamily ?? {})[0] || fileName.replace(/\.[^.]+$/, "");
  const sub = font.names?.fontSubfamily?.en;
  const label = `${family}${sub && !/^regular$/i.test(sub) ? " " + sub : ""} (uploaded)`;
  const id = `custom-${slug(fileName.replace(/\.[^.]+$/, ""))}`;
  const choice: FontChoice = { id, label, url: `font:${id}`, custom: true };
  cache.set(choice.url, Promise.resolve(font));
  const at = customChoices.findIndex(f => f.id === id);
  if (at >= 0) customChoices[at] = choice; else customChoices.push(choice);
  return choice;
}
export function removeCustomFont(id: string): void {
  const at = customChoices.findIndex(f => f.id === id);
  if (at >= 0) { cache.delete(customChoices[at].url); customChoices.splice(at, 1); }
}

const cache = new Map<string, Promise<opentype.Font>>();
export function loadFont(url: string): Promise<opentype.Font> {
  let p = cache.get(url);
  if (!p) { p = fetch(url).then(r => { if (!r.ok) throw new Error("A bundled font could not be loaded."); return r.arrayBuffer(); }).then(b => opentype.parse(b)); cache.set(url, p); }
  return p;
}

export type Align = "center" | "left";
export interface Canvas { w: number; h: number }
export const SHAPES: { id: string; label: string; w: number; h: number }[] = [
  { id: "wide", label: "Wide 16:9 (YouTube)", w: 1920, h: 1080 },
  { id: "tall", label: "Tall 9:16 (Shorts)", w: 1080, h: 1920 },
  { id: "square", label: "Square 1:1", w: 1080, h: 1080 },
];
/** write = handwritten: the pen follows the centre line of every letter (no outline). trace = the older look: outline drawn, then filled. */
export type WriteStyle = "write" | "trace";
export interface TextSettings { text: string; fontId: string; color: string; align: Align; size: number /* % of the biggest size that fits */; shape: string; style: WriteStyle; posX?: number /* 0 = far left, 100 = far right of the safe area; undefined = automatic */; posY?: number /* 0 = top, 100 = bottom; undefined = centred */ }
export const DEFAULT_TEXT: TextSettings = { text: "The Lord is my shepherd;\nI shall not want.\n— Psalm 23:1", fontId: "patrick", color: "#1b2a41", align: "center", size: 100, shape: "wide", style: "write" };

/** One character to draw: its font, size scale (1 = normal, smaller = superscript / subscript) and vertical shift (in em). */
interface Ch { ch: string; scale: number; dy: number }

/** Plain text with a tiny formula notation: x^2, x^{n+1} (superscript), H_2O, a_{ij} (subscript). A backslash before ^ or _ writes it literally. */
export function parseMarkup(line: string): Ch[] {
  const out: Ch[] = [];
  const chars = [...line];
  for (let i = 0; i < chars.length; i++) {
    const c = chars[i];
    if (c === "\\" && (chars[i + 1] === "^" || chars[i + 1] === "_")) { out.push({ ch: chars[++i], scale: 1, dy: 0 }); continue; }
    if (c === "^" || c === "_") {
      const sup = c === "^";
      const take: string[] = [];
      if (chars[i + 1] === "{") { let j = i + 2; while (j < chars.length && chars[j] !== "}") take.push(chars[j++]); i = j; }
      else if (i + 1 < chars.length && chars[i + 1] !== " ") take.push(chars[++i]);
      if (!take.length) { out.push({ ch: c, scale: 1, dy: 0 }); continue; }
      for (const t of take) out.push({ ch: t, scale: 0.62, dy: sup ? -0.38 : 0.16 });
      continue;
    }
    out.push({ ch: c, scale: 1, dy: 0 });
  }
  return out;
}

interface Placed { d: string; len: number; ch: string; font: opentype.Font; x: number; y: number; sz: number }
interface Laid { glyphs: Placed[]; width: number; height: number; size: number }

/** Approximate pen length of an outline (control polygon), used to give detailed letters more time than simple ones. */
function pathLen(cmds: opentype.PathCommand[]): number {
  let x = 0, y = 0, sx = 0, sy = 0, L = 0;
  const seg = (a: number, b: number) => { L += Math.hypot(a - x, b - y); x = a; y = b; };
  for (const c of cmds) {
    if (c.type === "M") { x = sx = c.x; y = sy = c.y; }
    else if (c.type === "L") seg(c.x, c.y);
    else if (c.type === "Q") { seg(c.x1, c.y1); seg(c.x, c.y); }
    else if (c.type === "C") { seg(c.x1, c.y1); seg(c.x2, c.y2); seg(c.x, c.y); }
    else if (c.type === "Z") seg(sx, sy);
  }
  return L;
}

/** Lays the text out inside the canvas (word wrap, centred or left, biggest size that fits x size%) and returns one outline per letter.
 *  The block is centred by the ink itself (top of the tallest letter to the bottom of the lowest), so it sits in the middle of the picture. */
export function layoutText(main: opentype.Font, mathFont: opentype.Font, s: TextSettings, cv: Canvas): Laid {
  const marginX = cv.w * 0.08, marginY = cv.h * 0.1;
  return layoutCore(main, mathFont, s.text, s.align, s.size, { x: marginX, y: marginY, w: cv.w - 2 * marginX, h: cv.h - 2 * marginY }, cv, false, 300, { x: s.posX, y: s.posY });
}

/** Writes `text` into a box of the picture (a detected heading or caption): biggest size that fits, centred in the box. Used by the Video tab to replace the AI's lettering. */
export function layoutInBox(main: opentype.Font, mathFont: opentype.Font, text: string, box: { x: number; y: number; w: number; h: number }, cv: Canvas, maxSize = 400): Laid {
  const pad = Math.max(2, box.h * 0.04);
  const area = { x: box.x - pad, y: box.y - pad, w: box.w + 2 * pad, h: box.h + 2 * pad };
  return layoutCore(main, mathFont, text.replace(/\s*\n\s*/g, " ").trim(), "center", 100, area, cv, true, maxSize);
}

/** Shared layout: word wrap inside `area`, biggest size that fits (times size%), centred or left, then the ink is centred up/down inside the area.
 *  tight = true measures the height by the letters (one line needs about 0.85 x size) instead of the roomy line spacing of the Text tab. */
function layoutCore(main: opentype.Font, mathFont: opentype.Font, text: string, align: Align, sizePct: number, area: { x: number; y: number; w: number; h: number }, cv: Canvas, tight: boolean, maxSize = 300, pos?: { x?: number; y?: number }): Laid {
  const maxW = area.w, maxH = area.h, marginX = area.x;
  const lineGap = 1.28;
  const fontFor = (ch: string) => (main.charToGlyphIndex(ch) > 0 || mathFont.charToGlyphIndex(ch) <= 0 ? main : mathFont);

  // Break into styled lines at size = 1 (units of em); wrapping happens at each size trial.
  const paras = text.replace(/\r/g, "").split("\n").map(parseMarkup);
  const adv = (c: Ch, prev: Ch | undefined, size: number) => {
    const f = fontFor(c.ch), sz = size * c.scale;
    let a = f.getAdvanceWidth(c.ch, sz);
    if (prev && fontFor(prev.ch) === f && prev.scale === c.scale) { const g1 = f.charToGlyph(prev.ch), g2 = f.charToGlyph(c.ch); a += (f.getKerningValue(g1, g2) * sz) / f.unitsPerEm; }
    return a;
  };
  const wrap = (size: number): Ch[][] => {
    const lines: Ch[][] = [];
    for (const para of paras) {
      if (!para.length) { lines.push([]); continue; }
      let line: Ch[] = [], w = 0, word: Ch[] = [], ww = 0;
      const flushWord = () => { line.push(...word); w += ww; word = []; ww = 0; };
      for (let i = 0; i < para.length; i++) {
        const c = para[i], a = adv(c, para[i - 1], size);
        if (c.ch === " ") {
          flushWord();
          if (w + a > maxW && line.length) { lines.push(line); line = []; w = 0; } else { line.push(c); w += a; }
          continue;
        }
        if (w + ww + a > maxW && line.length) { while (line.length && line[line.length - 1].ch === " ") line.pop(); lines.push(line); line = []; w = 0; }
        word.push(c); ww += a;
      }
      flushWord();
      while (line.length && line[line.length - 1].ch === " ") line.pop();
      lines.push(line);
    }
    return lines;
  };
  const lineWidth = (ln: Ch[], size: number) => ln.reduce((a, c, i) => a + adv(c, ln[i - 1], size), 0);
  const needH = (n: number, size: number) => (tight ? (n - 1) * size * lineGap + size * 0.85 : n * size * lineGap);
  const fits = (size: number) => { const L = wrap(size); return needH(L.length, size) <= maxH && L.every(ln => lineWidth(ln, size) <= maxW + 0.5); };
  let lo = tight ? 6 : 16, hi = maxSize;   // binary search: the biggest size that fits
  for (let i = 0; i < 14; i++) { const mid = (lo + hi) / 2; if (fits(mid)) lo = mid; else hi = mid; }
  const size = Math.max(tight ? 5 : 10, lo * (Math.min(100, Math.max(20, sizePct)) / 100));
  const lines = wrap(size);

  // First place every letter on a baseline, then move the whole block so its ink is centred up/down.
  interface Raw { ch: string; font: opentype.Font; x: number; y: number; sz: number }
  const raw: Raw[] = [];
  let y = size * 0.95;
  for (const ln of lines) {
    const w = lineWidth(ln, size);
    let x = align === "center" ? area.x + (area.w - w) / 2 : marginX;
    ln.forEach((c, i) => {
      const f = fontFor(c.ch), sz = size * c.scale;
      const a = adv(c, ln[i - 1], size);
      const kern = a - f.getAdvanceWidth(c.ch, sz);     // kerning moves the letter before it is drawn
      if (c.ch !== " " && f.charToGlyph(c.ch).index > 0) raw.push({ ch: c.ch, font: f, x: x + kern, y: y + c.dy * size, sz });
      x += a;
    });
    y += size * lineGap;
  }
  let top = Infinity, bot = -Infinity, left = Infinity, right = -Infinity;
  for (const r of raw) { const bb = r.font.charToGlyph(r.ch).getPath(r.x, r.y, r.sz).getBoundingBox(); top = Math.min(top, bb.y1); bot = Math.max(bot, bb.y2); left = Math.min(left, bb.x1); right = Math.max(right, bb.x2); }
  // Up/down: centred unless the user chose a position (0..100 % of the free space in the area). Left/right: only moved when a position is chosen.
  const pct = (v: number | undefined) => (v === undefined || !Number.isFinite(v) ? undefined : Math.min(100, Math.max(0, v)) / 100);
  const py = pct(pos?.y), px = pct(pos?.x);
  const shift = raw.length ? (py === undefined ? area.y + area.h / 2 - (top + bot) / 2 : area.y + (area.h - (bot - top)) * py - top) : 0;
  const shiftX = raw.length && px !== undefined ? area.x + (area.w - (right - left)) * px - left : 0;
  const glyphs: Placed[] = [];
  for (const r of raw) {
    const p = r.font.charToGlyph(r.ch).getPath(r.x + shiftX, r.y + shift, r.sz);
    if (p.commands.length) glyphs.push({ d: p.toPathData(2), len: pathLen(p.commands), ch: r.ch, font: r.font, x: r.x + shiftX, y: r.y + shift, sz: r.sz });
  }
  return { glyphs, width: cv.w, height: cv.h, size };
}

const f3 = (x: number) => +x.toFixed(3);
const f1 = (x: number) => +x.toFixed(1);

/** Everything needed to write one letter: its outline and its pen strokes, both in em units x 1000 with the baseline at y = 0. */
export interface GlyphPlan { outline: string; strokes: { d: string; len: number }[]; pen: number; box: { x1: number; y1: number; x2: number; y2: number } }
export type Rasterizer = (d: string, box: { x1: number; y1: number; x2: number; y2: number }, scale: number, pad: number) => { bits: Uint8Array; w: number; h: number };

/** Draws the letter outline into a small black and white bitmap. The browser version uses a canvas; tests can plug in another one. */
const browserRaster: Rasterizer = (d, b, scale, pad) => {
  const w = Math.ceil((b.x2 - b.x1) * scale) + 2 * pad, h = Math.ceil((b.y2 - b.y1) * scale) + 2 * pad;
  const c = document.createElement("canvas"); c.width = w; c.height = h;
  const ctx = c.getContext("2d", { willReadFrequently: true })!;
  ctx.translate(pad - b.x1 * scale, pad - b.y1 * scale); ctx.scale(scale, scale);
  ctx.fill(new Path2D(d));
  const px = ctx.getImageData(0, 0, w, h).data, bits = new Uint8Array(w * h);
  for (let i = 0; i < bits.length; i++) bits[i] = px[i * 4 + 3] > 127 ? 1 : 0;
  return { bits, w, h };
};
let rasterizer: Rasterizer = browserRaster;
export const setRasterizer = (r: Rasterizer) => { rasterizer = r; plans.clear(); };

const fontIds = new WeakMap<opentype.Font, number>();
let fontCount = 0;
const plans = new Map<string, GlyphPlan | null>();
const RS = 0.3, PAD = 6;   // bitmap scale (px per unit at size 1000) and empty border

/** Pen strokes of one letter (cached). Null when the letter has no ink. */
export function glyphPlan(font: opentype.Font, ch: string): GlyphPlan | null {
  if (!fontIds.has(font)) fontIds.set(font, ++fontCount);
  const key = `${fontIds.get(font)}|${ch}`;
  if (plans.has(key)) return plans.get(key)!;
  let plan: GlyphPlan | null = null;
  const path = font.charToGlyph(ch).getPath(0, 0, 1000);
  if (path.commands.length) {
    const bb = path.getBoundingBox(), d = path.toPathData(1);
    const box = { x1: bb.x1, y1: bb.y1, x2: bb.x2, y2: bb.y2 };
    const { bits, w, h } = rasterizer(d, box, RS, PAD);
    const sk = skeletonStrokes(bits, w, h);
    const toEm = (p: Pt) => ({ x: box.x1 + (p.x - PAD) / RS, y: box.y1 + (p.y - PAD) / RS });
    const strokes = sk.strokes.map(st => { const q = st.map(toEm); return { d: q.map((p, i) => `${i ? "L" : "M"}${f1(p.x)} ${f1(p.y)}`).join(""), len: Math.max(1, polyLength(q)) }; });
    plan = { outline: d, strokes, pen: sk.pen / RS, box };
  }
  plans.set(key, plan);
  return plan;
}

/** Animated SVG of the text being written. Uses only `<animate from to begin dur>` (like the picture mode), so it exports to MP4 the same way.
 *  - "write": each letter is uncovered by a pen that follows the centre line of its strokes, so the letters appear as if written by hand.
 *  - "trace": each letter is traced by its outline and then filled in.
 *  Letters follow one another with no gaps; the whole text is finished exactly after `seconds`. */
export function textSvg(laid: Laid, s: TextSettings, bgMarkupSvg: string, seconds: number): string {
  const parts = textParts(laid, s, Math.max(0.3, seconds), 0, "w");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${laid.width}" height="${laid.height}" viewBox="0 0 ${laid.width} ${laid.height}"><defs><clipPath id="frame"><rect width="${laid.width}" height="${laid.height}"/></clipPath></defs><g clip-path="url(#frame)">${bgMarkupSvg}${parts}</g></svg>`;
}

/** The letters of a laid-out text as animated SVG markup (no <svg> wrapper), written between `t0` and `t0 + T` seconds.
 *  `idp` makes the mask ids unique when several texts share one picture. T = 0 gives the finished text at once (no animation). */
export function textParts(laid: Laid, s: { color: string; style: WriteStyle }, T: number, t0: number, idp: string): string {
  if (T <= 0) return `<g fill="${s.color}">${laid.glyphs.map(g => `<path d="${g.d}"/>`).join("")}</g>`;
  const sw = Math.max(1.2, laid.size * 0.022);
  const write = s.style === "write";
  const pl = write ? laid.glyphs.map(g => glyphPlan(g.font, g.ch)) : [];
  const weights = laid.glyphs.map((g, i) => (write ? (pl[i] ? pl[i]!.strokes.reduce((a, q) => a + q.len, 0) : 0) + 260 : g.len + laid.size * 0.6));
  const total = weights.reduce((a, b) => a + b, 0) || 1;
  let t = t0;
  return laid.glyphs.map((g, i) => {
    const d = (weights[i] / total) * T, b = t; t += d;
    const plan = pl[i];
    if (!write) {
      return `<path d="${g.d}" pathLength="1" fill="${s.color}" fill-opacity="0" stroke="${s.color}" stroke-width="${f3(sw)}" stroke-linejoin="round" stroke-linecap="round" stroke-dasharray="1 2" stroke-dashoffset="1.02">`
        + `<animate attributeName="stroke-dashoffset" from="1.02" to="0" begin="${f3(b)}s" dur="${f3(d * 0.65)}s" fill="freeze"/>`
        + `<animate attributeName="fill-opacity" from="0" to="1" begin="${f3(b + d * 0.4)}s" dur="${f3(d * 0.6)}s" fill="freeze"/>`
        + `<animate attributeName="stroke-opacity" from="1" to="0" begin="${f3(b + d * 0.75)}s" dur="${f3(d * 0.25)}s" fill="freeze"/>`
        + `</path>`;
    }
    const k = g.sz / 1000, place = `translate(${f3(g.x)} ${f3(g.y)}) scale(${+k.toFixed(6)})`;
    if (!plan || !plan.strokes.length) {   // nothing to follow: fade the letter in
      return `<g transform="${place}"><path d="${plan?.outline ?? ""}" fill="${s.color}" fill-opacity="0"><animate attributeName="fill-opacity" from="0" to="1" begin="${f3(b)}s" dur="${f3(Math.max(0.05, d))}s" fill="freeze"/></path></g>`;
    }
    const penEnd = b + d * 0.9;                       // strokes take 90% of the letter's time, then anything the pen missed fades in
    const sum = plan.strokes.reduce((a, q) => a + q.len, 0) || 1;
    let u = b;
    // Dashes use real lengths (not pathLength) so every renderer agrees. The dash starts a pen-width before the stroke, so its round cap does not leave a dot early.
    const strokes = plan.strokes.map(q => {
      const du = Math.max(0.005, (q.len / sum) * (penEnd - b)), L = f1(q.len + 2), H = f1(L + plan.pen);
      const el = `<path d="${q.d}" fill="none" stroke="#fff" stroke-width="${f1(plan.pen)}" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="${L} ${f1(L + plan.pen * 3)}" stroke-dashoffset="${H}"><animate attributeName="stroke-dashoffset" from="${H}" to="0" begin="${f3(u)}s" dur="${f3(du)}s" fill="freeze"/></path>`;
      u += du; return el;
    }).join("");
    const m = plan.pen * 1.5, bx = plan.box;
    const mask = `<mask id="${idp}${i}" maskUnits="userSpaceOnUse" x="${f1(bx.x1 - m)}" y="${f1(bx.y1 - m)}" width="${f1(bx.x2 - bx.x1 + 2 * m)}" height="${f1(bx.y2 - bx.y1 + 2 * m)}">${strokes}`
      + `<path d="${plan.outline}" fill="#fff" fill-opacity="0"><animate attributeName="fill-opacity" from="0" to="1" begin="${f3(penEnd)}s" dur="${f3(Math.max(0.03, b + d - penEnd))}s" fill="freeze"/></path></mask>`;
    return `<g transform="${place}">${mask}<path d="${plan.outline}" fill="${s.color}" mask="url(#${idp}${i})"/></g>`;
  }).join("");
}

export async function buildTextSvg(s: TextSettings, bgMarkupFor: (w: number, h: number) => string, seconds: number): Promise<{ svg: string; width: number; height: number }> {
  const shape = SHAPES.find(x => x.id === s.shape) ?? SHAPES[0];
  const font = findFont(s.fontId);
  const [main, mathFont] = await Promise.all([loadFont(font.url), loadFont(MATH_URL)]);
  const laid = layoutText(main, mathFont, s, shape);
  return { svg: textSvg(laid, s, bgMarkupFor(shape.w, shape.h), seconds), width: shape.w, height: shape.h };
}
