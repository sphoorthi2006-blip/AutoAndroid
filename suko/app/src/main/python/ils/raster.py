"""v2 step 7: keep the ORIGINAL pixels. The panel detector works on a picture reduced to MAX_SIDE; these helpers cut the panels (and label / caption boxes)
out of the full-size upload, so the animation can reveal real pixels instead of a trace. Everything is returned in canvas coordinates
(the reduced picture's), as `box` = [x, y, w, h] floats that place the crop exactly (the crop is cut on whole original pixels)."""
import base64
import cv2
import numpy as np


def _crop_box(bbox, scale, src_w, src_h, pad=0):
    x, y, w, h = bbox
    x0 = max(0, int(np.floor(x / scale)) - pad); y0 = max(0, int(np.floor(y / scale)) - pad)
    x1 = min(src_w, int(np.ceil((x + w) / scale)) + pad); y1 = min(src_h, int(np.ceil((y + h) / scale)) + pad)
    return x0, y0, max(1, x1 - x0), max(1, y1 - y0)


def _canvas_box(c, scale):
    return [round(c[0] * scale, 2), round(c[1] * scale, 2), round(c[2] * scale, 2), round(c[3] * scale, 2)]


def _png_url(arr):
    ok, buf = cv2.imencode(".png", arr, [cv2.IMWRITE_PNG_COMPRESSION, 3])
    return "data:image/png;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def panel_raster(src, mask, bbox, scale, ink=False):
    """src = full-size BGR upload; mask = bool mask of the panel (outline + inside) on the reduced picture; bbox/scale in canvas terms
    (scale = reduced / original, 1.0 when not reduced). Returns {"href": PNG data URL (RGBA, transparent outside the panel), "box": [x,y,w,h], "size": [w,h]}."""
    sh, sw = src.shape[:2]
    c = _crop_box(bbox, scale, sw, sh)
    ox, oy, cw, ch = c
    x, y, w, h = bbox
    m = np.zeros((max(1, int(h)), max(1, int(w))), np.uint8)
    mm = mask[y:y + h, x:x + w].astype(np.uint8) * 255
    m[:mm.shape[0], :mm.shape[1]] = mm
    # place the reduced mask on the crop's own grid (the crop may differ from the bbox by a rounding pixel)
    cx0, cy0 = ox * scale - x, oy * scale - y
    M = np.array([[1 / scale, 0, -cx0 / scale], [0, 1 / scale, -cy0 / scale]], np.float32)
    alpha = cv2.warpAffine(m, M, (cw, ch), flags=cv2.INTER_LINEAR, borderValue=0) if scale != 1.0 else m[int(round(cy0)):int(round(cy0)) + ch, int(round(cx0)):int(round(cx0)) + cw]
    if alpha.shape != (ch, cw):
        alpha = cv2.resize(alpha, (cw, ch), interpolation=cv2.INTER_LINEAR)
    rgb = src[oy:oy + ch, ox:ox + cw, :3]
    if ink:   # 0.16 line art: the SAME pixels, but only where they are dark (soft ramp), so the line pass and the colour pass match exactly
        mx = rgb.max(axis=2).astype(np.float32)
        la = np.clip((160.0 - mx) / 70.0, 0, 1)
        k = max(7, int(0.012 * max(src.shape[:2])) | 1)             # dark FILLS (hair, shadows) wider than a pen line belong to the colour pass, not the line art
        dark = (mx < 130).astype(np.uint8)
        blob = cv2.dilate(cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
        la = la * (1 - blob)
        alpha = (alpha.astype(np.float32) * la).astype(np.uint8)
        if (alpha > 128).sum() < 0.01 * max(1, (mask[y:y + h, x:x + w]).sum() / (scale * scale)):
            return None   # no real line art (a painted panel): the front end draws it in one pass
    rgba = np.dstack([rgb, alpha])
    return {"href": _png_url(rgba), "box": _canvas_box(c, scale), "size": [cw, ch]}


def _box_fill(crop):
    """Step 11: the box's own colour (median of a ring 25-35% in from each edge, so the dark outline and the lettering are mostly missed), as #rrggbb.
    The label / caption box is redrawn with this colour under the handwriting."""
    import numpy as np
    h, w = crop.shape[:2]
    m = np.zeros((h, w), bool)
    y0, y1, x0, x1 = int(h * 0.12), max(int(h * 0.12) + 1, int(h * 0.3)), int(w * 0.04), max(int(w * 0.04) + 1, int(w * 0.96))
    m[y0:y1, x0:x1] = True
    m[max(0, h - y1):max(1, h - y0), x0:x1] = True
    px = crop[m] if m.any() else crop.reshape(-1, 3)
    r, g, b = (int(v) for v in np.median(px, axis=0))
    return "#%02x%02x%02x" % (r, g, b)


def box_raster(src, bbox, scale, pad=1):
    """A plain rectangular crop (yellow label, caption box) of the full-size upload, opaque."""
    sh, sw = src.shape[:2]
    c = _crop_box(bbox, scale, sw, sh, pad=pad)
    ox, oy, cw, ch = c
    crop = src[oy:oy + ch, ox:ox + cw, :3]
    return {"href": _png_url(crop), "box": _canvas_box(c, scale), "size": [cw, ch], "fill": _box_fill(crop)}


def frame_cutout(img, src, frame, scale):
    """0.15.2: the picture's black frame (ring only, transparent inside) cut from the ORIGINAL upload, same format as panel_raster. img = the reduced picture the
    frame box was found on, src = full-size upload, frame = [x, y, w, h]. The detected box may lie in the middle of the stroke, so the cut reaches a little outside it."""
    h, w = img.shape[:2]
    fx, fy, fw, fh = [int(v) for v in frame]
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    mid = g[fy:fy + fh, fx + fw // 2]; lim = min(len(mid), max(8, fh // 6)); a0 = 0
    while a0 < lim and mid[a0] >= 110: a0 += 1                       # the stroke starts a few pixels in from the box edge
    t = a0
    while t < lim and mid[t] < 110: t += 1                           # ... and ends where the dark run ends
    t = int(np.clip(t, 3, max(3, min(fw, fh) // 8)))
    pd = 2 * t + 2
    x0, y0, x1, y1 = max(0, fx - pd), max(0, fy - pd), min(w, fx + fw + pd), min(h, fy + fh + pd)
    ring = np.zeros(g.shape, bool); ring[y0:y1, x0:x1] = True; ring[fy + t + 2:fy + fh - t - 2, fx + t + 2:fx + fw - t - 2] = False
    ring &= cv2.dilate((g < 110).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    return panel_raster(src, ring, [x0, y0, x1 - x0, y1 - y0], scale) if ring.any() else None
