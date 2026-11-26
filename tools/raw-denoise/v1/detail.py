"""Raw-domain detail recovery + grain instead of binomial grain. Columns: noisy | v1 | old C | variants."""
import os,sys,json,glob,numpy as np,cv2
cv2.setNumThreads(1)
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import rd2,pipe
from lib import demosaic
from numpy.lib.stride_tricks import sliding_window_view as swv
F=np.float32; D=rd2.D; S4=[4]
def dct2(u,var,lam=2.7,wm=1.0,s=2,n=8):
    s=4; wm=np.asarray(wm,F); H,W=u.shape; p=4; ph=(-(H))%s; pw=(-(W))%s
    up=np.pad(u,((p,p+ph),(p,p+pw)),mode='reflect'); X=np.einsum('ki,yxij,lj->yxkl',D,swv(up,(8,8))[::s,::s],D,optimize=True)
    inv=lambda C:np.einsum('ki,yxkl,lj->yxij',D,C,D,optimize=True); o1=lambda c:np.ones(c.shape[:2],F)
    keep=X*X>lam*lam*var*np.minimum(wm,1.0); keep[...,0,0]=True
    pil=rd2.agg(inv(X*keep),o1(X),*up.shape,s); P2=np.einsum('ki,yxij,lj->yxkl',D,swv(pil,(8,8))[::s,::s],D,optimize=True)**2
    G=P2/(P2+var*wm); G[...,0,0]=1
    return rd2.agg(inv(G*X),o1(X),*up.shape,s)[p:p+H,p:p+W]
rd2.dct2=dct2
def resdct(r,var,K,lam,lamc,m):      # r: CFA residual, stabilised domain
    H,W=r.shape; up=np.pad(r,4,mode='reflect'); X=np.einsum('ki,yxij,lj->yxkl',D,swv(up,(8,8))[::4,::4],D,optimize=True)
    t=np.sqrt(var)*(lam*m+lamc*(1-m)); a=np.abs(X)
    g=np.clip((a/t-0.85)/0.3,0,1)                       # soft step at threshold
    G=K*m+(1-K*m)*g
    return rd2.agg(np.einsum('ki,yxkl,lj->yxij',D,G*X,D,optimize=True),np.ones(X.shape[:2],F),*up.shape,4)[4:4+H,4:4+W]
k=np.arange(8)
def mask(pw): c=np.cos(np.pi*k/16)**pw; return np.outer(c,c).astype(F)
def flat(): # carrier cells only: high in either axis
    c=np.clip((7.2-k)/2.0,0,1); return np.outer(c,c).astype(F)
def malvar_luma(r):
    """Luma (mean RGB) of the Malvar-He-Cutler demosaic of a residual mosaic (RGGB): one 5x5 filter per site class."""
    f=lambda k:cv2.filter2D(r,-1,np.array(k,F)/16,borderType=cv2.BORDER_REFLECT_101)
    G=f([[0,0,-2,0,0],[0,0,4,0,0],[-2,4,8,4,-2],[0,0,4,0,0],[0,0,-2,0,0]])               # G at R/B
    RH=f([[0,0,1,0,0],[0,-2,0,-2,0],[-2,8,10,8,-2],[0,-2,0,-2,0],[0,0,1,0,0]])            # colour of the row at G
    RV=f([[0,0,-2,0,0],[0,-2,8,-2,0],[1,0,10,0,1],[0,-2,8,-2,0],[0,0,-2,0,0]])
    X=f([[0,0,-3,0,0],[0,4,0,4,0],[-3,0,12,0,-3],[0,4,0,4,0],[0,0,-3,0,0]])               # R at B / B at R
    L=np.empty_like(r); L[0::2,0::2]=(r+G+X)[0::2,0::2]; L[1::2,1::2]=(r+G+X)[1::2,1::2]
    L[0::2,1::2]=(r+RH+RV)[0::2,1::2]; L[1::2,0::2]=(r+RH+RV)[1::2,0::2]
    return L/3
def lumadct(L,K,lam):   # detail recovery on the luma residual; per-coefficient noise = robust median, as v1
    H,W=L.shape; X=np.einsum('ki,yxij,lj->yxkl',D,swv(np.pad(L,4,mode='reflect'),(8,8))[::4,::4],D,optimize=True)
    sg=np.median(np.abs(X),axis=(0,1))/.6745; g=np.clip((np.abs(X)/(lam*sg)-.85)/.3,0,1)
    return rd2.agg(np.einsum('ki,yxkl,lj->yxij',D,(K+(1-K)*g)*X,D,optimize=True),np.ones(X.shape[:2],F),*(H+8,W+8),4)[4:4+H,4:4+W]

B5=[(dy,dx) for dy in range(-2,3) for dx in range(-2,3)]
def feats(r):   # 6 symmetric taps: centre, +-1 axis h, v, diag, +-2 h, +-2 v  (25 -> orbits)
    R=lambda dy,dx:np.roll(r,(dy,dx),(0,1))
    return np.stack([r,R(0,1)+R(0,-1),R(1,0)+R(-1,0),R(1,1)+R(1,-1)+R(-1,1)+R(-1,-1),R(0,2)+R(0,-2),R(2,0)+R(-2,0),
      R(2,1)+R(2,-1)+R(-2,1)+R(-2,-1),R(1,2)+R(1,-2)+R(-1,2)+R(-1,-2),R(2,2)+R(2,-2)+R(-2,2)+R(-2,-2)],-1)
CL=((0,0),(0,1),(1,0),(1,1)); COEF=None
def fitted(r):
    X=feats(r); L=np.empty_like(r)
    for c,(i,j) in enumerate(CL): L[i::2,j::2]=X[i::2,j::2]@COEF[c]
    return L
