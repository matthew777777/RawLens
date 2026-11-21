import sys,os,time,numpy as np,cv2
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); sys.path.insert(0,os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','..','wavelet-parity'))
from lib import load_dng,estimate_noise,demosaic,develop
import rd2, esd3d2_port as E
out=sys.argv[1]; os.makedirs(out,exist_ok=True)
CS=320
def crops(x):
    H,W=x.shape; Y=cv2.resize(x,(W//32,H//32),interpolation=cv2.INTER_AREA)
    lap=np.abs(cv2.Laplacian(cv2.GaussianBlur(x,(0,0),2),cv2.CV_32F)); L=cv2.resize(lap,(W//32,H//32),interpolation=cv2.INTER_AREA)
    k=CS//32; m=np.full_like(Y,np.inf); 
    res=[]
    # darkest-but-not-black flat, mid flat, most detailed mid-tone
    ys,xs=np.mgrid[0:Y.shape[0]-k,0:Y.shape[1]-k]
    mean=cv2.blur(Y,(k,k))[k//2:k//2+ys.shape[0],k//2:k//2+ys.shape[1]]
    tex=cv2.blur(L,(k,k))[k//2:k//2+ys.shape[0],k//2:k//2+ys.shape[1]]
    q=np.quantile(mean,[.15,.5]);
    for target,score in ((q[0],tex),(q[1],tex),(None,-tex)):
        sc=score.copy()
        if target is not None: sc=sc+1e3*np.abs(np.log((mean+1e-4)/(target+1e-4)))
        else: sc=sc+1e3*(mean<q[0])
        iy,ix=np.unravel_index(np.argmin(sc),sc.shape); res.append((iy*32&~1,ix*32&~1))
    return res
rows=[]
for f in sys.argv[2:]:
    x,wb,iso,_=load_dng(f); a,b,_=estimate_noise(x)
    mid=np.exp(np.mean(np.log(np.clip(x[::7,::7],1e-4,None)))); ev=min(np.log2(0.18/mid),1.5)*(0.25 if iso>300 else 1.0)  # ~new low-key lift
    print(os.path.basename(f),'iso',iso,'a=%.2e b=%.2e'%(a,b),'ev %.2f'%ev,flush=True)
    for (y0,x0) in crops(x):
        P=32; c=x[max(y0-P,0):y0+CS+P, max(x0-P,0):x0+CS+P]; oy=y0-max(y0-P,0); ox=x0-max(x0-P,0)
        import pipe
        NET=os.environ.get('NET'); V=[dict(_full=x)]+([dict(_full=x,net=NET)] if NET else [])
        t=time.time(); ds=[pipe.pipe(c,a,b,wb,v) for v in V]; t2=time.time()-t
        bb=demosaic(rd2.denoise(c,a,b,wb,dict(gr=0,chf=1.0,ycoarse=1.0,yhf=0.8,ylf=1.0,hfk=0.0))); nzz=demosaic(c)
        v7=bb+0.5*((nzz*wb).mean(-1)-(bb*wb).mean(-1))[...,None]/wb
        rgbN=demosaic(c)
        esd=E.esd3d2(np.clip(rgbN,0,None),a,b)[0]
        tiles=[develop(v,wb,ev=ev+np.log2(1/ wb[1]) if False else ev)[oy:oy+CS,ox:ox+CS][::-1] for v in [rgbN,esd]+ds]
        rows.append(np.concatenate(tiles,1))
g=(np.concatenate(rows,0)*255).clip(0,255).astype(np.uint8)
cv2.imwrite(os.path.join(out,'grid.png'),cv2.cvtColor(g,cv2.COLOR_RGB2BGR)); print('wrote',out)
