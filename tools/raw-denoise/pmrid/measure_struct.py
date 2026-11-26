"""Structured noise (row, LF chroma) vs the white model, as a function of patch flatness. Texture inflates both
ratios, so only the flattest patches (total HP std / model sigma ~ 1) are evidence."""
import sys,os,numpy as np,cv2
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng,estimate_noise
T=64
def stats(t,sw):   # t 4xTxT
    hp=t-cv2.blur(t.transpose(1,2,0),(15,15)).transpose(2,0,1)
    d=t-t.mean(0,keepdims=True)
    lo=cv2.resize(d.transpose(1,2,0),(8,8),interpolation=cv2.INTER_AREA).transpose(2,0,1)
    yy,xx=np.mgrid[-1:1:8j,-1:1:8j]; A=np.stack([yy.ravel(),xx.ravel(),np.ones(64)],1)   # detrend plane (scene gradient)
    r=lo.reshape(4,64).T; r=r-A@np.linalg.lstsq(A,r,rcond=None)[0]
    return hp.std()/sw, hp.mean(2).std()/(sw/8), r.std()/(sw/8)
rng=np.random.default_rng(0)
ref=np.array([stats(rng.normal(size=(4,T,T)),1.0) for _ in range(300)]).mean(0); print('white reference hp %.3f row %.3f lf %.3f'%tuple(ref))
for f in sys.argv[1:]:
    x,wb,_,_=load_dng(f); a,b,_=estimate_noise(x)
    P=np.stack([x[0::2,0::2],x[0::2,1::2],x[1::2,0::2],x[1::2,1::2]]).astype(np.float64); S=[]
    for y in range(0,P.shape[1]-T,T):
        for z in range(0,P.shape[2]-T,T):
            t=P[:,y:y+T,z:z+T]
            if not 0.002<t.mean()<0.2: continue
            t=t/np.sqrt(np.maximum(a*t.mean((1,2))+b,1e-12))[:,None,None]   # per-plane white sigma -> 1
            S.append(stats(t,1.0))
    S=np.array(S)/ref; S=S[np.argsort(S[:,0])]; n=len(S)
    print(os.path.basename(f),n,'| '+' | '.join('flat %d-%d%%: hp %.2f row %.2f lf %.2f'%((p0,p1)+tuple(np.median(S[n*p0//100:n*p1//100],0))) for p0,p1 in ((0,5),(5,15),(40,60))))