def fitall(files):
    global COEF
    A=[np.zeros((9,9)) for _ in CL]; Bv=[np.zeros(9) for _ in CL]
    for j in files:
        m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],F); K=.55+.35*m['t']
        raw={q:v for q,v in pipe.BASE.items() if q in rd2.P0}; raw.update(clev=2,ylev=2); d=rd2.denoise(x,m['a'],m['b'],wb,raw)
        WB=np.tile(np.array([[wb[0],wb[1]],[wb[1],wb[2]]],F),(256,256)); r=(x-d)*WB; sd=np.sqrt(m['a']*cv2.blur(np.clip(d,0,None),(9,9))+m['b'])
        T=(ref*wb).mean(-1)-(demosaic(d)*wb).mean(-1) if TARGET=='v1' else K*((demosaic(x)-demosaic(d))*wb).mean(-1)
        X=feats(r)/sd[...,None]; T=T/K/sd
        for c,(i,k) in enumerate(CL):
            u=X[i::2,k::2][8:-8,8:-8].reshape(-1,9); t=T[i::2,k::2][8:-8,8:-8].ravel(); ok=np.abs(t)<6; A[c]+=u[ok].T@u[ok]; Bv[c]+=u[ok].T@t[ok]
    c=[np.linalg.solve(a,b).astype(F) for a,b in zip(A,Bv)]
    sw=[0,2,1,3,5,4,7,6,8]; g=(c[1]+c[2][sw])/2; rb=(c[0]+c[3])/2; rb=(rb+rb[sw])/2; COEF=[rb,g,g[sw],rb]
    print(TARGET,'RB',np.round(rb,4),'G',np.round(g,4))
TARGET='ea'
Var=[('C binomial',None),('bilinear',('ea',0)),('bil x1.2',('g',1.2)),('bil+rec3',('ea',3.)),('bil+rec2.5',('ea',2.5)),('fit v1',('v1',0))]
FILES=sorted(glob.glob(sys.argv[1]+'/*.json')); CO={}
for TARGET in ('ea','v1'): fitall(FILES); CO[TARGET]=COEF
rows=[]
for j in sorted(glob.glob(sys.argv[1]+'/*.json')):
    m=json.load(open(j)); p=j[:-5]; x=np.load(p+'.in.npy'); ref=np.load(p+'.out.npy'); wb=np.array(m['wb'],F); K=.55+.35*m['t']; a,b=m['a'],m['b']
    f=lambda v:np.sqrt(np.clip(v*wb,0,None)); raw={q:v for q,v in pipe.BASE.items() if q in rd2.P0}; raw.update(clev=2,ylev=2)
    d=rd2.denoise(x,a,b,wb,raw); s=np.sqrt(np.array([wb[0],wb[1],wb[1],wb[2]])/wb[1]); g=np.tile(np.array([[s[0],s[1]],[s[2],s[3]]],F),(256,256))
    z=lambda v:(rd2.gat(v.astype(np.float64),a,b)*g).astype(F)
    # forward of exact-unbiased inverse ~ direct; use plane-domain estimate via numeric gat of d (bias-free enough)
    e=z(d); r=z(x)-e; var=float((g[:2,:2]**2).mean()); V=[demosaic(x),ref]
    fine=lambda v:(lambda l:(l-cv2.GaussianBlur(l,(0,0),1)))(f(v).mean(-1)); fr=fine(ref)
    nc=lambda v:(lambda q:(q-q.mean(-1,keepdims=True)))(f(v))
    print(os.path.basename(p),'| noisy fine %.4f v1 %.4f'%(fine(V[0]).std(),fr.std()))
    for n,c in Var:
        if c is None: b3=np.array([.25,.5,.25],F); q=K*cv2.sepFilter2D(r,-1,b3,b3,borderType=cv2.BORDER_REFLECT_101)
        if c is None: o=demosaic((rd2.igat(((e+q)/g).astype(np.float64),a,b)).astype(F))
        else:
            WB=np.tile(np.array([[wb[0],wb[1]],[wb[1],wb[2]]],F),(256,256)); dd=d
            gk=c[1] if c[0]=='g' else 1; COEF=CO['ea' if c[0]=='g' else c[0]]; c=0 if c[0]=='g' else c[1]; rl=fitted((x-dd)*WB)
            if c: # sigma-normalise by local brightness so thresholds are per-coefficient stationary
                sd=np.sqrt(a*cv2.blur(np.clip(d,0,None),(9,9))+b); rl=lumadct(rl/sd,K,abs(c))*sd
            else: rl=min(K*gk,1)*rl
            o=demosaic(dd)+rl[...,None]/wb
        o=o.astype(F); V.append(o); fo=fine(o); co=nc(o)
        print('  %-16s rms %.3f%% fine %.4f corr-v1 %.3f | chroma rms-v1 %.3f%% chroma fine %.4f (v1 %.4f)'%(n,100*np.sqrt(((f(o)-f(ref))**2).mean()),fo.std(),np.corrcoef(fo.ravel(),fr.ravel())[0,1],100*np.sqrt(((co-nc(ref))**2).mean()),(co-cv2.GaussianBlur(co,(0,0),1.5)).std(),(nc(ref)-cv2.GaussianBlur(nc(ref),(0,0),1.5)).std()),flush=True)
    q=np.quantile(f(ref),.99); rows.append(np.concatenate([np.clip(f(v)[165:285,160:290]/q*1.2,0,1) for v in V],1))
cv2.imwrite(sys.argv[2],cv2.resize((np.concatenate(rows,0)[...,::-1]*255).astype(np.uint8),None,fx=2,fy=2,interpolation=cv2.INTER_NEAREST))
