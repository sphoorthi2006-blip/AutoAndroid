"""Bezalel phone engine: the desktop backend's /api/process and /api/panels as plain functions (no web server), run inside the app by Chaquopy.
Copied from Image Layer Studio v0.15.7 backend/app/main.py; only the FastAPI wrapping was removed. Do not change the maths here: keep it equal to the desktop."""
import base64, json
import cv2
import numpy as np
from .segment import segment
from .panels import analyze
from .raster import panel_raster, box_raster, label_raster, frame_cutout
from .vectorize import element_to_svg_group, element_layers
from .reveal import reveal_paths

MAX_SIDE = 1600       # size the picture is analysed and laid out at (canvas coordinates; unchanged, so grouping and reveal order stay the same)
TRACE_MIN = 2800      # the tracing copy is made at least this long on its longest side (small images are enlarged)
TRACE_MAX = 4200      # ... and never longer than this (keeps big uploads fast)


def _trace_copy(src, work):
    """Sharp copy of the picture used only for tracing the vector shapes. Bigger/upscaled uploads keep their detail here
    instead of being shrunk to MAX_SIDE; small uploads are enlarged smoothly so outlines get many more pixels to follow."""
    side = max(src.shape[:2])
    target = min(TRACE_MAX, max(TRACE_MIN, side))
    if target <= max(work.shape[:2]):
        return None
    f = target / side
    interp = cv2.INTER_LANCZOS4 if f > 1 else cv2.INTER_AREA
    return cv2.resize(src, (int(round(src.shape[1] * f)), int(round(src.shape[0] * f))), interpolation=interp)


def _decode(data):
    """Bytes -> BGR uint8 picture on white (PNG/JPG/WebP, transparent, 16-bit, odd variants). Raises HTTP 400 when unreadable."""
    raw = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if raw is None:   # second chance: Pillow reads some files OpenCV cannot (odd JPEG/WebP variants, CMYK, palette PNG)
        try:
            import io
            from PIL import Image, ImageOps
            pim = ImageOps.exif_transpose(Image.open(io.BytesIO(data)))
            pim = pim.convert("RGBA")
            rgba = np.array(pim)
            raw = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGRA)
        except Exception:
            raw = None
    img = None
    if raw is not None:
        if raw.ndim == 2:
            raw = cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
        if raw.dtype != np.uint8:
            raw = (raw / 257.0).astype(np.uint8) if raw.dtype == np.uint16 else raw.astype(np.uint8)
        if raw.shape[2] == 4:   # transparent PNG/WebP: put it on white (a transparent background must not become black)
            a = raw[:, :, 3:4].astype(np.float32) / 255.0
            img = (raw[:, :, :3] * a + 255.0 * (1 - a)).astype(np.uint8)
        else:
            img = raw[:, :, :3]
    if img is None:
        raise ValueError("Could not read this image. Use PNG, JPG or WebP.")
    return img


def _pen(img, mask, bbox):
    """Stroke plan for one panel/arrow (same planner as the old element path). None when it fails: the front end then uses the plain sweep."""
    try:
        return reveal_paths(img, mask, tuple(int(v) for v in bbox), max_segments=4000, max_paths=2500)
    except Exception:
        return None


