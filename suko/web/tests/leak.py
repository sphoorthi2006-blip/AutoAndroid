"""Run: python3 tests/leak.py <svg folder made by cues.test.mjs --svg> [slides]
FIX PLAN steps 8 and 9, exact leak test: at cue - 0.03 s render the slide with ALL layers of panel k hidden and shown. Any differing pixel = something of panel k is visible before its cue (a strip of a lower panel, a crop that reached into the next panel). Panels that start at 0.00 s are skipped. Needs playwright, pillow, numpy."""
import sys, json, io, os, re
from playwright.sync_api import sync_playwright
from PIL import Image
import numpy as np
folder=sys.argv[1]; want=set(map(int,sys.argv[2].split(","))) if len(sys.argv)>2 and sys.argv[2] else None
S=0.5; tot=0; leaks=[]
with sync_playwright() as pw:
    br=pw.chromium.launch()
    for f in sorted(os.listdir(folder), key=lambda x:(len(x),x)):
        if not f.endswith(".json"): continue
        meta=json.load(open(os.path.join(folder,f)))
        if want and meta["n"] not in want: continue
        svg=open(os.path.join(folder,f.replace(".json",".svg")),encoding="utf-8").read()
        W,H=meta["w"],meta["h"]
        pg=br.new_page(viewport={"width":int(W*S),"height":int(H*S)})
        pg.set_content('<body style="margin:0;background:#fff"><div style="width:%dpx;height:%dpx">%s</div></body>'%(int(W*S),int(H*S),svg.replace("<svg ",'<svg style="width:100%;height:100%" ',1)))
        pg.evaluate("()=>document.querySelector('svg').pauseAnimations()")
        for P in meta["panels"]:
            k=P["k"]
            if k==1 and P["cue"]<=0.0: continue
            t=max(0.0,P["cue"]-0.03)
            pg.evaluate("t=>document.querySelector('svg').setCurrentTime(t)",t)
            pg.evaluate("k=>document.querySelectorAll('g[id=\"panel-'+k+'\"],g[id^=\"panel-'+k+'-\"]').forEach(g=>g.style.display='')",k)
            a=np.asarray(Image.open(io.BytesIO(pg.screenshot())).convert("RGB")).astype(int)
            pg.evaluate("k=>document.querySelectorAll('g[id=\"panel-'+k+'\"],g[id^=\"panel-'+k+'-\"]').forEach(g=>g.style.display='none')",k)
            b=np.asarray(Image.open(io.BytesIO(pg.screenshot())).convert("RGB")).astype(int)
            pg.evaluate("k=>document.querySelectorAll('g[id=\"panel-'+k+'\"],g[id^=\"panel-'+k+'-\"]').forEach(g=>g.style.display='')",k)
            d=int((np.abs(a-b).max(axis=2)>12).sum()); tot+=1
            if d: leaks.append((meta["n"],k,d)); print(f"slide {meta['n']:>2} panel {k} cue {P['cue']:.2f}: {d} px of its own layers visible before the cue")
print(f"\n{tot} panels checked, {len(leaks)} with something visible before their cue")
