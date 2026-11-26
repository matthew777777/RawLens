"""Quality cost of cheaper stage-2 / luma variants, vs frozen v1, on the frozen crops (in: CFA).
Reports, per fixture: RMS difference of the final RGB (sqrt domain, %) and residual LF chroma (16 px blocks) ratio to v1."""
import os,sys,json,glob,numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import rd2,lumafr,pipe
O2,W0=rd2.dct2,lumafr.wien
S=dict(sl=2,sc=2,sf=2,pil=2)
def dct2(u,var,lam=2.7,wm=1.0,s=2,n=8): return O2(u,var,lam,wm,S['sc'],n)
rd2.dct2=dct2
lumafr.wien=lambda *a,**k: W0(*a,**dict(k,s=S['sf']))
d=sys.argv[1]
for j in sorted(glob.glob(d+'/*.json')):
    m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],np.float32)
    t=m['t']; N=dict(frlam=2.7,frwm=.5,K=.55,ce=0.); Dd=dict(frlam=1.,frwm=.03,K=.9,ce=1.); base={k:N[k]+(Dd[k]-N[k])*t for k in N}
    f=lambda v:np.sqrt(np.clip(v*wb,0,None))
    bl=lambda v:(lambda c:(c-c.mean(-1,keepdims=True)))(f(v)[:512,:512].reshape(32,16,32,16,3).mean((1,3)))
    print(os.path.basename(p))
    for name,s,c in (('ref',{},{}),('raw stride4',dict(sc=4),{}),('luma stride4',dict(sf=4),{}),('both stride4',dict(sc=4,sf=4),{}),('no luma pass',{},dict(fr=0)),('clev2',{},dict(clev=2,ylev=2)),('no colour edge',{},dict(ce=0))):
        S.update(sc=2,sf=2); S.update(s); o=pipe.pipe(x,m['a'],m['b'],wb,dict(base,auto=0,**{k:v for k,v in c.items() if k not in base}) if not set(c)&set(base) else dict(base,auto=0)|c)
        e=f(o)-f(ref); print('  %-15s rms %.3f%%  p99 %.2f%%  LFchroma diff %.3f%%'%(name,100*np.sqrt((e**2).mean()),100*np.quantile(abs(e),.99),100*np.sqrt(((bl(o)-bl(ref))**2).mean())),flush=True)
