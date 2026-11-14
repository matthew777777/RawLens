import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from lite_model import load_trace
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
def checker(img, B=16):
    """Mean over BxB windows of |Nyquist (checkerboard) luminance amplitude|, relative to local mean."""
    Y=img.mean(0); H,W=Y.shape; sgn=(-1.0)**np.add.outer(np.arange(H),np.arange(W))
    c=(Y*sgn)[:H//B*B,:W//B*B].reshape(H//B,B,W//B,B).mean((1,3))
    m=Y[:H//B*B,:W//B*B].reshape(H//B,B,W//B,B).mean((1,3))
    v=np.abs(c)/np.maximum(m,1e-3); return v.mean(), np.percentile(v,99)
for case in sys.argv[1:]:
    t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]
    z=np.fromfile(S+"lite/"+case+".f32",np.float32).reshape(3,H,W)
    a,b=checker(t["z_ref"][:,16:-16,16:-16]),checker(z[:,16:-16,16:-16])
    print("%-13s checker mean/p99  LK-R %.5f/%.4f  lite %.5f/%.4f"%(case,a[0],a[1],b[0],b[1]))
