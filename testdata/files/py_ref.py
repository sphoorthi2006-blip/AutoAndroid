import sys, json
sys.path.insert(0, "/home/claude/proj/Auto")
from pathlib import Path
from scriptvideo import engine as E
from qt_app import bundle as B
for f in ["a.srt","b.vtt","c.txt","d.txt","e.txt","f.json","g.json"]:
    w = E.parse_timestamps(Path("/tmp/tt")/f)
    print("##", f, len(w))
    for t,a,b in w: print(f"{t}|{a:.3f}|{b:.3f}")
    print("##cues", f)
    for a,b,t in E.parse_cues(Path("/tmp/tt")/f): print(f"{a:.3f}|{b:.3f}|{t}")
items = E.parse_scene_file(E.read_text(Path("/tmp/tt/details.txt")))
print("##details", len(items))
for it in items: print("|".join(str(it.get(k)) if it.get(k) is not None else "None" for k in ("num","spoken","visual","labels","emotion","section")))
print("##script", len(E.parse_scene_file(E.read_text(Path("/tmp/tt/script.txt")))))
for f in ["details.txt","script.txt","a.srt","c.txt","d.txt","e.txt"]:
    print("##role", f, B._text_role(Path("/tmp/tt")/f))
