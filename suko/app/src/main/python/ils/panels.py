"""Panel detector (v0.15 step 4): finds the panels (circles, rectangles, comic panels) drawn with a THICK BLACK OUTLINE on white.

How it works (no colour, no rows):
  1. keep only thick dark strokes (an opening removes text, thin arrows and small details);
  2. every closed outline is a ring; the hole inside a ring is a panel's inside;
  3. the outer frame of the picture is ignored (a ring covering nearly the whole picture);
  4. a ring inside another panel is kept only when it looks like an inset panel (big enough, as thick as its parent),
     so icons drawn inside a panel are not mistaken for panels;
  5. small yellow boxes (the label under a panel, the caption box in its corner) are not panels.
Returns the panels in no particular order: reading order comes from the JSON (step 6). segment() stays as the fallback."""
import cv2
import numpy as np


def _is_yellow_box(img, inside, area_ratio):
    """A flat yellow box (label / caption box). Panels are bigger or not yellow."""
    if area_ratio > 0.06:
        return False
    px = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)[inside]
    if not len(px):
        return False
    return 18 <= np.median(px[:, 0]) <= 38 and np.median(px[:, 1]) > 90 and np.median(px[:, 2]) > 150


def _classify(cnt):
    """circle / rectangle / polygon for one inside contour."""
    a, per = cv2.contourArea(cnt), cv2.arcLength(cnt, True)
    if a <= 0 or per <= 0:
        return "polygon"
    _, _, bw, bh = cv2.boundingRect(cnt)
    circ = 4 * np.pi * a / (per * per)
    fill = a / float(bw * bh)
    if circ > 0.85 and 0.8 < bw / float(bh) < 1.25 and fill < 0.86:
        return "circle"
    if fill > 0.9:                      # rectangles, also with rounded corners
        return "rectangle"
    return "polygon"


