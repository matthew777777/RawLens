# SPDX-License-Identifier: Apache-2.0
"""Shared sensor-coordinate nuisance control with captured geometry/rejection.
Run: python shared_sensor_control.py TRACE_PREFIX OUTPUT_DIR
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
rng=np.random.default_rng(119);independent=rng.normal(size=(K,h,w)).astype('f4')*.0015*np.sqrt(.75);fixed=rng.normal(size=(h,w)).astype('f4')*.0015*np.sqrt(.25);noise=(independent+fixed).ravel();known=seed.ravel();pred=A@known
report=[];results=[]
for prior in [None,2,4,8,16]:
 def update(z,obs):
  residual=(A@z-obs).reshape(K,h,w)
  if prior is not None:
   common=(ww*residual).sum(0)/(ws+prior);residual=residual-common
  return z-(A.T@(ww*residual).ravel())/div
 z=known.copy()
 for it in range(8):z=update(z,pred+noise)
 err=z-known;lum=(np.array([.25,.5,.25])@(err.reshape(3,n)*wb)).reshape(H,W)[32:-32,32:-32]
 entry=dict(sensor_prior=prior,luma_noise_rms=float(np.sqrt(np.mean(lum*lum))))
 z=known.copy()
 for it in range(8):z=update(z,Y)
 z.astype('f4').tofile(r/f'shared-sensor-{prior}.f32');report.append(entry);results.append((str(prior),z.reshape(3,n)));print(json.dumps(entry),flush=True)
canvas=Image.new('RGB',(1500,1060))
for i,(name,z) in enumerate(results):
 a=np.rot90((z*wb).reshape(3,H,W).transpose(1,2,0),-1)[32:224,32:224];im=Image.fromarray(np.uint8(np.clip(a*2,0,1)**(1/2.2)*255)).resize((500,500));canvas.paste(im,((i%3)*500,(i//3)*530+30));ImageDraw.Draw(canvas).text(((i%3)*500+5,(i//3)*530+5),'Shared sensor prior '+name,fill='white')
canvas.save(r/'shared-sensor-control.png');(r/'shared-sensor-results.json').write_text(json.dumps(report,indent=2))
