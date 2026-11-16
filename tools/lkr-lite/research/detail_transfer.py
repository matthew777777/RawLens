# SPDX-License-Identifier: Apache-2.0
"""Known-detail and noise transfer audit with captured geometry/rejection.
Run: python detail_transfer.py TRACE_PREFIX OUTPUT_DIR
Requires numpy, scipy, pillow. No GPU required. It evaluates the documented
float operator, not fixed-point shader execution. Probe and noise responses
start at zero to measure the linear data response separately from the seed.
Real-scene solutions are controls, not ground truth. This cannot by itself
certify real-burst quality.
"""
import argparse, json
from pathlib import Path
import numpy as np
from lite_model import forward_matrix
parser=argparse.ArgumentParser()
parser.add_argument('trace_prefix',type=Path)
parser.add_argument('output_dir',type=Path)
parser.add_argument('--luma-operator',choices=['laplacian','axes'],default='laplacian')
parser.add_argument('--penalty',action='append',help='luma,colour strengths; repeat for multiple controls')
parser.add_argument('--angles',action='store_true',help='probe every 15 degrees from horizontal to vertical')
parser.add_argument('--max-iterations',type=int,default=120)
args=parser.parse_args()
if args.max_iterations<1: parser.error('--max-iterations must be positive')
p=args.trace_prefix;r=args.output_dir;r.mkdir(parents=True,exist_ok=True)
meta=json.loads(Path(str(p)+'-meta.json').read_text())
if (meta['h'],meta['w'],meta['s'],meta['K']) != (512,512,2,8):
    parser.error('This audit requires an eight-frame 512x512 2x tile trace')
f=lambda name:np.fromfile(str(p)+'-'+name+'.f32',np.float32)
K=8;h=w=128;H=W=256;n=H*W;o=128
raw=f('raw').reshape(K,512,512)[:,o:o+h,o:o+w]
weights=f('weights').reshape(K,512,512)[:,o:o+h,o:o+w]
P=f('affine').reshape(K,6).copy()
P[:,2]+=(P[:,0]+P[:,1]-1)*o
P[:,5]+=(P[:,3]+P[:,4]-1)*o
D=f('local').reshape(K,32,32,2)[:,8:16,8:16].reshape(K,-1,2)
A,valid=forward_matrix(dict(K=K,h=h,w=w,H=H,W=W,s=2),P,D)
A=A.astype(np.float32);sw=np.sqrt((weights*valid).ravel())
Y=raw.astype(np.float16).astype(np.float32).ravel()
wb=np.array(meta['wb'],np.float32)[:,None]
seed=f('seed').reshape(3,1024,1024)[:,2*o:2*(o+h),2*o:2*(o+w)].reshape(3,n)
from scipy.sparse.linalg import cg, LinearOperator
# Camera RGB -> WB-space Y,U,V, all at full resolution. Penalize derivatives
# inside the sensor-data objective rather than blur an already fitted image.
def rgb(x):
 y,u,v=x.reshape(3,n);return np.array([y+u,y-.5*(u+v),y+v])/wb
def adjrgb(g):
 g=g.reshape(3,n)/wb;return np.array([g.sum(0),g[0]-.5*g[1],g[2]-.5*g[1]]).ravel()
def lap(x):
 a=x.reshape(H,W);z=np.zeros_like(a);d=a[:,1:]-a[:,:-1];z[:,:-1]-=d;z[:,1:]+=d;d=a[1:]-a[:-1];z[:-1]-=d;z[1:]+=d;return z.ravel()
def axis_curvature(x):
 a=x.reshape(H,W);out=np.zeros_like(a)
 for axis in [0,1]:
  q=np.moveaxis(a,axis,0);z=np.moveaxis(out,axis,0)
  d=q[:-2]-2*q[1:-1]+q[2:]
  z[:-2]+=d;z[1:-1]-=2*d;z[2:]+=d
 return out.ravel()
weights0=sw**2
from scipy.ndimage import uniform_filter
import json
cov=(A.T@weights0).reshape(3,H,W);cv=uniform_filter(cov,size=(1,3,3),mode='constant');gain=1-np.abs(1-cv)**3;div=np.maximum(np.maximum(cv/np.maximum(gain,1e-4),1e-6),cov/1.8).ravel()
yy,xx=np.mgrid[:H,:W];core=(slice(32,-32),slice(32,-32));report=[]
# Six sinusoidal probes at specified horizontal/diagonal frequencies.
probes=[('horizontal .125',.125,0),('diagonal .125',.08838835,.08838835),('horizontal .25',.25,0),('diagonal .25',.1767767,.1767767),('horizontal .35',.35,0),('diagonal .35',.24748737,.24748737)]
if args.angles:
 probes=[(f'{frequency:g} at {angle} degrees',frequency*np.cos(np.deg2rad(angle)),frequency*np.sin(np.deg2rad(angle))) for frequency in [.125,.25,.35] for angle in range(0,91,15)]
penalties=[(.01,.1),(.015,.1),(.02,.1)]
if args.penalty:
 try:
  penalties=[tuple(float(v) for v in value.split(',')) for value in args.penalty]
  if any(len(p)!=2 or any(not np.isfinite(v) or v<0 for v in p) for p in penalties): raise ValueError()
 except ValueError:
  parser.error('--penalty must contain two finite nonnegative strengths: luma,colour')
rng=np.random.default_rng(732);noise=rng.normal(size=len(Y)).astype('f4')*.0015
for method in ['current',*penalties]:
 statuses=[]
 if method!='current':
  ly,lc=method
  def normal(x):
   g=adjrgb(A.T@(weights0*(A@rgb(x).ravel()))).reshape(3,n)
   g[0]+=ly*(axis_curvature(x[:n]) if args.luma_operator=='axes' else lap(lap(x[:n])));g[1]+=lc*lap(x[n:2*n]);g[2]+=lc*lap(x[2*n:]);return g.ravel()+1e-7*x
  op=LinearOperator((3*n,3*n),matvec=normal,dtype='f4')
 def solve(obs):
  if method=='current':
   z=np.zeros(3*n,'f4')
   for it in range(8):z-=A.T@(weights0*(A@z-obs))/div
   return z.reshape(3,n)
  rhs=adjrgb(A.T@(weights0*obs));x,info=cg(op,rhs,rtol=1e-5,maxiter=args.max_iterations);statuses.append(int(info));return rgb(x)
 z=solve(noise);l=(np.array([.25,.5,.25])@(z*wb)).reshape(H,W)[core];entry={'method':str(method),'luma_operator':args.luma_operator,'noise_luma_rms':float(np.sqrt(np.mean(l*l))),'transfer':[]}
 for name,fx,fy in probes:
  wave=np.sin(2*np.pi*(fx*xx+fy*yy)).astype('f4');truth=np.tile(wave.ravel(),(3,1))/wb;z=solve(A@truth.ravel());l=(np.array([.25,.5,.25])@(z*wb)).reshape(H,W)[core];g=float(np.sum(l*wave[core])/np.sum(wave[core]**2));entry['transfer'].append({'probe':name,'gain':g})
 solve(Y).astype(np.float32).tofile(r/('scene-'+str(method).replace(' ','')+'.f32'));entry['cg_statuses']=statuses;report.append(entry);print(json.dumps(entry),flush=True)
(r/'detail-transfer-refined.json').write_text(json.dumps(report,indent=2))