def detect_panels(img, min_area_ratio=0.02, stroke=3, virtual_frame=False):
    """img: BGR uint8. Returns {"panels": [...], "frame": bbox or None}.
    Each panel: {kind "panel", shape, mask (full-size bool: outline + inside), bbox (x,y,w,h) of the outline, inner (x,y,w,h) of the inside,
                 thickness, area, color (inside mean, hex), parent (index of the panel it sits in, or None), inset (bool)}."""
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    dark = (gray < 100).astype(np.uint8) * 255
    k = max(3, int(stroke))
    thick = cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    thick = cv2.morphologyEx(thick, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)))
    if virtual_frame:                   # panels that run to the edge of the picture: close them with a frame
        cv2.rectangle(thick, (0, 0), (w - 1, h - 1), 255, 2 * k + 2)
    dt = cv2.distanceTransform((thick > 0).astype(np.uint8), cv2.DIST_L2, 3)
    cnts, hier = cv2.findContours(thick, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
    if hier is None:
        return {"panels": [], "frame": None, "frame_ring": None}
    hier = hier[0]

    def depth(i):
        d = 0
        while hier[i][3] != -1:
            i, d = hier[i][3], d + 1
        return d
    area_img = float(h * w)
    cand, frame, frame_ring = [], None, None
    for i, c in enumerate(cnts):
        dp = depth(i)
        if dp % 2 == 0:                 # an outer contour of a ring; its holes (odd depth) are the panel insides
            continue
        outer = cnts[hier[i][3]]
        ox, oy, ow, oh = cv2.boundingRect(outer)
        hx, hy, hw, hh = cv2.boundingRect(c)
        if dp == 1 and ow >= 0.88 * w and oh >= 0.88 * h and hw >= 0.9 * ow and hh >= 0.9 * oh:
            frame = (ox, oy, ow, oh)    # the picture's own frame (outermost ring, inside is almost as big): not a panel
            fr = np.zeros((h, w), np.uint8); cv2.drawContours(fr, [outer], -1, 255, -1); cv2.drawContours(fr, [c], -1, 0, -1)
            frame_ring = fr > 0
            continue
        a = cv2.contourArea(c)
        if a < min_area_ratio * area_img:
            continue
        fm = np.zeros((h, w), np.uint8); cv2.drawContours(fm, [outer], -1, 255, -1)
        inside = np.zeros((h, w), np.uint8); cv2.drawContours(inside, [c], -1, 255, -1)
        if _is_yellow_box(img, inside > 0, a / area_img):
            continue
        ring = ((fm > 0) & (inside == 0))[oy:oy + oh, ox:ox + ow]
        v = dt[oy:oy + oh, ox:ox + ow][ring]
        thk = float(2 * np.percentile(v, 95)) if v.size else 0.0
        if thk < 0.8 * k:               # thin line art, not a panel outline
            continue
        ix, iy, iw, ih = cv2.boundingRect(c)
        col = img[inside > 0].mean(axis=0).astype(int)
        grow = int(np.ceil(thk)) + 1    # this panel's own outline only (panels that share a border get half each)
        mask = (cv2.dilate(inside, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * grow + 1, 2 * grow + 1))) > 0) & (fm > 0)
        cand.append({"kind": "panel", "shape": _classify(c), "mask": mask, "bbox": (max(ox, ix - grow), max(oy, iy - grow), min(ox + ow, ix + iw + grow) - max(ox, ix - grow), min(oy + oh, iy + ih + grow) - max(oy, iy - grow)), "inner": (ix, iy, iw, ih),
                     "thickness": round(thk, 1), "area": int(a), "color": "#%02x%02x%02x" % (col[2], col[1], col[0]), "parent": None, "inset": False})
    if frame is None:                   # frame merged with panels that share its border (stacked comics): its stroke network is the frame ring
        big = [(cv2.contourArea(cnts[i]), i) for i in range(len(cnts)) if hier[i][3] == -1 and cv2.boundingRect(cnts[i])[2] >= 0.88 * w and cv2.boundingRect(cnts[i])[3] >= 0.88 * h]
        if big:
            idx = max(big)[1]
            fr = np.zeros((h, w), np.uint8); cv2.drawContours(fr, [cnts[idx]], -1, 255, -1)
            for j in range(len(cnts)):
                if hier[j][3] == idx:
                    cv2.drawContours(fr, [cnts[j]], -1, 0, -1)
            frame, frame_ring = cv2.boundingRect(cnts[idx]), fr > 0
    # nesting: a candidate inside a bigger one is an inset only if it is big and as thick as its parent
    keep = []
    for i, p in enumerate(cand):
        cx, cy = p["inner"][0] + p["inner"][2] // 2, p["inner"][1] + p["inner"][3] // 2
        par = None
        for j, q in enumerate(cand):
            if i != j and q["area"] > p["area"] and q["mask"][cy, cx] and (par is None or q["area"] < cand[par]["area"]):
                par = j
        if par is None:
            keep.append(i); continue
        q = cand[par]
        if p["area"] >= 0.02 * area_img and p["area"] <= 0.55 * q["area"] and p["thickness"] >= 0.75 * q["thickness"]:
            p["parent"], p["inset"] = par, True
            keep.append(i)
    remap = {old: new for new, old in enumerate(keep)}
    panels = [cand[i] for i in keep]
    for p in panels:
        p["parent"] = remap.get(p["parent"]) if p["parent"] is not None else None
    return {"panels": panels, "frame": frame, "frame_ring": frame_ring}


def trim_margin(img):
    """A picture with a margin OUTSIDE its black frame (a checkerboard or a grey rim from an upscaler, a screenshot, a shadow) breaks the ring and
    blob detectors, which then take the frame for the only panel. When a long dark rectangle (the frame) leaves more than 1.5% of the picture on a
    side, everything outside it is painted white. Coordinates do not change. Returns img itself when there is no such margin."""
    H, W = img.shape[:2]
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    dark = g < 70
    cols = np.where(dark.mean(axis=0) >= 0.5)[0]; rows = np.where(dark.mean(axis=1) >= 0.5)[0]
    if len(cols) < 2 or len(rows) < 2: return img
    x0, x1, y0, y1 = int(cols[0]), int(cols[-1]), int(rows[0]), int(rows[-1])
    if x1 - x0 < 0.6 * W or y1 - y0 < 0.6 * H: return img
    if min(dark[y0, x0:x1 + 1].mean(), dark[y1, x0:x1 + 1].mean(), dark[y0:y1 + 1, x0].mean(), dark[y0:y1 + 1, x1].mean()) < 0.9: return img   # not a closed frame
    if max(x0 / W, y0 / H, (W - 1 - x1) / W, (H - 1 - y1) / H) < 0.015: return img
    out = img.copy(); out[:y0] = 255; out[y1 + 1:] = 255; out[:, :x0] = 255; out[:, x1 + 1:] = 255
    return out


