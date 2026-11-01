import random
random.seed(11)
rows=[]
def add(sc, match, root, rname, rels):
    for r in rels: rows.append("\t".join([sc, match, root, rname, r]))
sh=lambda l: random.sample(l,len(l))
add("T1","number","/z/Pictures","Pictures", sh([f"Intro/image_{i}.png" for i in (1,2,3)] + [f"Section 2/Image {i}.png" for i in (4,5,6,7)] + [f"03_The_Fall/image_{i}.png" for i in (8,10)]))
add("T2","number","/p/flat","flat", sh([f"scene_{i:02d}.png" for i in range(1,11)]))
add("T3","number","/p/Images A","Images A", sh(["Ancient_city_stood_proudly_above_sea_ab12cd34.jpg","Great_walls_protected_thousands_families_11aa22bb.jpg","Merchants_brought_silk_spices_distant_lands_33cc44dd.jpg"]))
add("T3","number","/p/Images B","Images B", sh(["Sultan_gathered_massive_army_spring_55ee66ff.jpg","Enormous_cannons_pounded_stone_defenses_77aa88bb.jpg","Defenders_repaired_breaches_every_night_99cc00dd.jpg","Hunger_fear_spread_crowded_streets_1234abcd.jpg"]))
add("T3","number","/p/Images C","Images C", sh(["Final_assault_began_before_dawn_aabbccdd.jpg","Soldiers_poured_broken_gate_ddeeff00.jpg","Empire_ended_new_age_began_01020304.jpg"]))
add("T4","name","/p/gen","gen", sh(["Ancient_city_stood_proudly_above_sea_ab12cd34.jpg","Great_walls_protected_thousands_families_11aa22bb.jpg","Merchants_brought_silk_spices_distant_lands_33cc44dd.jpg","Sultan_gathered_massive_army_spring_55ee66ff.jpg","Enormous_cannons_pounded_stone_defenses_77aa88bb.jpg","Defenders_repaired_breaches_every_night_99cc00dd.jpg","Hunger_fear_spread_crowded_streets_1234abcd.jpg","Final_assault_began_before_dawn_aabbccdd.jpg","Soldiers_poured_broken_gate_ddeeff00.jpg","Empire_ended_new_age_began_01020304.jpg"]))
add("T5","number","/p/gaps","gaps", sh([f"image_{i}.png" for i in (1,2,3,5,6,7,8,10)] + ["cover.png"]))
add("T6","order","/p/order","order", [f"{c}.png" for c in "abcdefghij"])
add("T7","number","/z/A","A", [f"Intro/img{i}.jpg" for i in (1,2,3)])
add("T7","number","/z/B","B", [f"Section 2/img{i}.jpg" for i in (4,5,6,7)])
open("scen.txt","w").write("\n".join(rows)+"\n")
