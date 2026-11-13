'use strict';
/*
 * Pixel pipeline, a line-by-line port of the Android app's ImageProcessor.kt so both give the same result:
 *   1. automatic corner-mark removal (on the original size)
 *   2. optional crop of the black strip at the top
 *   3. light edge-preserving denoise
 *   4. 2x Lanczos-3 upscale (pictures above ~2.2 megapixels are not doubled, to save memory)
 *   5. gentle sharpen
 * Pixels are RGBA Uint8ClampedArrays.
 */

function clamp8(v) {
  v = Math.trunc(v);
  return v < 0 ? 0 : (v > 255 ? 255 : v);
}

// ---------- 1. Mark removal ----------
// The sparkle sits at the same spot in every image: centre (1279, 669.5) on a 1376x768 image,
// about 30 px radius, a 4-point star (astroid). Positions scale with the picture size.
// The hole is filled by copying TEXTURE from the surrounding picture (patch-based inpainting), working inwards
// from the edge of the hole, so grain, weave and patterns continue through it instead of leaving a blurry patch.
function removeMark(src, w, h) {
  const px = new Uint8ClampedArray(src);
  const s = w / 1376;
  const cx = 1279 * s;
  const cy = 669.5 * (h / 768);
  const r = 30 * s;
  const x0 = Math.max(1, Math.trunc(cx - r - 4));
  const x1 = Math.min(w - 2, Math.trunc(cx + r + 4));
  const y0 = Math.max(1, Math.trunc(cy - r - 4));
  const y1 = Math.min(h - 2, Math.trunc(cy + r + 4));
  const bw = x1 - x0 + 1;
  const bh = y1 - y0 + 1;
  if (bw < 3 || bh < 3) return px;

  let mask = new Uint8Array(bw * bh);
  for (let y = 0; y < bh; y++) {
    for (let x = 0; x < bw; x++) {
      const dx = Math.abs(x + x0 - cx) / r;
      const dy = Math.abs(y + y0 - cy) / r;
      if (Math.pow(dx, 2 / 3) + Math.pow(dy, 2 / 3) <= 1) mask[y * bw + x] = 1;
    }
  }
  // grow the mask a little to cover the soft glow around the star
  const grow = Math.max(3, Math.trunc(3 * s));
  for (let k = 0; k < grow; k++) mask = dilate(mask, bw, bh);

  // work window = hole box plus a margin of picture to take texture from
  const margin = Math.max(30, Math.trunc(60 * s));
  const wx0 = Math.max(0, x0 - margin), wy0 = Math.max(0, y0 - margin);
  const wx1 = Math.min(w - 1, x1 + margin), wy1 = Math.min(h - 1, y1 + margin);
  const ww = wx1 - wx0 + 1, wh = wy1 - wy0 + 1;
  const ch = [new Float32Array(ww * wh), new Float32Array(ww * wh), new Float32Array(ww * wh)];
  for (let y = 0; y < wh; y++) {
    for (let x = 0; x < ww; x++) {
      const o = ((y + wy0) * w + (x + wx0)) * 4;
      const i = y * ww + x;
      ch[0][i] = px[o]; ch[1][i] = px[o + 1]; ch[2][i] = px[o + 2];
    }
  }
  const hole = new Uint8Array(ww * wh);
  const holeList = [];
  for (let y = 0; y < bh; y++) {
    for (let x = 0; x < bw; x++) {
      if (!mask[y * bw + x]) continue;
      const i = (y + y0 - wy0) * ww + (x + x0 - wx0);
      hole[i] = 1;
      holeList.push(i);
    }
  }
  const known = new Uint8Array(ww * wh);
  for (let i = 0; i < known.length; i++) known[i] = hole[i] ? 0 : 1;

  // candidate patch centres: a grid of spots whose whole patch is original picture (no hole pixels)
  const R = 2;                                         // patch is (2R+1) x (2R+1)
  const stride = Math.max(2, Math.round(2 * s));
  const cands = [];
  for (let y = R; y < wh - R; y += stride) {
    for (let x = R; x < ww - R; x += stride) {
      let ok = true;
      for (let dy = -R; dy <= R && ok; dy++) for (let dx = -R; dx <= R; dx++) if (hole[(y + dy) * ww + x + dx]) { ok = false; break; }
      if (ok) cands.push(y * ww + x);
    }
  }
  if (cands.length === 0) return px;
  const offs = [];
  for (let dy = -R; dy <= R; dy++) for (let dx = -R; dx <= R; dx++) offs.push(dy * ww + dx);
  const nOff = offs.length;

  // fill from the edge inwards, one layer at a time
  let left = holeList.length;
  let guard = 0;
  while (left > 0 && guard++ < 400) {
    const layer = [];
    for (const i of holeList) {
      if (known[i]) continue;
      const x = i % ww, y = (i - x) / ww;
      let touches = false;
      for (let dy = -1; dy <= 1 && !touches; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          if (dx === 0 && dy === 0) continue;
          const xx = x + dx, yy = y + dy;
          if (xx < 0 || yy < 0 || xx >= ww || yy >= wh) continue;
          if (known[yy * ww + xx]) { touches = true; break; }
        }
      }
      if (touches) layer.push(i);
    }
    if (layer.length === 0) break;
    const chosen = new Int32Array(layer.length);
    for (let li = 0; li < layer.length; li++) {
      const i = layer[li];
      let best = Infinity, bestC = cands[0];
      for (let ci = 0; ci < cands.length; ci++) {
        const c = cands[ci];
        let cost = 0;
        for (let k = 0; k < nOff && cost < best; k++) {
          const q = i + offs[k];
          if (!known[q]) continue;
          const cq = c + offs[k];
          const d0 = ch[0][q] - ch[0][cq], d1 = ch[1][q] - ch[1][cq], d2 = ch[2][q] - ch[2][cq];
          cost += d0 * d0 + d1 * d1 + d2 * d2;
        }
        if (cost < best) { best = cost; bestC = c; }
      }
      chosen[li] = bestC;
    }
    for (let li = 0; li < layer.length; li++) {
      const i = layer[li], c = chosen[li];
      ch[0][i] = ch[0][c]; ch[1][i] = ch[1][c]; ch[2][i] = ch[2][c];
    }
    for (const i of layer) known[i] = 1;
    left -= layer.length;
  }

  for (const i of holeList) {
    const x = i % ww, y = (i - x) / ww;
    const o = ((y + wy0) * w + (x + wx0)) * 4;
    px[o] = clamp8(ch[0][i]); px[o + 1] = clamp8(ch[1][i]); px[o + 2] = clamp8(ch[2][i]); px[o + 3] = 255;
  }
  return px;
}

