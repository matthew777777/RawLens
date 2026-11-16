# SPDX-License-Identifier: Apache-2.0
"""Matched-detail response audit of a shared sensor-coordinate nuisance model.
Run: python shared_sensor_transfer.py TRACE_PREFIX OUTPUT_DIR
Requires numpy, scipy, pillow. No GPU required. It evaluates the documented
float operator, not fixed-point shader execution. Manufactured noise has
75% independent variance and 25% shared sensor-coordinate variance, sigma
0.0015. This simplified control is not a calibrated full-frame noise model.
The captured seed is manufactured truth for noise response only. Real-scene
outputs are exploratory; no detail-preservation claim follows from them.
"""
import argparse, json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
from lite_model import forward_matrix
parser=argparse.ArgumentParser()
parser.add_argument('trace_prefix',type=Path)
parser.add_argument('output_dir',type=Path)
args=parser.parse_args()
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
from scipy.ndimage import uniform_filter
from PIL import Image,ImageDraw
ww=(weights*valid).astype('f4');cov=(A.T@ww.ravel()).reshape(3,H,W);cv=uniform_filter(cov,size=(1,3,3),mode='constant');gain=1-np.abs(1-cv)**3;div=np.maximum(np.maximum(cv/np.maximum(gain,1e-4),1e-6),cov/1.8).ravel();ws=ww.sum(axis=0)
yy,xx=np.mgrid[:H,:W];core=(slice(32,-32),slice(32,-32));q=np.array([.25,.5,.25]);probes=[('horizontal .125',.125,0),('diagonal .125',.08838835,.08838835),('horizontal .25',.25,0),('diagonal .25',.1767767,.1767767),('horizontal .35',.35,0),('diagonal .35',.24748737,.24748737)]
rng=np.random.default_rng(119);independent=rng.normal(size=(K,h,w)).astype('f4')*.0015*np.sqrt(.75);fixed=rng.normal(size=(h,w)).astype('f4')*.0015*np.sqrt(.25);noise=(independent+fixed).ravel()
checkpoints=[8,12,16,24];report=[]
for prior in [None,2,4,8]:
 def update(z,obs):
  residual=(A@z-obs).reshape(K,h,w)
  if prior is not None:residual=residual-(ww*residual).sum(0)/(ws+prior)
  return z-(A.T@(ww*residual).ravel())/div
 def run(obs):
  out={};z=np.zeros(3*n,'f4')
  for it in range(1,25):
   z=update(z,obs)
   if it in checkpoints:out[it]=z.copy()
  return out
 values={step:dict(sensor_prior=prior,steps=step,transfer=[]) for step in checkpoints}
 for step,z in run(noise).items():
  l=(q@(z.reshape(3,n)*wb)).reshape(H,W)[core];values[step]['luma_noise_rms']=float(np.sqrt(np.mean(l*l)))
 for name,fx,fy in probes:
  wave=np.sin(2*np.pi*(fx*xx+fy*yy)).astype('f4');truth=np.tile(wave.ravel(),(3,1))/wb
  for step,z in run(A@truth.ravel()).items():
   l=(q@(z.reshape(3,n)*wb)).reshape(H,W)[core];values[step]['transfer'].append(dict(probe=name,gain=float(np.sum(l*wave[core])/np.sum(wave[core]**2))))
 for step,entry in values.items():report.append(entry);print(json.dumps(entry),flush=True)
(r/'shared-sensor-transfer.json').write_text(json.dumps(report,indent=2))
base=report[0]
for entry in report:
 if entry['sensor_prior'] is not None:
  entry['noise_ratio_to_baseline8']=entry['luma_noise_rms']/base['luma_noise_rms'];entry['min_transfer_delta_to_baseline8']=min(y['gain']-x['gain'] for x,y in zip(base['transfer'],entry['transfer']))
(r/'shared-sensor-transfer.json').write_text(json.dumps(report,indent=2))
