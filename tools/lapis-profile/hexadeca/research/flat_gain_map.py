#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Field-varying 4x4 intra-block gain map from smooth (defocused / flat) 200 MP captures.
gain[ty, tx, colour, iy, ix] = sum(pixel) / sum(block mean) over unclipped blocks in tile (ty, tx);
tiles with too few blocks are filled from neighbours. Writes .npy + JSON summary.
Usage: flat_gain_map.py OUT.npy FLAT.raw16 [FLAT.raw16 ...]"""
import sys, json, numpy as np
from scipy import ndimage
W, H, N, BLACK, WHITE = 16320, 12240, 4, 64.0, 1023.0
TY, TX = 12, 16
GBRG = np.array([[1, 2], [0, 1]])
out, files = sys.argv[1], sys.argv[2:]
num = np.zeros((TY, TX, 3, N, N)); den = np.zeros((TY, TX, 3, N, N)); cnt = np.zeros((TY, TX, 3))
bh, bw = H // N, W // N
by, bx = np.mgrid[0:bh, 0:bw]
bc = GBRG[by % 2, bx % 2]; ty = by * TY // bh; tx = bx * TX // bw
for f in files:
    r = np.memmap(f, '<u2', 'r', shape=(H, W))
    for y0 in range(0, bh, 240):          # strips (+margin) to bound memory
        y1 = min(bh, y0 + 240); m0, m1 = max(0, y0 - 4), min(bh, y1 + 4)
        b = np.asarray(r[m0 * N:m1 * N], np.float32).reshape(m1 - m0, N, bw, N).transpose(0, 2, 1, 3)
        mx = b.max(axis=(2, 3)); v = b - BLACK; m = v.mean(axis=(2, 3))
        good = (m > 60) & (mx < WHITE - 8)
        bcs = bc[m0:m1]
        # de-trended reference: per colour, smooth block-mean field (normalised conv over same-colour
        # blocks), evaluated at each pixel by bilinear interpolation -> removes in-block light gradients
        ref = np.zeros_like(v)
        for c in range(3):
            w = (good & (bcs == c)).astype(np.float32)
            fld = ndimage.gaussian_filter(m * w, 1.2) / np.maximum(ndimage.gaussian_filter(w, 1.2), 1e-6)
            up = ndimage.zoom(fld, N, order=1, mode='nearest', grid_mode=True)  # pixel-resolution field
            ref[bcs == c] = up.reshape(m1 - m0, N, bw, N).transpose(0, 2, 1, 3)[bcs == c]
        s0 = y0 - m0
        v, ref, good, bcs = v[s0:s0 + y1 - y0], ref[s0:s0 + y1 - y0], good[s0:s0 + y1 - y0], bcs[s0:s0 + y1 - y0]
        for c in range(3):
            sel = good & (bcs == c)
            t = (ty[y0:y1][sel] * TX + tx[y0:y1][sel])
            np.add.at(num.reshape(TY * TX, 3, N, N)[:, c], t, v[sel])
            np.add.at(den.reshape(TY * TX, 3, N, N)[:, c], t, ref[sel])
            np.add.at(cnt.reshape(TY * TX, 3)[:, c], t, 1)
g = num / np.maximum(den, 1e-9)
g /= g.mean(axis=(3, 4), keepdims=True)
valid = cnt > 2000
for c in range(3):                      # fill empty tiles with nearest valid tile
    idx = ndimage.distance_transform_edt(~valid[..., c], return_distances=False, return_indices=True)
    g[:, :, c] = g[idx[0], idx[1], c]
np.save(out, g.astype(np.float32))
print(json.dumps({'validTiles': [int(valid[..., c].sum()) for c in range(3)], 'tiles': TY * TX,
                  'gRange': [float(g.min()), float(g.max())]}))
