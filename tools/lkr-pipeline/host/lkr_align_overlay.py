# SPDX-License-Identifier: Apache-2.0
"""Alignment check overlay for one traced LK-R tile (idea: Lecouat et al. 2023, appendix J).

For every frame k, red = the merged model's prediction of frame k (y + r),
green and blue = frame k itself. Where they agree the overlay is grey; a
misaligned edge or a moving object shows as red/cyan fringes. The reference
frame (k = 0) is left out of the grid.

  LKR_TRACE_PREFIX=/tmp/t LKR_TRACE_TILE=0 lkr_full_run BURST out.dng --crop 448 448 --at X Y
  python3 lkr_align_overlay.py /tmp/t_tile_0_0 overlay.png [--gain 4]

--gain exaggerates the difference (pred - frame) so sub-pixel misalignment
is visible: red channel = frame + gain * (pred - frame).
"""
import argparse
import re

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('prefix', help='trace prefix incl. _tile_X_Y')
    ap.add_argument('out')
    ap.add_argument('--gain', type=float, default=1.0)
    ap.add_argument('--cols', type=int, default=4)
    a = ap.parse_args()

    meta = open(a.prefix + '_meta.txt').read()
    get = lambda k: int(re.search(rf'\b{k}=(\d+)', meta).group(1))
    h, w, K = get('h'), get('w'), get('K')
    y = np.fromfile(a.prefix + '_frames.f32', 'f4').reshape(K, h, w)
    r = np.fromfile(a.prefix + '_final_resid.f32', 'f4').reshape(K, h, w)

    def grey(m):   # Bayer mosaic -> half-resolution grey (2x2 mean)
        m = m[:h // 2 * 2, :w // 2 * 2]
        return m.reshape(h // 2, 2, w // 2, 2).mean((1, 3))

    scale = 0.45 / max(np.median(grey(y[0])), 1e-6)
    tiles = []
    for k in range(1, K):
        valid = grey((r[k] != 0).astype('f4')) > 0.99
        f = grey(y[k]) * scale
        p = f + a.gain * grey(r[k]) * scale
        p = np.where(valid, p, f)
        rgb = np.stack([p, f, f], -1)
        rgb = np.clip(rgb, 0, 1) ** (1 / 2.2)
        tiles.append(np.uint8(rgb * 255))
    cols = min(a.cols, len(tiles))
    rows = (len(tiles) + cols - 1) // cols
    th, tw = tiles[0].shape[:2]
    grid = np.full((rows * (th + 4), cols * (tw + 4), 3), 255, np.uint8)
    for i, t in enumerate(tiles):
        y0, x0 = (i // cols) * (th + 4), (i % cols) * (tw + 4)
        grid[y0:y0 + th, x0:x0 + tw] = t
    Image.fromarray(grid).save(a.out)
    print(f'{K - 1} frames -> {a.out}')


if __name__ == '__main__':
    main()