def find_panels(img):
    """Tries the thickest stroke first (so thin arrows and text never join the outlines), then thinner ones, then a virtual frame
    for pictures whose panels run to the picture edge. Returns the first result that has panels."""
    img = trim_margin(img)
    side = max(img.shape[:2])
    last = {"panels": [], "frame": None, "frame_ring": None}
    for vf in (False, True):
        for f in (0.005, 0.0035, 0.0025):
            last = detect_panels(img, stroke=int(np.clip(round(side * f), 3, 11)), virtual_frame=vf)
            if last["panels"]:
                break
        if last["panels"]:
            break
    blobs = detect_blobs(img)               # step 6b: real pictures; wins only when it finds more panels than the ring detector
    return blobs if len(blobs["panels"]) > len(last["panels"]) else last


# ---------------------------------------------------------------------------------------------------------------------------
# Step 6b: REAL pictures (docs/reference/). Their outlines are not always closed rings of thick black (labels overlap the panels' bottom edge,
# dark artwork touches the outline), so the ring detector above misses panels. Second detector, used only when it finds MORE panels:
#   1. find the label / caption boxes (flat pale-yellow boxes with dark text) and paint them white, with their outline;
#   2. everything that is not background is a blob; the frame is the blob that spans the picture; thin arrows / lettering are too small;
#   3. each remaining big blob, filled, is a panel (circle / rectangle / polygon by its convex hull).
# ---------------------------------------------------------------------------------------------------------------------------
def _yellow_boxes(img):
    """Flat pale-yellow boxes with dark lettering (labels, caption boxes) -> list of ((x,y,w,h), filled bool mask). Panel artwork that is merely yellowish is not a box:
    a box is a solid rectangle (fills >80% of its bounding box once the text is filled in) that is wider than tall and holds dark text."""
    h, w = img.shape[:2]
    area_img = float(h * w)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    H, S, V = hsv[:, :, 0], hsv[:, :, 1], hsv[:, :, 2]
    ym = (((H >= 15) & (H <= 36) & (S >= 35) & (S <= 170) & (V >= 195)) | ((H >= 18) & (H <= 38) & (S > 90) & (V > 150))).astype(np.uint8) * 255
    ym = cv2.morphologyEx(ym, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))   # small: a bigger one jumps the box's dark outline and joins it to the artwork
    ym = cv2.morphologyEx(ym, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5)))
    out = []
    for (x, y, bw, bh), m in _comps(ym, 0.0003 * area_img):
        f = _fill(m.astype(np.uint8) * 255) > 0
        a = float(f.sum())
        if a > 0.06 * area_img or a < 0.8 * bw * bh or bw < 1.2 * bh:
            continue
        dk = float(((gray < 110) & f).sum()) / a
        if not (0.03 <= dk <= 0.6):
            continue
        out.append(((x, y, bw, bh), f))
    return out


def _classify_hull(hull, mask_area):
    x, y, bw, bh = cv2.boundingRect(hull)
    ha = cv2.contourArea(hull)
    if ha <= 0:
        return "polygon"
    (_, _), r = cv2.minEnclosingCircle(hull)
    circle_fit = ha / (np.pi * r * r)
    if circle_fit > 0.82 and 0.78 < bw / float(bh) < 1.3 and ha / float(bw * bh) < 0.9:
        return "circle"
    if ha / float(bw * bh) > 0.92 and mask_area / float(bw * bh) > 0.8:
        return "rectangle"
    return "polygon"


