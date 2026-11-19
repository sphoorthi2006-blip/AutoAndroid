"""Run: python3 check_engine.py <path to the folder that contains `ils/`> <reference folder>   (checks the phone engine on the two REAL reference pictures; needs numpy + opencv-python-headless)"""
import sys, json, base64, time
sys.path.insert(0, sys.argv[1])
from ils import engine
ref = sys.argv[2]
fails = 0
def ok(c, m):
    global fails
    print(("PASS " if c else "FAIL ") + m)
    if not c: fails += 1
truth = {"ref1_lost_son_rows.jpg": (6, 3), "ref2_david_rows.png": (5, 3), "ref3_david_margin.png": (5, 3), "ref4_garden_rows.jpg": (5, 0)}
for name, (np_, na) in truth.items():
    b64 = base64.b64encode(open(f"{ref}/{name}", "rb").read()).decode()
    t = time.time(); out = json.loads(engine.run("panels", b64)); dt = time.time() - t
    ok("error" not in out and len(out["panels"]) == np_, f"{name}: {len(out.get('panels', []))} panels (truth {np_}) in {dt:.1f}s")
    ok(len(out.get("arrows", [])) == na, f"{name}: {len(out.get('arrows', []))} arrows (truth {na})")
    ok(all(p.get("raster") and p.get("reveal") is not None for p in out["panels"]), f"{name}: every panel has its original-pixel crop and a pen plan")
# FIX PLAN step 7: a label crop is cut with the label's own shape and never reaches into another panel (alpha > 128 outside every OTHER panel's mask)
import cv2, numpy as np
from ils.raster import label_raster, box_raster
def _alpha(href):
    a = cv2.imdecode(np.frombuffer(base64.b64decode(href.split(",", 1)[1]), np.uint8), cv2.IMREAD_UNCHANGED)
    return a[:, :, 3] if a.ndim == 3 and a.shape[2] == 4 else np.full(a.shape[:2], 255, np.uint8)
for name in truth:
    data = open(f"{ref}/{name}", "rb").read()
    src = engine._decode(data); H, W = src.shape[:2]; sc = min(1.0, engine.MAX_SIDE / max(H, W))
    img = cv2.resize(src, (int(W * sc), int(H * sc)), interpolation=cv2.INTER_AREA) if sc < 1 else src
    res = engine.analyze(img); out = json.loads(engine.run("panels", base64.b64encode(data).decode()))
    leak = shaped = n = 0
    for i, p in enumerate(out["panels"], start=1):
        if not p["label"]: continue
        n += 1; rr = p["label"]["raster"]
        others = np.zeros(img.shape[:2], bool)
        for j, q in enumerate(res["panels"], start=1):
            if j != i: others |= q["mask"]
        bx, by, bw, bh = rr["box"]; x0, y0, w_, h_ = int(round(bx)), int(round(by)), max(1, int(round(bw))), max(1, int(round(bh)))
        a = cv2.resize(_alpha(rr["href"]), (w_, h_), interpolation=cv2.INTER_AREA)
        cm = np.zeros(img.shape[:2], bool)
        ys, xs = slice(max(0, y0), min(img.shape[0], y0 + h_)), slice(max(0, x0), min(img.shape[1], x0 + w_))
        cm[ys, xs] = a[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0] > 128
        leak += int((cm & others).sum()); shaped += int((_alpha(rr["href"]) < 255).any())
    ok(n > 0 and leak == 0, f"{name}: {n} label crops, {leak} pixels inside another panel")
    ok(shaped == n, f"{name}: every label crop is cut with its own shape (not a full rectangle)")
    ok(all(p["label"]["raster"].get("fill", "").startswith("#") for p in out["panels"] if p["label"]), f"{name}: every label crop still has its box colour (fill)")
# FIX PLAN step 8: NOTHING of a panel (its border, its picture) is drawn before its own cue: no crop of another piece (panel, caption, arrow, arrow line art) has a pixel
# (alpha > 128) inside a panel that is not its own; an arrow crop (and its line art) touches NO panel at all.
def _canvas(rr, shape):
    a = cv2.resize(_alpha(rr["href"]), (max(1, int(round(rr["box"][2]))), max(1, int(round(rr["box"][3])))), interpolation=cv2.INTER_AREA)
    x0, y0 = int(round(rr["box"][0])), int(round(rr["box"][1])); cm = np.zeros(shape, bool)
    ys, xs = slice(max(0, y0), min(shape[0], y0 + a.shape[0])), slice(max(0, x0), min(shape[1], x0 + a.shape[1]))
    cm[ys, xs] = a[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0] > 128
    return cm
