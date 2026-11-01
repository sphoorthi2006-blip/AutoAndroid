import sys; sys.path.insert(0,"/home/claude/work/quality/Auto")
from pathlib import Path
from collections import OrderedDict
from scriptvideo import engine as E
det=E.read_text(Path("/tmp/tt2/details.txt")); scr=E.read_text(Path("/tmp/tt2/script.txt"))
sc_rows=OrderedDict()
for ln in open("/tmp/tt3/scen.txt").read().splitlines():
    sc,match,root,rname,rel=ln.split("\t"); sc_rows.setdefault(sc,{"match":match,"rows":[]})["rows"].append((root,rname,rel))
for sc,d in sc_rows.items():
    items=E.parse_scene_file(det); lines=[i["spoken"] for i in items]
    scenes=E.estimate_align(lines,scr)
    for k,(s,it) in enumerate(zip(scenes,items)): s.meta=it; s.num=k+1
    imgs=[(Path(r),Path(r)/rel) for r,rn,rel in d["rows"]]; rname={Path(r):rn for r,rn,rel in d["rows"]}
    groups=[]; usable=[]
    for name,paths in E._image_groups(imgs,rname):
        groups.append(E.Group(name,paths,list(range(len(usable),len(usable)+len(paths))))); usable.extend(paths)
    sections=E.find_sections(items,scr); warns=[]
    _,kept=E._place_groups(groups,items,usable,sections,scenes,d["match"],E.progress_noop,warns)
    print("##",sc,d["match"],"sections",sections)
    for w in dict.fromkeys(warns): print("W",w)
    for g in groups: print("G",f"{g.name}|{g.window[0]}-{g.window[1]}|{g.sure}|{g.skipped}|{len(g.extras)}|{[p.name for p in g.images][:2]}")
    for s in scenes: print("S",f"{s.num}|{s.image.name if s.image else None}|{(s.meta or {}).get('conf','')}|{bool((s.meta or {}).get('missing'))}")
