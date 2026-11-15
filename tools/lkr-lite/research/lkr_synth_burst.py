# SPDX-License-Identifier: Apache-2.0
"""Labelled synthetic RAW burst for LK-R (static detail vs motion, scored by numbers).

Idea from Lecouat et al. 2023 (arXiv 2312.05190): render bursts with known
motion so alignment / merging can be measured instead of judged by eye. Here
the scene is flat (no parallax), built from layers with exact labels:

  background  static, sharp real image content (fine detail that aliases)
  person      textured saturated patch with its own motion (per-frame mask)
  railing     static thin bars IN FRONT of the person (the hard case)

Frame k = camera tremor (shift + small rotation) of the composite, lens blur,
2x2 pixel integration onto the sensor grid, Bayer CFA, Poisson-Gaussian noise
(our normalised model var = a y + b). Frame 0 has no camera motion, so the
ground truth is in its geometry. The scene fills a LR square inside a real
template DNG; outside it the template is copied with fresh noise per frame.

  python3 lkr_synth_burst.py TEMPLATE.dng SOURCE.dng OUT_DIR [--frames 16]
       [--size 512] [--at X Y] [--seed 1] [--person-speed 1.5] [--tremor 1.5]

OUT_DIR gets f000.dng.. plus labels.npz: gt (2*size, 2*size, 3) linear RGB in
the template's camera space at output scale 2, person masks (K, size, size)
on the LR grid, region (x, y, size), shifts.
"""
import argparse
import os
import shutil

import numpy as np
import rawpy
import tifffile
from scipy.ndimage import gaussian_filter, map_coordinates


