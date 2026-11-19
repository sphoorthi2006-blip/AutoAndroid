"""Vectorization step: turns one masked element into editable SVG paths (VTracer)."""
import re
import cv2
import numpy as np
try:
    import vtracer   # not available on Android: the traced SVG groups are then empty (the app uses the ORIGINAL-pixel raster crops, raster=1)
except ImportError:
    vtracer = None
from .ink import ink_mask


def _quantize(crop, m, k, f=1.0):
    """Reduce an element to k flat colors so the SVG has few, clean, recolorable shapes."""
    smooth = cv2.bilateralFilter(crop, _odd(5 * f) if f > 1 else 5, 40, 5 * f)
    pixels = smooth[m].reshape(-1, 3).astype(np.float32)
    if len(pixels) < k * 20:
        return crop
    k = min(k, len(pixels))
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 0.5)
    _, labels, centers = cv2.kmeans(pixels, k, None, crit, 2, cv2.KMEANS_PP_CENTERS)
    out = crop.copy()
    out[m] = centers[labels.flatten()].astype(np.uint8)
    # Remove isolated label noise inside the element
    out = cv2.medianBlur(out, 5 if f >= 1.8 else 3)
    out[~m] = crop[~m]
    return out


def _odd(n):
    n = max(1, int(round(n)))
    return n if n % 2 else n + 1


def hires_crop(hi, img_shape, mask, bbox):
    """Cuts the element out of the sharp high-resolution copy `hi` of the picture.
    Segmentation works on the normal-size picture (`img_shape`); this returns the same region at higher resolution:
    (crop, mask, sx, sy) where sx, sy = work pixels per hi-res pixel (used to scale the traced SVG back)."""
    x, y, w, h = bbox
    H, W = img_shape[:2]
    fy, fx = hi.shape[0] / H, hi.shape[1] / W
    x0, y0 = int(round(x * fx)), int(round(y * fy))
    x1, y1 = max(x0 + 1, int(round((x + w) * fx))), max(y0 + 1, int(round((y + h) * fy)))
    crop = hi[y0:y1, x0:x1]
    ch, cw = crop.shape[:2]
    m255 = cv2.resize(mask[y:y + h, x:x + w].astype(np.uint8) * 255, (cw, ch), interpolation=cv2.INTER_LINEAR)
    f = max(1.0, cw / w)
    k = _odd(f * 4)   # the canvas-size mask is blocky: smooth it, and let it grow by a fraction of a pixel so edge outlines are not clipped
    m = cv2.GaussianBlur(m255, (k, k), f * 1.2) > 50
    return crop, m, w / cw, h / ch


def _wrap(inner, ident, x, y, sx, sy):
    sc = "" if (abs(sx - 1) < 1e-6 and abs(sy - 1) < 1e-6) else f" scale({sx:.6f},{sy:.6f})"
    return f'<g id="{ident}" transform="translate({x},{y}){sc}">{inner}</g>'


def _paths(rgba, **kw):
    if vtracer is None:
        return ""
    ok, png = cv2.imencode(".png", rgba)
    svg = vtracer.convert_raw_image_to_svg(png.tobytes(), img_format="png", colormode="color", hierarchical="stacked", mode="spline", **kw)
    return "".join(re.findall(r"<path.*?/>", svg, flags=re.S))


def element_layers(img, mask, bbox, layer_id, colors=8, hi=None):
    """Splits one element into an INK layer (dark line art only) and a COLOUR layer (colour only, ink painted out),
    like the layers VideoScribe images are made of. Drawn ink-over-colour they give back the original artwork."""
    x, y, w0, h0 = bbox
    if hi is not None:   # trace from the sharp high-resolution copy, then scale the SVG back to canvas coordinates
        crop, m, sx, sy = hires_crop(hi, img.shape, mask, bbox)
        h, w = crop.shape[:2]
    else:
        w, h, sx, sy = w0, h0, 1.0, 1.0
        crop = img[y:y + h, x:x + w]
        m = mask[y:y + h, x:x + w]
    f = max(1.0, 1.0 / min(sx, sy))              # how much sharper than the canvas the trace source is
    ink = ink_mask(crop, m)
    if f > 1.2:   # round off the stair-steps of the enlarged mask so outlines trace as smooth curves
        k = _odd(f * 2)
        ink = (cv2.GaussianBlur(ink.astype(np.uint8) * 255, (k, k), f * 0.9) > 127) & m
    if ink.sum() < 0.01 * m.sum():
        return {"ink": "", "color": ""}          # no dark line art: the frontend uses the plain element instead
    core = crop.max(axis=2) < 80
    col = crop[ink & core].mean(axis=0) if (ink & core).any() else crop[ink].mean(axis=0)
    ink_rgba = np.zeros((h, w, 4), np.uint8)
    ink_rgba[ink] = (int(col[0]), int(col[1]), int(col[2]), 255)
    ink_svg = _paths(ink_rgba, filter_speckle=max(3, int(round(3 * f))), color_precision=6, layer_difference=16, corner_threshold=60 if f <= 1.2 else 85,
                     length_threshold=min(10.0, 4.0 * f), splice_threshold=45, path_precision=2)
    grow = cv2.dilate(ink.astype(np.uint8), np.ones((_odd(3 * f), _odd(3 * f)), np.uint8), iterations=1)
    painted = cv2.inpaint(crop, grow * 255, 3 * f, cv2.INPAINT_TELEA)
    painted = _quantize(painted, m, colors, f)
    rgba = cv2.cvtColor(painted, cv2.COLOR_BGR2BGRA)
    rgba[~m] = (0, 0, 0, 0)
    col_svg = _paths(rgba, filter_speckle=max(10, int(round(10 * f))), color_precision=5, layer_difference=32, corner_threshold=60 if f <= 1.2 else 85,
                     length_threshold=min(10.0, 4.0 * f), splice_threshold=45, path_precision=2)
    wrap = lambda inner, tag: _wrap(inner, f"{layer_id}-{tag}", x, y, sx, sy)
    return {"ink": wrap(ink_svg, "ink"), "color": wrap(col_svg, "color")}


def element_to_svg_group(img, mask, bbox, layer_id, colors=8, hi=None):
    x, y, w0, h0 = bbox
    if hi is not None:
        crop, m, sx, sy = hires_crop(hi, img.shape, mask, bbox)
        h, w = crop.shape[:2]
    else:
        w, h, sx, sy = w0, h0, 1.0, 1.0
        crop = img[y:y + h, x:x + w]
        m = mask[y:y + h, x:x + w]
    f = max(1.0, 1.0 / min(sx, sy))
    if vtracer is None:
        return _wrap("", layer_id, x, y, sx, sy)
    crop = _quantize(crop, m, colors, f)
    rgba = cv2.cvtColor(crop, cv2.COLOR_BGR2BGRA)
    rgba[~m] = (0, 0, 0, 0)
    ok, png = cv2.imencode(".png", rgba)
    svg = vtracer.convert_raw_image_to_svg(
        png.tobytes(), img_format="png", colormode="color", hierarchical="stacked",
        mode="spline", filter_speckle=max(10, int(round(10 * f))), color_precision=5, layer_difference=32,
        corner_threshold=60, length_threshold=min(10.0, 4.0 * f), splice_threshold=45, path_precision=2,
    )
    inner = "".join(re.findall(r"<path.*?/>", svg, flags=re.S))
    # Same coordinate space as the canvas image: translate by the crop offset, scale the sharp trace back down.
    return _wrap(inner, layer_id, x, y, sx, sy)
