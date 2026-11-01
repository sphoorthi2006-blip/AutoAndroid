import sys; sys.path.insert(0,"/home/claude/proj/Auto")
import numpy as np
from pathlib import Path
from scriptvideo import engine as E
x = np.fromfile("/tmp/tt2/audio.f32", dtype=np.float32)
E.decode_audio_cached = lambda p, sr: x
words = E.parse_timestamps("/tmp/tt2/caps.srt")
det = E.read_text(Path("/tmp/tt2/details.txt")); scr = E.read_text(Path("/tmp/tt2/script.txt"))
warn=[]; items = E.scoped_items(E.parse_scene_file(det), scr, warn)
lines=[i["spoken"] for i in items]
heads={k:i["section"] for k,i in enumerate(items) if k>0 and i.get("section") and i["section"]!=items[k-1].get("section")}
def show(tag, sc, rep):
    print("##",tag)
    for s in sc: print(f"{s.start:.3f}|{s.end:.3f}")
    print("rep", {k:(round(v,3) if isinstance(v,float) else v) for k,v in rep.items()})
sc, rep = E.align(words, lines, scr, audio_path="x", heads=heads); show("with-audio", sc, rep)
res = E.anchor_cuts_to_pauses("x", sc)
print("##anchor", None if res is None else ([round(c,3) for c in res[0]], res[1]))
sc2, rep2 = E.align(words, lines, scr, audio_path=None, heads=heads); show("captions-only", sc2, rep2)
print("##estimate"); [print(f"{s.start:.3f}|{s.end:.3f}") for s in E.estimate_align(lines, scr)]
print("##heads", heads, "warn", warn)
