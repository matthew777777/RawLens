import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
def lite(t,y,sched,local_from=1):
    """sched: string of J (Jacobi merge step) and G (GN motion step + weight refresh)."""
    m=t["meta"]; z0=t["z0"].ravel().astype(float); z=z0.copy()
    p,d=t["p0"].astype(float),t["d0"].astype(float)
    U,valid=forward_matrix(t,p,d); W=weights(t,U,valid,y,z0,m["robust_k"],1.0); D=None
    g=0
    for c in sched:
        if c=="J":
            z=jacobi(U,W,y,z,1)
        else:
            p,d=gn_motion(t,z,y,W,p,d,local=g>=local_from); g+=1
            U,valid=forward_matrix(t,p,d); W=weights(t,U,valid,y,z0,m["robust_k"],1.0)
    return z
scheds=sys.argv[1].split(",")
for case in sys.argv[2:]:
    name, s = case.split("-x"); s=int(s)
    t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]
    syn=name.startswith("syn")
    gt=gt_align(t["z_ref"],np.load(S+"syn-"+name[3:]+"/labels.npz")["gt"],s)[0] if syn else None
    y=t["y"].ravel().astype(np.float64)
    sc=lambda z: ("%.2f"%gt_score(z.reshape(3,H,W),gt,s)) if syn else ("~%.1f"%psnr(z.reshape(3,H,W),t["z_ref"]))
    out=["%-14s"%case, "LK-R "+(sc(t["z_ref"].ravel()) if syn else "-")]
    for sd in scheds: out.append(sd+" "+sc(lite(t,y,sd)))
    print(" | ".join(out),flush=True)
