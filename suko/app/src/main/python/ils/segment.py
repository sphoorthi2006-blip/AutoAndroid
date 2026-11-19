"""Segmentation step. v0 = classical (no GPU): background color + connected regions.
Later: replace `segment()` with Grounding DINO + SAM 2 returning the same output."""
import cv2
import numpy as np


def _background_color(img):
    border = np.concatenate([img[0], img[-1], img[:, 0], img[:, -1]])
    q = (border // 16).astype(np.int32)
    keys = q[:, 0] * 256 + q[:, 1] * 16 + q[:, 2]
    common = np.bincount(keys).argmax()
    return border[keys == common].mean(axis=0)


def _union(a, b):
    x0, y0 = min(a[0], b[0]), min(a[1], b[1])
    x1, y1 = max(a[0] + a[2], b[0] + b[2]), max(a[1] + a[3], b[1] + b[3])
    return (x0, y0, x1 - x0, y1 - y0)


def _merge_groups(groups, can_merge):
    """groups: list of (box, members, text_like). Merges pairs until no pair can merge."""
    groups = list(groups)
    changed = True
    while changed:
        changed = False
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                a, b = groups[i], groups[j]
                if can_merge(a, b):
                    groups[i] = (_union(a[0], b[0]), a[1] + b[1], a[2] and b[2])
                    del groups[j]
                    changed = True
                    break
            if changed:
                break
    return groups



def _split_connectors(fg, h, w):
    """Separates long thin connectors (arrows, paths, loops) from the solid shapes they touch or surround.
    Solid parts survive an opening with a kernel wider than any stroke; what is left over is line work. A leftover
    piece that is long is a connector and becomes its own element. Its bounding box may be huge, but its MASK only holds
    its own pixels, so it can never swallow what lies inside it. Returns (fg without connectors, [element dicts])."""
    m = max(h, w)
    k = max(9, int(m * 0.02) | 1)                                     # wider than a thick outline (about 8 px at 1600 px)
    solid = cv2.morphologyEx(fg, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    rr = max(5, int(m * 0.0065))                                      # about one outline width: takes the shape's own outline, not an arrowhead that touches it
    reclaim = cv2.dilate(solid, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * rr + 1, 2 * rr + 1))) & fg
    lines = cv2.bitwise_and(fg, cv2.bitwise_not(reclaim))
    n, lab, st, _ = cv2.connectedComponentsWithStats(lines, connectivity=8)
    out, drop = [], np.zeros(fg.shape, bool)
    conn_ids = []
    for i in range(1, n):
        x, y, bw, bh, area = (int(v) for v in st[i])
        longest = max(bw, bh)
        fill = area / float(bw * bh)
        if longest >= 0.14 * m and (fill < 0.45 or longest >= 6 * min(bw, bh)):
            conn_ids.append(i)
    # Arrowheads: the head's arms are separate small line pieces (the tip is joined to the shape it points at). A small line piece
    # lying right next to a connector belongs to it.
    reach = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * k + 1, 2 * k + 1))
    extra = {c: [] for c in conn_ids}
    for i in range(1, n):
        if i in conn_ids:
            continue
        x, y, bw, bh, area = (int(v) for v in st[i])
        if not (0.012 * m <= max(bw, bh) < 0.14 * m) or area < 120:
            continue
        piece = (lab == i)[y:y + bh, x:x + bw]
        for c in conn_ids:
            cx, cy, cw, ch, _a = (int(v) for v in st[c])
            if x + bw < cx - k or x > cx + cw + k or y + bh < cy - k or y > cy + ch + k:
                continue
            near = cv2.dilate(((lab == c)).astype(np.uint8), reach)[y:y + bh, x:x + bw]
            if (near & piece).any():
                extra[c].append(i); break
    for i in conn_ids:
        mask = (lab == i)
        for j in extra[i]:
            mask |= (lab == j)
        ys, xs = np.where(mask)
        x, y = int(xs.min()), int(ys.min())
        drop |= mask
        out.append({"mask": mask, "bbox": (x, y, int(xs.max()) - x + 1, int(ys.max()) - y + 1), "area": int(mask.sum()), "kind": "connector"})
    fg2 = fg.copy()
    fg2[drop] = 0
    return fg2, out


def _attach_labels(groups, boxes, h, w):
    """groups: (box, members, text_like). Every text group that is right next to exactly the nearest icon joins it."""
    icons = [i for i, g in enumerate(groups) if not g[2] and min(g[0][2], g[0][3]) >= 0.04 * min(h, w)]
    texts = [i for i, g in enumerate(groups) if g[2]]
    taken, joins = set(), {}
    cand = []
    for t in texts:
        T = groups[t][0]
        for i in icons:
            I = groups[i][0]
            ov = min(T[0] + T[2], I[0] + I[2]) - max(T[0], I[0])
            if ov < 0.3 * min(T[2], I[2]):
                continue
            gap = max(T[1] - (I[1] + I[3]), I[1] - (T[1] + T[3]), 0)
            if gap <= 0.45 * max(I[2], I[3]):
                cand.append((gap, t, i))
    for gap, t, i in sorted(cand):
        if t in taken or i in joins:
            continue
        joins[i] = t; taken.add(t)
    out = []
    for gi, g in enumerate(groups):
        if gi in taken:
            continue
        if gi in joins:
            t = groups[joins[gi]]
            g = (_union(g[0], t[0]), g[1] + t[1], False)
        out.append(g)
    return out

