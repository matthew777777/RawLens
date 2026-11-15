#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Version-2 200 MP -> 50 MP prototype: 4x4-aware full-resolution demosaic, then 2x downsample.

Compares against the current path (rawr bin -> 50 MP Bayer -> MHC demosaic) on
  * synthetic ground truth pushed through a simulated 4x4-block (hexadeca) GBRG sensor
    with per-position gain mismatch, lens blur and Poisson-Gaussian noise, and
  * real crops of bright200.raw16 (no ground truth: visual + spectral artefact checks).

Usage: hexa_v2_proto.py synth OUTDIR [--convert PATH] [--photo JPG]
       hexa_v2_proto.py real  OUTDIR RAW16 [--convert PATH]
"""
import argparse, subprocess, tempfile, json, os
import numpy as np
from scipy import ndimage
from PIL import Image

N = 4                      # pixels per block side
BLACK, WHITE = 64.0, 1023.0
GBRG = np.array([[1, 2], [0, 1]])   # block colour: 0 R, 1 G, 2 B (order code 2)


def colour_map(h, w):
    y, x = np.mgrid[0:h, 0:w]
    return GBRG[(y // N) % 2, (x // N) % 2]


# ---------------------------------------------------------------- gains
def estimate_gains(raw, cmap):
    """Per (colour, iy, ix) gain: sum(pixel) / sum(block mean), mid-tone unclipped blocks (rawr's estimator,
    single global table). Returns g[c, iy, ix]."""
    h, w = raw.shape
    v = raw - BLACK
    blocks = v.reshape(h // N, N, w // N, N).transpose(0, 2, 1, 3)
    mean = blocks.mean(axis=(2, 3))
    mx = (raw.reshape(h // N, N, w // N, N).transpose(0, 2, 1, 3)).max(axis=(2, 3))
    ok = (mean > 2) & (mx < WHITE - 1)
    bc = cmap[::N, ::N]
    g = np.ones((3, N, N))
    for c in range(3):
        sel = ok & (bc == c)
        g[c] = blocks[sel].sum(axis=0) / mean[sel].sum()
    return g


def flatten_gains(raw, cmap, g):
    h, w = raw.shape
    iy, ix = np.mgrid[0:h, 0:w]
    return (raw - BLACK) / g[cmap, iy % N, ix % N]


# ---------------------------------------------------------------- version 2
def gauss(a, s):
    return ndimage.gaussian_filter(a, s, mode='mirror', truncate=3.0)


KMIN, KMAX = 0.02, 50.0      # colour/luma ratio bounds (physical range is far inside)


def demosaic_v2(v, cmap, sigma=(3.0, 2.2, 3.0), iters=2, eps=0.5, floor=2.0):
    """Ratio-domain demosaic for 4x4 block CFAs.
    Luminance keeps every sample's high frequencies (each sample rescaled by its local colour ratio);
    chroma (colour / luminance ratios) is smooth and interpolated by normalised convolution."""
    masks = [(cmap == c).astype(np.float32) for c in range(3)]
    den = [gauss(m, sigma[c]) for c, m in enumerate(masks)]
    lp = [gauss(v * masks[c], sigma[c]) / den[c] for c in range(3)]
    K = [np.maximum(l, eps) for l in lp]                         # first pass: absolute colour planes
    Y = (K[0] + 2 * K[1] + K[2]) / 4
    K = [k / Y for k in K]                                       # -> colour/luma ratios
    for _ in range(iters):
        Kc = np.choose(cmap, K)
        L = v / np.maximum(Kc, 1e-3)                             # full-res luminance from every sample
        # Regularised ratio: in deep shadow both sums are noise around 0 and an unregularised
        # quotient explodes (white/blue dots). The noise-floor term shrinks K towards the previous,
        # bounded first-pass ratio there (not towards neutral, which would desaturate shadows).
        K = [(gauss(masks[c] * v, sigma[c]) + floor * den[c] * K[c]) / (gauss(masks[c] * L, sigma[c]) + floor * den[c])
             for c in range(3)]
        K = [np.clip(k, KMIN, KMAX) for k in K]
        Yk = (K[0] + 2 * K[1] + K[2]) / 4
        K = [k / Yk for k in K]
    Kc = np.choose(cmap, K)
    L = v / np.maximum(Kc, 1e-3)
    return np.stack([L * K[c] for c in range(3)], -1)


def box(a, r):
    return ndimage.uniform_filter(a, 2 * r + 1, mode='mirror')


def demosaic_ri(v, cmap, r=5, iters=3, eps=4.0, sigma=(3.0, 2.2, 3.0)):
    """Residual interpolation for 4x4 block CFAs (version 2b).
    Each colour is modelled locally as a*L + b (guided filter fitted on that colour's samples only),
    with L the full-resolution luminance; the residual at the samples is then interpolated and added back.
    Edges between two colours stay sharp because the model is linear in the guide, not a smoothed ratio."""
    masks = [(cmap == c).astype(np.float32) for c in range(3)]
    planes = demosaic_v2(v, cmap, sigma=sigma, iters=1)       # initial estimate
    planes = [planes[..., c] for c in range(3)]
    for _ in range(iters):
        own = [np.where(cmap == c, v, planes[c]) for c in range(3)]
        L = (own[0] + 2 * own[1] + own[2]) / 4
        new = []
        for c in range(3):
            m = masks[c]
            n = np.maximum(box(m, r), 1e-6)
            mL, mV = box(m * L, r) / n, box(m * v, r) / n
            cLV = box(m * L * v, r) / n - mL * mV
            vL = box(m * L * L, r) / n - mL * mL
            a = cLV / (vL + eps)
            b = mV - a * mL
            a, b = box(a, r), box(b, r)
            est = a * L + b
            res = gauss(m * (v - est), sigma[c]) / gauss(m, sigma[c])
            new.append(est + res)
        planes = new
    return np.stack([np.where(cmap == c, v, planes[c]) for c in range(3)], -1)


def down2(rgb):
    h, w = rgb.shape[:2]
    return rgb.reshape(h // 2, 2, w // 2, 2, -1).mean(axis=(1, 3))


# ---------------------------------------------------------------- current path
def rawr_bin(raw, convert):
    h, w = raw.shape
    with tempfile.TemporaryDirectory() as t:
        src, dst = os.path.join(t, 's.raw16'), os.path.join(t, 'd.raw16')
        np.clip(np.rint(raw), 0, 1023).astype('<u2').tofile(src)
        subprocess.run([convert, src, dst, str(w), str(h), '2', str(int(BLACK)), str(int(WHITE)), '8'],
                       check=True, capture_output=True)
        return np.fromfile(dst, '<u2').reshape(h // 2, w // 2).astype(np.float32)


def mhc_gbrg(bayer):
    """Malvar-He-Cutler on a GBRG mosaic (values already black-subtracted)."""
    h, w = bayer.shape
    y, x = np.mgrid[0:h, 0:w]
    cm = GBRG[y % 2, x % 2]
    k = lambda a: np.array(a, np.float32) / 8
    G_at_RB = k([[0, 0, -1, 0, 0], [0, 0, 2, 0, 0], [-1, 2, 4, 2, -1], [0, 0, 2, 0, 0], [0, 0, -1, 0, 0]])
    Rrow = k([[0, 0, .5, 0, 0], [0, -1, 0, -1, 0], [-1, 4, 5, 4, -1], [0, -1, 0, -1, 0], [0, 0, .5, 0, 0]])
    Rcol = Rrow.T
    Rdiag = k([[0, 0, -1.5, 0, 0], [0, 2, 0, 2, 0], [-1.5, 0, 6, 0, -1.5], [0, 2, 0, 2, 0], [0, 0, -1.5, 0, 0]])
    f = lambda kk: ndimage.convolve(bayer, kk, mode='mirror')
    g_rb, rr, rc, rd = f(G_at_RB), f(Rrow), f(Rcol), f(Rdiag)
    R, G, B = bayer.copy(), bayer.copy(), bayer.copy()
    isR, isB, isG = cm == 0, cm == 2, cm == 1
    G[isR | isB] = g_rb[isR | isB]
    # green pixel in a G-B row (row 0 of GBRG) / G-R row (row 1)
    gInB = isG & (y % 2 == 0)
    gInR = isG & (y % 2 == 1)
    R[gInB], B[gInB] = rc[gInB], rr[gInB]
    R[gInR], B[gInR] = rr[gInR], rc[gInR]
    R[isB], B[isR] = rd[isB], rd[isR]
    return np.stack([R, G, B], -1)


def current_path(raw, cmap, convert):
    b = rawr_bin(raw, convert) - BLACK
    return mhc_gbrg(b)


# ---------------------------------------------------------------- synthetic scene
def synth_scene(s, photo=None, seed=1):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:s, 0:s].astype(np.float32) / s
    q = s // 2
    img = np.zeros((s, s, 3), np.float32)
    # TL: zone plate (radial chirp), grey
    yy, xx = (y[:q, :q] - .25) * s, (x[:q, :q] - .25) * s
    zp = 0.5 + 0.4 * np.cos(np.pi * (xx ** 2 + yy ** 2) / (q * 1.0))
    img[:q, :q] = zp[..., None] * [0.8, 0.8, 0.8]
    # TR: slanted (5 deg) edges: grey and red/blue, plus fine bar groups
    ty, tx = y[:q, q:] * s, x[:q, q:] * s - q
    e1 = ((tx - q / 2) * np.cos(.087) + ty * np.sin(.087)) > 0
    top = ty < q / 2
    img[:q, q:] = np.where(top[..., None], np.where(e1[..., None], [.75, .75, .75], [.08, .08, .08]),
                           np.where(e1[..., None], [.7, .15, .1], [.1, .2, .7]))
    # BL: real texture from a photo (linearised) or random dead leaves
    if photo:
        p = Image.open(photo).convert('RGB')
        W, H = p.size
        p = p.crop((W // 2 - q // 2, H // 2 - q // 2, W // 2 - q // 2 + q, H // 2 - q // 2 + q))
        img[q:, :q] = (np.asarray(p, np.float32) / 255) ** 2.2
    else:
        t = np.full((q, q, 3), .3, np.float32)
        for _ in range(4000):
            r = rng.uniform(2, 40); cy, cx = rng.uniform(0, q, 2)
            yy, xx = np.ogrid[0:q, 0:q]
            t[(yy - cy) ** 2 + (xx - cx) ** 2 < r * r] = rng.uniform(.02, .9, 3)
        img[q:, :q] = t
    # BR: 1-px colour text-like hatching at several pitches
    by, bx = np.mgrid[0:q, 0:q]
    pitch = 2 + (bx * 6 // q)
    hatch = ((by // pitch) % 2).astype(np.float32)
    img[q:, q:] = np.where(hatch[..., None] > 0, [.8, .7, .2], [.15, .1, .35])
    return img


def sensor(img, cmap, g, blur=0.7, peak=800.0, read=2.0, seed=2):
    rng = np.random.default_rng(seed)
    if blur > 0:
        img = np.stack([ndimage.gaussian_filter(img[..., c], blur) for c in range(3)], -1)
    h, w = cmap.shape
    iy, ix = np.mgrid[0:h, 0:w]
    sig = np.choose(cmap, [img[..., 0], img[..., 1], img[..., 2]]) * peak * g[cmap, iy % N, ix % N]
    sig = rng.poisson(np.maximum(sig, 0)).astype(np.float32) + rng.normal(0, read, sig.shape)
    return np.clip(sig + BLACK, 0, WHITE).astype(np.float32), img


# ---------------------------------------------------------------- metrics / output
def psnr(a, b, peak):
    return float(10 * np.log10(peak ** 2 / np.mean((a - b) ** 2)))


def to8(rgb, scale):
    return Image.fromarray((np.clip(rgb / scale, 0, 1) ** (1 / 2.2) * 255 + .5).astype(np.uint8))


def wb_neutral(rgb, ref=None):
    m = (ref if ref is not None else rgb).reshape(-1, 3).mean(0)
    return rgb * (m[1] / m)


def synth(args):
    s = 2048
    cmap = colour_map(s, s)
    rng = np.random.default_rng(7)
    g_true = 1 + rng.normal(0, .06, (3, N, N)); g_true /= g_true.mean(axis=(1, 2), keepdims=True)
    gt, blurred = None, None
    scene = synth_scene(s, args.photo)
    raw, blurred = sensor(scene, cmap, g_true, blur=args.blur, peak=800)
    truth = down2(blurred * 800)
    g = estimate_gains(raw, cmap)
    v = flatten_gains(raw, cmap, g)
    out = {'gainEstimateRmsErr': float(np.sqrt(np.mean((g - g_true) ** 2)))}
    a = current_path(raw, cmap, args.convert)
    b = down2(demosaic_v2(v, cmap))
    c2 = down2(demosaic_ri(v, cmap))
    vt = flatten_gains(raw, cmap, g_true)
    c3 = down2(demosaic_ri(vt, cmap))
    q = s // 4
    regions = {'all': (slice(8, -8), slice(8, -8)), 'zoneplate': (slice(8, q), slice(8, q)),
               'edges': (slice(8, q), slice(q, -8)), 'texture': (slice(q, -8), slice(8, q)),
               'hatch': (slice(q, -8), slice(q, -8))}
    for name, r in regions.items():
        out[name] = {k: round(psnr(im[r], truth[r], 800), 2) for k, im in
                     (('current', a), ('v2ratio', b), ('v2ri', c2), ('v2ri_trueGains', c3))}
    print(json.dumps(out))
    os.makedirs(args.outdir, exist_ok=True)
    json.dump(out, open(os.path.join(args.outdir, f'synth-blur{args.blur}.json'), 'w'), indent=1)
    strip = np.concatenate([truth, a, b, c2], 1)
    to8(strip, 800).save(os.path.join(args.outdir, f'synth-blur{args.blur}-truth-current-v2.png'))


def real(args):
    W, H = 16320, 12240
    full = np.memmap(args.raw, '<u2', 'r', shape=(H, W))
    g = estimate_gains(np.asarray(full[::1, :][:4096, :4096], np.float32), colour_map(4096, 4096))  # gain table
    os.makedirs(args.outdir, exist_ok=True)
    crops = [(int(y), int(x)) for y, x in (c.split(',') for c in args.crops)]
    for i, (y0, x0) in enumerate(crops):
        raw = np.asarray(full[y0:y0 + 1024, x0:x0 + 1024], np.float32)
        cmap = colour_map(1024, 1024)
        gy, gx = min(max(y0 - 512, 0), H - 2048), min(max(x0 - 512, 0), W - 2048)
        g = estimate_gains(np.asarray(full[gy:gy + 2048, gx:gx + 2048], np.float32), colour_map(2048, 2048))
        a = current_path(raw, cmap, args.convert)
        b = down2(demosaic_ri(flatten_gains(raw, cmap, g), cmap))
        a, b = wb_neutral(a), wb_neutral(b, a)
        sc = np.percentile(a[..., 1], 99.5)
        to8(np.concatenate([a, b], 1), sc).resize((2048, 1024), Image.NEAREST).save(
            os.path.join(args.outdir, f'real{i}-current-v2.png'))
        # periodic artefact energy at the 4x4/8x8 block frequencies (in the 50 MP grid: periods 2 and 4)
        def per(rgb):
            l = rgb.mean(-1); F = np.abs(np.fft.fft2(l - l.mean())); n = l.shape[0]
            return float(sum(F[i_, j_] for i_ in (0, n // 4, n // 2) for j_ in (0, n // 4, n // 2) if i_ or j_) / F.sum())
        print(json.dumps({'crop': [y0, x0], 'current_blockFreqEnergy': per(a), 'v2_blockFreqEnergy': per(b)}))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('mode', choices=['synth', 'real'])
    ap.add_argument('outdir')
    ap.add_argument('raw', nargs='?')
    ap.add_argument('--convert', default='convert')
    ap.add_argument('--photo')
    ap.add_argument('--blur', type=float, default=0.7)
    ap.add_argument('--crops', nargs='*', default=['5600,7600', '2000,3000', '9000,12000'])
    a = ap.parse_args()
    synth(a) if a.mode == 'synth' else real(a)
