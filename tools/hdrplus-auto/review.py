#!/usr/bin/env python3
"""Create matched development comparisons, without per-output auto exposure."""
import argparse,json,re
from pathlib import Path
import numpy as np
import rawpy
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('fixtures',type=Path);a=p.parse_args()
report=[]
for folder in sorted(a.fixtures.iterdir()):
    if not (folder/'hq6.dng').exists(): continue
    plan=dict(line.split('=',1) for line in (folder/'decision.txt').read_text().splitlines())
    rows=[line.split('\t') for line in (folder/'frames.tsv').read_text().splitlines()]
    ref=int(plan['kept'].split(',')[int(plan['ref'])])
    with rawpy.imread(rows[ref][0]) as raw:
        wb=raw.camera_whitebalance
        base=raw.postprocess(use_camera_wb=False,user_wb=wb,no_auto_bright=True,output_bps=8,user_flip=0)
    # Shared post-render exposure for every panel (including source).
    gain=min(5,160/max(1,np.percentile(base,95)))
    modes=['reference','auto','auto-hq','hq3','hq6']
    if (folder/'google-source.txt').exists(): modes.append('google')
    canvas=Image.new('RGB',(500*len(modes),420),'white');draw=ImageDraw.Draw(canvas)
    crop=Image.new('RGB',(480*len(modes),1000),'white');dc=ImageDraw.Draw(crop)
    metrics={'burst':folder.name,'decision':plan,'variants':{}}
    for index,mode in enumerate(modes):
        file=Path(rows[ref][0]) if mode=='reference' else folder/f'{mode}.dng'
        if mode=='google': file=Path((folder/'google-source.txt').read_text().strip())
        with rawpy.imread(str(file)) as raw:
            rgb=raw.postprocess(use_camera_wb=False,user_wb=wb,no_auto_bright=True,output_bps=8,user_flip=0)
            cfa=raw.raw_image.astype(np.float64)
            blacks=np.array([raw.black_level_per_channel[int(c)] for c in raw.raw_pattern.flat]).reshape(2,2)
            norm=(cfa-np.tile(blacks,(cfa.shape[0]//2,cfa.shape[1]//2)))/(raw.white_level-blacks.mean())
        # Descriptive high-frequency statistic, NOT a quality/ghosting score.
        hh=(norm[:-2:4,:-2:4]-norm[:-2:4,2::4]-norm[2::4,:-2:4]+norm[2::4,2::4])/2
        metrics['variants'][mode]={'haar_mad':float(np.median(np.abs(hh-np.median(hh)))/0.67448975)}
        image=Image.fromarray(np.clip(rgb.astype(float)*gain,0,255).astype('uint8'))
        image.save(folder/f'{mode}-review.jpg',quality=94)
        small=image.copy();small.thumbnail((500,380));canvas.paste(small,(index*500,30));draw.text((index*500+5,5),mode,fill='black')
        w,h=image.size
        for j,(cx,cy) in enumerate([(w//2,h//2),(w*3//4,h//3)]):
            region=image.crop((cx-240,cy-240,cx+240,cy+240));crop.paste(region,(index*480,j*500+20))
            dc.text((index*480+5,j*500+3),f'{mode} crop {j+1}',fill='black')
    canvas.save(folder/'comparison.jpg',quality=95);crop.save(folder/'crops.jpg',quality=95)
    report.append(metrics);print(folder.name,flush=True)
(a.fixtures/'report.json').write_text(json.dumps(report,indent=2))
