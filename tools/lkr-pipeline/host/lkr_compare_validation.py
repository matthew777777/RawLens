#!/usr/bin/env python3
"""Compare full sensor reconstructions and extract native crops of changes.

These measurements detect changes; they do not provide ground-truth quality or
identify motion. A real moving subject must be verified from source frames.
"""
import argparse
import html
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
import tifffile

from lkr_dataset_validation import atomic_json, crop_specs


def pair_crop(before, after, box, path, label):
    a, b = before.crop(box), after.crop(box)
    panel = Image.new('RGB', (a.width * 2, a.height + 30), 'white')
    panel.paste(a, (0, 30)); panel.paste(b, (a.width, 30))
    d = ImageDraw.Draw(panel)
    d.text((4, 7), 'BEFORE: ' + label, fill='black')
    d.text((a.width + 4, 7), 'EXPERIMENT', fill='black')
    panel.save(path)


def compare(root, case, scale, variant):
    folder = root / 'runs' / case['collection'] / case['burst'] / f'x{scale}'
    before, after = folder / 'before', folder / variant
    if not (before / 'done.json').exists() or not (after / 'done.json').exists():
        return None
    dest = folder / ('compare-' + variant)
    dest.mkdir(exist_ok=True)
    if (dest / 'metrics.json').exists():
        return json.loads((dest / 'metrics.json').read_text())
    a = tifffile.imread(before / 'linear-camera-rgb.tif')
    b = tifffile.imread(after / 'linear-camera-rgb.tif')
    if a.shape != b.shape:
        raise ValueError('before/after geometry mismatch')
    h, w, _ = a.shape
    if (w,h) != (case['width']*scale,case['height']*scale):
        raise ValueError('full sensor dimensions mismatch')
    blocks = []
    abs_sum = squared_sum = n = 0
    wb = np.array(case['wb'], dtype='f4')
    for y in range(0,h-319,320):
        for x in range(0,w-319,320):
            aa = a[y:y+320,x:x+320].astype('f4')/65535
            bb = b[y:y+320,x:x+320].astype('f4')/65535
            diff = bb-aa
            abs_sum += float(np.abs(diff).sum()); squared_sum += float((diff*diff).sum()); n += diff.size
            la = (aa*wb*np.array([.25,.5,.25],dtype='f4')).sum(2)
            lb = (bb*wb*np.array([.25,.5,.25],dtype='f4')).sum(2)
            detail_a = float((np.abs(np.diff(la,axis=0)).mean()+np.abs(np.diff(la,axis=1)).mean())*.5)
            detail_b = float((np.abs(np.diff(lb,axis=0)).mean()+np.abs(np.diff(lb,axis=1)).mean())*.5)
            blocks.append(dict(box=[x,y,x+320,y+320], mae=float(np.abs(diff).mean()),
                               gradient_before=detail_a, gradient_after=detail_b,
                               gradient_loss=(detail_a-detail_b)/max(detail_a,.002)))
    del a,b
    selected = []
    for key in ('mae','gradient_loss'):
        count=0
        for row in sorted(blocks,key=lambda x:x[key],reverse=True):
            if any(abs(row['box'][0]-s['box'][0])<320 and abs(row['box'][1]-s['box'][1])<320 for s in selected):
                continue
            row={**row,'selection':key};selected.append(row);count+=1
            if count==3:break
    with Image.open(before/'full.png') as ia, Image.open(after/'full.png') as ib:
        for i,row in enumerate(selected):
            pair_crop(ia,ib,tuple(row['box']),dest/f'change-{i}.png',row['selection'])
        for name,box in crop_specs(case,scale):
            if name.startswith('grid-'):continue
            pair_crop(ia,ib,box,dest/(name+'.png'),name)
    result=dict(collection=case['collection'],burst=case['burst'],scale=scale,variant=variant,
                width=w,height=h,mae=abs_sum/n,rmse=float(np.sqrt(squared_sum/n)),
                note='Change metrics over 320-pixel blocks; not quality/PSNR; exclude partial edge blocks.',
                selected_crops=selected,
                before=json.loads((before/'done.json').read_text()),after=json.loads((after/'done.json').read_text()))
    atomic_json(dest/'metrics.json',result)
    print('COMPARED',case['burst'],scale,variant,'MAE',round(result['mae'],6),flush=True)
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--variant',default='guide2-experiment')
    args=ap.parse_args()
    inv=json.loads((args.out/'inventory.json').read_text());rows=[];parts=[]
    parts+=['<!doctype html><meta charset="utf-8"><title>LK-R full-resolution comparisons</title>',
            '<style>body{background:#222;color:#eee;font:16px system-ui;margin:24px}a{color:#9cf}img{max-width:95vw} .overview img{max-width:45vw}</style>',
            '<h1>Full-resolution before/after comparisons</h1>',
            '<p>Changes are not automatic quality scores. Crops below retain native output pixels. '
            'The 080720 balcony region contains a bicycle, not a person. Motion must be verified in source frames.</p>',
            '<p><a href="index.html">Every full-resolution image, TIFF, grid crop, and log</a></p>']
    for c in inv['cases']:
        for scale in (1,2):
            r=compare(args.out,c,scale,args.variant)
            if r is None:continue
            rows.append(r)
            base=Path('runs')/c['collection']/c['burst']/f'x{scale}'
            parts.append(f'<h2>{html.escape(c["collection"]+" / "+c["burst"])} ×{scale}</h2>')
            parts.append(f'<p>Sensor output {r["width"]}×{r["height"]}; MAE {r["mae"]:.6f} (camera-linear).</p>')
            parts.append('<div class="overview">')
            for name in ('before',args.variant):
                parts.append(f'<a href="{base/name}/full.png"><img src="{base/name}/overview.jpg" title="{name}"></a>')
            parts.append('</div>')
            for i,row in enumerate(r['selected_crops']):
                parts.append(f'<p>{row["selection"]}, output pixels {row["box"]}</p><img src="{base/("compare-"+args.variant)}/change-{i}.png">')
            for name,box in crop_specs(c,scale):
                if not name.startswith('grid-'):
                    parts.append(f'<p>{name}</p><img src="{base/("compare-"+args.variant)}/{name}.png">')
    atomic_json(args.out/('metrics-'+args.variant+'.json'),rows)
    parts.insert(4,f'<p>{len(rows)} of {len(inv["cases"])*2} paired full-resolution comparisons available.</p>')
    (args.out/('compare-'+args.variant+'.html')).write_text('\n'.join(parts))


if __name__=='__main__':main()
