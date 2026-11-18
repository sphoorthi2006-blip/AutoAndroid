export type AnimType = "none" | "float" | "bounce" | "sway" | "spin" | "pulse" | "fade" | "pop" | "slide";
export interface Anim { type: AnimType; dur: number; delay: number; loop: boolean }
export interface Layer {
  id: string; name: string; group: string; visible: boolean;
  /** From the backend: "icon", "text" or "connector" (a long thin arrow or path). */
  kind?: string;
  bbox: { x: number; y: number; w: number; h: number };
  /** Move offset (image pixels), uniform scale, and rotation in degrees, all around the element's center. */
  tx: number; ty: number; scale: number; rot: number;
  anim: Anim;
  /** Ink-only and colour-only versions of the element (like VideoScribe's layers). Empty when the element has no dark line art. */
  inkGroup?: string; colorGroup?: string;
  /** Stroke plan from the backend: outline strokes (w wide) and colour sweeps (cw wide) that uncover the artwork, like a whiteboard video. */
  reveal?: Reveal;
  /** Sketch timing for this element only. A missing value means: use the global default. */
  timing?: { draw?: number; pause?: number };
  /** How this element gets onto the canvas, and (for "draw") in which style. A missing value means: use the global default. */
  entrance?: Entrance; style?: DrawStyle; dir?: Dir;
  /** v2 step 7: the ORIGINAL pixels of this element (PNG data URL cut from the upload, transparent outside a panel's outline). `rasterBox` = where it sits on the canvas [x, y, w, h]. Used from step 8 on. */
  rasterHref?: string; rasterBox?: [number, number, number, number];
  /** 0.16: only the dark line art of the raster (same pixels, same box, transparent elsewhere). Absent for painted pieces with no line art. */
  inkRasterHref?: string;
  /** v2 step 10: this layer belongs to panel `k` (1-based, the panel's number in the JSON file). role: the panel itself, its yellow label, its caption box, or the arrow that leaves it. */
  /** v2 step 11: the colour of a label / caption box (#rrggbb), redrawn under the handwriting that replaces its lettering. */
  fill?: string;
  panel?: { k: number; role: "panel" | "label" | "caption" | "arrow" };
}
export interface Stroke { d: string; len: number }
export interface Reveal { v?: number; w: number; paths: Stroke[]; cw?: number; cpaths?: Stroke[]; edgeFirst?: boolean }
/** Entrance types (VideoScribe: Draw, Fade in, Pop/Bounce in, Move in, None). */
export type Entrance = "draw" | "fade" | "pop" | "slide" | "none";
/** Where "Move in" comes from. */
export type Dir = "left" | "right" | "top" | "bottom";
/** Draw style (VideoScribe): illustrate = follow the lines of the artwork; reveal = uncover it top to bottom in diagonal strokes. */
export type DrawStyle = "illustrate" | "reveal";
/** order = ids of the elements in the order they are drawn. Missing = reading order (row by row, left to right). It does not change which element is on top. */
export interface Project { width: number; height: number; backgroundColor: string; layers: Layer[]; order?: string[];
  /** 0.16: the whole upload (JPEG data URL). In "lines first" mode it fades in last, behind the elements (the picture's own background). */
  bgRasterHref?: string }
export interface BgImage { dataUrl: string; w: number; h: number; zoom: number; offX: number; offY: number }
export type Background = { kind: "color"; color: string } | { kind: "image"; image: BgImage };
export const DEFAULT_ANIM: Anim = { type: "none", dur: 2, delay: 0, loop: true };
/** How raster pieces are drawn: "each" = one piece at a time (its line art, then its colour, like a colouring book); "all" = the lines of ALL pieces first, then ALL the colour (the sketch-then-paint video look); "off" = the older pen + colour sweep; "auto" = "each" for comic panels, "all" for any other picture. */
export type LinesMode = "auto" | "each" | "all" | "off";
/** pen = share (0.1 to 0.9) of the drawing time spent on the lines; the rest is the colour. linesFirst = draw the lines of ALL elements first, then colour ALL of them (the whiteboard / sketch-then-paint look). */
/** Global sketch timing (like VideoScribe's draw time, pause time and its limits). */
export interface SketchSettings { draw: number; pause: number; min: number; max: number; fit: boolean; target: number; entrance: Entrance; style: DrawStyle; enterTime: number; dir: Dir; pen: number; lines: LinesMode }
export const DEFAULT_SKETCH: SketchSettings = { draw: 3, pause: 0.5, min: 0.5, max: 10, fit: false, target: 30, entrance: "draw", style: "illustrate", enterTime: 0.6, dir: "left", pen: 0.5, lines: "auto" };
