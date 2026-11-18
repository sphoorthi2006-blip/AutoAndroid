/** v2 step 9: quality check. Compares the LAST frame of an image's animation with the picture that was uploaded (same size, RGBA bytes).
 *  Pure functions (no browser needed) so they can be tested. A region is a box in the compared bitmaps' pixels; without regions the whole bitmap counts. */
export interface Region { x: number; y: number; w: number; h: number }
export interface FrameDiff { mean: number; bad: number; pixels: number }

/** mean = average absolute difference of R, G, B (0 = identical, 1 = opposite); bad = share of pixels where some channel differs by more than 20%. */
export function compareFrames(a: ArrayLike<number>, b: ArrayLike<number>, w: number, h: number, regions?: Region[]): FrameDiff {
  let sum = 0, bad = 0, n = 0;
  const boxes = regions && regions.length ? regions : [{ x: 0, y: 0, w, h }];
  for (const r of boxes) {
    const x0 = Math.max(0, Math.floor(r.x)), y0 = Math.max(0, Math.floor(r.y)), x1 = Math.min(w, Math.ceil(r.x + r.w)), y1 = Math.min(h, Math.ceil(r.y + r.h));
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
      const i = (y * w + x) * 4;
      const d = Math.max(Math.abs(a[i] - b[i]), Math.abs(a[i + 1] - b[i + 1]), Math.abs(a[i + 2] - b[i + 2])) / 255;
      sum += (Math.abs(a[i] - b[i]) + Math.abs(a[i + 1] - b[i + 1]) + Math.abs(a[i + 2] - b[i + 2])) / (3 * 255);
      if (d > 0.2) bad++;
      n++;
    }
  }
  return n ? { mean: sum / n, bad: bad / n, pixels: n } : { mean: 0, bad: 0, pixels: 0 };
}

/** OK when the last frame is the picture (a few anti-aliased edge pixels may differ). */
export function qualityVerdict(d: FrameDiff, scope = "the picture"): { ok: boolean; text: string } {
  const ok = d.pixels > 0 && d.bad <= 0.02 && d.mean <= 0.02;
  const pct = (v: number) => (100 * v).toFixed(1) + "%";
  return { ok, text: d.pixels === 0 ? "Nothing to compare." : `${ok ? "OK" : "CHECK"}: the last frame differs from ${scope} by ${pct(d.mean)} on average, ${pct(d.bad)} of the pixels clearly differ.` };
}
