/** Pen paths for handwriting: finds the centre line of a letter bitmap and turns it into a few long strokes.
 *  Pure functions on a 0/1 bitmap (no canvas, no DOM), so they are easy to test. Coordinates are bitmap pixels. */
export interface Pt { x: number; y: number }
export interface Skeleton { strokes: Pt[][]; /** pen width in pixels that covers the thickest part of the letter */ pen: number }

const RING: [number, number][] = [[0, -1], [1, -1], [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1]];

/** Zhang-Suen thinning: shrinks every shape to a line one pixel wide, in the middle of the shape. The bitmap needs a border of empty pixels. */
export function thin(bits: Uint8Array, w: number, h: number): Uint8Array {
  const s = Uint8Array.from(bits);
  const off = RING.map(([dx, dy]) => dy * w + dx);
  const kill: number[] = [];
  let changed = true;
  while (changed) {
    changed = false;
    for (let step = 0; step < 2; step++) {
      kill.length = 0;
      for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
        const i = y * w + x;
        if (!s[i]) continue;
        const p = off.map(o => s[i + o]);      // N NE E SE S SW W NW
        const B = p[0] + p[1] + p[2] + p[3] + p[4] + p[5] + p[6] + p[7];
        if (B < 2 || B > 6) continue;
        let A = 0; for (let k = 0; k < 8; k++) if (!p[k] && p[(k + 1) % 8]) A++;
        if (A !== 1) continue;
        if (step === 0 ? p[0] * p[2] * p[4] || p[2] * p[4] * p[6] : p[0] * p[2] * p[6] || p[0] * p[4] * p[6]) continue;
        kill.push(i);
      }
      if (kill.length) { changed = true; for (const i of kill) s[i] = 0; }
    }
  }
  return s;
}

/** Chamfer distance to the nearest empty pixel (about 1 per pixel). */
function distance(bits: Uint8Array, w: number, h: number): Float32Array {
  const d = new Float32Array(w * h), INF = 1e9, a = 1, b = Math.SQRT2;
  for (let i = 0; i < d.length; i++) d[i] = bits[i] ? INF : 0;
  for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) { const i = y * w + x; if (d[i]) d[i] = Math.min(d[i], d[i - 1] + a, d[i - w] + a, d[i - w - 1] + b, d[i - w + 1] + b); }
  for (let y = h - 2; y > 0; y--) for (let x = w - 2; x > 0; x--) { const i = y * w + x; if (d[i]) d[i] = Math.min(d[i], d[i + 1] + a, d[i + w] + a, d[i + w + 1] + b, d[i + w - 1] + b); }
  return d;
}

function simplify(pts: Pt[], eps: number): Pt[] {
  if (pts.length < 3) return pts;
  let idx = 0, dmax = 0;
  const a = pts[0], b = pts[pts.length - 1], dx = b.x - a.x, dy = b.y - a.y, L = Math.hypot(dx, dy) || 1e-9;
  for (let i = 1; i < pts.length - 1; i++) { const d = Math.abs(dy * pts[i].x - dx * pts[i].y + b.x * a.y - b.y * a.x) / L; if (d > dmax) { dmax = d; idx = i; } }
  if (L < 1e-6) {   // a closed loop: split at the point farthest from the start so it does not collapse
    let k = 1, far = 0; for (let i = 1; i < pts.length - 1; i++) { const d = Math.hypot(pts[i].x - a.x, pts[i].y - a.y); if (d > far) { far = d; k = i; } }
    if (far < eps) return [a, b];
    return [...simplify(pts.slice(0, k + 1), eps).slice(0, -1), ...simplify(pts.slice(k), eps)];
  }
  if (dmax <= eps) return [a, b];
  return [...simplify(pts.slice(0, idx + 1), eps).slice(0, -1), ...simplify(pts.slice(idx), eps)];
}
/** One round of corner cutting: keeps the ends, rounds the bends. */
function smooth(p: Pt[]): Pt[] {
  if (p.length < 3) return p;
  const o: Pt[] = [p[0]];
  for (let i = 0; i < p.length - 1; i++) {
    const a = p[i], b = p[i + 1];
    o.push({ x: a.x * 0.75 + b.x * 0.25, y: a.y * 0.75 + b.y * 0.25 }, { x: a.x * 0.25 + b.x * 0.75, y: a.y * 0.25 + b.y * 0.75 });
  }
  o.push(p[p.length - 1]);
  return o;
}
export const polyLength = (p: Pt[]) => { let L = 0; for (let i = 1; i < p.length; i++) L += Math.hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y); return L; };

/** Centre line of the shape as pen strokes, in the order a person would write them: largest piece first, starting at the top left end,
 *  at each crossing carrying straight on; the side branches follow as their own strokes. */
