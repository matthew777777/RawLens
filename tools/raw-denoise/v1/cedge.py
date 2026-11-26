"""Luma-guided chroma edge restore (joint bilateral of chroma residual, guide = clean full-res luma)."""
import numpy as np,cv2
F=np.float32
def jbf(R,G,r,ss,sr):
    """R (H,W,C) residual, G (H,W) guide. weights exp(-d2/2ss2 - (dG)^2/2sr2)."""
    H,W,C=R.shape; Rp=np.pad(R,((r,r),(r,r),(0,0)),mode='reflect'); Gp=np.pad(G,r,mode='reflect')
    num=np.zeros_like(R); den=np.zeros((H,W,1),F)
    for dy in range(-r,r+1):
        for dx in range(-r,r+1):
            ws=np.exp(-(dx*dx+dy*dy)/(2*ss*ss))
            g=Gp[r+dy:r+dy+H,r+dx:r+dx+W]
            w=(ws*np.exp(-((g-G)**2)/(2*sr*sr)))[...,None].astype(F)
            num+=w*Rp[r+dy:r+dy+H,r+dx:r+dx+W]; den+=w
    return num/den
def restore(nz,base,wb,Lguide,r=3,ss=2.0,srk=2.0,shk=1.0,med=0):
    """nz, base: camera-linear RGB; returns base with chroma edges restored."""
    Wn=nz*wb; Wb=base*wb
    Cn=Wn-Wn.mean(-1,keepdims=True); Cb=Wb-Wb.mean(-1,keepdims=True)
    G=np.sqrt(np.clip(Lguide,0,None)).astype(F)     # perceptual guide
    # noise of guide in sqrt domain: estimate from MAD of HF of guide
    hp=G-cv2.GaussianBlur(G,(0,0),1.0); sr=srk*float(np.median(np.abs(hp))/0.6745)+1e-6
    Rc=jbf((Cn-Cb).astype(F),G,r,ss,sr)
    if med: Rc=np.stack([cv2.medianBlur(np.ascontiguousarray(Rc[...,i]),med) for i in range(3)],-1)
    if shk>0:
        E=cv2.blur((Rc*Rc).sum(-1),(5,5))
        nv=float(np.median(E))                     # edges are sparse: median local energy = residual noise
        g=np.maximum(0,1-shk*nv/np.maximum(E,1e-12)).astype(F)
        Rc=Rc*g[...,None]
    return ((Wb+Rc)/wb).astype(F)
