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
t = time.time(); pr = json.loads(engine.run("process", base64.b64encode(open(f"{ref}/ref1_lost_son_rows.jpg", "rb").read()).decode())); dt = time.time() - t
ok("error" not in pr and len(pr["layers"]) > 0 and all(l.get("rasterHref") for l in pr["layers"]) and pr.get("bgRasterHref"), f"process: {len(pr.get('layers', []))} layers with raster crops, in {dt:.1f}s")
bad = json.loads(engine.run("panels", base64.b64encode(b"not a picture").decode()))
ok(bad.get("error") == "unreadable", "a broken file gives a clear 'unreadable' answer, no crash")
print(f"\n{fails} FAILED" if fails else "\nall passed"); sys.exit(1 if fails else 0)
