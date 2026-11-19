"""Stroke planning for the VideoScribe-style reveal (v0.10).
VideoScribe draws with invisible strokes: a mask grows along each stroke and uncovers the artwork underneath.
Like its own recipe, each element gets two sets of strokes:
  1. OUTLINE strokes: follow the centre line of the dark ink, joined into long continuous pen strokes, in reading order.
  2. COLOUR strokes: back-and-forth sweeps through the element that uncover the colour-only layer afterwards.
The browser applies them in apps/web/src/compose.ts (revealGroup)."""
import math
import cv2
import numpy as np
from .ink import ink_mask

_NB = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def _thin(img):
    """Zhang-Suen thinning, vectorised. img: bool array -> bool skeleton."""
    im = img.astype(np.uint8)
    while True:
        changed = False
        for step in (0, 1):
            P = np.pad(im, 1)
            p2, p3, p4, p5 = P[:-2, 1:-1], P[:-2, 2:], P[1:-1, 2:], P[2:, 2:]
            p6, p7, p8, p9 = P[2:, 1:-1], P[2:, :-2], P[1:-1, :-2], P[:-2, :-2]
            seq = [p2, p3, p4, p5, p6, p7, p8, p9, p2]
            B = sum(s.astype(np.int16) for s in seq[:8])
            A = sum(((seq[i] == 0) & (seq[i + 1] == 1)).astype(np.int16) for i in range(8))
            c = (im == 1) & (B >= 2) & (B <= 6) & (A == 1)
            if step == 0:
                c &= (p2 * p4 * p6 == 0) & (p4 * p6 * p8 == 0)
            else:
                c &= (p2 * p4 * p8 == 0) & (p2 * p6 * p8 == 0)
            if c.any():
                im[c] = 0
                changed = True
        if not changed:
            return im.astype(bool)


def _trace(sk):
    """Skeleton pixels -> list of polylines [(x, y), ...]."""
    H, W = sk.shape
    pts = set(zip(*[a.tolist() for a in np.nonzero(sk)[::-1]]))  # (x, y)
    def nbrs(p):
        x, y = p
        return [(x + dx, y + dy) for dy, dx in _NB if (x + dx, y + dy) in pts]
    deg = {p: len(nbrs(p)) for p in pts}
    used = set()
    def edge(a, b): return (a, b) if a < b else (b, a)
    lines = []
    def walk(s, n):
        path = [s, n]; used.add(edge(s, n)); cur = n
        while deg[cur] == 2:
            nxt = [q for q in nbrs(cur) if edge(cur, q) not in used]
            if not nxt: break
            q = nxt[0]; used.add(edge(cur, q)); path.append(q); cur = q
        return path
    nodes = [p for p in pts if deg[p] != 2]
    for s in nodes + sorted(pts):
        for n in nbrs(s):
            if edge(s, n) not in used:
                lines.append(walk(s, n))
    return lines


def _length(pl):
    return sum(math.hypot(pl[i + 1][0] - pl[i][0], pl[i + 1][1] - pl[i][1]) for i in range(len(pl) - 1))


def _d(pl, x0, y0):
    return "M" + " L".join(f"{x + x0} {y + y0}" for x, y in pl)


