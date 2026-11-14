#!/usr/bin/env python3
"""Offline feasibility probe on saved foliage sweep outputs; never alters solver data.

Input is the handoff's sw/guide directory (f1,f2,t1,t2 .dng and .dng.f32).
Replays the FinishLook B3 notch on the finished image with full strength,
and a diagonal-only variant. These are probes, not proposed production fixes
or bounds on every possible spatial gate. Arithmetic RGB luma scores match
the handoff; the filtering itself uses FinishLook's camera-WB luma.
"""
import argparse
import json
from pathlib import Path
import numpy as np
import tifffile
from PIL import Image


def blur(a):
    h, w = a.shape
    kernel = (1/16, 4/16, 6/16, 4/16, 1/16)
    p = np.pad(a, ((0, 0), (2, 2)), mode='reflect')
    b = sum(v*p[:, i:i+w] for i, v in enumerate(kernel))
    p = np.pad(b, ((2, 2), (0, 0)), mode='reflect')
    return sum(v*p[i:i+h] for i, v in enumerate(kernel))


def notch(a, modes):
    out = a.copy()
    y, x = np.indices(a.shape)
    for mode in modes:
        sign = (1-2*((x if mode == 0 else y if mode == 1 else x+y) % 2)).astype('f4')
        out -= blur(out*sign)*sign
    return out


def score(a):
    l = a.mean(2)
    return dict(checker=float(np.abs(l[::2, ::2]-l[::2, 1::2]-l[1::2, ::2]+l[1::2, 1::2]).mean()/4/l.mean()),
                detail=float((np.abs(np.diff(l, axis=0)).mean()+np.abs(np.diff(l, axis=1)).mean())/l.mean()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    for case in ('f1', 'f2', 't1', 't2'):
        n = 512 if case.endswith('1') else 1024
        a = np.fromfile(args.input/(case+'.dng.f32'), 'f4').reshape(n, n, 3)
        with tifffile.TiffFile(args.input/(case+'.dng')) as t:
            neutral = np.asarray(t.pages[0].tags[50728].value).reshape(3, 2)
            neutral = neutral[:, 0]/neutral[:, 1]
            wb = (neutral[1]/neutral).astype('f4')
        luma = (a*wb*np.array([.25, .5, .25], 'f4')).sum(2)
        views = [a]
        row = dict(case=case, guide=score(a))
        for name, modes in [('diagonal', (2,)), ('existing_three_modes', (0, 1, 2))]:
            b = a+(notch(luma, modes)-luma)[:, :, None]/wb
            row[name] = score(b)
            b.astype('f4').tofile(args.output/(case+'-'+name+'.f32'))
            views.append(b)
        # Shared display exposure, no resizing: central native 320px crops.
        exposure = 0.5/max(float(np.percentile(a*wb, 90)), 1e-8)
        y = x = (n-320)//2
        crops = [np.clip(v[y:y+320, x:x+320]*wb*exposure, 0, 1)**(1/2.2) for v in views]
        Image.fromarray((np.concatenate(crops, axis=1)*255).astype('u1')).save(args.output/(case+'.png'))
        results.append(row)
    (args.output/'results.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