def process(data, raster=1):
    """Bytes of a picture -> the project dict (same as the desktop POST /api/process)."""
    img = _decode(data)
    src = img
    h, w = img.shape[:2]
    scale = min(1.0, MAX_SIDE / max(h, w))
    if scale < 1.0:
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
        h, w = img.shape[:2]
    hi = _trace_copy(src, img)
    elements, bg = segment(img)
    k = w / src.shape[1]                                              # reduced / original, for the raster crops
    layers = []
    for i, e in enumerate(elements, start=1):
        lid = f"layer-{i}"
        group = element_to_svg_group(img, e["mask"], e["bbox"], lid, hi=hi)
        x, y, bw, bh = e["bbox"]
        parts = element_layers(img, e["mask"], e["bbox"], lid, hi=hi)
        layers.append({"id": lid, "name": f"Element {i}", "kind": e.get("kind", "icon"), "group": group, "inkGroup": parts["ink"], "colorGroup": parts["color"],
                       "bbox": {"x": x, "y": y, "w": bw, "h": bh},
                       "reveal": _pen(img, e["mask"], e["bbox"]) if raster else reveal_paths(img, e["mask"], e["bbox"])})
        if raster:                                                    # 0.16: the element's ORIGINAL pixels (any picture, not only comic panels); the traced group stays for the other features
            rc = panel_raster(src, e["mask"], e["bbox"], k)
            layers[-1]["rasterHref"], layers[-1]["rasterBox"] = rc["href"], rc["box"]
            ir = panel_raster(src, e["mask"], e["bbox"], k, ink=True)
            if ir: layers[-1]["inkRasterHref"] = ir["href"]
            if not layers[-1]["reveal"]: layers[-1]["reveal"] = reveal_paths(img, e["mask"], e["bbox"])
    out = {"width": w, "height": h, "backgroundColor": "#%02x%02x%02x" % tuple(bg), "layers": layers}
    if raster:   # 0.16: the whole upload (JPEG), faded in LAST behind the elements, so a textured or coloured background comes back exactly
        ok, buf = cv2.imencode(".jpg", src, [cv2.IMWRITE_JPEG_QUALITY, 93])
        if ok: out["bgRasterHref"] = "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode("ascii")
    return _plain(out)


def _plain(o):
    """numpy numbers (int32, float32, bool_) -> plain Python ones, so FastAPI can turn the answer into JSON."""
    if isinstance(o, dict): return {k: _plain(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)): return [_plain(v) for v in o]
    if isinstance(o, np.generic): return o.item()
    return o


