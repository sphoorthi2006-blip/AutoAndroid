import type opentype from "opentype.js";
import { layoutInBox, textParts } from "./textanim";
import type { SlideSpec } from "./slides";

/* New workflow, step 1 and 2: heading and subscribe slides.
 * The AI picture of such a slide is never used. The editor draws a plain WHITE screen and writes the words in black Patrick Hand,
 * stroke by stroke (the same pen-follows-the-letter engine as the Text Editor, `textParts`). No zoom, no fade, no box, no colour. */

export type TextSlideKind = "heading" | "subscribe";
export interface TextSlideInfo { kind: TextSlideKind; words: string }

/** What the editor writes on a subscribe slide when the file gives no words of its own. */
export const SUBSCRIBE_WORDS = "LIKE AND SUBSCRIBE";
const NUMBERED = /^\s*\d+[.)]\s+\S/;
const SUBSCRIBE = /subscribe/i;
const clean = (v: string | undefined | null): string => (v ?? "").replace(/\s+/g, " ").trim();

/** Decides whether one canvas of the timing file is a heading or a subscribe slide (same order as the auto editor: subscribe first, then heading).
 *  - subscribe: a canvas with at most one panel that mentions "subscribe" anywhere in its words.
 *  - heading:   a single panel with NO label and a verse (how svg-bh writes a heading canvas), or a short numbered line ("3. Living Blessing") as the only text,
 *               or a canvas the file itself marks with system "heading" / "text".
 *  Everything else (scenes, verse canvases with a label, normal canvases) returns null and stays a picture. */
export function classifyTextSlide(spec: SlideSpec | undefined): TextSlideInfo | null {
  if (!spec) return null;
  const ps = spec.panels ?? [];
  if (ps.length <= 1) {
    const mention = [spec.heading, spec.text, ps[0]?.verse, ps[0]?.label, ps[0]?.cue, ps[0]?.caption, ps[0]?.balloon].map(clean).find(t => SUBSCRIBE.test(t));
    if (mention !== undefined) {
      const own = clean(ps[0]?.verse) || clean(spec.heading);
      return { kind: "subscribe", words: own && SUBSCRIBE.test(own) && own.length <= 60 ? own : SUBSCRIBE_WORDS };
    }
  }
  const sys = (spec.system ?? "").toLowerCase();
  if (sys === "heading" || sys === "text") {
    const words = clean(ps[0]?.verse) || clean(spec.heading) || clean(spec.text);
    return words ? { kind: "heading", words } : null;
  }
  // a real verse is a long sentence; a heading is a short line. A long unlabelled verse stays a picture (verse panels keep their own layout).
  if (ps.length === 1 && !clean(ps[0].label) && clean(ps[0].verse) && clean(ps[0].verse).length <= 80) return { kind: "heading", words: clean(ps[0].verse) };
  if (ps.length <= 1 && !clean(ps[0]?.label)) {
    const t = clean(spec.text);
    if (t && t.length <= 90 && NUMBERED.test(t)) return { kind: "heading", words: t };
  }
  if (!ps.length && !spec.elements.length && !spec.text && NUMBERED.test(clean(spec.heading))) return { kind: "heading", words: clean(spec.heading) };
  return null;
}

export interface TextSlide { svg: string; width: number; height: number; writeSeconds: number }
export interface TextSlideOptions { color?: string; width?: number; height?: number; maxSize?: number }

/** How long the pen takes: about 0.09 s per character, at least 0.8 s, and always done 0.5 s before the slide ends (the finished words then stay still). */
export function writeSeconds(words: string, slideSeconds: number): number {
  const want = Math.max(0.8, [...words].length * 0.09);
  return Math.max(0.3, Math.min(want, 4, slideSeconds - 0.5));
}

/** The animated SVG of one text slide: white from edge to edge, the words in black Patrick Hand written by hand, nothing else.
 *  There is NO zoom and NO fade: the only movement is the pen. `slideSeconds` is the length of the slide on the video clock. */
export function buildTextSlide(words: string, slideSeconds: number, fonts: { main: opentype.Font; math: opentype.Font }, o: TextSlideOptions = {}): TextSlide {
  const W = o.width ?? 1920, H = o.height ?? 1080, color = o.color ?? "#000000";
  const box = { x: W * 0.08, y: H * 0.2, w: W * 0.84, h: H * 0.6 };
  const laid = layoutInBox(fonts.main, fonts.math, words, box, { w: W, h: H }, o.maxSize ?? Math.round(220 * (H / 1080)));
  const T = writeSeconds(words, slideSeconds);
  const pen = textParts(laid, { color, style: "write" }, T, 0.15, "t");
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><rect width="${W}" height="${H}" fill="#ffffff"/>${pen}</svg>`;
  return { svg, width: W, height: H, writeSeconds: T };
}

/* ---- step 3: the owner can decide per row ---- */
export type KindChoice = "auto" | "picture" | "heading" | "subscribe";
/** `kind` is the owner's choice for one row; `words` replaces the words found in the timing file (empty = keep them). */
export interface KindOverride { kind: KindChoice; words?: string }

/** The final decision for one row. Auto = what the timing file says (classifyTextSlide), optionally with the owner's own words.
 *  Heading / Subscribe force a text slide even when the file says nothing (the words then come from the box, the file's heading or text, or LIKE AND SUBSCRIBE).
 *  Picture keeps the AI picture. A forced heading with no words anywhere stays a picture (null): there is nothing to write. */
export function resolveTextSlide(spec: SlideSpec | undefined, ov?: KindOverride): TextSlideInfo | null {
  const auto = classifyTextSlide(spec), typed = clean(ov?.words);
  if (!ov || ov.kind === "auto") return auto && typed ? { kind: auto.kind, words: typed } : auto;
  if (ov.kind === "picture") return null;
  const fallback = ov.kind === "subscribe" ? SUBSCRIBE_WORDS : clean(spec?.heading) || clean(spec?.text);
  const words = typed || (auto?.kind === ov.kind ? auto.words : fallback);
  return words ? { kind: ov.kind, words } : null;
}
