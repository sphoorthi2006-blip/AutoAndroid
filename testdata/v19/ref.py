import sys, os; sys.path.insert(0, os.environ.get("AUTO_ENGINE", "/home/claude/work/quality/Auto"))
from pathlib import Path
from scriptvideo import engine as E
base = Path(sys.argv[1])
for sd in sorted(p for p in base.iterdir() if p.is_dir()):
    det = E.read_text(sd/"details.txt"); scr = E.read_text(sd/"script.txt"); match = (sd/"match.txt").read_text().strip()
    rows = [l.split("\t") for l in (sd/"pics.tsv").read_text().splitlines() if l.strip()]
    imgs = [(Path(r), Path(r)/rel) for r, rn, rel in rows]; rname = {Path(r): rn for r, rn, rel in rows}
    groups = []; usable = []
    for name, paths in E._image_groups(imgs, rname):
        groups.append(E.Group(name, paths, list(range(len(usable), len(usable)+len(paths))))); usable.extend(paths)
    warns = []
    items = E.scoped_items(E.parse_scene_file(det), scr, warns)
    wt = None
    if (sd/"spoken.txt").exists():
        wt = ["".join(E.tokens(w)) for w in (sd/"spoken.txt").read_text().split() if E.tokens(w)]
    items = E.add_heading_items(items, scr, groups, warns, wtoks=wt)
    lines = [i["spoken"] for i in items]
    scenes = E.estimate_align(lines, scr)
    for k, (s, it) in enumerate(zip(scenes, items)): s.meta = it; s.num = k+1
    sections = E.find_sections(items, scr)
    print("##", sd.name, match, "sections", sections)
    try:
        _, kept = E._place_groups(groups, items, usable, sections, scenes, match, E.progress_noop, warns)
    except Exception as e:
        print("ERR", e); kept = []
    for w in dict.fromkeys(warns): print("W", w)
    for g in groups: print("G", f"{g.name}|{g.window[0]}-{g.window[1]}|{g.sure}|{g.skipped}|{len(g.extras)}")
    for s in scenes: print("S", f"{s.num}|{(s.meta or {}).get('num')}|{s.text[:30]}|{s.image.name if s.image else None}|{(s.meta or {}).get('conf','')}|{bool((s.meta or {}).get('missing'))}|{bool((s.meta or {}).get('heading'))}")
