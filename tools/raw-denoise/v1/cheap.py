"""Cheap candidates for the Vulkan port. Columns: noisy | frozen v1 | A raw s2 + demosaic grain | B raw s4 + demosaic grain
| C raw s4 + RAW-domain grain (no second demosaic) | D = C with DCT stage 1 only on chroma (no Wiener)."""
import os,sys,json,glob,numpy as np,cv2
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import rd2,pipe
from lib import demosaic
F=np.float32; O2=rd2.dct2; ST=[2]
rd2.dct2=lambda u,var,lam=2.7,wm=1.0,s=2,n=8: O2(u,var,lam,wm,ST[0],n)
d=sys.argv[1]; out=sys.argv[2]; rows=[]
def rawgrain(x,d,wb,K):
    W=np.tile(np.array([[wb[0],wb[1]],[wb[1],wb[2]]],F),(x.shape[0]//2,x.shape[1]//2)); r=(x-d)*W
    b3=np.array([.25,.5,.25],F); L=cv2.sepFilter2D(r,-1,b3,b3,borderType=cv2.BORDER_REFLECT_101)
    return d+K*L/W
for j in sorted(glob.glob(d+'/*.json')):
    m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],F); K=.55+.35*m['t']
    f=lambda v:np.sqrt(np.clip(v*wb,0,None)); raw={k:v for k,v in pipe.BASE.items() if k in rd2.P0}; raw.update(clev=2,ylev=2)
    nz=demosaic(x); V=[nz,ref]
    ST[0]=2; d2=rd2.denoise(x,m['a'],m['b'],wb,raw); ST[0]=4; d4=rd2.denoise(x,m['a'],m['b'],wb,raw)
    for dd in (d2,d4):
        b=demosaic(dd); V.append(b+K*(((nz-b)*wb).mean(-1))[...,None]/wb)
    V.append(demosaic(rawgrain(x,d4,wb,K)))
    print(os.path.basename(p),' '.join('%s %.3f%%'%(n,100*np.sqrt(((f(v)-f(ref))**2).mean())) for n,v in zip('ABC',V[2:])))
    # luma HF energy vs v1 by band (detail + grain)
    g=lambda v,s:cv2.GaussianBlur(f(v).mean(-1),(0,0),s)
    for n,v in zip(('noisy','v1','A','B','C'),V): 
        l=f(v).mean(-1); print('   %-6s fine %.4f mid %.4f'%(n,(l-g(v,1)).std(),(g(v,1)-g(v,3)).std()))
    q=np.quantile(f(ref),.99); rows.append(np.concatenate([np.clip(f(v)[160:320,160:340]/q,0,1) for v in V],1))
cv2.imwrite(out,cv2.resize((np.concatenate(rows,0)[...,::-1]*255).astype(np.uint8),None,fx=2,fy=2,interpolation=cv2.INTER_NEAREST))