function dilate(m, w, h) {
  const o = new Uint8Array(m);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!m[y * w + x]) continue;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx, yy = y + dy;
          if (xx >= 0 && xx < w && yy >= 0 && yy < h) o[yy * w + xx] = 1;
        }
      }
    }
  }
  return o;
}

// ---------- optional: crop the black strip some generators leave at the top ----------
function cropTopBlack(px, w, h) {
  let rows = 0;
  const limit = Math.min(Math.trunc(h / 8), 60);
  while (rows < limit) {
    let sum = 0;
    for (let x = 0; x < w; x++) {
      const o = (rows * w + x) * 4;
      sum += px[o] + px[o + 1] + px[o + 2];
    }
    if (sum / (w * 3) > 12) break;
    rows++;
  }
  if (rows === 0) return { px, h, rows: 0 };
  return { px: px.slice(rows * w * 4), h: h - rows, rows };
}

// ---------- 2. Light denoise (edge-preserving, so thin lines survive) ----------
function denoise(src, w, h, sigmaColor) {
  sigmaColor = sigmaColor || 14;
  const out = new Uint8ClampedArray(src);
  const inv = 1 / (2 * sigmaColor * sigmaColor);
  const wt = [1, 2, 1, 2, 4, 2, 1, 2, 1];
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const co = (y * w + x) * 4;
      const cr = src[co], cg = src[co + 1], cb = src[co + 2];
      let sr = 0, sg = 0, sb = 0, sw = 0, k = 0;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const po = ((y + dy) * w + (x + dx)) * 4;
          const pr = src[po], pg = src[po + 1], pb = src[po + 2];
          const d = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)) / 3;
          const f = wt[k++] * Math.exp(-d * inv);
          sr += pr * f; sg += pg * f; sb += pb * f; sw += f;
        }
      }
      out[co] = clamp8(sr / sw); out[co + 1] = clamp8(sg / sw); out[co + 2] = clamp8(sb / sw); out[co + 3] = 255;
    }
  }
  return out;
}

// ---------- 3. Lanczos-3 upscale ----------
function lanczos(x) {
  if (x === 0) return 1;
  if (Math.abs(x) >= 3) return 0;
  const px = Math.PI * x;
  return 3 * Math.sin(px) * Math.sin(px / 3) / (px * px);
}

function makeTaps(srcLen, dstLen) {
  const scale = dstLen / srcLen;
  const first = new Int32Array(dstLen);
  const weights = new Float32Array(dstLen * 6);
  const ws = new Float64Array(6);
  for (let o = 0; o < dstLen; o++) {
    const c = (o + 0.5) / scale - 0.5;
    const f = Math.floor(c) - 2;
    first[o] = f;
    let sum = 0;
    for (let t = 0; t < 6; t++) { ws[t] = lanczos(c - (f + t)); sum += ws[t]; }
    for (let t = 0; t < 6; t++) weights[o * 6 + t] = ws[t] / sum;
  }
  return { first, weights };
}