def half_rgb(path):
    """Linear camera RGB at half resolution (each 2x2 CFA cell -> 1 px, no interpolation)."""
    with rawpy.imread(path) as r:
        raw = r.raw_image_visible.astype('f4')
        bl = np.array(r.black_level_per_channel, 'f4')
        wl = float(r.white_level)
        c = r.raw_colors_visible
        pat = r.raw_pattern
    h, w = raw.shape[0] // 2 * 2, raw.shape[1] // 2 * 2
    out = np.zeros((h // 2, w // 2, 3), 'f4')
    cnt = np.zeros(3, 'f4')
    for dy in (0, 1):
        for dx in (0, 1):
            ch = int(c[dy, dx])
            ch = 1 if ch == 3 else ch
            plane = (raw[dy:h:2, dx:w:2] - bl[pat[dy, dx]]) / (wl - bl[pat[dy, dx]])
            out[..., ch] += plane
            cnt[ch] += 1
    return np.clip(out / cnt, 0, None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('template')
    ap.add_argument('source', help='DNG whose half-resolution content becomes the ground truth')
    ap.add_argument('out')
    ap.add_argument('--frames', type=int, default=16)
    ap.add_argument('--size', type=int, default=512, help='LR side of the synthetic square (even)')
    ap.add_argument('--at', type=int, nargs=2, default=[1536, 1024], help='LR top-left x y (even)')
    ap.add_argument('--src-at', type=int, nargs=2, default=None, help='half-res source top-left x y')
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--tremor', type=float, default=1.5, help='camera shift std, LR px')
    ap.add_argument('--rot', type=float, default=0.03, help='camera rotation std, degrees')
    ap.add_argument('--person-speed', type=float, default=1.5, help='person motion per frame, LR px')
    ap.add_argument('--blur', type=float, default=0.3, help='lens blur sigma, LR px')
    ap.add_argument('--person-level', type=float, default=0.12, help='person mean level (bars are 0.08)')
    ap.add_argument('--person-stripes', type=float, default=7.0, help='clothing stripe period, HR px (0 = off)')
    ap.add_argument('--person-wobble', type=float, default=0.0, help='non-rigid motion amount (0 = rigid)')
    ap.add_argument('--person-blur', type=int, default=1, help='motion-blur sub-positions per exposure (1 = sharp)')
    ap.add_argument('--person-chroma', action='store_true', help='person at the background brightness, colour only')
    ap.add_argument('--no-rail', action='store_true', help='no railing (noise / SNR scenes)')
    ap.add_argument('--rail-level', type=float, default=0.08, help='bar brightness (background median 0.35)')
    ap.add_argument('--noise-a', type=float, default=2.05e-5)
    ap.add_argument('--noise-b', type=float, default=1.4e-6)
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)
    S, K = a.size, a.frames
    HR = 2 * S
    x0, y0 = a.at

    # ---- ground-truth layers on the HR grid (output scale 2) ----
    src = half_rgb(a.source)
    sy, sx = a.src_at[::-1] if a.src_at else ((src.shape[0] - HR) // 2, (src.shape[1] - HR) // 2)
    bg = src[sy:sy + HR, sx:sx + HR].copy()
    bg *= 0.35 / max(np.median(bg[..., 1]), 1e-6)            # mid-grey exposure
    yy, xx = np.mgrid[0:HR, 0:HR].astype('f4')
    # person: textured ellipse, strongly blue, own texture from another part of the source
    ptex = src[(sy + HR // 2) % (src.shape[0] - HR):, :][:HR, :HR]
    # fine-textured clothing, mean level close to the bars (the real hard case), blue tint
    lum = ptex.mean(-1, keepdims=True)
    ptex = (lum / max(float(np.median(lum)), 1e-6)) * a.person_level * np.array([0.6, 0.9, 1.6], 'f4')
    if a.person_stripes > 0:   # clothing pattern at bar-like scale (moves with the person)
        ptex = ptex * (1 + 0.6 * np.sin(2 * np.pi * (xx + 0.5 * yy) / a.person_stripes))[..., None]
    pc = np.array([0.40 * HR, 0.55 * HR])                    # centre (x, y) in frame 0
    prx, pry = 0.07 * HR, 0.20 * HR

    def person_mask(cx, cy, wob=1.0):
        return (((xx - cx) / (prx * wob)) ** 2 + ((yy - cy) / pry) ** 2 <= 1.0).astype('f4')

    # railing: vertical bars, 2 HR px wide, period 9 HR px, over the person's band, plus a top rail
    rail = np.zeros((HR, HR), 'f4')
    band = (xx > 0.15 * HR) & (xx < 0.75 * HR) & (yy > 0.25 * HR) & (yy < 0.85 * HR)
    rail[band & ((xx.astype(int) % 9) < 2)] = 1.0
    rail[(np.abs(yy - 0.25 * HR) < 2) & (xx > 0.15 * HR) & (xx < 0.75 * HR)] = 1.0
    if a.no_rail: rail[:] = 0.0
    rail_col = np.array([a.rail_level] * 3, 'f4')
    tint = np.array([0.6, 0.9, 1.6], 'f4'); tint /= tint.mean()
    bg_lum = gaussian_filter(bg.mean(-1), 6)   # local background brightness (--person-chroma)
    stripes = (1 + 0.15 * np.sin(2 * np.pi * (xx + 0.5 * yy) / max(a.person_stripes, 1e-6))) if a.person_stripes > 0 else 1.0

    def composite_sharp(px, py, k=0):
        # non-rigid (--person-wobble): outline width and clothing pattern change per frame like a walk
        wob = 1.0 + a.person_wobble * np.sin(1.3 * k)
        m = person_mask(px, py, wob)[..., None]
        tx = map_coordinates_shift(ptex, px - pc[0] + a.person_wobble * 6 * np.sin(2.1 * k), py - pc[1])
        if a.person_chroma:   # same brightness as the background behind it, differs in colour only
            sh = map_coordinates(stripes if np.ndim(stripes) else np.ones_like(xx), [yy - (py - pc[1]), xx - (px - pc[0])], order=1, mode='reflect')
            tx = (bg_lum * sh)[..., None] * tint
        img = bg * (1 - m) + tx * m
        r = rail[..., None]
        return img * (1 - r) + rail_col * r, m[..., 0]

    def composite(px, py, k=0):
        # motion blur within the exposure (--person-blur N): average N person positions along the step
        n = max(1, a.person_blur)
        if n == 1:
            return composite_sharp(px, py, k)
        acc, macc = 0.0, 0.0
        for j in range(n):
            o = 2 * a.person_speed * ((j + 0.5) / n - 0.5)
            img, m = composite_sharp(px + o, py, k)
            acc = acc + img; macc = macc + m
        return acc / n, macc / n

    def map_coordinates_shift(img, dx, dy):
        out = np.empty_like(img)
        for ch in range(3):
            out[..., ch] = map_coordinates(img[..., ch], [yy - dy, xx - dx], order=1, mode='reflect')
        return out

    gt, _ = composite(pc[0], pc[1])

    # ---- template sensor geometry ----
    with rawpy.imread(a.template) as r:
        tmpl = r.raw_image_visible.astype('f4')
        bl = np.array(r.black_level_per_channel, 'f4')
        wl = float(r.white_level)
        colors = r.raw_colors_visible.copy()
        pat = r.raw_pattern.copy()
    H, W = tmpl.shape
    assert x0 % 2 == 0 and y0 % 2 == 0 and x0 + S <= W and y0 + S <= H
    blv = bl[pat[np.arange(H)[:, None] % 2, np.arange(W)[None, :] % 2]]
    tnorm = (tmpl - blv) / (wl - blv)                        # template, normalised

    def moved_template(t, dx, dy, th):
        """Template seen by a camera moved by (dx, dy) LR px and th rad about the square's centre;
        each CFA plane resampled on its own grid so the mosaic stays valid."""
        out = np.empty_like(t)
        cx, cy = x0 + S / 2, y0 + S / 2
        for oy in (0, 1):
            for ox in (0, 1):
                plane = t[oy::2, ox::2]
                pi, pj = np.mgrid[0:plane.shape[0], 0:plane.shape[1]].astype('f4')
                X, Y = 2 * pj + ox - cx, 2 * pi + oy - cy
                sx = np.cos(th) * X - np.sin(th) * Y + cx + dx
                sy = np.sin(th) * X + np.cos(th) * Y + cy + dy
                out[oy::2, ox::2] = map_coordinates(plane, [(sy - oy) / 2, (sx - ox) / 2], order=3, mode='nearest')
        return out

    os.makedirs(a.out, exist_ok=True)
    masks, shifts = [], []
    ly, lx = np.mgrid[0:S, 0:S].astype('f4')
    for k in range(K):
        dx, dy = (0.0, 0.0) if k == 0 else rng.normal(0, a.tremor, 2)
        th = 0.0 if k == 0 else np.deg2rad(rng.normal(0, a.rot))
        px, py = pc[0] + 2 * a.person_speed * k, pc[1]
        scene, pm = composite(px, py, k)
        scene = np.stack([gaussian_filter(scene[..., c], 2 * a.blur) for c in range(3)], -1)
        # sensor sample (i, j) integrates HR 2x2 cell around camera-mapped position
        cxh, cyh = HR / 2, HR / 2
        u = (lx + 0.5) * 2 - cxh
        v = (ly + 0.5) * 2 - cyh
        hx = np.cos(th) * u - np.sin(th) * v + cxh + 2 * dx - 0.5
        hy = np.sin(th) * u + np.cos(th) * v + cyh + 2 * dy - 0.5
        lr = np.zeros((S, S, 3), 'f4')
        for oy in (0.0, 1.0):
            for ox in (0.0, 1.0):
                for c in range(3):
                    lr[..., c] += 0.25 * map_coordinates(scene[..., c], [hy + oy, hx + ox], order=3, mode='reflect')
        mm = 0.25 * sum(map_coordinates(pm, [hy + oy, hx + ox], order=1, mode='constant')
                        for oy in (0.0, 1.0) for ox in (0.0, 1.0))
        masks.append(mm > 0.05)
        shifts.append((dx, dy, np.rad2deg(th)))
        # Bayer
        cfa = colors[y0:y0 + S, x0:x0 + S]
        cfa = np.where(cfa == 3, 1, cfa)
        mos = np.take_along_axis(lr, cfa[..., None].astype(int), 2)[..., 0]
        # The rest of the frame moves with the same camera (rotation about the square's
        # centre): otherwise the global alignment sees a static frame and rejects the square.
        frame = moved_template(tnorm, dx, dy, th) if k > 0 else tnorm.copy()
        frame[y0:y0 + S, x0:x0 + S] = np.clip(mos, 0, 1)
        clean = np.clip(frame, 0, None)
        noisy = clean + rng.normal(0, 1, clean.shape).astype('f4') * np.sqrt(a.noise_a * clean + a.noise_b)
        codes = np.clip(np.round(noisy * (wl - blv) + blv), 0, wl).astype('<u2')
        dst = os.path.join(a.out, f'f{k:03}.dng')
        shutil.copyfile(a.template, dst)
        mm_tif = tifffile.memmap(dst, page=0, mode='r+')
        mm_tif[...] = codes
        mm_tif.flush()
        del mm_tif
        print(f'frame {k}: shift ({dx:+.2f}, {dy:+.2f}) LR px, rot {np.rad2deg(th):+.3f} deg', flush=True)
    np.savez_compressed(os.path.join(a.out, 'labels.npz'), gt=gt.astype('f4'), masks=np.array(masks),
                        region=np.array([x0, y0, S]), rail=rail > 0.5, shifts=np.array(shifts))
    print('wrote', a.out)


if __name__ == '__main__':
    main()
