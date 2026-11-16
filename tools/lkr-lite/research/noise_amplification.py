# SPDX-License-Identifier: Apache-2.0
"""Manufactured-noise audit using captured real geometry and rejection.
Run: python noise_amplification.py TRACE_PREFIX OUTPUT_DIR
Requires numpy, scipy, pillow. No GPU required. It evaluates the documented
float operator, not fixed-point shader execution. A noiseless run must stay
unchanged; output error with added noise measures the reconstruction response.
The manufactured truth is the captured demosaic seed, not ground truth for
actual scene detail. This cannot by itself certify real-burst quality.
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
# Use real geometry and rejection but manufactured, exactly consistent observations.
# Starting at truth makes every output error attributable to added sensor noise.
ztrue=seed.ravel().copy();pred=A@ztrue;variance=np.maximum(meta['a']*pred+meta['b'],1e-12)
rng=np.random.default_rng(732);noise=(rng.normal(size=len(Y))*np.sqrt(variance)).astype('f4');ww=sw**2
cov=(A.T@ww).reshape(3,H,W);cv=uniform_filter(cov,size=(1,3,3),mode='constant')
gain=1-np.abs(1-cv)**3;div=np.maximum(cv/np.maximum(gain,1e-4),1e-6);div=np.maximum(div,cov/1.8).ravel()
canvas=Image.new('RGB',(1200,840));report=[]
for i,noise_scale in enumerate([0,1,2]):
 obs=pred+noise_scale*noise;z=ztrue.copy()
 for it in range(8):z-=A.T@(ww*(A@z-obs))/div
 error=(z-ztrue).reshape(3,H,W);core=error[:,32:-32,32:-32];rgb=z.reshape(3,H,W).transpose(1,2,0)*wb.ravel()
 lum=np.array([.25,.5,.25])@(error.reshape(3,n)*wb);lum=lum.reshape(H,W)[32:-32,32:-32];sp=np.abs(np.fft.fftshift(np.fft.fft2(lum)))**2;yy,xx=np.mgrid[:192,:192];fx=(xx-96)/192;fy=(yy-96)/192;rad=np.hypot(fx,fy);power=float(sp[(rad>.2)&(rad<.4)].sum()/max(sp.sum(),1e-30))
 report.append(dict(noise_scale=noise_scale,camera_rgb_error_rms=float(np.sqrt(np.mean(core*core))),luma_error_rms=float(np.sqrt(np.mean(lum*lum))),fraction_error_power_02_04=power))
 crop=np.rot90(rgb,-1)[32:224,32:224];im=Image.fromarray(np.uint8(np.clip(crop*2,0,1)**(1/2.2)*255)).resize((400,400));canvas.paste(im,(i*400,30));ImageDraw.Draw(canvas).text((i*400+5,5),f'Added sensor noise {noise_scale}x',fill='white')
 vis=np.rot90(error.transpose(1,2,0)*wb.ravel(),-1)[32:224,32:224];im=Image.fromarray(np.uint8(np.clip(.5+vis*30,0,1)*255)).resize((400,400));canvas.paste(im,(i*400,440));ImageDraw.Draw(canvas).text((i*400+5,420),'Error only, amplified 30x',fill='white')
canvas.save(r/'noise-amplification.png');(r/'noise-amplification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
