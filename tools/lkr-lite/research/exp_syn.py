import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import *
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
for case in sys.argv[1:]:
    name, s = case.split("-x"); s=int(s)
    t=load_trace(S+"ref/"+case+"_tile_0_0"); m=t["meta"]
    gt,sh=gt_align(t["z_ref"],np.load(S+"syn-"+name[3:]+"/labels.npz")["gt"],s); print("shift",sh)
    y=t["y"].ravel().astype(np.float64)
    print(case, "LK-R z vs gt %.2f dB"%gt_score(t["z_ref"],gt,s), " MHC start %.2f"%gt_score(t["z0"],gt,s))
    U,valid=forward_matrix(t,t["p1"],t["d1"])
    Wo=t["wt"].ravel()*valid.ravel()*(y<m["clip"])
    for n in (2,4,8): print("  oracle(LK-R motion+weights) jacobi",n,"%.2f"%gt_score(jacobi(U,Wo,y,t["z0"].ravel().astype(float),n).reshape(3,t["H"],t["W"]),gt,s))
    U,valid=forward_matrix(t,t["p0"],t["d0"])
    z=t["z0"].ravel().astype(np.float64)
    W=weights(t,U,valid,y,z,m["robust_k"],1.0)
    z1=jacobi(U,W,y,z,2)
    W2=weights(t,U,valid,y,z1,m["robust_k"],1.0)
    for n in (2,4,6): print("  lite(init motion, 2 weight passes) jacobi 2+%d"%n,"%.2f"%gt_score(jacobi(U,W2,y,z1,n).reshape(3,t["H"],t["W"]),gt,s))
