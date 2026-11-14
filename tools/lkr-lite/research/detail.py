"""Lite vs LK-R z on trace tiles: PSNR, fine-detail ratio (DoG 0.7/2 band energy, Lite/LK-R,
1 = same detail), and correlation of that band (1 = same detail in the same places)."""
import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from scipy.ndimage import gaussian_filter
from lite_model import load_trace, psnr
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
def band(z): Y=z.mean(0); return (gaussian_filter(Y,0.7)-gaussian_filter(Y,2.0))[16:-16,16:-16]
out=[]
for case in sys.argv[1:]:
    t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]
    z=np.fromfile(S+"lite/"+case+".f32",np.float32).reshape(3,H,W)
    a,b=band(t["z_ref"]),band(z)
    out.append("%s %.1fdB det %.2f corr %.3f"%(case.replace("-x","x"),psnr(z,t["z_ref"]),np.sqrt((b*b).mean()/(a*a).mean()),np.corrcoef(a.ravel(),b.ravel())[0,1]))
print(" | ".join(out))
