#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Local DNG comparison using the app's native standalone FFDNet implementation.

Uses LibRaw AHD demosaic as a desktop reference, with identical fixed exposure,
WB and camera-to-sRGB transform for every variant (not an exact app renderer).
Requires numpy, rawpy, Pillow. Original DNGs are read-only.
"""
import argparse
import json
from pathlib import Path
import subprocess
import numpy as np
import rawpy
from PIL import Image, ImageDraw


def render(rgb, matrix, wb, exposure):
    x = (rgb * wb) @ matrix.T * exposure
    x = np.clip(x, 0, 1)
    x = np.where(x <= .0031308, 12.92*x, 1.055*x**(1/2.4)-.055)
    return Image.fromarray(np.uint8(np.clip(x*255+.5,0,255)))


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('dng',type=Path)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--crop',type=int,default=1024)
    ap.add_argument('--at',type=int,nargs=2,metavar=('X','Y'))
    ap.add_argument('--exposure',type=float,default=1.)
    ap.add_argument('--chroma-stride',type=int,choices=[0,2,4],default=4)
    ap.add_argument('--no-colour-guard',action='store_true',help='Reproduce the earlier unconstrained chroma experiment')
    ap.add_argument('--phase-luma',type=float,default=0.,help='Experimental luma transfer, 0 to 1')
    args=ap.parse_args()
    if not 0 <= args.phase_luma <= 1: ap.error("--phase-luma must be in [0,1]")
    repo=Path(__file__).resolve().parents[2]
    args.out.mkdir(parents=True,exist_ok=True)
    with rawpy.imread(str(args.dng)) as r:
        if r.raw_pattern is None or r.raw_pattern.shape!=(2,2):
            raise ValueError('This validation tool requires a single-frame 2x2 Bayer DNG')
        pattern=np.where(r.raw_pattern==3,1,r.raw_pattern)
        cfa=''.join(str(int(v)) for v in pattern.flat)
        wb=np.asarray(r.camera_whitebalance[:3],np.float32);wb/=wb[1]
        matrix=np.asarray(r.color_matrix[:3,:3],np.float32)
        if not np.isfinite(matrix).all() or not np.any(matrix): raise ValueError('Missing camera-to-sRGB matrix')
        full=r.postprocess(output_color=rawpy.ColorSpace.raw, user_wb=[1,1,1,1],
            use_camera_wb=False,use_auto_wb=False,no_auto_bright=True,
            gamma=(1,1),output_bps=16,demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD).astype(np.float32)/65535
        # LibRaw scales to the sensor range; retain that same normalization for all outputs.
        # Full frame demosaic prevents crop boundary artifacts entering the comparison.
    h,w=full.shape[:2]
    size=min(args.crop,h,w)&~1
    x,y=args.at or ((w-size)//2,(h-size)//2)
    x=max(0,min(w-size,x))&~1;y=max(0,min(h-size,y))&~1
    rgb=full[y:y+size,x:x+size].copy()
    del full
    rgba=np.ones((size,size,4),dtype=np.float16);rgba[:,:,:3]=rgb
    before=args.out/'input.rgba16f';rgba.tofile(before)
    exe=repo/'tools/lkr-pipeline/build/host/lkr_denoise_run'
    models=repo/'app/src/main/assets/models'
    target=args.out/'full.rgba16f'
    command=[str(exe),str(before),str(target),str(size),str(size),cfa,*map(str,wb),'1',
             str(models/'lkr_ffdnet.ncnn.param'),str(models/'lkr_ffdnet.ncnn.bin'),str(args.chroma_stride),str(args.phase_luma),str(int(not args.no_colour_guard))]
    result=subprocess.run(command,text=True,capture_output=True)
    (args.out/'native.log').write_text(result.stdout+result.stderr)
    if result.returncode: raise RuntimeError(result.stderr)
    denoised=np.fromfile(target,np.float16).reshape(size,size,4)[:,:,:3].astype(np.float32)
    source=rgba[:,:,:3].astype(np.float32)
    variants=[('Before',source),('35%',source*.65+denoised*.35),('65%',source*.35+denoised*.65),('100%',denoised)]
    thumbs=[]
    for name,array in variants:
        img=render(array,matrix,wb,args.exposure)
        img.save(args.out/(name.replace('%','').lower()+'.png'))
        thumb=img.copy();thumb.thumbnail((512,512));thumbs.append((name,thumb))
    sheet=Image.new('RGB',(1024,2*548),'#181818');draw=ImageDraw.Draw(sheet)
    for i,(name,img) in enumerate(thumbs):
        px=i%2*512;py=i//2*548
        draw.text((px+12,py+8),name,fill='white');sheet.paste(img,(px,py+30))
    sheet.save(args.out/'strength-comparison.jpg',quality=95)
    meta={'colour_guard':not args.no_colour_guard,'phase_luma':args.phase_luma,'chroma_stride':args.chroma_stride,'cfa':cfa,'source':str(args.dng),'crop':[x,y,size,size],'wb':wb.tolist(),
          'matrix':matrix.tolist(),'exposure':args.exposure,'renderer':'LibRaw AHD, camera matrix, sRGB transfer',
          'native_log':result.stderr,'mean_before':source.mean((0,1)).tolist(),
          'mean_after':denoised.mean((0,1)).tolist()}
    (args.out/'comparison.json').write_text(json.dumps(meta,indent=2))
    print(json.dumps(meta,indent=2))

if __name__=='__main__': main()
