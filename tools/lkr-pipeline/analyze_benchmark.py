#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarize FfdnetBenchmarkDeviceTest JSONs and numerical backend agreement."""
import argparse
import json
from pathlib import Path
import statistics
import numpy as np


def agreement(a_path,b_path,width=4000,height=3000):
    a=np.memmap(a_path,dtype=np.float16,mode='r',shape=(height,width,4))
    b=np.memmap(b_path,dtype=np.float16,mode='r',shape=(height,width,4))
    se=0.;sa=0.;maximum=0.;count=0;alpha_equal=True;means=np.zeros(3)
    for y in range(0,height,64):
        x=a[y:y+64,:,:3].astype(np.float32);z=b[y:y+64,:,:3].astype(np.float32)
        if not np.isfinite(x).all() or not np.isfinite(z).all():raise ValueError('Non-finite output')
        delta=z-x
        se+=np.sum(delta.astype(np.float64)**2);sa+=np.sum(abs(delta),dtype=np.float64)
        maximum=max(maximum,float(abs(delta).max()));count+=delta.size;means+=delta.sum((0,1),dtype=np.float64)
        alpha_equal=alpha_equal and bool(np.array_equal(a[y:y+64,:,3],b[y:y+64,:,3]))
    return {'rgb_rmse':float((se/count)**.5),'rgb_mean_absolute_difference':float(sa/count),
            'rgb_max_absolute_difference':maximum,'mean_signed_difference_RGB':(means/(width*height)).tolist(),
            'alpha_equal':alpha_equal,'note':'Backend agreement in normalized camera-linear RGB, not accuracy against clean ground truth.'}


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('directory',type=Path);args=ap.parse_args();d=args.directory
    records={};rows=[]
    for label,file in [('NPU','npu.json'),('GPU FP32','gpu-fp32.json'),('GPU FP16','gpu-fp16.json')]:
        if not (d/file).exists():continue
        report=json.loads((d/file).read_text());runs=[r for r in report['runs'] if r['success']==1]
        if not runs:continue
        warm=runs[1:]
        means={key:statistics.mean(r[key] for r in warm) for key in warm[0] if isinstance(warm[0][key],(int,float))} if warm else None
        records[label]={'first_run':runs[0],'completed_images':len(runs),'warm_mean':means}
        rows.append([label,f"{runs[0]['wall_ms']/1000:.2f}",f"{means['wall_ms']/1000:.2f}" if means else 'not measured',
            f"{means['inference_ms']/1000:.2f}" if means else f"{runs[0]['inference_ms']/1000:.2f} (first)",len(runs)])
    result={'timings':records,'agreement':{}}
    if (d/'gpu-fp32-output.rgba16f').exists():
        for label,file in [('NPU versus GPU FP32','npu-output.rgba16f'),('GPU FP16 versus GPU FP32','gpu-fp16-output.rgba16f')]:
            if (d/file).exists():result['agreement'][label]=agreement(d/'gpu-fp32-output.rgba16f',d/file)
    (d/'summary.json').write_text(json.dumps(result,indent=2))
    table='| Backend | First image (s) | Warm mean (s) | Model execution (s) | Completed images |\n|---|---:|---:|---:|---:|\n'
    table+='\n'.join('| '+' | '.join(map(str,row))+' |' for row in rows)+'\n'
    (d/'timings.md').write_text(table);print(table)
    print(json.dumps(result['agreement'],indent=2))

if __name__=='__main__':main()
