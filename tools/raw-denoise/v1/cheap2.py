"""DCT variants for the port, all raw-domain with raw grain. Prints RMS vs frozen v1 and flat-area block-grid energy."""
import os,sys,json,glob,numpy as np,cv2
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import rd2,pipe
from lib import demosaic
from numpy.lib.stride_tricks import sliding_window_view as swv
F=np.float32; D=rd2.D; M=dict(s=4,uni=0,one=0,win=0)
w1=np.sin(np.pi*(np.arange(8)+.5)/8)**2; W2=(np.outer(w1,w1)).astype(F)   # sums to a constant at stride 4
def agg(est,w,H,W,s):
    n=np.zeros((H,W),F); d=np.zeros((H,W),F); by,bx=w.shape; K=W2 if M['win'] else np.ones((8,8),F)
    for i in range(8):
        for j in range(8): n[i:i+by*s:s,j:j+bx*s:s]+=est[:,:,i,j]*w*K[i,j]; d[i:i+by*s:s,j:j+bx*s:s]+=w*K[i,j]
    return n/np.maximum(d,1e-12)
def dct2(u,var,lam=2.7,wm=1.0,s=2,n=8):
    s=M['s']; wm=np.asarray(wm,F); H,W=u.shape; p=4; ph=(-(H+8-n))%s; pw=(-(W+8-n))%s
    up=np.pad(u,((p,p+ph),(p,p+pw)),mode='reflect'); X=np.einsum('ki,yxij,lj->yxkl',D,swv(up,(8,8))[::s,::s],D,optimize=True)
    inv=lambda C:np.einsum('ki,yxkl,lj->yxij',D,C,D,optimize=True); one=lambda c:np.ones(c.shape[:2],F)
    if M['one']:
        P=X*X; th=lam*lam*var*np.minimum(wm,1.0); G=np.where(P>th,P/(P+var*wm),0); G[...,0,0]=1
        return agg(inv(G*X),one(X) if M['uni'] else 1/np.maximum((G*G).sum((-1,-2)),1e-6),*up.shape,s)[p:p+H,p:p+W]
    keep=X*X>lam*lam*var*np.minimum(wm,1.0); keep[...,0,0]=True
    pil=agg(inv(X*keep),one(X) if M['uni'] else 1/keep.sum((-1,-2)).astype(F),*up.shape,s)
    P2=np.einsum('ki,yxij,lj->yxkl',D,swv(pil,(8,8))[::s,::s],D,optimize=True)**2; G=P2/(P2+var*wm); G[...,0,0]=1
    return agg(inv(G*X),one(X) if M['uni'] else 1/np.maximum((G*G).sum((-1,-2)),1e-6),*up.shape,s)[p:p+H,p:p+W]
rd2.dct2=dct2
def rawgrain(x,d,wb,K,c):
    W=np.tile(np.array([[wb[0],wb[1]],[wb[1],wb[2]]],F),(x.shape[0]//2,x.shape[1]//2)); b3=np.array([.25,.5,.25],F)
    return d+K*cv2.sepFilter2D((x-d)*W,-1,b3,b3,borderType=cv2.BORDER_REFLECT_101)/W
names=[('2stage s4 adaptive',{}),('2stage s4 uniform',dict(uni=1)),('2stage s4 window',dict(uni=1,win=1)),('1stage s4 window',dict(uni=1,win=1,one=1)),('1stage s2 uniform',dict(s=2,uni=1,one=1)),('2stage s2 adaptive',dict(s=2))]
rows=[]
for j in sorted(glob.glob(sys.argv[1]+'/*.json')):
    m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],F); K=.55+.35*m['t']
    f=lambda v:np.sqrt(np.clip(v*wb,0,None)); raw={k:v for k,v in pipe.BASE.items() if k in rd2.P0}; raw.update(clev=2,ylev=2); V=[ref]; print(os.path.basename(p))
    for n,c in names:
        M.update(s=4,uni=0,one=0,win=0); M.update(c); d=rd2.denoise(x,m['a'],m['b'],wb,raw); o=demosaic(rawgrain(x,d,wb,K,0)); V.append(o)
        L=f(demosaic(d)).mean(-1); gx=np.abs(np.diff(L,axis=1)).mean(0)[8:-8]; k=len(gx)//8*8; g=gx[:k].reshape(-1,8).mean(0)
        C=f(o); C=C-C.mean(-1,keepdims=True); C0=f(ref); C0=C0-C0.mean(-1,keepdims=True)
        print('  %-20s rms %.3f%% chroma rms %.3f%% grid %.3f'%(n,100*np.sqrt(((f(o)-f(ref))**2).mean()),100*np.sqrt(((C-C0)**2).mean()),g.std()/g.mean()))
    q=np.quantile(f(ref),.99); rows.append(np.concatenate([np.clip(f(v)[160:300,160:330]/q*1.2,0,1) for v in V],1))
cv2.imwrite(sys.argv[2],cv2.resize((np.concatenate(rows,0)[...,::-1]*255).astype(np.uint8),None,fx=2,fy=2,interpolation=cv2.INTER_NEAREST))
