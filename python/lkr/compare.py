# SPDX-License-Identifier: GPL-3.0-or-later
"""Side-by-side: bicubic ref | RawLens MFSR | LK-Reloaded, same crop, same tone.

  python -I compare.py --mfsr-dir tools/sr-pipeline/build/c13a --tag mb_<burst>_<place> \
      --lkr out/lkr_x2_drunet_T8.npy --wb 1.879 1 1.49 --out cmp.png [--x 0 --y 0 --n 384 --zoom 2]

MFSR .f32 dumps come from MultiBurst2xTest (SRP_DUMP=1): [y][x][c] float32.
Also prints the edge/flat metrics on the full crop for all three.
"""
import argparse
import os

import numpy as np


def load_f32(path, n):
    return np.fromfile(path, '<f4').reshape(n, n, 3).transpose(2, 0, 1).astype(np.float64)


def tone(x, wb, ev):
    v = np.clip(np.nan_to_num(x) * np.asarray(wb).reshape(3, 1, 1) * ev, 0, 1)
    v = np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)
    return (v.transpose(1, 2, 0) * 255 + 0.5).astype(np.uint8)


def metrics(x, wb):
    y = (x * np.asarray(wb).reshape(3, 1, 1)).mean(0)[16:-16, 16:-16]
    gy, gx = np.gradient(y)
    g = np.hypot(gx, gy)
    edge = np.mean(np.sort(g.ravel())[-g.size // 20:])          # top-5 % gradient
    lap = 4 * y[1:-1, 1:-1] - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]
    flat = np.median(np.abs(lap)[g[1:-1, 1:-1] < np.percentile(g, 30)])
    return edge, flat


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mfsr-dir', required=True)
    ap.add_argument('--tag', required=True)
    ap.add_argument('--lkr', required=True, nargs='+', help='one or more LKR .npy outputs')
    ap.add_argument('--wb', type=float, nargs=3, required=True)
    ap.add_argument('--ev', type=float, default=2.0)
    ap.add_argument('--x', type=int, default=320)
    ap.add_argument('--y', type=int, default=320)
    ap.add_argument('--n', type=int, default=384)
    ap.add_argument('--zoom', type=int, default=1)
    ap.add_argument('--out', required=True)
    ap.add_argument('--swap-rb', action='store_true', help='MFSR dumps read a GBRG burst as GRBG')
    a = ap.parse_args()
    Ls = [np.load(f).astype(np.float64) for f in a.lkr]
    N = Ls[0].shape[1]
    A = load_f32(os.path.join(a.mfsr_dir, a.tag + '_A.f32'), N)
    C = load_f32(os.path.join(a.mfsr_dir, a.tag + '_C.f32'), N)
    if a.swap_rb:
        A, C = A[::-1].copy(), C[::-1].copy()
    ea, fa = metrics(A, a.wb)
    named = [('bicubic', A), ('mfsr', C)] + [(os.path.basename(f)[:-4], L) for f, L in zip(a.lkr, Ls)]
    for name, img in named:
        e, f = metrics(img, a.wb)
        print(f'{name:22s} edge {e / ea:.2f}  flat-noise {f / fa:.2f}')
    s = (slice(None), slice(a.y, a.y + a.n), slice(a.x, a.x + a.n))
    tiles = [tone(img[s], a.wb, a.ev) for _, img in named]
    tiles = [t.repeat(a.zoom, 0).repeat(a.zoom, 1) for t in tiles]
    sep = np.full((tiles[0].shape[0], 6, 3), 255, np.uint8)
    import imageio.v3 as iio
    row = [tiles[0]]
    for t in tiles[1:]:
        row += [sep, t]
    iio.imwrite(a.out, np.concatenate(row, 1))
    print('wrote', a.out)


if __name__ == '__main__':
    main()
