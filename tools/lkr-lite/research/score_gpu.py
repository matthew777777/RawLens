import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
for case in sys.argv[1:]:
    name, s = case.split("-x"); s=int(s)
    t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]
    z=np.fromfile(S+"lite/"+case+".f32",np.float32).reshape(3,H,W)
    line="%-14s lite vs LK-R %.1f dB"%(case,psnr(z,t["z_ref"]))
    if name.startswith("syn"):
        g=gt_align(t["z_ref"],np.load(S+"syn-"+name[3:]+"/labels.npz")["gt"],s)[0]
        line+=" | vs truth: LK-R %.2f  lite %.2f"%(gt_score(t["z_ref"],g,s),gt_score(z,g,s))
    print(line)