def panels(data, raster=1, pen=1):
    """v0.15: the panels (thick black outlines) of a picture, in the same canvas coordinates as /api/process. No masks are sent, only
    each panel's shape, boxes, outline polygon, label box and caption box, plus the ribbon and the arrows (from/to = panel ids). An empty list means: no panels found, use /api/process (segment) instead.
    Step 7: with raster=1 (default) every panel, label and caption box also carries `raster` = {href (PNG data URL cut from the ORIGINAL upload), box, size}; raster=0 leaves it out."""
    img = _decode(data)
    src = img                                                        # the ORIGINAL upload: the rasters are cut from it at full size
    h, w = img.shape[:2]
    scale = min(1.0, MAX_SIDE / max(h, w))
    if scale < 1.0:
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
        h, w = img.shape[:2]
    k = w / src.shape[1]   # reduced / original (1.0 when the upload was not reduced)
    r = analyze(img)
    ex = r.get("extras") or {"ribbon": None, "labels": {}, "captions": {}, "arrows": [], "unassigned": []}
    out = []
    for i, p in enumerate(r["panels"], start=1):
        cs, _ = cv2.findContours(p["mask"].astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        c = max(cs, key=cv2.contourArea)
        poly = cv2.approxPolyDP(c, 0.003 * cv2.arcLength(c, True), True)[:, 0, :].tolist()
        out.append({"id": i, "shape": p["shape"], "bbox": list(p["bbox"]), "inner": list(p["inner"]), "thickness": p["thickness"], "color": p["color"],
                    "inset": p["inset"], "parent": None if p["parent"] is None else p["parent"] + 1, "polygon": poly,
                    "label": {"bbox": list(ex["labels"][i]["bbox"])} if i in ex["labels"] else None,
                    "caption": {"bbox": list(ex["captions"][i]["bbox"]), "corner": ex["captions"][i]["corner"]} if i in ex["captions"] else None})
        # step 5 (real frames): the panel's own label / caption box often overlaps the panel (a label on its bottom edge, a caption in its corner), so its printed
        # pixels were part of the panel crop and were drawn with the picture, BEFORE the label's own turn. The panel is cut without them; the label / caption crop brings them later.
        pm = p["mask"].copy()
        for ex_k in ("labels", "captions"):
            if i in ex[ex_k]: pm &= ~ex[ex_k][i]["mask"]
        if not pm.any(): pm = p["mask"]
        if raster:
            out[-1]["raster"] = panel_raster(src, pm, p["bbox"], k)
            if out[-1]["label"]:                                     # step 7: cut with the label's own mask, never reaching into another panel
                others = np.zeros(img.shape[:2], bool)
                for j, q in enumerate(r["panels"], start=1):
                    if j != i: others |= q["mask"]
                out[-1]["label"]["raster"] = label_raster(src, ex["labels"][i]["mask"], out[-1]["label"]["bbox"], k, others)
            if out[-1]["caption"]:                                   # step 8: same for the caption box (it may stick out over a circle's corner)
                others = np.zeros(img.shape[:2], bool)
                for j, q in enumerate(r["panels"], start=1):
                    if j != i: others |= q["mask"]
                out[-1]["caption"]["raster"] = label_raster(src, ex["captions"][i]["mask"], out[-1]["caption"]["bbox"], k, others)
        if raster and pen:                                           # 0.16: pen-stroke plan (outline strokes + colour sweeps) so the ORIGINAL pixels are drawn by a pen, not a plain sweep
            out[-1]["reveal"] = _pen(img, pm, p["bbox"])
            ir = panel_raster(src, pm, p["bbox"], k, ink=True)
            if ir:
                out[-1]["inkRaster"] = ir
                if isinstance(out[-1].get("reveal"), dict): out[-1]["reveal"]["ink"] = ir.get("share")   # FIX PLAN step 9b
    allp = np.zeros(img.shape[:2], bool)                             # step 8: every panel, so no arrow crop can carry a piece of a panel's border before the panel's own cue
    for q in r["panels"]: allp |= q["mask"]
    arrows = []                                                      # step 10: an arrow is cropped from the original too, so it can be revealed as a unit
    for ai, a in enumerate(ex["arrows"], start=1):
        arrows.append({"id": ai, "bbox": list(a["bbox"]), "from": a["from"], "to": a["to"], "headKnown": a["head_known"]})
        if raster: arrows[-1]["raster"] = label_raster(src, a["mask"], a["bbox"], k, allp)   # the arrow's own mask, minus the panels (was: the opaque bbox rectangle)
        if raster and pen:
            bx, by, bw_, bh_ = [int(v) for v in a["bbox"]]
            am = np.zeros(img.shape[:2], bool); am[by:by + bh_, bx:bx + bw_] = True
            am &= a["mask"] & ~(cv2.dilate(allp.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)   # its pen plan and line art come from the arrow's own pixels, not the panel borders inside its box
            if not am.any(): am[by:by + bh_, bx:bx + bw_] = True
            arrows[-1]["reveal"] = _pen(img, am, a["bbox"])
            ir = panel_raster(src, am, a["bbox"], k, ink=True)
            if ir: arrows[-1]["inkRaster"] = ir
    frame_raster = frame_cutout(img, src, r["frame"], k) if raster and r["frame"] else None   # 0.15.2: the black frame as its own cut-out, so the Image tab can draw it first
    return _plain({"width": w, "height": h, "frame": list(r["frame"]) if r["frame"] else None, "frameRaster": frame_raster, "panels": out,
            "ribbon": {"bbox": list(ex["ribbon"]["bbox"]), "color": ex["ribbon"]["color"]} if ex["ribbon"] else None,
            "arrows": arrows,
            "unassigned": [list(u["bbox"]) for u in ex["unassigned"]]})


def run(method, b64, raster=1):
    """Entry point for Kotlin: method 'process' or 'panels', the picture as base64 text -> JSON text. Errors come back as {"error":..., "detail":...} (the page shows the detail)."""
    try:
        data = base64.b64decode(b64)
        res = process(data, int(raster)) if method == "process" else panels(data, int(raster))
        return json.dumps(_plain(res), separators=(",", ":"))
    except ValueError as e:
        return json.dumps({"error": "unreadable", "detail": str(e)})
    except MemoryError:
        return json.dumps({"error": "memory", "detail": "The phone ran out of memory reading this picture. Try a smaller picture."})
    except Exception as e:
        return json.dumps({"error": "failed", "detail": "Picture analysis failed: %s: %s" % (type(e).__name__, e)})
