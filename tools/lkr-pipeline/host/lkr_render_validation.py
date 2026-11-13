#!/usr/bin/env python3
"""Render every native 1x before/after Linear DNG using one matched RT profile.

Archived linear TIFFs hold the exact uint16 reconstruction. DNG containers are
recreated losslessly from those pixels, with the native writer's CM1/neutral
metadata convention. They are not re-merged or resized. Every DNG is read back
and its pixel identity checked before RawTherapee renders it.
"""
import argparse
import html
import json
import os
from pathlib import Path
import subprocess
import numpy as np
from PIL import Image, ImageDraw
import tifffile

from lkr_dataset_validation import atomic_json, crop_specs, fingerprint


PROFILE = '''[Version]
AppVersion=5.13
Version=352

[Exposure]
Auto=false
Compensation=0
Brightness=0
Contrast=0
Saturation=0
Black=0
HighlightCompr=0
ShadowCompr=0

[White Balance]
Enabled=true
Setting=Camera

[Sharpening]
Enabled=false

[SharpenEdge]
Enabled=false

[SharpenMicro]
Enabled=false

[Impulse Denoising]
Enabled=false

[Directional Pyramid Denoising]
Enabled=false

[Defringing]
Enabled=false

[Resize]
Enabled=false

[Crop]
Enabled=false

[Color Management]
InputProfile=(camera)
WorkingProfile=ProPhoto
OutputProfile=RTv4_sRGB
OutputBPC=true
'''


def wrap_linear(source, linear, dest):
    pixels=tifffile.imread(linear)
    with tifffile.TiffFile(source) as src:
        tags=src.pages[0].tags
        fields=[(50706,'B',4,(1,4,0,0),False),(50707,'B',4,(1,1,0,0),False),
                (50713,'H',2,(1,1),False),(50714,'2I',3,(0,1,0,1,0,1),False),
                (50717,'I',3,(65535,65535,65535),False),(50778,'H',1,21,False)]
        for code in (271,272,50708,50721,50728):
            if code in tags:
                t=tags[code];fields.append((code,t.dtype,t.count,t.value,False))
        if 50721 not in tags:
            matrix=tuple(v for i in range(9) for v in (int(i%4==0),1))
            fields.append((50721,'2i',9,matrix,False))
        if 50728 not in tags:fields.append((50728,'2I',3,(1,1,1,1,1,1),False))
        orientation=int(tags[274].value) if 274 in tags else 1
        if orientation not in range(1,9):orientation=1
        fields.append((274,'H',1,orientation,False))
    tifffile.imwrite(dest,pixels,photometric=34892,planarconfig='contig',metadata=None,
                     compression=None,extratags=fields)
    with tifffile.TiffFile(dest) as dng:
        page=dng.pages[0]
        if page.tags[50714].count!=page.samplesperpixel or page.tags[50717].count!=page.samplesperpixel:
            raise ValueError('Linear DNG requires one black/white level per RGB sample')
        if page.photometric!=34892 or not np.array_equal(pixels,page.asarray()):
            raise ValueError('DNG reconstruction changed uint16 pixels')
    return pixels.shape,orientation


def oriented_box(box, w, h, orientation):
    x0,y0,x1,y1=box
    return {
        1:(x0,y0,x1,y1),2:(w-x1,y0,w-x0,y1),3:(w-x1,h-y1,w-x0,h-y0),
        4:(x0,h-y1,x1,h-y0),5:(y0,x0,y1,x1),6:(h-y1,x0,h-y0,x1),
        7:(h-y1,w-x1,h-y0,w-x0),8:(y0,w-x1,y1,w-x0),
    }[orientation]


