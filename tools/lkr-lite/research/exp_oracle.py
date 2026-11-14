import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sys, time, numpy as np
from lite_model import *
R="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/ref/"
for case in sys.argv[1:]:
    t=load_trace(R+case+"_tile_0_0"); m=t["meta"]
    y=t["y"].ravel().astype(np.float64)
    out=[]
    for mot in ("final","initial"):
        p,d=(t["p1"],t["d1"]) if mot=="final" else (t["p0"],t["d0"])
        U,valid=forward_matrix(t,p,d)
        wmask=(valid.ravel())&(y<m["clip"])
        for wname in ("lkr","ones"):
            W=(t["wt"].ravel() if wname=="lkr" else np.ones_like(y))*wmask
            if mot=="final" and wname=="lkr":
                r=U@t["z_ref"].ravel()-y
                fr=np.fromfile(R+case+"_tile_0_0_final_resid.f32",np.float32)[:y.size]
                print(case,"resid check: corr",np.corrcoef(r[wmask],fr[wmask])[0,1])
            D=U.T@W
            z=t["z0"].ravel().astype(np.float64).copy(); zs=z.copy()
            res=[]
            for n in range(1,13):
                g=U.T@(W*(U@z-y))
                z=z-1.0*g/np.maximum(D,1e-6)
                if n in (1,2,3,4,6,8,12): res.append("%d:%.2f"%(n,psnr(z.reshape(3,t["H"],t["W"]),t["z_ref"])))
            print(case,mot,wname,"jacobi PSNR vs LK-R z:"," ".join(res),flush=True)
