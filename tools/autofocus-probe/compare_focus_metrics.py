#!/usr/bin/env python3
"""Compare candidate metrics on exact-frame crops. Requires numpy; no training or runtime AF changes."""
import argparse
import json
import statistics
from pathlib import Path
import numpy as np


def convolve(image, kernel):
    windows = np.lib.stride_tricks.sliding_window_view(image, kernel.shape)
    # A clipped input invalidates the complete support, including zero-coefficient positions.
    valid = np.isfinite(windows).all(axis=(-1, -2))
    values = np.einsum('ijkl,kl->ij', np.nan_to_num(windows), kernel)
    return values, valid


SOBEL = np.outer([1, 2, 1], [-1, 0, 1]) / 8.0
BLUR = np.outer([1, 2, 1], [1, 2, 1]) / 16.0
SMOOTH = np.zeros((5, 5))
for y in range(3):
    for x in range(3):
        SMOOTH[y:y+3, x:x+3] += BLUR[y, x] * SOBEL
LAPLACIAN = np.array([[0., 1., 0.], [1., -4., 1.], [0., 1., 0.]])
BRENNER = np.array([[0., 0., 0.], [-1., 0., 1.], [0., 0., 0.]])


def metric(image, kernel, variance=0.0, two_axes=True):
    gx, valid = convolve(image, kernel)
    energy = gx * gx
    factor = float((kernel * kernel).sum())
    if two_axes:
        gy, vy = convolve(image, kernel.T)
        valid &= vy
        energy += gy * gy
        factor *= 2
    if valid.sum() < energy.size // 2:
        return None
    return max(0., float(energy[valid].mean()) - factor * variance)


def scores(image, variance):
    return {'brenner': metric(image, BRENNER, variance),
            'tenengrad': metric(image, SOBEL, variance),
            'smooth_gradient': metric(image, SMOOTH, variance),
            'laplacian_energy': metric(image, LAPLACIAN, variance, False)}


def analyze(trace, crops):
    records = [json.loads(line) for line in trace.read_text().splitlines() if line.strip()]
    config = next(r for r in records if r['type'] == 'config')
    frames = [r for r in records if r['type'] == 'frame']
    data = np.memmap(crops, dtype='>f4', mode='r')
    if len(data) % 4096 or max(f['cropIndex'] for f in frames) >= len(data) // 4096:
        raise ValueError('Crop file truncated or incompatible with trace')
    commands = [r for r in records if r['type'] == 'command']
    rows, selected = [], []
    step = config.get('focusStep') or .25
    tolerance = min(.02, step * .2)  # Do not let the old coarse tolerance hide fine-step quantization.
    for command in commands:
        samples = sorted((f for f in frames if f['command'] == command['command'] and
                          f.get('lensState') == 0 and f.get('reportedFocus') is not None and
                          abs(f['reportedFocus'] - command['target']) <= tolerance), key=lambda f: f['frame'])[-8:]
        if len(samples) < 5:
            rows.append(dict(command=command['command'], target=command['target'], usable=False))
            continue
        measured = []
        for f in samples:
            image = np.asarray(data[f['cropIndex']*4096:(f['cropIndex']+1)*4096], dtype=float).reshape(64, 64)
            values = scores(image, f.get('noiseVarianceBound', 0))
            measured.append(values)
            selected.append((f, image))
        row = dict(command=command['command'], target=command['target'], usable=True,
                   reportedMedian=statistics.median(f['reportedFocus'] for f in samples),
                   mean=statistics.median(f['mean'] for f in samples), metrics={})
        for name in measured[0]:
            values = [m[name] for m in measured if m[name] is not None]
            median = statistics.median(values) if values else 0
            row['metrics'][name] = dict(median=median, mad=statistics.median(abs(v-median) for v in values) if values else None)
        rows.append(row)
    names = list(scores(np.ones((64, 64)), 0))
    comparisons = {}
    unique = sorted(set(c['target'] for c in commands))
    # Alternating ascending and descending legs, including duplicate turn endpoints.
    for name in names:
        legs = []
        for start in range(0, len(rows), len(unique)):
            leg = [r for r in rows[start:start+len(unique)] if r['usable']]
            if not leg:
                continue
            best = max(leg, key=lambda r: r['metrics'][name]['median'])
            peak = best['metrics'][name]
            ordered = sorted(leg, key=lambda r: r['target'])
            contrasts = []
            for a, b in zip(ordered, ordered[1:]):
                ma, mb = a['metrics'][name], b['metrics'][name]
                # Descriptive separation criterion, not a calibrated significance test.
                contrasts.append(abs(ma['median']-mb['median']) > 3 * max(ma['mad'], mb['mad'], 1e-12))
            legs.append(dict(bestTarget=best['target'], peakScore=peak['median'],
                             peakMAD=peak['mad'], peakAtBoundary=best['target'] in (unique[0], unique[-1]),
                             separatedAdjacentPairs=sum(contrasts), adjacentPairs=len(contrasts)))
        comparisons[name] = dict(legs=legs, peakSpread=max((l['bestTarget'] for l in legs), default=0)-min((l['bestTarget'] for l in legs), default=0))
    return dict(config=config, commands=rows, metrics=comparisons,
                unqualifiedCommands=sum(not r['usable'] for r in rows),
                limitations=['Same ROI/scene only; not independent ground truth or production acceptance.',
                             'Noise subtraction assumes independent spatial noise and uses a conservative metadata bound.',
                             'Adjacent separation is descriptive; MAD is not a confidence interval.']), selected


def write_contact_sheet(selected, path):
    # Portable grayscale PGM; fixed display scaling across positions avoids artificial contrast improvements.
    chosen = selected[::8]
    if not chosen:
        return
    canvas = np.zeros((((len(chosen)+7)//8)*64, 8*64), dtype=np.uint8)
    all_pixels = np.concatenate([im[np.isfinite(im)] for _, im in chosen])
    lo, hi = np.percentile(all_pixels, [1, 99])
    for i, (_, image) in enumerate(chosen):
        tile = np.clip((np.nan_to_num(image, nan=lo)-lo) / max(hi-lo, 1e-6), 0, 1)
        canvas[(i//8)*64:(i//8+1)*64, (i%8)*64:(i%8+1)*64] = (tile*255).astype(np.uint8)
    path.write_bytes(f'P5\n{canvas.shape[1]} {canvas.shape[0]}\n255\n'.encode()+canvas.tobytes())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('trace', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    result, selected = analyze(args.trace, args.trace.with_suffix('.f32'))
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
    write_contact_sheet(selected, args.output.with_suffix('.pgm'))
    for name, result in result['metrics'].items():
        print(name, result)

if __name__ == '__main__':
    main()
