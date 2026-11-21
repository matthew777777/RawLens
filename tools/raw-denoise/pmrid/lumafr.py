"""Full-res post-demosaic luma pass.
Ln noisy luma, Lb clean base luma (from raw stage). Noise of Ln measured from (Ln-Lb):
  var(L) = al*L + be fit on flat-ish residual, GAT, per-DCT-coef noise var = robust median of residual coefs.
Pilot = hard threshold of z (keeps strong 1px edges) with LF (<= 1/4 band) taken from zb.
Wiener of z with that pilot. Returns denoised full-res luma."""
import numpy as np,cv2
import rd2
from numpy.lib.stride_tricks import sliding_window_view as swv
F=np.float32; D=rd2.D
def fitvar(Ln,Lb):
    r=(Ln-Lb).ravel(); l=Lb.ravel(); o=np.argsort(l); l,r=l[o],r[o]
    idx=np.array_split(np.arange(len(l))[(l>1e-4)[np.arange(len(l))]],24)
    xs=np.array([np.median(l[i]) for i in idx]); ys=np.array([(np.median(np.abs(r[i]-np.median(r[i])))/0.6745)**2 for i in idx])
    A=np.stack([xs,np.ones_like(xs)],1); w=1/np.maximum(ys,1e-14); s=np.linalg.lstsq(A*w[:,None],ys*w,rcond=None)[0]
    return max(s[0],1e-9),max(s[1],1e-12)
def coefs(u,s=2,n=8):
    return np.einsum('ki,yxij,lj->yxkl',D,swv(u,(n,n))[::s,::s],D,optimize=True)
def luma_fr(Ln,Lb,lam=2.7,wm=1.0,lfcut=2,tile=512):
    al,be=fitvar(Ln,Lb)
    z=rd2.gat(Ln.astype(np.float64),al,be).astype(F); zb=rd2.gat(Lb.astype(np.float64),al,be).astype(F)
    H,W=z.shape
    # per-coef noise variance from residual, robust
    R=coefs((z-zb)[:min(H,1024),:min(W,1024)],s=8)
    var=(np.median(np.abs(R),axis=(0,1))/0.6745)**2
    out=np.empty_like(z); P=8
    for y in range(0,H,tile):
        for x in range(0,W,tile):
            y0,x0=max(y-P,0),max(x-P,0); y1,x1=min(y+tile+P,H),min(x+tile+P,W)
            o=wien(z[y0:y1,x0:x1],zb[y0:y1,x0:x1],var,lam,wm,lfcut)
            out[y:y+tile,x:x+tile]=o[y-y0:y-y0+min(tile,H-y),x-x0:x-x0+min(tile,W-x)]
    return rd2.igat(out.astype(np.float64),al,be).astype(F)
def wien(u,ub,var,lam,wm,lfcut,s=2,n=8):
    H,W=u.shape; p=n//2; ph=(-(H+2*p-n))%s; pw=(-(W+2*p-n))%s
    pad=lambda a: np.pad(a,((p,p+ph),(p,p+pw)),mode='reflect')
    up,ubp=pad(u),pad(ub)
    X=coefs(up,s); Xb=coefs(ubp,s)
    keep=X*X>lam*lam*var
    k=np.arange(n); lf=(k[:,None]<lfcut)&(k[None]<lfcut)
    Pc=np.where(lf,Xb,X*keep)          # pilot: LF from clean base, HF hard-thresholded own
    Wn=Pc*Pc/(Pc*Pc+var*wm); Wn[...,0,0]=1
    est=np.einsum('ki,yxkl,lj->yxij',D,Wn*X,D,optimize=True)
    o=rd2.agg(est,1/np.maximum((Wn*Wn).sum((-1,-2)),1e-6),*up.shape,s)
    return o[p:p+H,p:p+W]
