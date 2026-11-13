#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare fixed-setting baseline/candidate float RGB outputs without rescaling.

Reports full-image error and native-pixel crops. Display uses the same simple
camera-RGB transfer on both sides; it is not a camera-profile rendering.
Requires NumPy and Pillow. Float files are lkr_full_run's interleaved .dng.f32.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def fingerprint(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('baseline', type=Path)
    ap.add_argument('candidate', type=Path)
    ap.add_argument('--width', type=int, required=True)
    ap.add_argument('--height', type=int, required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    shape = (args.height, args.width, 3)
    expected = math.prod(shape)*4
    for path in (args.baseline, args.candidate):
        if path.stat().st_size != expected:
            raise ValueError(f'float output length mismatch: {path}')
    a = np.memmap(args.baseline, dtype='<f4', mode='r', shape=shape)
    b = np.memmap(args.candidate, dtype='<f4', mode='r', shape=shape)
    changed = total = 0
    squared = maximum = 0.0
    for y in range(0, args.height, 128):
        aa, bb = a[y:y+128], b[y:y+128]
        if not np.isfinite(aa).all() or not np.isfinite(bb).all():
            raise ValueError('non-finite output')
        diff = aa.astype('f8')-bb
        changed += int(np.count_nonzero(diff))
        squared += float(np.sum(diff*diff))
        maximum = max(maximum, float(np.max(np.abs(diff))))
        total += diff.size
    mse = squared/total
    metrics = dict(width=args.width, height=args.height, finite=True,
                   baseline_sha256=fingerprint(args.baseline), candidate_sha256=fingerprint(args.candidate),
                   changed_float_samples=changed, total_float_samples=total,
                   max_abs_error=maximum, rmse=math.sqrt(mse),
                   psnr_db=None if not mse else -10*math.log10(mse),
                   psnr_infinite=not bool(mse))
    metrics['byte_identical'] = metrics['baseline_sha256']==metrics['candidate_sha256']
    # Shared baseline-derived exposure, no sharpening, denoising or image resizing.
    sample = np.maximum(a[::16, ::16].mean(axis=-1), 0)
    exposure = float(np.clip(.18/max(float(np.median(sample)), .001), .25, 16))
    metrics['display_exposure'] = exposure
    def display(array):
        z = np.clip(np.asarray(array)*exposure, 0, 1)
        return np.uint8(np.where(z<=.0031308, z*12.92, 1.055*z**(1/2.4)-.055)*255+.5)
    args.output.mkdir(parents=True, exist_ok=True)
    overview = Image.fromarray(display(a[::8, ::8]))
    overview.save(args.output/'overview.png')
    rois = []
    side = min(320, args.width, args.height)
    for row, fy in enumerate((.15, .5, .85)):
        for col, fx in enumerate((.15, .5, .85)):
            x = min(max(int(args.width*fx-side/2), 0), args.width-side)
            y = min(max(int(args.height*fy-side/2), 0), args.height-side)
            aa, bb = np.asarray(a[y:y+side, x:x+side]), np.asarray(b[y:y+side, x:x+side])
            def texture(z):
                luma = z.mean(axis=-1)
                dx, dy = np.diff(luma, axis=1), np.diff(luma, axis=0)
                lap = (4*luma[1:-1,1:-1]-luma[:-2,1:-1]-luma[2:,1:-1]
                       -luma[1:-1,:-2]-luma[1:-1,2:])
                return dict(mean=float(luma.mean()), std=float(luma.std()),
                            gradient_rms=float(np.sqrt((np.mean(dx*dx)+np.mean(dy*dy))/2)),
                            laplacian_rms=float(np.sqrt(np.mean(lap*lap))))
            name = f'crop-{row}-{col}'
            rois.append(dict(name=name, box=[x,y,x+side,y+side], baseline=texture(aa), candidate=texture(bb)))
            pair = Image.new('RGB',(side*2,side+24),'white')
            pair.paste(Image.fromarray(display(aa)),(0,24))
            pair.paste(Image.fromarray(display(bb)),(side,24))
            draw = ImageDraw.Draw(pair)
            draw.text((4,5),'BASELINE',fill='black');draw.text((side+4,5),'CANDIDATE',fill='black')
            pair.save(args.output/(name+'.png'))
    metrics['regions'] = rois
    metrics['quality_limit'] = ('Byte identity proves this optimization preserves every baseline pixel; '
        'it does not establish that the baseline is artifact-free. Texture statistics mix real '
        'detail and noise and are not independent noise estimates without ground truth.')
    (args.output/'metrics.json').write_text(json.dumps(metrics,indent=2))
    print(json.dumps({k:v for k,v in metrics.items() if k!='regions'},indent=2))


if __name__=='__main__':
    main()
