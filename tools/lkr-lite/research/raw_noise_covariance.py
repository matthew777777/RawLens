# SPDX-License-Identifier: Apache-2.0
"""Audit spatial and cross-frame RAW residual correlation in a marked sky ROI.
The ROI must be independently known to contain smooth open sky. The fit
removes a degree-three illumination polynomial per CFA phase; the highpass
and offset controls distinguish smooth scene residual from sensor-coordinate
structure. This does not identify a hardware/ISP cause or certify a fix.
"""
import argparse,json
from pathlib import Path
import numpy as np
from scipy.ndimage import gaussian_filter
p=argparse.ArgumentParser();p.add_argument('trace_prefix',type=Path);p.add_argument('output',type=Path);p.add_argument('--sky-roi',required=True,help='x,y,width,height in trace RAW coordinates; all even');args=p.parse_args();prefix=args.trace_prefix
m=json.loads(Path(str(prefix)+'-meta.json').read_text());K,h,w=m['K'],m['h'],m['w'];raw=np.fromfile(str(prefix)+'-raw.f32','f4').reshape(K,h,w)
try:
 x0,y0,rw,rh=[int(v) for v in args.sky_roi.split(',')]
 if min(x0,y0)<0 or min(rw,rh)<16 or any(v%2 for v in [x0,y0,rw,rh]) or x0+rw>w or y0+rh>h: raise ValueError()
except ValueError:
 p.error('--sky-roi requires in-bounds even x,y,width,height, size >=16')
# The marked ROI must contain smooth open sky, away from the skyline.
# Fit only smooth illumination; this is not an image-quality score.
report=[]
residuals={}
for k in range(K):
 for py in range(2):
  for px in range(2):
   a=raw[k,y0+py:y0+rh:2,x0+px:x0+rw:2].astype(float);yy,xx=np.mgrid[:a.shape[0],:a.shape[1]];yy=(yy-yy.mean())/np.ptp(yy);xx=(xx-xx.mean())/np.ptp(xx)
   X=np.stack([xx**i*yy**j for i in range(4) for j in range(4-i)],axis=-1);coef=np.linalg.lstsq(X.reshape(-1,10),a.ravel(),rcond=None)[0];signal=X@coef;res=a-signal
   def corr(dy,dx):
    y0=max(0,-dy);y1=min(a.shape[0],a.shape[0]-dy);x0=max(0,-dx);x1=min(a.shape[1],a.shape[1]-dx);u=res[y0:y1,x0:x1].ravel();v=res[y0+dy:y1+dy,x0+dx:x1+dx].ravel();return float(np.corrcoef(u,v)[0,1])
   residuals[(k,py,px)]=res.copy()
   variance=np.maximum(m['a']*signal+m['b'],1e-12)
   report.append(dict(frame=k,phase=[py,px],mean=float(a.mean()),residual_rms=float(np.sqrt(np.mean(res*res))),model_rms=float(np.sqrt(np.mean(variance))),covariance={f'{dy},{dx}':corr(dy,dx) for dy,dx in [(0,1),(1,0),(1,1),(0,2),(2,0),(0,3),(3,0)]}))
args.output.write_text(json.dumps(report,indent=2))
for phase in [[0,0],[0,1],[1,0],[1,1]]:
 rows=[r for r in report if r['phase']==phase];print(phase,'RMS ratio',np.median([r['residual_rms']/r['model_rms'] for r in rows]),'correlations median',{key:float(np.median([r['covariance'][key] for r in rows])) for key in rows[0]['covariance']})

fixed=[]
for phase in [(0,0),(0,1),(1,0),(1,1)]:
 a=np.stack([residuals[(k,*phase)].ravel() for k in range(K)])
 corr=np.corrcoef(a);off=corr[np.triu_indices(K,1)]
 entry=dict(phase=phase,crossframe_correlation_median=float(np.median(off)),crossframe_correlation_max=float(off.max()),correlation_matrix=corr.tolist())
 fixed.append(entry);print('cross-frame',phase,'median',entry['crossframe_correlation_median'],'max',entry['crossframe_correlation_max'])
args.output.with_name(args.output.stem+'-crossframe.json').write_text(json.dumps(fixed,indent=2))

highpass=[]
for phase in [(0,0),(0,1),(1,0),(1,1)]:
 a=np.stack([(residuals[(k,*phase)]-gaussian_filter(residuals[(k,*phase)],2)).ravel() for k in range(K)])
 corr=np.corrcoef(a);off=corr[np.triu_indices(K,1)]
 highpass.append(dict(phase=phase,crossframe_highpass_correlation_median=float(np.median(off)),correlation_matrix=corr.tolist()))
 print('cross-frame highpass',phase,'median',np.median(off))
args.output.with_name(args.output.stem+'-highpass.json').write_text(json.dumps(highpass,indent=2))

lagged=[]
for phase in [(0,0),(0,1),(1,0),(1,1)]:
 a=np.stack([residuals[(k,*phase)]-gaussian_filter(residuals[(k,*phase)],2) for k in range(K)])
 vals={}
 for dy,dx in [(0,0),(0,1),(0,-1),(1,0),(-1,0),(1,1)]:
  scores=[];hh,ww=a.shape[1:];y0=max(0,-dy);y1=min(hh,hh-dy);x0=max(0,-dx);x1=min(ww,ww-dx)
  for k in range(K):
   for j in range(k+1,K):
    u=a[k,y0:y1,x0:x1].ravel();v=a[j,y0+dy:y1+dy,x0+dx:x1+dx].ravel();scores.append(float(np.corrcoef(u,v)[0,1]))
  vals[f'{dy},{dx}']=float(np.median(scores))
 lagged.append(dict(phase=phase,median_crossframe_correlations=vals));print('shifted',phase,vals)
args.output.with_name(args.output.stem+'-lagged.json').write_text(json.dumps(lagged,indent=2))
