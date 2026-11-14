import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
for case in sys.argv[1:]:
    name, s = case.split("-x"); s=int(s)
    t=load_trace(S+"ref/"+case+"_tile_0_0"); m=t["meta"]; H,W=t["H"],t["W"]
    gt,_=gt_align(t["z_ref"],np.load(S+"syn-"+name[3:]+"/labels.npz")["gt"],s)
    y=t["y"].ravel().astype(np.float64); z0=t["z0"].ravel().astype(float)
    sc=lambda z: "%.2f"%gt_score(z.reshape(3,H,W),gt,s)
    row=[case,"LK-R",sc(t["z_ref"].ravel())]
    for mot in ("final","init"):
        U,valid=forward_matrix(t,*( (t["p1"],t["d1"]) if mot=="final" else (t["p0"],t["d0"])))
        vm=valid.ravel()*(y<m["clip"])
        row+=[mot+"+lkrW", sc(jacobi(U,t["wt"].ravel()*vm,y,z0,2))]
        row+=[mot+"+ones", sc(jacobi(U,vm.astype(float),y,z0,2))]
        for k in (1.5,3.0):
          for sp in (0,3):
            Wl=weights(t,U,valid,y,z0,k*m["robust_k"]/3,1.0,spread=sp)
            row+=[mot+"+W(z0,k%.1f,sp%d)"%(k,sp), sc(jacobi(U,Wl,y,z0,2))]
    print(" ".join(row),flush=True)
