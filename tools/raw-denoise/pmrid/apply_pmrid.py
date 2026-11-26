"""Run the trained PMRID on a CFA (RGGB normalised) with our own noise estimate; returns denoised CFA."""
import os,sys,numpy as np,torch
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from model import PMRID
_M={}
def net(path):
    if not isinstance(path,str): return path          # a module (QNet) passed directly
    if path not in _M:
        if path.endswith('.q.pt'):                       # INT8 simulation (quantize.py / QAT)
            import qnet; _M[path]=qnet.load(path)
        else:
            m=PMRID(); m.load_state_dict(torch.load(path,map_location='cpu')); m.eval(); _M[path]=m
    return _M[path]
def gat(x,a,b): return (2.0/a)*np.sqrt(np.maximum(a*x+0.375*a*a+b,0))
def igat(z,a,b):
    z=np.maximum(z,1.0); i0=0.25*z*z+0.25*np.sqrt(1.5)/z-11/8/(z*z)+5/8*np.sqrt(1.5)/z**3-0.125
    return a*i0-b/a
def denoise(x,a,b,path,T=512,P=32):
    m=net(path); H,W=x.shape
    p=np.stack([x[0::2,0::2],x[0::2,1::2],x[1::2,0::2],x[1::2,1::2]]).astype(np.float64)
    g0=gat(0.0,a,b); g1=gat(1.0,a,b)-g0; u=((gat(p,a,b)-g0)/g1).astype(np.float32); h,w=u.shape[1:]
    # pad to multiple of 16
    ph=(-h)%16; pw=(-w)%16; up=np.pad(u,((0,0),(0,ph),(0,pw)),mode='reflect'); hp,wp=up.shape[1:]
    out=np.empty_like(up)
    with torch.no_grad():
        for y in range(0,hp,T):
            for z in range(0,wp,T):
                y0,z0=max(y-P,0),max(z-P,0); y1,z1=min(y+T+P,hp),min(z+T+P,wp)
                t=up[:,y0:y1,z0:z1]; th,tw=t.shape[1:]; qh=(-th)%16; qw=(-tw)%16
                tt=torch.from_numpy(np.pad(t,((0,0),(0,qh),(0,qw)),mode='reflect'))[None]
                s=torch.full((1,1,th+qh,tw+qw),float(1.0/g1))
                o=m(tt,s)[0].numpy()[:,:th,:tw]
                out[:,y:y+T,z:z+T]=o[:,y-y0:y-y0+min(T,hp-y),z-z0:z-z0+min(T,wp-z)]
    d=igat(out[:,:h,:w].astype(np.float64)*g1+g0,a,b).astype(np.float32)
    c=np.empty_like(x); c[0::2,0::2],c[0::2,1::2],c[1::2,0::2],c[1::2,1::2]=d
    return c
