import numpy as np, random
random.seed(3); rng = np.random.default_rng(7)
secs = [("INTRO", None, ["The ancient city stood proudly above the sea.","Its great walls protected thousands of families.","Merchants brought silk and spices from distant lands."]),
        ("Section 2: The Siege", "Section two. The Siege.", ["In spring the sultan gathered a massive army.","Enormous cannons pounded the stone defenses daily.","Defenders repaired breaches every single night.","Hunger and fear spread across the crowded streets."]),
        ("Section 3: The Fall", "Section three. The Fall.", ["Finally the final assault began before dawn.","Soldiers poured through the broken gate.","The empire ended and a new age began."])]
script = []
for h, spoken_h, sents in secs:
    script.append(h)
    for s in sents: script.append(s)
open("script.txt","w").write("\n".join(script)+"\n")
SR=16000; t=0.5; audio=np.full(int(SR*80),0,np.float32); cues=[]; idx=0
def burst(a,b):
    i,j=int(a*SR),int(b*SR); audio[i:j]=(rng.standard_normal(j-i)*0.2).astype(np.float32)
def say(text, start):
    t=start
    for w in text.split():
        d=0.07*len(w)+0.12; burst(t,t+d); t+=d+0.05
    return t
for h, spoken_h, sents in secs:
    if spoken_h:
        t+=0.6; a=t; t=say(spoken_h,t); cues.append((a,t,spoken_h))
    for s in sents:
        t+=0.55; a=t; t=say(s,t); cues.append((a,t,s))
audio=audio[:int((t+1.0)*SR)]; audio+= (rng.standard_normal(audio.size)*0.0005).astype(np.float32)
audio.astype(np.float32).tofile("audio.f32")
def ts(x): return f"{int(x//3600):02d}:{int(x%3600//60):02d}:{int(x%60):02d},{int(round((x%1)*1000)):03d}"
with open("caps.srt","w") as f:
    for k,(a,b,s) in enumerate(cues,1): f.write(f"{k}\n{ts(max(a+0.25,0))} --> {ts(b+0.4)}\n{s}\n\n")
print("audio seconds", audio.size/SR, "cues", len(cues))
