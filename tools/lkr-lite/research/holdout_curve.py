# SPDX-License-Identifier: Apache-2.0
"""Conditional held-out-frame fitting audit with captured geometry/rejection.
Run: python holdout_curve.py TRACE_PREFIX OUTPUT_DIR
Requires numpy, scipy, pillow. No GPU required. It evaluates the documented
float operator, not fixed-point shader execution. Geometry and rejection
were derived using all frames, so this is NOT a blind end-to-end holdout.
It measures update-count behaviour conditional on those captures.
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
summary=[]
# Conditional validation: geometry/rejection captured using the whole burst.
# This isolates fitting but is not a fully independent end-to-end validation.
for held in [1,3,5,7]:
 train=(weights*valid).ravel().copy();train[held*h*w:(held+1)*h*w]=0
 cov=(A.T@train).reshape(3,H,W);cv=uniform_filter(cov,size=(1,3,3),mode='constant');gain=1-np.abs(1-1.8*cv/(1.8*7/8))**3;div=np.maximum(np.maximum(cv/np.maximum(gain,1e-4),1e-6),cov/1.8).ravel()
 sel=np.zeros((K,h,w),'f4');sel[held,16:-16,16:-16]=weights[held,16:-16,16:-16]*valid[held,16:-16,16:-16];sel=sel.ravel()
 z=seed.ravel().copy();curve=[]
 for step in range(25):
  residual=A@z-Y;variance=np.maximum(meta['a']*np.maximum(Y,0)+meta['b'],1e-12)
  error=float(np.sum(sel*residual**2/variance)/max(sel.sum(),1e-12));curve.append(error)
  if step in [0,1,2,4,8,16,24]:z.astype('f4').tofile(r/f'held{held}-J{step}.f32')
  z-=A.T@(train*residual)/div
 result=dict(heldout=held,normalized_squared_error=curve,best_step=int(np.argmin(curve)));summary.append(result);print(json.dumps(result),flush=True)
(r/'holdout-curves.json').write_text(json.dumps(summary,indent=2))
