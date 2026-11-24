"""Measure structured noise in dark flat areas of real high-ISO frames (our own estimate, no DNG profile):
row noise (std of row means of high-passed planes vs white expectation) and low-freq chroma noise
(std of 8x-box-downsampled per-plane residual minus plane mean, vs white expectation). Ratios to white sigma."""
import sys,os,numpy as np,cv2
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng,estimate_noise
for f in sys.argv[1:]:
    x,wb,_,_=load_dng(f); a,b,_=estimate_noise(x)
    P=np.stack([x[0::2,0::2],x[0::2,1::2],x[1::2,0::2],x[1::2,1::2]]).astype(np.float64)
    L=P.mean(0); T=64; rr=[];cc=[]
    for y in range(0,L.shape[0]-T,T):
        for z in range(0,L.shape[1]-T,T):
            t=P[:,y:y+T,z:z+T]; m=t.mean()
            if m<=0 or m>0.03: continue
            sw=np.sqrt(a*m+b)
            hp=t-cv2.blur(t.transpose(1,2,0),(15,15)).transpose(2,0,1)  # remove scene LF
            if hp.std()>2*sw: continue              # not flat
            row=hp.mean(2).std()/(sw/np.sqrt(T)); rr.append(row)
            d=t-t.mean(0,keepdims=True)              # chroma differences
            lo=cv2.resize(d.transpose(1,2,0),(T//8,T//8),interpolation=cv2.INTER_AREA).transpose(2,0,1)
            cc.append((lo-lo.mean((1,2),keepdims=True)).std()/(sw/8))
    print(os.path.basename(f),'a %.2e b %.2e flats %d  row/white %.2f  LFchroma/white %.2f'%(a,b,len(rr),np.median(rr),np.median(cc)))
