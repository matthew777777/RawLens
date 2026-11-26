import numpy as np,json,sys
pipe=sys.modules[__name__]
from lib import demosaic
import rd2,lumafr,cedge
BASE=dict(gr=0,chf=1.0,chroma=4.0,clev=3,ycoarse=1.0,yhf=0.8,ylf=1.0,hfk=0.0,cer=5,ces=3.0,shk=2.0,cek=3.0,frwm=0.15,frlam=2.0,K=0.7,ghp=0.0)
def raw_tiled(x,a,b,wb,raw,T=512,P=64):
    H,W=x.shape; d=np.empty_like(x)
    for y in range(0,H,T):
        for xx in range(0,W,T):
            y0,x0=max(y-P,0),max(xx-P,0); y1,x1=min(y+T+P,H),min(xx+T+P,W)
            r=rd2.denoise(x[y0:y1,x0:x1],a,b,wb,raw)
            d[y:y+T,xx:xx+T]=r[y-y0:y-y0+min(T,H-y),xx-x0:xx-x0+min(T,W-xx)]
    return d
def pipe(x,a,b,wb,cfg=None,K=0.5,tiled=False):
    C=dict(BASE)
    # auto strength from the frame's own midtone SNR (ISO 12232: 10 acceptable, 40 excellent)
    xf=(cfg or {}).get('_full',x)
    mid=float(np.exp(np.mean(np.log(np.clip(xf[::5,::5],1e-4,None)))))
    snr=mid/np.sqrt(a*mid+b)
    t=float(np.clip(np.log(snr/10.0)/np.log(4.0),0,1))
    NIGHT=dict(frlam=2.7,frwm=0.5,K=0.55,ce=0.0); DAY=dict(frlam=1.0,frwm=0.03,K=0.9,ce=1.0)
    if (cfg or {}).get('auto',1):
        for k in NIGHT: C[k]=NIGHT[k]+(DAY[k]-NIGHT[k])*t
    C.update({k:v for k,v in (cfg or {}).items() if k not in ('auto','_full')}); K=C.get('K',K)
    pipe.last=dict(snr=snr,t=t)
    raw={k:v for k,v in C.items() if k in rd2.P0 or k in ('chf','ccoarse','clam','hfk','hfs')}
    d=raw_tiled(x,a,b,wb,raw) if tiled else rd2.denoise(x,a,b,wb,raw)
    nz=demosaic(x); base=demosaic(d)
    Ln=(nz*wb).mean(-1); Lb=(base*wb).mean(-1)
    Lw=lumafr.luma_fr(Ln,Lb,lam=C.get('frlam',2.7),wm=C.get('frwm',0.5),lfcut=C.get('lfcut',2)) if C.get('fr',1) else Lb
    R=Ln-Lw
    if C.get('ghp',0)>0:
        import cv2; R=R-cv2.GaussianBlur(R,(0,0),C['ghp'],borderType=cv2.BORDER_REFLECT)
    Lf=Lw+K*R
    o=base+(Lf-Lb)[...,None]/wb
    if C.get('ce',1)>0.01:
        o0=o; o=cedge.restore(nz,o,wb,Lw,r=C.get('cer',3),ss=C.get('ces',2.0),srk=C.get('cek',2.0),shk=C.get('shk',2.0),med=C.get('med',0)); o=o0+C.get('ce',1)*(o-o0)
    return o