for name in truth:
    data = open(f"{ref}/{name}", "rb").read()
    src = engine._decode(data); H, W = src.shape[:2]; sc = min(1.0, engine.MAX_SIDE / max(H, W))
    img = cv2.resize(src, (int(W * sc), int(H * sc)), interpolation=cv2.INTER_AREA) if sc < 1 else src
    res = engine.analyze(img); out = json.loads(engine.run("panels", base64.b64encode(data).decode())); shp = img.shape[:2]
    M = [q["mask"] for q in res["panels"]]; U = np.zeros(shp, bool)
    for m in M: U |= m
    bad = n = 0
    for i, p in enumerate(out["panels"], start=1):
        oth = np.zeros(shp, bool)
        for j, m in enumerate(M, start=1):
            if j != i: oth |= m
        for rr in [p["raster"], p["inkRaster"] if p.get("inkRaster") else None, p["caption"]["raster"] if p["caption"] else None]:
            if rr: n += 1; bad += int((_canvas(rr, shp) & oth).sum())
    for a in out["arrows"]:
        for rr in [a["raster"], a.get("inkRaster")]:
            if rr: n += 1; bad += int((_canvas(rr, shp) & U).sum())
    ok(n > 0 and bad == 0, f"{name}: {n} panel / caption / arrow crops, {bad} pixels inside a panel that is not theirs")
# step 5 (real frames): the panel crop carries NOTHING of its own label / caption box (they come later, in their own window), and nothing is lost:
# panel crop + label crop + caption crop still cover the whole panel (>= 99.5% of its mask pixels)
for name in truth:
    data = open(f"{ref}/{name}", "rb").read()
    src = engine._decode(data); H, W = src.shape[:2]; sc = min(1.0, engine.MAX_SIDE / max(H, W))
    img = cv2.resize(src, (int(W * sc), int(H * sc)), interpolation=cv2.INTER_AREA) if sc < 1 else src
    res = engine.analyze(img); ex = res["extras"]; out = json.loads(engine.run("panels", base64.b64encode(data).decode())); shp = img.shape[:2]
    early = 0; worst = 1.0; n = 0
    for i, p in enumerate(out["panels"], start=1):
        pc = _canvas(p["raster"], shp); cover = pc.copy(); boxes = np.zeros(shp, bool)
        for kind in ("label", "caption"):
            if p[kind]:
                boxes |= ex[kind + "s"][i]["mask"]; cover |= _canvas(p[kind]["raster"], shp)
        if not boxes.any(): continue
        n += 1; early += int((pc & boxes).sum())
        pm = res["panels"][i - 1]["mask"]; worst = min(worst, float((cover & pm).sum()) / max(1, int(pm.sum())))
    ok(n > 0 and early == 0, f"{name}: {n} panels with a label / caption: {early} label / caption pixels inside the panel's own crop")
    ok(worst >= 0.995, f"{name}: panel + label + caption crops cover {worst * 100:.2f}% of the panel (nothing lost)")
# synthetic overlap: the dilated label box reaches 3 px into the panel below; the old opaque rectangle copied those pixels, the new crop does not
srcS = np.full((100, 200, 3), 200, np.uint8)
mS = np.zeros((100, 200), bool); mS[40:63, 20:180] = True                      # label box rows 40..62
othS = np.zeros((100, 200), bool); othS[60:100, 0:200] = True                  # panel below starts at row 60 (3 rows inside the label box)
rect = box_raster(srcS, [20, 40, 160, 23], 1.0); shp = label_raster(srcS, mS, [20, 40, 160, 23], 1.0, othS)
def _inside(rr):
    a = _alpha(rr["href"]); bx, by = int(rr["box"][0]), int(rr["box"][1]); c = np.zeros((100, 200), bool); c[by:by + a.shape[0], bx:bx + a.shape[1]] = a > 128; return int((c & othS).sum())
ok(_inside(rect) > 0, f"synthetic: the old opaque rectangle copies {_inside(rect)} px of the panel below (the bug)")
ok(_inside(shp) == 0, f"synthetic: the new label crop copies {_inside(shp)} px of the panel below")
# synthetic arrow: its bounding box (diagonal arrow) covers a corner of a panel; the old opaque box copied it, the arrow's own mask minus the panels does not
mA = np.zeros((100, 200), bool); mA[10:40, 30:34] = True                          # the arrow stroke
panA = np.zeros((100, 200), bool); panA[30:100, 20:200] = True; mA[30:40, 30:34] = False   # a panel below; the stroke ends above it
arR = box_raster(srcS, [20, 10, 60, 40], 1.0); arN = label_raster(srcS, mA, [20, 10, 60, 40], 1.0, panA)
def _in(rr, M_):
    a = _alpha(rr["href"]); bx, by = int(rr["box"][0]), int(rr["box"][1]); c = np.zeros((100, 200), bool); c[by:by + a.shape[0], bx:bx + a.shape[1]] = a > 128; return int((c & M_).sum())
ok(_in(arR, panA) > 0 and _in(arN, panA) == 0, f"synthetic arrow: old box copies {_in(arR, panA)} px of the panel, the new crop {_in(arN, panA)}")
t = time.time(); pr = json.loads(engine.run("process", base64.b64encode(open(f"{ref}/ref1_lost_son_rows.jpg", "rb").read()).decode())); dt = time.time() - t
ok("error" not in pr and len(pr["layers"]) > 0 and all(l.get("rasterHref") for l in pr["layers"]) and pr.get("bgRasterHref"), f"process: {len(pr.get('layers', []))} layers with raster crops, in {dt:.1f}s")
bad = json.loads(engine.run("panels", base64.b64encode(b"not a picture").decode()))
ok(bad.get("error") == "unreadable", "a broken file gives a clear 'unreadable' answer, no crash")
print(f"\n{fails} FAILED" if fails else "\nall passed"); sys.exit(1 if fails else 0)
