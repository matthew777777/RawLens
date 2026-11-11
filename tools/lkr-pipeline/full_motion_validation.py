#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Full-frame replay of frozen burst inventory; maps are diagnostics, not truth."""
import argparse,json,subprocess,os,time,re
from pathlib import Path
import numpy as np
import rawpy
from PIL import Image
from scipy.ndimage import gaussian_filter


def main(a):
 root=Path(__file__).resolve().parents[2];cases=json.loads(a.inventory.read_text());a.out.mkdir(parents=True,exist_ok=True);rows=[]
 for case in cases:
  dest=a.out/case['collection']/case['burst'];src=dest/'input';src.mkdir(parents=True,exist_ok=True)
  for i,f in enumerate(case['files']):
   p=src/f'frame{i:03}.dng'
   if not p.exists():p.symlink_to(Path(f).resolve())
  with rawpy.imread(case['reference']) as r:
   H,W=r.raw_image.shape;wb=np.array(r.camera_whitebalance[:3]);wb/=wb[1]
  K=len(case['files']);images={}
  for mode,args in [('protected',[]),('no-temporal',['--tk','0']),('paper',['--paper','1'])]:
   p=dest/mode;cmd=[str(root/'tools/lkr-pipeline/build/host/lkr_full_run'),str(src),str(p)+'.dng','--s','1','--prior','0','--model',str(root/'tools/lkr-pipeline/build/models')]+args
   start=time.monotonic()
   if not Path(str(p)+'.dng').exists():
    with p.with_suffix('.log').open('w') as log:subprocess.run(cmd,env=dict(os.environ,DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib',LKR_DEBUG_MAPS=str(p)+'.maps'),stdout=log,stderr=subprocess.STDOUT,check=True)
   z=np.memmap(str(p)+'.dng.f32',dtype='f4',mode='r',shape=(H,W,3));lum=(z*np.array([.25,.5,.25])*wb).sum(2);images[mode]=lum.astype('f4')
   display=np.clip(z*wb,0,1);display=np.where(display<=.0031308,12.92*display,1.055*display**(1/2.4)-.055);im=Image.fromarray(np.uint8(display*255));im.thumbnail((1600,1600));im.save(p.with_suffix('.jpg'),quality=95)
   mh,mw=map(int,re.search(r'debug maps: 5 x (\d+) x (\d+)',p.with_suffix('.log').read_text()).groups())
   m=np.memmap(str(p)+'.maps',dtype='f4',mode='r',shape=(5,mh,mw));support=np.clip(m[0]/K,0,1)
   # Consistent scales across bursts: green retains frames, red loses support.
   heat=np.stack([1-support,support,np.zeros_like(support)],-1);Image.fromarray(np.uint8(heat*255)).resize(im.size).save(dest/(mode+'-support.png'))
   temporal=np.clip(m[1],0,1);Image.fromarray(np.uint8(temporal*255)).resize(im.size).save(dest/(mode+'-temporal.png'))
   detail=np.sqrt(gaussian_filter((lum-gaussian_filter(lum,1))**2,3))
   np.save(dest/(mode+'-detail.npy'),detail.astype('f4'))
   row={**{k:case[k] for k in ('collection','burst','reference')},'mode':mode,'frames':K,'support_mean':float(support.mean()),'fraction_support_below_half':float((support<.5).mean()),'seconds':time.monotonic()-start}
   rows.append(row);(a.out/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
  for mode in ('protected','no-temporal'):
   p=np.load(dest/(mode+'-detail.npy'));b=np.load(dest/'paper-detail.npy');ratio=np.clip(p/np.maximum(b,1e-5),0,2)
   # Noise and ghosts also increase this ratio. Inspect alongside reference.
   heat=np.stack([np.clip(1-ratio,0,1),np.minimum(ratio,1),np.clip(ratio-1,0,1)],-1)
   Image.fromarray(np.uint8(heat*255)).resize(im.size).save(dest/(mode+'-detail-vs-paper.png'))
  print('BURST COMPLETE',case['burst'],flush=True)
 print('COMPLETE',len(cases),'full-frame bursts',flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('inventory',type=Path);p.add_argument('--out',type=Path,required=True);main(p.parse_args())
