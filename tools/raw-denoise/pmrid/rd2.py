"""rawdn2 numpy prototype (no torch). CFA in -> CFA out.
GAT + sqrt(wb) eq -> Hadamard (Y,Ca,Cb,D) half-res planes
Y   : 2-stage sliding 8x8 DCT (hard pilot -> Wiener), 2-level pyramid for LF, uniform grain back
C   : same DCT core, strong, 3-level pyramid, then Y-guided filter (no bleeding)
"""
import numpy as np
from numpy.lib.stride_tricks import sliding_window_view as swv
F=np.float32
def dctm(n=8):
    k=np.arange(n)[:,None]; i=np.arange(n)[None]
    m=np.cos(np.pi*(2*i+1)*k/(2*n))*np.sqrt(2/n); m[0]/=np.sqrt(2); return m.astype(F)
D=dctm()
def gat(x,a,b): return (2/a)*np.sqrt(np.maximum(a*x+0.375*a*a+b,0))
def igat(z,a,b):
    z=np.maximum(z,1.0); i0=0.25*z*z+0.25*np.sqrt(1.5)/z-11/8/(z*z)+5/8*np.sqrt(1.5)/z**3-0.125
    return a*i0-b/a
def agg(est,w,H,W,s,n=8):
    num=np.zeros((H,W),F); den=np.zeros((H,W),F)
    by,bx=w.shape
    for i in range(n):
        for j in range(n):
            num[i:i+by*s:s, j:j+bx*s:s]+=est[:,:,i,j]*w
            den[i:i+by*s:s, j:j+bx*s:s]+=w
    return num/np.maximum(den,1e-12)
def dct2(u,var,lam=2.7,wm=1.0,s=2,n=8):
    wm=np.asarray(wm,F)
    """u plane, var = white noise variance of u."""
    H,W=u.shape; p=n//2
    ph=(-(H+2*p-n))%s; pw=(-(W+2*p-n))%s
    up=np.pad(u,((p,p+ph),(p,p+pw)),mode='reflect')
    B=swv(up,(n,n))[::s,::s]
    X=np.einsum('ki,yxij,lj->yxkl',D,B,D,optimize=True)
    keep=X*X>lam*lam*var*np.minimum(wm,1.0); keep[...,0,0]=True
    est=np.einsum('ki,yxkl,lj->yxij',D,X*keep,D,optimize=True)
    pil=agg(est,1/keep.sum((-1,-2)).astype(F),*up.shape,s)
    PB=np.einsum('ki,yxij,lj->yxkl',D,swv(pil,(n,n))[::s,::s],D,optimize=True)
    P2=PB*PB; Wn=P2/(P2+var*wm); Wn[...,0,0]=1
    est=np.einsum('ki,yxkl,lj->yxij',D,Wn*X,D,optimize=True)
    out=agg(est,1/np.maximum((Wn*Wn).sum((-1,-2)),1e-6),*up.shape,s)
    return out[p:p+H,p:p+W]
def down(u):
    H,W=u.shape; u=u[:H&~1,:W&~1]; return 0.25*(u[0::2,0::2]+u[0::2,1::2]+u[1::2,0::2]+u[1::2,1::2])
def up2(u,shape):
    import cv2; return cv2.resize(u,(shape[1],shape[0]),interpolation=cv2.INTER_LINEAR)
def fmask(hf,lf,pw=1.5,n=8):
    k=np.arange(n); r=np.sqrt(k[:,None]**2+k[None]**2)/np.sqrt(2*(n-1)**2)
    return (hf+(lf-hf)*(1-r)**pw).astype(F)
def ms(u,var,lv,wm,lam,wmc=None):
    """wm: multiplier (scalar or 8x8) at this level; wmc: used for coarser levels."""
    if lv==0 or min(u.shape)<64: return dct2(u,var,lam,wm)
    c=ms(down(u),var/4,lv-1,wm if wmc is None else wmc,lam,wmc)
    f=dct2(u,var,lam,wm)
    return f+up2(c-down(f),f.shape)
def box(u,r):
    import cv2; return cv2.blur(u,(2*r+1,2*r+1),borderType=cv2.BORDER_REFLECT)
def guided(I,P,r,eps):
    mI=box(I,r); mP=box(P,r); a=(box(I*P,r)-mI*mP)/(box(I*I,r)-mI*mI+eps); b=mP-a*mI
    return box(a,r)*I+box(b,r)
P0=dict(yhf=0.25,ylf=2.0,ycoarse=3.0,lamy=2.7,ylev=3,chroma=4.0,clev=3,dhf=0.5,grain=0.0,gr=3,geps=2.0,hfk=0.6,hfs=1.0,chf=None,ccoarse=None,clam=2.7)
def denoise(x,a,b,wb,prm=None):
    p=dict(P0); p.update(prm or {})
    H,W=x.shape
    s=np.sqrt(np.array([wb[0],wb[1],wb[1],wb[2]])/wb[1])
    g=np.tile(np.array([[s[0],s[1]],[s[2],s[3]]],F),(H//2,W//2))
    z=(gat(x.astype(np.float64),a,b)*g).astype(F)
    r,g1,g2,bb=z[0::2,0::2],z[0::2,1::2],z[1::2,0::2],z[1::2,1::2]
    Y=(r+g1+g2+bb)/4; Ca=(r-bb)/2; Cb=(r+bb-g1-g2)/4; Dd=(g1-g2)/2
    sr,sg,sb=s[0]**2,s[1]**2,s[3]**2
    vY=(sr+2*sg+sb)/16; vA=(sr+sb)/4; vB=(sr+sb+2*sg)/16; vD=sg/2
    Yd=ms(Y,vY,p['ylev'],fmask(p['yhf'],p['ylf']),p['lamy'],p['ycoarse'])
    cw=p['chroma'] if p['chf'] is None else fmask(p['chf'],p['chroma'])
    cc=p['ccoarse'] or p['chroma']
    Ad=ms(Ca,vA,p['clev'],cw,p['clam'],cc); Bd=ms(Cb,vB,p['clev'],cw,p['clam'],cc)
    Dn=dct2(Dd,vD,2.7,fmask(p['dhf'],p['chroma']))
    if p['gr']>0:
        e=p['geps']*vY
        Ad=guided(Yd,Ad,p['gr'],e); Bd=guided(Yd,Bd,p['gr'],e)
    Yo=Yd+p['grain']*(Y-Yd)
    out=np.empty_like(z)
    out[0::2,0::2]=Yo+Bd+Ad; out[1::2,1::2]=Yo+Bd-Ad
    out[0::2,1::2]=Yo-Bd+Dn; out[1::2,0::2]=Yo-Bd-Dn
    if p['hfk']>0:
        import cv2
        r=z-out
        b3=np.array([.25,.5,.25],F)
        L=cv2.sepFilter2D(r,-1,b3,b3,borderType=cv2.BORDER_REFLECT)      # kills CFA carrier -> achromatic
        hp=L-cv2.GaussianBlur(L,(0,0),p['hfs'],borderType=cv2.BORDER_REFLECT)  # finest band only
        out=out+p['hfk']*hp
    return igat((out/g).astype(np.float64),a,b).astype(F)