def render_crops(case, dest, run, variant):
    with tifffile.TiffFile(dest/'before.dng') as t:orientation=int(t.pages[0].tags[274].value)
    boxes=crop_specs(case,1)
    metrics=run/('compare-'+variant)/'metrics.json'
    if metrics.exists():
        boxes += [(f'largest-change-{i}',tuple(row['box'])) for i,row in enumerate(json.loads(metrics.read_text())['selected_crops'])]
    folder=dest/'crops';folder.mkdir(exist_ok=True)
    with Image.open(dest/'before.png') as before,Image.open(dest/'after.png') as after:
        for name,box in boxes:
            box=oriented_box(box,case['width'],case['height'],orientation)
            a,b=before.crop(box),after.crop(box)
            pair=Image.new('RGB',(a.width*2,a.height+30),'white')
            pair.paste(a,(0,30));pair.paste(b,(a.width,30))
            d=ImageDraw.Draw(pair);d.text((4,8),'BEFORE / '+name,fill='black');d.text((a.width+4,8),'AFTER',fill='black')
            pair.save(folder/(name+'.png'),icc_profile=before.info.get('icc_profile'))
    return [name for name,_ in boxes]


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--variant',default='guide2-experiment')
    ap.add_argument('--only',default='')
    ap.add_argument('--decoder-checker',type=Path,help='optional checker linked against RapidRAW Rawler')
    ap.add_argument('--rt',type=Path,default=Path('/Applications/RawTherapee.app/Contents/MacOS/rawtherapee-cli'))
    args=ap.parse_args()
    inv=json.loads((args.out/'inventory.json').read_text())
    root=args.out/'final-1x';root.mkdir(exist_ok=True)
    profile=root/'matched-neutral.pp3';profile.write_text(PROFILE)
    rows=[];page=['<!doctype html><meta charset="utf-8"><title>LK-R native 1x DNG renders</title>',
                '<style>body{background:#222;color:#eee;font:16px system-ui;margin:24px}a{color:#9cf}img{max-width:46vw}.crop{max-width:none}table{width:100%}</style>',
                '<h1>Native 1× before/after DNG renders</h1>',
                '<p>RawTherapee 5.13; identical neutral profile, camera WB, sRGB output, no resize, sharpening or denoising. '
                'The AFTER column is an experiment, not an assertion of a universal fix. Full PNG and Linear DNG links below.</p>']
    for c in inv['cases']:
        if args.only and args.only not in c['burst']:continue
        run=args.out/'runs'/c['collection']/c['burst']/'x1'
        if not all((run/v/'done.json').exists() for v in ('before',args.variant)):continue
        dest=root/c['collection']/c['burst'];dest.mkdir(parents=True,exist_ok=True)
        states=[]
        for name,variant in [('before','before'),('after',args.variant)]:
            dng=dest/(name+'.dng');png=dest/(name+'.png');meta=dest/(name+'.json')
            if not meta.exists():
                shape,orientation=wrap_linear(c['reference'],run/variant/'linear-camera-rgb.tif',dng)
                cmd=[str(args.rt),'-q','-p',str(profile),'-o',str(png),'-n','-b8','-Y','-c',str(dng)]
                with (dest/(name+'-render.log')).open('w') as log:
                    subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True,
                                   env=dict(os.environ,OMP_NUM_THREADS='2'))
                with Image.open(png) as im:
                    expected=(shape[0],shape[1]) if orientation in (5,6,7,8) else (shape[1],shape[0])
                    if im.size!=expected:raise ValueError(f'render resized: {im.size} != {expected}')
                    thumb=im.copy();thumb.thumbnail((1400,1000));thumb.save(dest/(name+'-overview.jpg'),quality=94)
                atomic_json(meta,dict(width=expected[0],height=expected[1],dng_sha256=fingerprint(dng),
                                      source_linear_sha256=fingerprint(run/variant/'linear-camera-rgb.tif'),
                                      profile_sha256=fingerprint(profile),command=cmd,
                                      dng_origin='losslessly rewrapped archived uint16 reconstruction'))
                print('RENDERED 1x',c['collection'],c['burst'],name,flush=True)
            states.append(json.loads(meta.read_text()))
            if args.decoder_checker:
                checked=dest/(name+'-rapidraw.json')
                if not checked.exists() or json.loads(checked.read_text()).get('dng_sha256')!=states[-1]['dng_sha256']:
                    r=subprocess.run([str(args.decoder_checker),str(dng)],capture_output=True,text=True,
                                     env=dict(os.environ,RAYON_NUM_THREADS='2'))
                    (dest/(name+'-rapidraw.log')).write_text(r.stdout+r.stderr)
                    if r.returncode:raise RuntimeError(f'RapidRAW decoder rejected {dng}: {r.stderr}')
                    atomic_json(checked,dict(dng_sha256=states[-1]['dng_sha256'],
                                            checker_sha256=fingerprint(args.decoder_checker),result=r.stdout))
                    print('RAPIDRAW PASS',c['collection'],c['burst'],name,flush=True)
        if (states[0]['width'],states[0]['height'])!=(states[1]['width'],states[1]['height']):
            raise ValueError('rendered before/after dimensions differ')
        rel=dest.relative_to(root)
        page.append(f'<h2>{html.escape(c["collection"]+" / "+c["burst"])}</h2><p>{states[0]["width"]}×{states[0]["height"]} pixels</p><table><tr>')
        for name in ('before','after'):
            page.append(f'<td><b>{name.upper()}</b><br><a href="{rel}/{name}.png"><img src="{rel}/{name}-overview.jpg"></a>'
                        f'<br><a href="{rel}/{name}.png">Full PNG</a> · <a href="{rel}/{name}.dng">Linear DNG</a></td>')
        page.append('</tr></table>')
        crops=render_crops(c,dest,run,args.variant)
        page.append('<details><summary>Native 1:1 rendered crops — before / after</summary>')
        for name in crops:
            page.append(f'<p>{name}</p><img class="crop" src="{rel}/crops/{name}.png">')
        page.append('</details>')
        rows.append(dict(collection=c['collection'],burst=c['burst'],before=states[0],after=states[1]))
        atomic_json(root/'manifest.json',rows)
        (root/'index.html').write_text('\n'.join(page))


if __name__=='__main__':main()
