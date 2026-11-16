# SPDX-License-Identifier: Apache-2.0
"""No-added-noise GPU reconstruction from the production initializer.
Run: python noiseless_initialization.py TRACE_PREFIX OUTPUT_DIR
Requires numpy, scipy, pillow, a Vulkan GPU, and built lkrl_run at the
repository-relative default path. Independent float operator manufactures
observations; the actual solver initializes and reconstructs them. No sensor
noise is added, but production fp16 storage/rounding remains. Rejection and
motion refinement are disabled to isolate initialization and reconstruction.
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
import subprocess
from PIL import Image,ImageDraw
# Exact synthetic scene with smooth geometry and explicitly known fine texture.
yy,xx=np.mgrid[:H,:W];base=(.18+.04*np.tanh((xx-.6*yy-60)/4)).astype('f4');texture=(.012*np.sin(2*np.pi*(.125*xx+.0625*yy))+.008*np.cos(2*np.pi*(.23*xx-.17*yy))).astype('f4')
truth=np.tile((base+texture).ravel(),(3,1));manufactured=A@truth.ravel();pref=r/'manufactured'
Path(str(pref)+'_meta.txt').write_text(f'K={K} h={h} w={w} H={H} a=0 b=0 clip=.98 robust_k=0 robust_tau=.1')
manufactured.astype('f4').tofile(str(pref)+'_frames.f32');P.astype('f4').tofile(str(pref)+'_motion_initial.f32');D.astype('f4').tofile(str(pref)+'_local_initial.f32')
report=[];results=[('known truth',truth)]
for steps in [8,32,128]:
 out=r/f'fit-J{steps}.f32';prefix=r/f'fit-J{steps}-audit'
 subprocess.run(['tools/lkr-lite/build/host/lkrl_run','--trace',str(pref),str(out),'--schedule','J'*steps,'--guide','0','--data-steps',str(steps*3),'--audit-prefix',str(prefix)],check=True,stdout=subprocess.DEVNULL)
 z=np.fromfile(out,'f4').reshape(3,H,W);e=(z-truth.reshape(3,H,W))[:,32:-32,32:-32];err=np.sqrt(np.mean(e*e));l=np.array([.25,.5,.25])@z.reshape(3,n);t=texture[32:-32,32:-32];gain=np.sum((l.reshape(H,W)-base)[32:-32,32:-32]*t)/np.sum(t*t);report.append(dict(steps=steps,error_rms=float(err),texture_gain=float(gain)));results.append((f'production init + J{steps}',z.reshape(3,n)))
seed0=np.fromfile(str(r/'fit-J8-audit')+'-seed.f32','f4').reshape(3,n);results.append(('MHC seed',seed0));print(json.dumps(report,indent=2));(r/'noiseless-init.json').write_text(json.dumps(report,indent=2))
canvas=Image.new('RGB',(1500,1060))
for i,(name,z) in enumerate(results):
 a=(z.reshape(3,H,W).transpose(1,2,0))[32:-32,32:-32];im=Image.fromarray(np.uint8(np.clip(a*2,0,1)**(1/2.2)*255)).resize((500,500));canvas.paste(im,((i%3)*500,(i//3)*530+30));ImageDraw.Draw(canvas).text(((i%3)*500+5,(i//3)*530+5),name,fill='white')
canvas.save(r/'noiseless-init.png')

# At the same 128-update budget, compare a different production initializer.
out=r/'bilinear-J128.f32';audit=r/'bilinear-J128-audit'
subprocess.run(['tools/lkr-lite/build/host/lkrl_run','--trace',str(pref),str(out),'--schedule','J'*128,'--data-steps','384','--guide','0','--mhc','0','--audit-prefix',str(audit)],check=True,stdout=subprocess.DEVNULL)
rowvalid=valid.ravel();Yhalf=manufactured.astype('f2').astype('f4');comparison=[];zs=[]
for name,path in [('MHC',r/'fit-J128-audit-J128.f32'),('bilinear',r/'bilinear-J128-audit-J128.f32')]:
 z=np.fromfile(path,'f4');pred=A@z;err=(z-truth.ravel()).reshape(3,H,W)[:,32:-32,32:-32];comparison.append(dict(initializer=name,sensor_rms_ideal=float(np.sqrt(np.mean((pred-manufactured)[rowvalid]**2))),sensor_rms_fp16=float(np.sqrt(np.mean((pred-Yhalf)[rowvalid]**2))),channel_rms=np.sqrt(np.mean(err*err,axis=(1,2))).tolist()));zs.append(z)
diff=zs[0]-zs[1];comparison.append(dict(initializer_difference_rgb_rms=float(np.sqrt(np.mean(diff.reshape(3,H,W)[:,32:-32,32:-32]**2))),initializer_difference_sensor_rms=float(np.sqrt(np.mean((A@diff)[rowvalid]**2))),operator_rows=A.shape[0],operator_columns=A.shape[1]));(r/'seed-dependence.json').write_text(json.dumps(comparison,indent=2))
