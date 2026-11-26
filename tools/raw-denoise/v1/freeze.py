"""Frozen v1 parity fixtures: CFA in -> raw-stage CFA, Lw, final RGB (float32 LE .npy) for night / mid / day crops.
  freeze.py OUT FILE..."""
import os,sys,json,numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng,estimate_noise,demosaic
import rd2,pipe
out=sys.argv[1]; os.makedirs(out,exist_ok=True)
for f in sys.argv[2:]:
    x,wb,_,_=load_dng(f); a,b,_=estimate_noise(x); H,W=x.shape; y,z=(H//2-256)&~1,(W//2-256)&~1
    c=x[y:y+512,z:z+512]; n=os.path.splitext(os.path.basename(f))[0]
    raw={k:v for k,v in pipe.BASE.items() if k in rd2.P0}
    o=pipe.pipe(c,a,b,wb,dict(_full=x)); L=pipe.pipe.last
    for k,v in (('in',c),('raw',rd2.denoise(c,a,b,wb,raw)),('out',o)): np.save(os.path.join(out,n+'.'+k+'.npy'),v.astype(np.float32))
    json.dump(dict(a=float(a),b=float(b),wb=[float(v) for v in wb],y=int(y),x=int(z),**{k:float(v) for k,v in L.items()}),open(os.path.join(out,n+'.json'),'w'))
    print(n,L)
