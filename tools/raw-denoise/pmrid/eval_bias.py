"""Local colour bias + residual LF chroma of denoisers on real CFAs: 16x16-plane block means, denoised vs noisy.
Block-mean chroma noise of the input is known (a*m+b)/256, so excess/lost colour is measurable without truth.
  eval_bias.py NET.pt FILE..."""
import sys,os,numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng,estimate_noise
import rd2,apply_pmrid,pipe
net=sys.argv[1]
pk=lambda x:np.stack([x[0::2,0::2],x[0::2,1::2],x[1::2,0::2],x[1::2,1::2]])
def blk(p,n=16):
    H,W=p.shape[1]//n*n,p.shape[2]//n*n; return p[:,:H,:W].reshape(4,H//n,n,W//n,n).mean((2,4))
raw={k:v for k,v in pipe.BASE.items() if k in rd2.P0}
for f in sys.argv[2:]:
    x,wb,_,_=load_dng(f); a,b,_=estimate_noise(x)
    H,W=x.shape; y,z=(H//4)&~1,(W//4)&~1; c=x[y:y+1536,z:z+1536]
    B=blk(pk(c)); sn=np.sqrt(np.maximum(a*B+b,0)/256)
    print(os.path.basename(f),'a %.1e b %.1e g1b/a %.3f'%(a,b,b/a))
    for n,d in (('classic',rd2.denoise(c,a,b,wb,raw)),('net',apply_pmrid.denoise(c,a,b,net))):
        Db=blk(pk(d))
        rel=[100*(Db[i].mean()/B[i].mean()-1) for i in range(4)]
        dev=(Db-B)/sn
        print('  %-8s mean err %% RGGB %s | block dev/noise std %s'%(n,' '.join('%+.2f'%r for r in rel),' '.join('%.2f'%dev[i].std() for i in range(4))),flush=True)