def segment(img, min_area_ratio=0.00012, tolerance=38):
    """img: BGR uint8. Returns list of dicts {mask (full-size bool), bbox (x,y,w,h)} ordered back->front.
    v0.10.2: pieces are grouped into whole OBJECTS, so every icon is ONE element and every line / label of text is ONE element
    (instead of one element per letter). Each object is then drawn completely before the next one starts."""
    h, w = img.shape[:2]
    bg = _background_color(img)
    diff = np.linalg.norm(img.astype(np.float32) - bg.astype(np.float32), axis=2)
    fg = (diff > tolerance).astype(np.uint8) * 255
    k = max(3, int(min(h, w) * 0.004) | 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, kernel)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, kernel)
    fg, connectors = _split_connectors(fg, h, w)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(fg, connectivity=8)
    ids = [i for i in range(1, n) if stats[i][4] >= 12]          # keep even tiny dots here: they get merged into their object
    boxes = {i: tuple(int(v) for v in stats[i][:4]) for i in ids}
    r = max(4, int(round(max(h, w) * 0.0038)))                   # pieces closer than ~2r pixels belong to the same object
    thick_max = max(12, int(max(h, w) * 0.02))

    def thin_long(b):  # underline, divider: a long thin line stays its own element
        return min(b[2], b[3]) <= thick_max and max(b[2], b[3]) > 12 * max(1, min(b[2], b[3]))

    # Stage 1: pixel-level proximity (dilate, then connected regions). Thin lines are left out so an underline never swallows the text above it.
    keep = np.isin(labels, [i for i in ids if not thin_long(boxes[i])]).astype(np.uint8) * 255
    grown = cv2.dilate(keep, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1)))
    _, glab = cv2.connectedComponents(grown, connectivity=8)
    by_grp = {}
    singles = []
    for i in ids:
        if thin_long(boxes[i]):
            singles.append([i]); continue
        x, y, bw, bh = boxes[i]
        g = int(glab[y + bh // 2, x + bw // 2]) if glab[y + bh // 2, x + bw // 2] else int(glab[labels == i][0])
        by_grp.setdefault(g, []).append(i)
    g1 = []
    for mem in list(by_grp.values()) + singles:
        box = boxes[mem[0]]
        for i in mem[1:]:
            box = _union(box, boxes[i])
        g1.append((box, mem, False))

    # Stage 2: text. Small groups that sit on one line join into one line; lines stacked closely join into one label.
    small = max(60, int(h * 0.1))   # every piece of a text group is small (letters, digits, dots); an icon always has a big piece
    # v0.14.6 heading slide: if everything on the picture fits one band of at most 22% of the height, it is a single line of big lettering
    # (a centred title), so its letters may be taller than the usual limit and still count as text.
    if ids:
        top = min(boxes[i][1] for i in ids); bot = max(boxes[i][1] + boxes[i][3] for i in ids)
        left = min(boxes[i][0] for i in ids); right = max(boxes[i][0] + boxes[i][2] for i in ids)
        if bot - top <= 0.22 * h and right - left >= 0.15 * w and len(ids) >= 3 and not connectors:
            small = max(small, int(0.22 * h))
    g1 = [(b, m, all(boxes[i][3] <= small for i in m) and not thin_long(b)) for b, m, _t in g1]

    def stage2(a, b):
        if not (a[2] and b[2]):
            return False
        A, B = a[0], b[0]
        mh = min(max(boxes[i][3] for i in a[1]), max(boxes[i][3] for i in b[1]))   # letter height, not block height
        sh = min(A[3], B[3])
        # same line: vertical overlap, small horizontal gap
        top, bot = max(A[1], B[1]), min(A[1] + A[3], B[1] + B[3])
        dx = max(0, max(A[0], B[0]) - min(A[0] + A[2], B[0] + B[2]))
        if bot - top >= 0.6 * sh and dx <= 0.9 * mh:
            return True
        # stacked lines: horizontal overlap, small vertical gap
        dy = max(0, max(A[1], B[1]) - min(A[1] + A[3], B[1] + B[3]))
        ov = min(A[0] + A[2], B[0] + B[2]) - max(A[0], B[0])
        return ov >= 0.5 * min(A[2], B[2]) and dy <= 0.5 * mh
    g2 = _merge_groups(g1, stage2)

    # Stage 2b (until v0.14.4): a text group right under an icon was merged into the icon. Since v0.14.5 captions stay separate "text" elements:
    # the Video tab joins each caption to its icon by position (slides.ts) and can replace the caption with handwriting.
    # g2 = _attach_labels(g2, boxes, h, w)

    # Stage 3: a small leftover that lies completely inside a bigger object's box (e.g. a coin outline inside the tree) belongs to it.
    def inside(a, b, pad=2):
        return a[0] >= b[0] - pad and a[1] >= b[1] - pad and a[0] + a[2] <= b[0] + b[2] + pad and a[1] + a[3] <= b[1] + b[3] + pad
    g2 = sorted(g2, key=lambda g: -(g[0][2] * g[0][3]))
    merged = []
    for g in g2:
        for k2, m in enumerate(merged):
            if not m[2] and inside(g[0], m[0]):
                merged[k2] = (m[0], m[1] + g[1], m[2]); break
        else:
            merged.append(g)
    g2 = merged

    out = []
    for gb, mem, _t in g2:
        area = int(sum(stats[i][4] for i in mem))
        if area < h * w * min_area_ratio:
            continue
        out.append({"mask": np.isin(labels, mem), "bbox": tuple(int(v) for v in gb), "area": area,
                    "kind": "text" if _t else "icon"})
    for c in connectors:                                            # arrows, paths, loops: each is its own element and never contains others
        if c["area"] >= h * w * min_area_ratio:
            out.append(c)
    out.sort(key=lambda e: -e["area"])  # large (usually behind) first
    return out, [int(c) for c in bg[::-1]]
