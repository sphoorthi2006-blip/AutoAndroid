"""Writes the v19 test scenarios (S1..S8): details / script / spoken text / picture list, read by ref.py (Python) and Check6.kt (Kotlin)."""
import os, random
random.seed(5)
INTRO = ["The old harbour town woke slowly each morning.", "Fishermen hauled their nets onto the grey stones.", "Nobody suspected what the tide would bring."]
S1 = ["A crafty question reached the mayor one evening.", "He read the letter twice beside the lantern.", "Every word seemed to hide another meaning.", "By midnight he had made up his mind."]
S2 = ["The hated slitter arrived on the first ferry.", "Children hid behind the market stalls.", "His knives glittered in the cold light."]
def prompt_block(n, line, style="Prompt"):
    return f'{style} {n} - Script line: "{line}"\nSetting and era: harbour town, long ago\nKey object: something visual\n'
def write(name, details, script, spoken, pics, match="number"):
    d=name; os.makedirs(d, exist_ok=True)
    open(f"{d}/details.txt","w").write(details); open(f"{d}/script.txt","w").write(script)
    if spoken is not None: open(f"{d}/spoken.txt","w").write(spoken)
    open(f"{d}/match.txt","w").write(match)
    open(f"{d}/pics.tsv","w").write("\n".join("\t".join(r) for r in pics)+"\n")
def restart_details(style="Prompt"):
    out=""
    for blk in (INTRO,S1,S2):
        for i,l in enumerate(blk,1): out+=prompt_block(i,l,style)
    return out
script_numbered = "\n".join(INTRO)+"\n1. Crafty Question\n"+"\n".join(S1)+"\n2. Hated Slitter\n"+"\n".join(S2)+"\n"
spoken_numbered = " ".join(INTRO)+" 1 Crafty Question "+" ".join(S1)+" 2 Hated Slitter "+" ".join(S2)+"\n"
def folder(root, rname, rel_list): return [(root, rname, r) for r in rel_list]
img = lambda n, k: [f"image_{i}.png" for i in range(1, k+1)]
# S1: Prompt blocks, numbering restarts, script has numbered headings, folders intro/1/2, as many pictures as scenes -> black heading screens
write("S1", restart_details(), script_numbered, spoken_numbered,
      folder("/z/Intro","Intro",img(0,3))+folder("/z/1","1",img(0,4))+folder("/z/2","2",img(0,3)))
# S2: folder '1' has one picture MORE than its section -> heading gets the first picture
write("S2", restart_details(), script_numbered, spoken_numbered,
      folder("/z/Intro","Intro",img(0,3))+folder("/z/1","1",img(0,5))+folder("/z/2","2",img(0,4)))
# S3: no narration to check against (heading in front of the 2nd block onward is used)
write("S3", restart_details(), script_numbered, None,
      folder("/z/Intro","Intro",img(0,3))+folder("/z/1","1",img(0,4))+folder("/z/2","2",img(0,3)))
# S4: headings in the details, folder names like '01_Crafty_Question'
det4="INTRO\n"+"".join(prompt_block(i,l,"Image") for i,l in enumerate(INTRO,1))+"Crafty Question\n"+"".join(prompt_block(i,l,"Image") for i,l in enumerate(S1,4))+"Hated Slitter\n"+"".join(prompt_block(i,l,"Image") for i,l in enumerate(S2,8))
write("S4", det4, "INTRO\n"+"\n".join(INTRO)+"\nCrafty Question\n"+"\n".join(S1)+"\nHated Slitter\n"+"\n".join(S2)+"\n",
      " ".join(INTRO)+" Crafty Question "+" ".join(S1)+" Hated Slitter "+" ".join(S2)+"\n",
      folder("/z/a","00_Intro",[f"image_{i}.png" for i in (1,2,3)])+folder("/z/b","01_Crafty_Question",[f"image_{i}.png" for i in (4,5,6,7)])+folder("/z/c","02_Hated_Slitter",[f"image_{i}.png" for i in (8,9,10)]))
# S5: first block has no heading (Start section) and an 'Intro' folder; other sections have headings
det5="".join(prompt_block(i,l,"Scene") for i,l in enumerate(INTRO,1))+"Section 2: Crafty Question\n"+"".join(prompt_block(i,l,"Scene") for i,l in enumerate(S1,4))+"Section 3: Hated Slitter\n"+"".join(prompt_block(i,l,"Scene") for i,l in enumerate(S2,8))
write("S5", det5, "\n".join(INTRO+S1+S2)+"\n", None,
      folder("/z/a","Intro",[f"image_{i}.png" for i in (1,2,3)])+folder("/z/b","Section 2",[f"image_{i}.png" for i in (4,5,6,7)])+folder("/z/c","Section 3",[f"image_{i}.png" for i in (8,9,10)]))
# S6: ONE big folder with more pictures than any section: covers the whole script
write("S6", restart_details(), script_numbered, spoken_numbered, folder("/z/All","All",[f"image_{i}.png" for i in range(1,11)]))
# S7: numbered pictures with gaps and black heading screens
write("S7", restart_details(), script_numbered, spoken_numbered,
      folder("/z/Intro","Intro",img(0,3))+folder("/z/1","1",["image_1.png","image_2.png","image_4.png"])+folder("/z/2","2",img(0,3)))
# S8: match by name words, un-numbered pictures, headings in the script
names={"Intro":["Old_harbour_town_woke_slowly_ab12cd34.jpg","Fishermen_hauled_nets_grey_stones_11aa22bb.jpg","Nobody_suspected_tide_would_bring_33cc44dd.jpg"],
       "1":["Crafty_question_reached_mayor_evening_55ee66ff.jpg","Read_letter_twice_beside_lantern_77aa88bb.jpg","Every_word_hide_another_meaning_99cc00dd.jpg","Midnight_made_mind_1234abcd.jpg"],
       "2":["Hated_slitter_arrived_first_ferry_aabbccdd.jpg","Children_hid_behind_market_stalls_ddeeff00.jpg","Knives_glittered_cold_light_01020304.jpg"]}
rows=[]
for k,v in names.items(): rows+=folder("/z/"+k,k,random.sample(v,len(v)))
write("S8", restart_details(), script_numbered, spoken_numbered, rows, "name")