def detect_blobs(img, min_area_ratio=0.012):
    h, w = img.shape[:2]
    side, area_img = max(h, w), float(h * w)
    work = img.copy()
    g = max(3, int(round(side * 0.005)))
    for (_, _, _, _), f in _yellow_boxes(img):
        work[cv2.dilate(f.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * g + 3, 2 * g + 3))) > 0] = 255
    bgp = np.concatenate([work[:6, :6].reshape(-1, 3), work[:6, -6:].reshape(-1, 3), work[-6:, :6].reshape(-1, 3), work[-6:, -6:].reshape(-1, 3)])
    bg = np.median(bgp, axis=0)
    nonbg = (np.abs(work.astype(np.int16) - bg).max(axis=2) > 18).astype(np.uint8) * 255
    k = max(3, int(round(side * 0.004)))
    nonbg = cv2.morphologyEx(nonbg, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))   # cuts thin connectors / dashes away
    nonbg = cv2.morphologyEx(nonbg, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    frame, frame_ring, cand = None, None, []
    rest = nonbg.copy()
    for (x, y, bw, bh), m in _comps(nonbg, 0.004 * area_img):
        if bw >= 0.88 * w and bh >= 0.88 * h:
            frame, frame_ring = (x, y, bw, bh), cv2.dilate(m.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
            rest[m] = 0
    # panels joined by an arrow (or by blur / JPEG noise) are split by a big opening: only the cores of the panels survive it; each panel then gets back
    # its own original pixels inside the core's box (so thin rounded corners and the outline are kept, the arrow pieces outside the box are not)
    ro = max(5, int(round(0.02 * side)) // 2 * 2 + 1)
    core = cv2.morphologyEx(rest, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ro, ro)))
    allfill = _fill(rest) > 0
    for (x, y, bw, bh), m in _comps(core, min_area_ratio * area_img):
        if min(bw, bh) < 0.06 * side:
            continue
        clip = np.zeros((h, w), bool); clip[max(0, y - 2):y + bh + 2, max(0, x - 2):x + bw + 2] = True
        f = (_fill(m.astype(np.uint8) * 255) > 0) | (allfill & clip)
        a = float(f.sum())
        if a < min_area_ratio * area_img:
            continue
        cs, _ = cv2.findContours(f.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        c = max(cs, key=cv2.contourArea)
        hull = cv2.convexHull(c)
        shape = _classify_hull(hull, a)
        full = np.zeros((h, w), np.uint8)
        if shape == "circle":
            (ccx, ccy), cr = cv2.minEnclosingCircle(hull)
            cv2.circle(full, (int(round(ccx)), int(round(ccy))), int(round(cr)), 255, -1)
        else:
            cv2.drawContours(full, [hull], -1, 255, -1)
        f = (full > 0) | f
        x, y, bw, bh = cv2.boundingRect(f.astype(np.uint8)); a = float(f.sum())
        dkm = (gray < 90) & f
        dtv = cv2.distanceTransform(dkm.astype(np.uint8), cv2.DIST_L2, 3)
        band = f & ~(cv2.erode(f.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (4 * k + 1, 4 * k + 1))) > 0)
        v = dtv[band & dkm]
        thk = float(max(2.0, 2 * np.percentile(v, 90))) if v.size else float(k)
        t = int(round(thk))
        col = img[f].mean(axis=0).astype(int)
        cand.append({"kind": "panel", "shape": shape, "mask": f, "bbox": (x, y, bw, bh), "inner": (x + t, y + t, max(1, bw - 2 * t), max(1, bh - 2 * t)),
                     "thickness": round(thk, 1), "area": int(a), "color": "#%02x%02x%02x" % (col[2], col[1], col[0]), "parent": None, "inset": False})
    return {"panels": cand, "frame": frame, "frame_ring": frame_ring}


# ---------------------------------------------------------------------------------------------------------------------------
# Step 5: the extras around the panels, found BY POSITION relative to each panel (never by rows):
#   label   = the yellow box right under a panel (roughly centred under it)
#   caption = the yellow narration box inside a panel's corner
#   arrow   = a thin dark stroke next to a panel; from = panel at its tail, to = panel at its head (the head is the heavier end)
#   ribbon  = the heading banner sitting on the frame's top edge (no ribbon = no heading)
# ---------------------------------------------------------------------------------------------------------------------------
def _fill(mask_u8):
    """External contours of a binary mask, filled (text holes inside a box are closed)."""
    cs, _ = cv2.findContours(mask_u8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = np.zeros_like(mask_u8)
    cv2.drawContours(out, cs, -1, 255, -1)
    return out


def _comps(mask_u8, min_area):
    n, lab, st, _ = cv2.connectedComponentsWithStats(mask_u8, connectivity=8)
    return [((st[i][0], st[i][1], st[i][2], st[i][3]), lab == i) for i in range(1, n) if st[i][4] >= min_area]


def _box_of(mask):
    ys, xs = np.nonzero(mask)
    return (int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1))


def find_extras(img, res):
    """img: BGR uint8; res: the result of find_panels(img). Panel ids used below are 1-based positions in res["panels"].
    Returns {"ribbon": {bbox, mask, color}|None, "labels": {id: {bbox, mask}}, "captions": {id: {bbox, mask, corner}},
             "arrows": [{bbox, mask, from, to, head_known}], "unassigned": [{bbox, mask}]}"""
    h, w = img.shape[:2]
    side, area_img = max(h, w), float(h * w)
    panels = res["panels"]
    g = max(3, int(round(side * 0.004)))                       # about one outline width
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    yellow = ((hsv[:, :, 0] >= 18) & (hsv[:, :, 0] <= 38) & (hsv[:, :, 1] > 90) & (hsv[:, :, 2] > 150)).astype(np.uint8) * 255
    yellow = cv2.morphologyEx(yellow, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7)))
    U = np.zeros((h, w), bool)
    for p in panels:
        U |= p["mask"]
    out = {"ribbon": None, "labels": {}, "captions": {}, "arrows": [], "unassigned": []}
    dil = lambda m, r: cv2.dilate(m.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))) > 0
    ring = res.get("frame_ring")
    taken = np.zeros((h, w), bool)                              # pixels explained so far (ribbon, labels, captions)

    # ---- ribbon: coloured (not black, not white) shape outside the panels that sits on the frame's top edge
    colored = ((gray >= 100) & (gray < 238) | (hsv[:, :, 1] > 60)).astype(np.uint8) * 255
    colored = cv2.morphologyEx(colored, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5)))
    colored[U] = 0
    colored = cv2.morphologyEx(colored, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9)))
    fy = res["frame"][1] if res.get("frame") else None
    top_of_panels = min((p["bbox"][1] for p in panels), default=h)
    best = None
    for (x, y, bw, bh), m in _comps(colored, 0.0004 * area_img):
        if bw < 0.15 * w or bh > 0.2 * h:
            continue
        on_edge = (y <= fy + g and y + bh > fy + 2 * g) if fy is not None else (y + bh <= 0.2 * h and y <= 0.08 * h and bw >= 0.25 * w and y + bh <= top_of_panels + g)
        if on_edge and (best is None or bw > best[0][2]):
            best = ((x, y, bw, bh), m)
    if best:
        m = dil(_fill(best[1].astype(np.uint8) * 255) > 0, g + 3)
        col = img[best[1]].mean(axis=0).astype(int)
        out["ribbon"] = {"bbox": _box_of(m), "mask": m, "color": "#%02x%02x%02x" % (col[2], col[1], col[0])}
        taken |= m

    def centred_under(cx, y0, bw_c):
        """the panel this box is a label of: centred under it, just below its bottom edge, not wider than it."""
        bestp = None
        for i, p in enumerate(panels):
            bx, by, bw, bh = p["bbox"]
            gap = y0 - (by + bh)
            if bx <= cx <= bx + bw and -max(2 * g, 0.2 * bh) <= gap <= 0.35 * max(bh, 0.1 * h) and bw_c <= 1.3 * bw and abs(cx - (bx + bw / 2)) <= 0.2 * bw:
                if bestp is None or gap < bestp[0]:
                    bestp = (gap, i)
        return None if bestp is None else bestp[1]

    def smallest_containing(cx, cy):
        c = [(p["area"], i) for i, p in enumerate(panels) if p["mask"][cy, cx]]
        return min(c)[1] if c else None

    # ---- yellow boxes: label (under a panel), caption (inside a panel's corner), or unassigned
    for (x, y, bw, bh), m in [b for b in _yellow_boxes(img) if not taken[b[1]].any() or (b[1] & taken).sum() < 0.5 * b[1].sum()]:
        cx, cy = x + bw // 2, y + bh // 2
        inside = smallest_containing(cx, cy)
        if inside is not None and m.sum() >= 0.5 * panels[inside]["area"]:
            continue                                          # the panel's own yellow fill, not a box
        box = dil(_fill(m.astype(np.uint8) * 255) > 0, g + 3)
        if inside is None:
            k = centred_under(cx, y, bw)
            kind = "label" if k is not None else "unassigned"
            if k is None:                                      # a caption box that sticks out over a panel's corner (circle): its centre is in the panel's box
                for i2, p2 in enumerate(panels):
                    qx, qy, qw, qh = p2["bbox"]
                    if qx <= cx <= qx + qw and qy <= cy <= qy + qh:
                        k, kind = i2, "caption"; break
        else:
            px, py, pw, ph = panels[inside]["inner"]
            k = centred_under(cx, y, bw)                       # a label of the panel above, lying in the next stacked panel
            qx, qy, qw, qh = panels[inside]["bbox"]
            if y + bh >= qy + qh - 2 * g and abs(cx - (qx + qw / 2)) <= 0.25 * qw and bw <= 1.3 * qw:
                k, kind = inside, "label"                      # a label that overlaps the panel's bottom edge (real pictures): reaches the edge, centred
            elif k is not None and k != inside and y >= panels[k]["bbox"][1] + panels[k]["bbox"][3] - 2 * g:
                kind = "label"
            else:
                k, kind = inside, "caption"
        if kind == "label" and (k + 1) in out["labels"]:
            old = out["labels"][k + 1]["bbox"]
            by_, bh_ = panels[k]["bbox"][1], panels[k]["bbox"][3]
            if abs(y - (by_ + bh_)) >= abs(old[1] - (by_ + bh_)):
                kind = "unassigned"
        if kind == "caption" and (k + 1) in out["captions"]:
            kind = "unassigned"
        rec = {"bbox": _box_of(box), "mask": box}
        if kind == "label":
            out["labels"][k + 1] = rec
        elif kind == "caption":
            px, py, pw, ph = panels[k]["inner"]
            rec["corner"] = ("t" if cy < py + ph / 2 else "b") + ("l" if cx < px + pw / 2 else "r")
            out["captions"][k + 1] = rec
        else:
            out["unassigned"].append(rec)
        taken |= box

    # ---- arrows: thin dark strokes next to a panel (outside every panel, the frame and the boxes found above)
    dark = (gray < 100).astype(np.uint8) * 255
    dark[dil(U, 2)] = 0
    if ring is not None:
        dark[dil(ring, 2)] = 0
    dark[taken] = 0
    ak = max(5, int(round(0.02 * side)) | 1)
    dark = cv2.morphologyEx(dark, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ak, ak)))
    near_max = 0.07 * side
    near_taken = dil(taken, 4)
    dists = [cv2.distanceTransform((~p["mask"]).astype(np.uint8), cv2.DIST_L2, 3) for p in panels]
    for (x, y, bw, bh), m in _comps(dark, 40):
        if max(bw, bh) < 0.017 * side or max(bw, bh) > 0.45 * side:
            continue                                           # too small = lettering; spanning the picture = a leftover of the frame, never an arrow
        ys, xs = np.nonzero(m)
        if near_taken[ys, xs].mean() > 0.25:
            continue                                           # what is left of a label / caption box's outline, not an arrow
        d = [float(dt[ys, xs].min()) for dt in dists]
        if not d or min(d) > near_max:
            continue                                           # not next to any panel: lettering, not an arrow
        pts = np.stack([xs, ys], 1).astype(np.float32)
        c0 = pts.mean(0)
        ev = np.linalg.eigh(np.cov((pts - c0).T))[1][:, -1]
        proj = (pts - c0) @ ev
        a_pt, b_pt = pts[proj.argmin()], pts[proj.argmax()]
        r = 0.03 * side
        na = int((np.linalg.norm(pts - a_pt, axis=1) < r).sum()); nb = int((np.linalg.norm(pts - b_pt, axis=1) < r).sum())
        known = max(na, nb) > 1.25 * min(na, nb)
        head, tail = (a_pt, b_pt) if na > nb else (b_pt, a_pt)

        def nearest(pt):
            dd = [(float(dt[int(pt[1]), int(pt[0])]), i) for i, dt in enumerate(dists)]
            v, i = min(dd)
            return i + 1 if v <= near_max else None
        to, fr_ = nearest(head), nearest(tail)
        if to == fr_:                                          # both ends at one panel: keep the nearer end only
            fr_ = None
        out["arrows"].append({"bbox": (int(x), int(y), int(bw), int(bh)), "mask": dil(m, 1), "from": fr_, "to": to, "head_known": bool(known)})
    return out


def analyze(img):
    """find_panels + find_extras in one call. Returns the panel result with an "extras" key."""
    img = trim_margin(img)
    res = find_panels(img)
    res["extras"] = find_extras(img, res) if res["panels"] else None
    return res
