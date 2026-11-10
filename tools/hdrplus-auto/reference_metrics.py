#!/usr/bin/env python3
"""Descriptive RAW agreement with Google HDR+ (not a noise-free ground truth).
Uses active-area crops, per-CFA black/white normalization and one integer
CFA-cell translation estimated from the selected input reference. The same
translation applies to all variants. No exposure or color fitting.
"""
import argparse,json
from pathlib import Path
import numpy as np
import rawpy
p=argparse.ArgumentParser();p.add_argument('fixtures',type=Path);a=p.parse_args()

def load(file):
    with rawpy.imread(str(file)) as raw:
        data=raw.raw_image_visible.astype(np.float32)
        planes=[]
        for y in range(2):
            for x in range(2):
                b=raw.black_level_per_channel[int(raw.raw_pattern[y,x])]
                planes.append((data[y::2,x::2]-b)/(raw.white_level-b))
        return np.stack(planes,axis=-1)

def shift(ref,mov):
    # Ignore border artifacts and clipped highlights for phase correlation.
    r=ref[32:-32,32:-32].mean(axis=2);m=mov[32:-32,32:-32].mean(axis=2)
    win=np.hanning(r.shape[0])[:,None]*np.hanning(r.shape[1])[None,:]
    rf=np.fft.rfft2((r-r.mean())*win);mf=np.fft.rfft2((m-m.mean())*win)
    power=rf*np.conj(mf);corr=np.fft.irfft2(power/np.maximum(abs(power),1e-12),s=r.shape)
    y,x=np.unravel_index(corr.argmax(),corr.shape)
    if y>r.shape[0]//2:y-=r.shape[0]
    if x>r.shape[1]//2:x-=r.shape[1]
    if abs(y)>64 or abs(x)>64: raise ValueError(f'Unreliable registration {y,x}')
    return int(y),int(x)

def overlap(ref,mov,dy,dx):
    h,w=ref.shape[:2];pad=32
    y0=max(0,dy)+pad;y1=min(h,h+dy)-pad
    x0=max(0,dx)+pad;x1=min(w,w+dx)-pad
    return ref[y0:y1,x0:x1],mov[y0-dy:y1-dy,x0-dx:x1-dx]

report=[]
for folder in sorted(a.fixtures.iterdir()):
    if not (folder/'google-source.txt').exists():continue
    plan=dict(line.split('=',1) for line in (folder/'decision.txt').read_text().splitlines())
    rows=[line.split('\t') for line in (folder/'frames.tsv').read_text().splitlines()]
    ref=int(plan['kept'].split(',')[int(plan['ref'])])
    google=load((folder/'google-source.txt').read_text().strip())
    source=load(rows[ref][0])
    if google.shape!=source.shape:raise ValueError(f'{folder.name}: active geometry mismatch')
    dy,dx=shift(google,source)
    r,s=overlap(google,source,dy,dx)
    # Shared mask, excluding black/clipped samples in the actual source.
    mask=(s>0.005)&(s<0.95)&(r>0.005)&(r<0.95)
    row={'burst':folder.name,'google_reference':int((folder/'google-reference.txt').read_text()),
         'auto_reference':ref,'shift_raw_pixels':[2*dy,2*dx],'metrics':{}}
    for mode in ['reference','auto','auto-hq','hq3','hq6']:
        data=source if mode=='reference' else load(folder/f'{mode}.dng')
        _,v=overlap(google,data,dy,dx)
        diff=(v-r)[mask].astype(np.float64)
        mse=np.mean(diff*diff)
        row['metrics'][mode]={'psnr_db':float(-10*np.log10(mse)), 'mae':float(np.mean(abs(diff))),
                             'p99_abs_error':float(np.quantile(abs(diff),0.99))}
    report.append(row);print(folder.name,dy,dx,{k:round(v['psnr_db'],2) for k,v in row['metrics'].items()},flush=True)
(a.fixtures/'google-metrics.json').write_text(json.dumps(report,indent=2))
