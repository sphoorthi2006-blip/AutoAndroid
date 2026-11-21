/** A picture used as the background of the text (cover-fitted, with a zoom and offsets as fractions of the canvas). */
export interface BgImage { dataUrl: string; w: number; h: number; zoom: number; offX: number; offY: number }
export type Background = { kind: "color"; color: string } | { kind: "image"; image: BgImage };
