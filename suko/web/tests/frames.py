"""Run: python3 tests/frames.py <folder made by `node tests/cues.test.mjs ... --svg <folder>`> [slide numbers e.g. 1,3,4] [fps, default 10]
FIX PLAN step 1: REAL pictures of the animation. Renders the slide's SVG in headless Chromium at fixed times and measures, for every panel, how much of
its box is drawn (non-white pixels) over time: when it first becomes visible, when it is 95% done, and the biggest one-step jump (a 'pop').
Needs: pip install playwright pillow numpy, and a Chromium for playwright."""
import sys, json, io, os
from playwright.sync_api import sync_playwright
from PIL import Image
import numpy as np
folder = sys.argv[1]
want = set(map(int, sys.argv[2].split(","))) if len(sys.argv) > 2 and sys.argv[2] else None
fps = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
SCALE = 0.5   # screenshots at half size: enough to see what is drawn, and fast
FIRST = 0.02  # a panel is "visible" when 2% of its final ink / colour is on screen
bad = 0
with sync_playwright() as pw:
    br = pw.chromium.launch()
    for f in sorted(os.listdir(folder), key=lambda x: (len(x), x)):
        if not f.endswith(".json"): continue
        meta = json.load(open(os.path.join(folder, f)))
        if want and meta["n"] not in want: continue
        svg = open(os.path.join(folder, f.replace(".json", ".svg")), encoding="utf-8").read()
        W, H = meta["w"], meta["h"]
        pg = br.new_page(viewport={"width": int(W * SCALE), "height": int(H * SCALE)})
        svg2 = svg.replace("<svg ", '<svg style="width:100%;height:100%" ', 1)
        pg.set_content('<body style="margin:0;background:#fff"><div style="width:%dpx;height:%dpx">%s</div></body>' % (int(W * SCALE), int(H * SCALE), svg2))
        pg.evaluate("() => { const s = document.querySelector('svg'); s.pauseAnimations(); }")
        def shot(t, box):
            pg.evaluate("t => document.querySelector('svg').setCurrentTime(t)", t)
            x, y, w, h = [v * SCALE for v in (box["x"], box["y"], box["w"], box["h"])]
            png = pg.screenshot(clip={"x": max(0, x), "y": max(0, y), "width": max(1, min(w, W * SCALE - x)), "height": max(1, min(h, H * SCALE - y))})
            a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB")).astype(int)
            return int((a.min(axis=2) < 235).sum())
        print(f"\nSLIDE {meta['n']}  ({meta['dur']:.2f} s, sampled at {fps:g} fps)")
        print(" k  label               cue   visible@  lag    95%done@  next   biggest step   flags")
        for P in meta["panels"]:
            cue, nxt = P["cue"], P["next"]
            ts = [cue - 0.2] + [cue + i / fps for i in range(int((nxt - cue) * fps))] + [nxt - 0.02]
            ts = [max(0.0, t) for t in ts]
            cnt = [shot(t, P["box"]) for t in ts]
            fin = cnt[-1] or 1
            base = cnt[0]
            frac = [max(0.0, (c - base) / max(1, fin - base)) for c in cnt]
            vis = next((t for t, v in zip(ts, frac) if v >= FIRST), None)
            d95 = next((t for t, v in zip(ts, frac) if v >= 0.95), None)
            step = max((frac[i + 1] - frac[i]) for i in range(len(frac) - 1)) if len(frac) > 1 else 0
            fl = []
            if vis is None or vis - cue > 0.05 + 1.0 / fps: fl.append("LATE")   # one frame of sampling is not lateness
            if step > 0.5: fl.append("POP")
            if d95 is None or d95 > nxt: fl.append("NOT-DONE")
            bad += len(fl)
            f2 = lambda v: "  -  " if v is None else f"{v:5.2f}"
            print(f" {P['k']}  {(P['label'] or '(none)')[:17]:17} {cue:5.2f}  {f2(vis)}   {f2(None if vis is None else vis-cue)}   {f2(d95)}   {nxt:5.2f}   {step*100:5.0f}%        {' '.join(fl)}")
        pg.close()
    br.close()
print(f"\n{bad} flags")
