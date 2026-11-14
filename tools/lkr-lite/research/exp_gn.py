import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
def lite(t, y, gn_steps, local, j0=2, j1=2, lam=0.1):
    m=t["meta"]; z=t["z0"].ravel().astype(float); p,d=t["p0"].astype(np.float64),t["d0"].astype(np.float64)
    U,valid=forward_matrix(t,p,d)
    W=weights(t,U,valid,y,z,m["robust_k"],1.0)
    z=jacobi(U,W,y,z,j0)
    for _ in range(gn_steps):
        p,d=gn_motion(t,z,y,W,p,d,lam,local=local)
    if gn_steps:
        U,valid=forward_matrix(t,p,d)
        W=weights(t,U,valid,y,t["z0"].ravel().astype(float),m["robust_k"],1.0)
        z=jacobi(U,W,y,z,j1)
    return z,p,d
for case in sys.argv[1:]:
    name, s = case.split("-x"); s=int(s)
    t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]
    syn=name.startswith("syn")
    gt=gt_align(t["z_ref"],np.load(S+"syn-"+name[3:]+"/labels.npz")["gt"],s)[0] if syn else None
    y=t["y"].ravel().astype(np.float64)
    sc=lambda z: ("gt %.2f"%gt_score(z.reshape(3,H,W),gt,s)) if syn else ("vsLKR %.2f"%psnr(z.reshape(3,H,W),t["z_ref"]))
    out=[case, "LK-R", sc(t["z_ref"].ravel()) if syn else ""]
    for g,loc in ((0,False),(1,False),(1,True),(2,True),(3,True)):
        z,p,d=lite(t,y,g,loc)
        out.append("gn%d%s: %s"%(g,"L" if loc else "",sc(z)))
    print(" | ".join(out),flush=True)
