#!/usr/bin/env python3
"""Render opt-in LK-SR stage dumps. Requires numpy and Pillow.

LKR_TRACE_PREFIX=/tmp/grass LKR_TRACE_TILE=0 lkr_full_run ...
python inspect_trace.py /tmp/grass_tile_0_0_meta.txt
Images share exposure; temporal maps are in the source frame's coordinates.
"""
import argparse
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('meta', type=Path)
    ap.add_argument('--frame', type=int, default=1)
    args = ap.parse_args()
    meta = dict(t.split('=') for t in args.meta.read_text().split())
    H, W, h, w, K = [int(meta[k]) for k in ('H', 'W', 'h', 'w', 'K')]
    if not 0 <= args.frame < K:
        ap.error('--frame must be within the burst')
    stem = str(args.meta).removesuffix('_meta.txt')
    wb = np.array(list(map(float, meta['wb'].split(','))))[:, None, None]

    def read(tag, shape):
        return np.fromfile(stem + '_' + tag + '.f32', '<f4').reshape(shape)

    z = read('final_z', (3, H, W))
    exposure = min(32., .25 / max(np.median((z * wb).mean(0)), .001))
    iterations = sorted(int(f.name.split('_iter')[-1].split('_')[0])
                        for f in args.meta.parent.glob(Path(stem).name + '_iter*_z.f32'))
    if not iterations:
        ap.error('trace has no prior calls')
    first, middle, last = iterations[0], iterations[len(iterations) // 2], iterations[-1]
    tags = [f'iter{it}_{stage}' for it in (first, middle) for stage in ('z', 'network', 'x')]
    tags += ['final_z', 'final_x', 'final_look']
    sheet = Image.new('RGB', (3 * W, 3 * (H + 24)), 'white')
    draw = ImageDraw.Draw(sheet)
    for i, tag in enumerate(tags):
        if not Path(stem + '_' + tag + '.f32').exists():
            continue
        a = np.clip(read(tag, (3, H, W)) * wb * exposure, 0, 1)
        a = np.where(a <= .0031308, 12.92 * a, 1.055 * a ** (1 / 2.4) - .055)
        im = Image.fromarray((a.transpose(1, 2, 0) * 255).astype('uint8'))
        x, y = (i % 3) * W, (i // 3) * (H + 24)
        sheet.paste(im, (x, y + 24))
        draw.text((x + 4, y + 4), tag, fill='black')
    sheet.save(stem + '_stages.jpg', quality=95)
    # Actual residual from the same GPU pass that produced temporal weights;
    # no CPU re-warp with the slightly newer motion parameters.
    residual_path = Path(stem + f'_iter{last}_temporal_residual.f32')
    if not residual_path.exists():
        return
    r = read(f'iter{last}_temporal_residual', (K, h, w))[args.frame]
    rows = read(f'iter{last}_temporal_rows', (K, h, w, 4))[args.frame]
    a = np.pad(rows, ((2, 2), (0, 0), (0, 0)), mode='edge')
    sums = sum(a[i:i + h] for i in range(5))
    count = np.maximum(sums[:, :, 2], 1)
    mean = sums[:, :, 0] / count
    rms = np.sqrt(np.maximum(sums[:, :, 3] / count, 0))
    sigma = np.sqrt(np.maximum(2 * sums[:, :, 1] / count, 1e-12))
    panels = [('signed residual / 6 sigma', .5 + r / (12 * sigma)),
              ('signed mean / 6 sigma', .5 + mean / (12 * sigma)),
              ('RMS / 6 sigma (no edge allowance)', rms / (6 * sigma)),
              ('temporal acceptance (white=1)', read(f'iter{last}_temporal', (K, h, w))[args.frame]),
              ('combined weight (white=1)', read(f'iter{last}_weights', (K, h, w))[args.frame])]
    sheet = Image.new('RGB', (len(panels) * w, h + 24), 'white')
    draw = ImageDraw.Draw(sheet)
    for i, (label, data) in enumerate(panels):
        sheet.paste(Image.fromarray((np.clip(data, 0, 1) * 255).astype('uint8')), (i*w, 24))
        draw.text((i*w+4, 4), label, fill='black')
    sheet.save(stem + f'_frame{args.frame}_motion.jpg', quality=95)
    print(f'exposure={exposure:g}; wrote {stem}_stages.jpg and motion maps')


if __name__ == '__main__':
    main()
