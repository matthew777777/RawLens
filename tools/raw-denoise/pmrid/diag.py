# clean crops + synthetic noise: block colour error (in block-noise sigmas) vs a and b/a -> in-domain or not?
import sys,os,numpy as np
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import apply_pmrid as ap
C=np.load(os.path.expanduser('~/Downloads/pmrid_cache/crops.npy'),mmap_mode='r'); rng=np.random.default_rng(3)
def un(p):
    h,w=p.shape[1:]; x=np.empty((2*h,2*w),np.float32); x[0::2,0::2],x[0::2,1::2],x[1::2,0::2],x[1::2,1::2]=p; return x
bl=lambda p:p.reshape(4,16,16,16,16).mean((2,4))
for net in sys.argv[1:]:
  print(net)
  for a in (2e-5,1e-4,4e-4,1.3e-3):
    for r in (1e-3,0.02,0.05,0.3):
        E=[];M=[]
        for i in range(0,12):
            t=np.asarray(C[i*5],np.float64); b=a*r
            n=a*rng.poisson(np.maximum(t,0)/a)+rng.normal(size=t.shape)*np.sqrt(b)
            d=ap.denoise(un(n).astype(np.float32),a,b,net); D=np.stack([d[0::2,0::2],d[0::2,1::2],d[1::2,0::2],d[1::2,1::2]])
            T=bl(t); E.append(((bl(D)-T)/np.sqrt((a*np.maximum(T,0)+b)/256)).std()); M.append(100*abs(D.mean((1,2))/t.mean((1,2))-1).mean())
        print('a %.0e b/a %.3f  block err/noise %.2f  mean colour err %.2f%%'%(a,r,np.mean(E),np.mean(M)),flush=True)