export function skeletonStrokes(bits: Uint8Array, w: number, h: number): Skeleton {
  const sk = thin(bits, w, h);
  const dt = distance(bits, w, h);
  const off = RING.map(([dx, dy]) => dy * w + dx);
  const on: number[] = []; for (let i = 0; i < sk.length; i++) if (sk[i]) on.push(i);
  if (!on.length) return { strokes: [], pen: 4 };

  // Pen width: covers the thickest part of the letter (90th percentile of the local thickness along the centre line).
  const th = on.map(i => dt[i]).sort((a, b) => a - b);
  const pen = Math.max(2, 2 * th[Math.floor(th.length * 0.9)] * 1.12 + 1.5);
  const spur = Math.max(3, pen * 0.45);

  const nb = (i: number) => { const r: number[] = []; for (let k = 0; k < 8; k++) { const j = i + off[k]; if (sk[j]) r.push(j); } return r; };
  const adj8 = (i: number, j: number) => Math.abs((i % w) - (j % w)) <= 1 && Math.abs(Math.floor(i / w) - Math.floor(j / w)) <= 1;
  const X = (i: number) => i % w, Y = (i: number) => Math.floor(i / w);
  const pt = (i: number): Pt => ({ x: X(i) + 0.5, y: Y(i) + 0.5 });

  // Pieces of the shape (an "i" has two: the stem and the dot), biggest first.
  const comp = new Map<number, number>(); const comps: number[][] = [];
  for (const i of on) {
    if (comp.has(i)) continue;
    const c: number[] = [], st = [i]; comp.set(i, comps.length);
    while (st.length) { const a = st.pop()!; c.push(a); for (const b of nb(a)) if (!comp.has(b)) { comp.set(b, comps.length); st.push(b); } }
    comps.push(c);
  }
  comps.sort((a, b) => b.length - a.length);

  const seen = new Uint8Array(sk.length);
  const out: Pt[][] = [];
  const score = (i: number) => X(i) + 0.6 * Y(i);          // top-left first, like writing

  for (const c of comps) {
    if (c.length < 4) {                                       // a dot
      const m = c.reduce((a, i) => ({ x: a.x + pt(i).x / c.length, y: a.y + pt(i).y / c.length }), { x: 0, y: 0 });
      out.push([m, { x: m.x, y: m.y + 1 }]); for (const i of c) seen[i] = 1; continue;
    }
    let first = true;
    for (;;) {
      // Where does the next stroke start? First stroke: the free end at the top left (or the top of a loop). Later strokes: a branch off the part already written.
      let start = -1, from = -1;
      if (first) {
        const ends = c.filter(i => !seen[i] && nb(i).length === 1);
        start = (ends.length ? ends : c).reduce((a, i) => (a < 0 || (ends.length ? score(i) < score(a) : Y(i) < Y(a) || (Y(i) === Y(a) && X(i) < X(a))) ? i : a), -1);
      } else {
        let best = Infinity;
        for (const i of c) if (!seen[i]) { const touch = nb(i).find(j => seen[j]); if (touch !== undefined && score(i) < best) { best = score(i); start = i; from = touch; } }
        if (start < 0) { const left = c.filter(i => !seen[i]); if (!left.length) break; start = left.reduce((a, i) => (score(i) < score(a) ? i : a)); }
      }
      if (start < 0) break;
      first = false;
      const path: number[] = from >= 0 ? [from, start] : [start];
      seen[start] = 1;
      let cur = start;
      for (;;) {
        const k0 = Math.max(0, path.length - 5);
        let dx = X(cur) - X(path[k0]), dy = Y(cur) - Y(path[k0]);
        if (!dx && !dy) { dx = path.length === 1 ? -1 : 0; }   // a loop starts by going left (counter-clockwise, like writing an "o")
        const dl = Math.hypot(dx, dy) || 1;
        let next = -1, cost = Infinity;
        for (const j of nb(cur)) {
          if (seen[j]) continue;
          const sx = X(j) - X(cur), sy = Y(j) - Y(cur), sl = Math.hypot(sx, sy);
          const turn = Math.acos(Math.max(-1, Math.min(1, (sx * dx + sy * dy) / (sl * dl))));
          const c2 = turn + (sx && sy ? 0.02 : 0);
          if (c2 < cost) { cost = c2; next = j; }
        }
        if (next < 0) break;
        for (const j of nb(cur)) if (j !== next && !seen[j] && adj8(j, next)) seen[j] = 1;   // staircase leftovers
        seen[next] = 1; path.push(next); cur = next;
      }
      if (path.length > 8 && nb(cur).includes(path[0]) && from < 0) path.push(path[0]);   // a loop closes
      const pts = path.map(pt);
      const len = polyLength(pts);
      // Short stubs off a longer line are rendering noise, not pen strokes. (The whole letter is still revealed at the end of its time.)
      if (len >= spur || from < 0) out.push(smooth(simplify(pts, 0.8)));
    }
  }
  return { strokes: out, pen };
}
