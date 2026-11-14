import os,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
from lite_model import load_trace
S="/private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/"
def look(z,wb,ex):
    x=np.clip(z*np.array(wb)[:,None,None]*ex,0,1)
    return (np.where(x<0.0031308,12.92*x,1.055*x**(1/2.4)-0.055)*255).astype(np.uint8).transpose(1,2,0)
def main(cases):
    rows=[]
    for case in cases:
        t=load_trace(S+"ref/"+case+"_tile_0_0"); H,W=t["H"],t["W"]; wb=t["meta"]["wb"]
        z=np.fromfile(S+"lite/"+case+".f32",np.float32).reshape(3,H,W); r=t["z_ref"]
        ex=0.45/max(np.percentile(r[1],50),1e-4)
        c=slice(H//2-128,H//2+128) ; d=slice(W//2-128,W//2+128)
        a,b=look(r[:,c,d],wb,ex),look(z[:,c,d],wb,ex)
        diff=np.clip(128+8*(b.astype(int)-a.astype(int)),0,255).astype(np.uint8)
        rows.append(np.concatenate([a,np.full((256,4,3),255,np.uint8),b,np.full((256,4,3),255,np.uint8),diff],1))
        rows.append(np.full((4,rows[-1].shape[1],3),255,np.uint8))
    Image.fromarray(np.concatenate(rows,0)).save(S+"lite/ab_"+cases[0].split("-x")[1]+"x.png")


if __name__ == "__main__":
    main(sys.argv[1:])
