#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare protected SR to static baseline and single-frame motion reference.

Outputs are diagnostics, not ground-truth quality scores. No source files are
modified. Every burst uses one frozen frame list and reference across modes.
"""
import argparse,json,os,subprocess,time
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/"python/lkr"))
import lkr
import numpy as np
import rawpy
from PIL import Image,ImageDraw


def main(a):
    root=Path(__file__).resolve().parents[2]
    a.out.mkdir(parents=True,exist_ok=True)
    cases=[]
    for base in a.inputs:
        for burst in sorted(base.iterdir()):
            if not burst.is_dir():continue
            candidates=[burst/'lkr_in',burst/'in',burst/'frames',burst]
            selected=None
            for folder in candidates:
                files=[]
                for p in sorted(folder.glob('*.dng')):
                    if not p.exists():continue
                    try:
                        with rawpy.imread(str(p)) as r:
                            if r.raw_pattern is not None:files.append(p)
                    except rawpy.LibRawError:continue
                if len(files)>=2:selected=files;break
            if selected:cases.append((base.name,burst.name,selected))
    inventory=[{'collection':c,'burst':n,'files':[str(p) for p in f],'reference':str(f[0])} for c,n,f in cases]
    (a.out/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
    results=[]
    for collection,name,files in cases:
        dest=a.out/collection/name;src=dest/'input';src.mkdir(parents=True,exist_ok=True)
        for i,f in enumerate(files):
            link=src/f'frame{i:03}.dng'
            if not link.exists():link.symlink_to(f.resolve())
        with rawpy.imread(str(files[0])) as r:
            H,W=r.raw_image.shape;wb=np.array(r.camera_whitebalance[:3]);wb/=wb[1]
            pattern=r.raw_pattern
        edge=min(a.crop,(min(H,W)-8)//2*2)
        for iy,fy in enumerate((.1,.5,.9)):
            for ix,fx in enumerate((.1,.5,.9)):
                x=max(0,min(W-edge-2,int((W-edge)*fx)))//2*2
                y=max(0,min(H-edge-2,int((H-edge)*fy)))//2*2
                tile=dest/f'crop{iy}{ix}';tile.mkdir(exist_ok=True)
                # Native loader adjusts to R phase; use source single RAW via
                # the same pipeline, with exactly one frame, no refinement.
                modes={'protected':[], 'no-temporal':['--tk','0'], 'paper':['--paper','1'],
                       'reference':['--frames','1','--paper','1','--refine','0']}
                images=[];labels=[]
                for mode,args in modes.items():
                    prefix=tile/mode;dng=prefix.with_suffix('.dng')
                    cmd=[str(root/'tools/lkr-pipeline/build/host/lkr_full_run'),str(src),str(dng),'--s','2','--crop',str(edge),str(edge),'--at',str(x),str(y),'--prior','0','--model',str(root/'tools/lkr-pipeline/build/models')]+args
                    if mode == 'reference':
                        from scipy.ndimage import zoom
                        with rawpy.imread(str(files[0])) as rawref:
                            raw=rawref.raw_image.astype('f4'); pat=rawref.raw_pattern
                            oy,ox=next((yy,xx) for yy in range(2) for xx in range(2) if pat[yy,xx]==0)
                            # Same even sensor crop plus native RGGB phase shift.
                            x0=x+ox; y0=y+oy
                            mosaic=raw[y0:y0+edge,x0:x0+edge].copy()
                            black=np.array(rawref.black_level_per_channel)[pat]
                            for yy in range(2):
                                for xx in range(2):
                                    b=black[(y0+yy)%2,(x0+xx)%2]
                                    mosaic[yy::2,xx::2]=(mosaic[yy::2,xx::2]-b)/(rawref.white_level-b)
                        z=zoom(lkr.demosaic_bilinear(mosaic),(1,2,2),order=1).transpose(1,2,0).astype('f4')
                        z.tofile(str(dng)+'.f32')
                    elif not dng.exists():
                        env=dict(os.environ,DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib',LKR_DEBUG_MAPS=str(prefix)+'.maps')
                        with prefix.with_suffix('.log').open('w') as log:subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
                    z=np.fromfile(str(dng)+'.f32',dtype='f4').reshape(edge*2,edge*2,3)
                    display=np.clip(z*wb,0,1);display=np.where(display<=.0031308,display*12.92,1.055*display**(1/2.4)-.055)
                    im=Image.fromarray(np.uint8(display*255));im.save(prefix.with_suffix('.png'));images.append(im);labels.append(mode)
                    maps=Path(str(prefix)+'.maps')
                    row={'collection':collection,'burst':name,'crop':[x,y,edge,edge],'mode':mode,'reference':str(files[0])}
                    if maps.exists():
                        m=np.fromfile(maps,dtype='f4').reshape(5,edge*2,edge*2)
                        row.update(frames_mean=float(m[0].mean()),frames_p10=float(np.quantile(m[0],.1)),temporal_mean=float(m[1].mean()))
                        heat=np.uint8(np.clip(m[0]/len(files),0,1)*255);Image.fromarray(heat).save(tile/(mode+'-support.png'))
                    results.append(row)
                    (a.out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
                sheet=Image.new('RGB',(len(images)*edge*2,edge*2+24));draw=ImageDraw.Draw(sheet)
                for i,(im,label) in enumerate(zip(images,labels)):sheet.paste(im,(i*edge*2,24));draw.text((i*edge*2+4,4),label,fill='white')
                sheet.save(tile/'comparison.png')
                print(collection,name,iy,ix,'done',flush=True)
        print('BURST COMPLETE',name,flush=True)
    print('COMPLETE',len(cases),'bursts',len(results),'renders',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('inputs',nargs='+',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--crop',type=int,default=256)
    main(p.parse_args())