def _order_outline(lines, sk, bh):
    """Pen order: connected ink pieces in reading order (top to bottom, then left to right); inside a piece the pen
    keeps going from where it stopped, so connected segments become ONE continuous stroke (no lifting the pen)."""
    n, lab = cv2.connectedComponents(sk.astype(np.uint8), connectivity=8)
    comps = {}
    for pl in lines:
        c = int(lab[pl[0][1], pl[0][0]])
        comps.setdefault(c, []).append(pl)
    band = max(24.0, bh / 10.0)
    info = {}
    for c in comps:
        pts = [p for pl in comps[c] for p in pl]
        info[c] = (min(p[0] for p in pts), min(p[1] for p in pts), max(p[1] for p in pts))   # left, top, bottom
    def key(c):
        return (int(info[c][1] // band), info[c][0])
    def order_comps():
        """Text (many small ink pieces on lines): line by line, left to right, like handwriting.  Pictures: top to bottom bands."""
        rows = []   # [top, bottom, [comps]]
        for c in sorted(comps, key=lambda c: info[c][1]):
            l, t, b = info[c]
            for r in rows:
                if min(b, r[1]) - max(t, r[0]) >= 0.5 * max(1, b - t):
                    r[0], r[1] = min(r[0], t), max(r[1], b); r[2].append(c); break
            else:
                rows.append([t, b, [c]])
        if len(comps) >= 4 and len(comps) >= 2.5 * len(rows):
            rows.sort(key=lambda r: r[0])
            return [c for r in rows for c in sorted(r[2], key=lambda c: info[c][0])]
        return sorted(comps, key=key)
    out = []
    for c in order_comps():
        rest = comps[c][:]
        allp = [p for pl in rest for p in pl]
        cw_ = max(p[0] for p in allp) - min(p[0] for p in allp); ch_ = max(p[1] for p in allp) - min(p[1] for p in allp)
        if cw_ > 1.2 * ch_:   # wide piece (a chart, a banner): draw left to right
            xb = max(24.0, cw_ / 12.0); rk = lambda p, xb=xb: (int(p[0] // xb), p[1])
        else:                 # tall or square piece: draw top to bottom
            rk = lambda p: (int(p[1] // band), p[0])
        stroke, cur = None, None
        while rest:
            cand = []
            if cur is not None:                       # strokes that continue exactly where the pen is
                for i, pl in enumerate(rest):
                    for rev in (False, True):
                        a = pl[-1] if rev else pl[0]
                        if math.hypot(a[0] - cur[0], a[1] - cur[1]) <= 3.5:
                            cand.append((i, rev))
            if cand:   # keep going, preferring to move right and down
                i, rev = max(cand, key=lambda t: (lambda o: (o[0] - cur[0]) + 0.6 * (o[1] - cur[1]))(rest[t[0]][0] if t[1] else rest[t[0]][-1]))
                pl = rest.pop(i)
                if rev: pl = pl[::-1]
                stroke.extend(pl[1:])
            else:      # lift the pen: first stroke of a piece in reading order; after that the nearest free end, so the pen travels smoothly
                if cur is None:
                    _, i, rev = min((rk(pl[-1] if rev else pl[0]), i, rev) for i, pl in enumerate(rest) for rev in (False, True))
                else:
                    _, i, rev = min((math.hypot((pl[-1] if rev else pl[0])[0] - cur[0], (pl[-1] if rev else pl[0])[1] - cur[1]), i, rev) for i, pl in enumerate(rest) for rev in (False, True))
                pl = rest.pop(i)
                if rev: pl = pl[::-1]
                if stroke is not None: out.append(stroke)
                stroke = list(pl)
            cur = pl[-1]
        if stroke is not None: out.append(stroke)
    return out


def _colour_sweeps(m, bw, bh, max_paths=140):
    """Back-and-forth strokes through the whole element (top to bottom; left to right for wide, text-like elements)."""
    region = cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8)).astype(bool)
    thin = min(bw, bh) <= 36                  # an underline, a bar: ONE stroke along its length
    cols = (bh > bw) if thin else bw > 2.2 * bh   # text-like: sweep left to right
    R = region.T if cols else region          # rows are then "scan lines"
    L, W = R.shape                            # L = length across scan lines, W = length of a scan line
    sp = (L + 1.0) if thin else max(7.0, min(24.0, L / 22.0))
    while True:
        strokes, k, flip = [], 0, False
        y = sp / 2
        while y < L + sp / 2:
            a, b = int(max(0, y - sp / 2)), int(min(L, y + sp / 2 + 1))
            row = R[a:b].any(axis=0)
            idx = np.nonzero(row)[0]
            if idx.size:
                runs, s0, prev = [], idx[0], idx[0]
                for v in idx[1:]:
                    if v - prev > 2.5 * sp: runs.append((s0, prev)); s0 = v
                    prev = v
                runs.append((s0, prev))
                if flip: runs = [(e, s) for s, e in reversed(runs)]
                for s, e in runs:
                    p0, p1 = ((s, y), (e, y)) if not cols else ((y, s), (y, e))
                    strokes.append([p0, p1])
                flip = not flip
            y += sp
        if len(strokes) <= max_paths or sp > 120: break
        sp *= 1.25
    if not strokes:
        return [], round(sp * 1.5, 1)
    chain = []                                  # ONE continuous snake: the pen never leaves the paper while colouring
    for st in strokes:
        chain.extend(st)
    return [chain], round(sp * 1.5, 1)


def reveal_paths(img, mask, bbox, max_segments=400, max_paths=160):
    """Returns the stroke plan for one element, in image coordinates:
    {"v": 2, "w": outline stroke width, "paths": [outline strokes], "cw": colour stroke width, "cpaths": [colour sweeps],
     "edgeFirst": True when the element has no dark ink and the outline strokes reveal its colour instead}"""
    x0, y0, bw, bh = bbox
    crop = img[y0:y0 + bh, x0:x0 + bw]
    m = mask[y0:y0 + bh, x0:x0 + bw]
    ink = ink_mask(crop, m)
    edge_first = bool(ink.sum() < 0.01 * m.sum())
    if edge_first:  # no dark outline: trace the shape's own edge instead
        er = cv2.erode(m.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
        ink = m & ~er
    ink = cv2.morphologyEx(ink.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8)).astype(bool)
    dist = cv2.distanceTransform(ink.astype(np.uint8), cv2.DIST_L2, 3)
    sk = _thin(ink)
    thick = float(np.median(dist[sk].astype(np.float64)) * 2) if sk.any() else 3.0
    width = float(min(36, max(6, 2.4 * thick)))

    lines = []
    for pl in _trace(sk):
        s = cv2.approxPolyDP(np.array(pl, np.int32).reshape(-1, 1, 2), 1.5, False).reshape(-1, 2).tolist()
        if len(s) >= 2 and _length(s) >= 1.0:   # keep even tiny pieces: dropping them leaves holes in a long smooth line
            lines.append(s)
    # Drop tiny spurs (short branches ending in a free tip next to a junction): they only break one smooth line into many pieces.
    cnt = {}
    for pl in lines:
        for e in (pl[0], pl[-1]):
            k = (round(e[0] / 3), round(e[1] / 3)); cnt[k] = cnt.get(k, 0) + 1
    def near_cnt(e):
        return max(cnt.get((round(e[0] / 3) + dx, round(e[1] / 3) + dy), 0) for dx in (-1, 0, 1) for dy in (-1, 0, 1))
    lines = [pl for pl in lines if not (_length(pl) < 3 * thick + 6 and (near_cnt(pl[0]) >= 3) != (near_cnt(pl[-1]) >= 3) and min(near_cnt(pl[0]), near_cnt(pl[-1])) <= 1)] or lines
    if len(lines) > max_segments:
        lines = sorted(lines, key=_length, reverse=True)[:max_segments]
    strokes = _order_outline(lines, sk, bh)
    if len(strokes) > max_paths:
        strokes = sorted(strokes, key=_length, reverse=True)[:max_paths]
    colour_px = int((m & ~cv2.dilate(ink.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)).sum())
    has_colour = edge_first or colour_px >= 0.05 * m.sum()
    csw, cw = _colour_sweeps(m, bw, bh) if has_colour else ([], 10.0)
    return {
        "v": 2, "w": round(width, 1), "edgeFirst": edge_first, "cw": cw,
        "paths": [{"d": _d(pl, x0, y0), "len": round(_length(pl), 1)} for pl in strokes],
        "cpaths": [{"d": _d(pl, x0, y0), "len": round(_length(pl), 1)} for pl in csw],
    }
