"""Run: python3 analyse_folder.py <folder with 1.jpg, 2.jpg ...> <output folder> [first last]
Runs the phone's picture analyser (the same Python as the app) on numbered pictures and saves N.json (the 'panels' answer, with the raster crops) for
`web/tests/cues.test.mjs`. Needs numpy + opencv-python-headless. About 1-15 s per picture; single-panel text pictures answer with no panels (that is normal)."""
import sys, os, json, base64, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "main", "python"))
from ils import engine
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
names = {int(os.path.splitext(f)[0]): f for f in os.listdir(src) if os.path.splitext(f)[0].isdigit() and f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))}
lo, hi = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (min(names), max(names))
for n in range(lo, hi + 1):
    if n not in names or os.path.exists(f"{out}/{n}.json"): continue
    t = time.time(); r = engine.run("panels", base64.b64encode(open(os.path.join(src, names[n]), "rb").read()).decode())
    open(f"{out}/{n}.json", "w").write(r); print(n, f"{time.time() - t:.1f}s", flush=True)