function upscale(src, w, h, factor) {
  const w2 = w * factor, h2 = h * factor;
  const tx = makeTaps(w, w2);
  const ty = makeTaps(h, h2);
  const tmp = new Float32Array(h * w2 * 3);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let ox = 0; ox < w2; ox++) {
      let r = 0, g = 0, b = 0;
      const f = tx.first[ox];
      for (let t = 0; t < 6; t++) {
        let sx = f + t;
        sx = sx < 0 ? 0 : (sx > w - 1 ? w - 1 : sx);
        const p = (row + sx) * 4;
        const wv = tx.weights[ox * 6 + t];
        r += src[p] * wv; g += src[p + 1] * wv; b += src[p + 2] * wv;
      }
      const i = (y * w2 + ox) * 3;
      tmp[i] = r; tmp[i + 1] = g; tmp[i + 2] = b;
    }
  }
  const out = new Uint8ClampedArray(w2 * h2 * 4);
  for (let oy = 0; oy < h2; oy++) {
    const f = ty.first[oy];
    for (let ox = 0; ox < w2; ox++) {
      let r = 0, g = 0, b = 0;
      for (let t = 0; t < 6; t++) {
        let sy = f + t;
        sy = sy < 0 ? 0 : (sy > h - 1 ? h - 1 : sy);
        const i = (sy * w2 + ox) * 3;
        const wv = ty.weights[oy * 6 + t];
        r += tmp[i] * wv; g += tmp[i + 1] * wv; b += tmp[i + 2] * wv;
      }
      const o = (oy * w2 + ox) * 4;
      out[o] = clamp8(r); out[o + 1] = clamp8(g); out[o + 2] = clamp8(b); out[o + 3] = 255;
    }
  }
  return { px: out, w: w2, h: h2 };
}

// ---------- 4. Gentle unsharp mask: original*1.4 - blurred*0.4 ----------
function sharpen(src, w, h, amount) {
  amount = amount === undefined ? 0.4 : amount;
  const k = [1, 4, 6, 4, 1];
  const n = w * h;
  const ch = [new Float32Array(n), new Float32Array(n), new Float32Array(n)];
  for (let i = 0; i < n; i++) {
    ch[0][i] = src[i * 4]; ch[1][i] = src[i * 4 + 1]; ch[2][i] = src[i * 4 + 2];
  }
  const blur = [new Float32Array(n), new Float32Array(n), new Float32Array(n)];
  const tmp = new Float32Array(n);
  for (let c = 0; c < 3; c++) {
    const f = ch[c];
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        let s = 0;
        for (let t = -2; t <= 2; t++) {
          let xx = x + t; xx = xx < 0 ? 0 : (xx > w - 1 ? w - 1 : xx);
          s += f[y * w + xx] * k[t + 2];
        }
        tmp[y * w + x] = s / 16;
      }
    }
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        let s = 0;
        for (let t = -2; t <= 2; t++) {
          let yy = y + t; yy = yy < 0 ? 0 : (yy > h - 1 ? h - 1 : yy);
          s += tmp[yy * w + x] * k[t + 2];
        }
        blur[c][y * w + x] = s / 16;
      }
    }
  }
  const out = new Uint8ClampedArray(n * 4);
  const a = 1 + amount;
  for (let i = 0; i < n; i++) {
    out[i * 4] = clamp8(ch[0][i] * a - blur[0][i] * amount);
    out[i * 4 + 1] = clamp8(ch[1][i] * a - blur[1][i] * amount);
    out[i * 4 + 2] = clamp8(ch[2][i] * a - blur[2][i] * amount);
    out[i * 4 + 3] = 255;
  }
  return out;
}

function processImage(data, w, h, doRemoveMark, doCrop) {
  let px = new Uint8ClampedArray(data);
  for (let i = 3; i < px.length; i += 4) px[i] = 255;       // the app ignores transparency, like the Android version
  if (doRemoveMark) px = removeMark(px, w, h);              // mark position is defined on the uncropped image
  let curH = h;
  let cropRows = 0;
  if (doCrop) {
    const c = cropTopBlack(px, w, h);
    px = c.px; curH = c.h; cropRows = c.rows;
  }
  px = denoise(px, w, curH);
  const factor = (w * curH <= 2200000) ? 2 : 1;             // very large pictures are not doubled (memory)
  const up = upscale(px, w, curH, factor);
  const out = sharpen(up.px, up.w, up.h);
  return { px: out, w: up.w, h: up.h, factor, cropRows };
}

if (typeof self !== 'undefined' && typeof self.postMessage === 'function' && typeof window === 'undefined') {
  self.onmessage = (e) => {
    const m = e.data;
    try {
      const r = processImage(new Uint8ClampedArray(m.buffer), m.w, m.h, m.removeMark, m.crop);
      self.postMessage({ id: m.id, w: r.w, h: r.h, factor: r.factor, cropRows: r.cropRows, buffer: r.px.buffer }, [r.px.buffer]);
    } catch (err) {
      self.postMessage({ id: m.id, error: String((err && err.message) || err) });
    }
  };
}

if (typeof module !== 'undefined') module.exports = { processImage, removeMark, denoise, upscale, sharpen, cropTopBlack };
