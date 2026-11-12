# SPDX-License-Identifier: Apache-2.0
"""Score an LK-R merge of a lkr_synth_burst.py burst against its labels.

Runs lkr_full_run on the whole frame (so the noise fit sees the normal scene),
cuts the synthetic square out of the output and the debug maps, and reports:

  static  pixels the person never covers (incl. the railing bars): detail
          PSNR vs ground truth, frames kept
  trail   where the person was in OTHER frames but not in the reference:
          ghosting shows here as error; frames kept should be LOW
  person  the person in the reference frame: damage shows here as error

  python3 lkr_synth_score.py BIN BURST_DIR [--s 2] [-- extra lkr_full_run args]
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

import numpy as np
from scipy.ndimage import binary_dilation, gaussian_filter, zoom


def psnr(a, b, m):
    e = ((a - b) ** 2).mean(-1)[m].mean()
    return 10 * np.log10(1.0 / max(e, 1e-12))


def main():
    argv = sys.argv[1:]
    extra = []
    if '--' in argv:
        i = argv.index('--')
        argv, extra = argv[:i], argv[i + 1:]
    ap = argparse.ArgumentParser()
    ap.add_argument('bin')
    ap.add_argument('burst')
    ap.add_argument('--s', type=int, default=2)
    ap.add_argument("--blur", type=float, default=0.5, help="lens blur sigma used by the burst, LR px")
    ap.add_argument('--tag', default='')
    ap.add_argument('--save', default='', help='write a PNG of gt | output | support')
    a = ap.parse_args(argv)
    L = np.load(os.path.join(a.burst, 'labels.npz'))
    gt, masks = L['gt'], L['masks']
    x0, y0, S = (int(v) for v in L['region'])
    s = a.s
    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, 'o.dng')
        maps = os.path.join(td, 'o.maps')
        env = dict(os.environ, DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib', LKR_DEBUG_MAPS=maps)
        r = subprocess.run([a.bin, a.burst, out, '--s', str(s), '--prior', '0', *extra],
                           env=env, capture_output=True, text=True)
        log = r.stdout + r.stderr
        if r.returncode:
            sys.exit(log[-2000:])
        h, w = map(int, re.search(r'debug maps: 5 x (\d+) x (\d+)', log).groups())
        K = int(re.search(r'(\d+) frames', log).group(1))
        n = os.path.getsize(out + '.f32') // 12
        HH, WW = [(h + p, w + q) for p in range(0, 33, 2) for q in range(0, 33, 2) if (h + p) * (w + q) == n][0]
        z = np.memmap(out + '.f32', 'f4', 'r', shape=(HH, WW, 3))
        mp = np.memmap(maps, 'f4', 'r', shape=(5, h, w))
        sl = (slice(y0 * s, (y0 + S) * s), slice(x0 * s, (x0 + S) * s))
        o = np.array(z[sl])
        sup = np.array(mp[0][sl])
    # ground truth at output scale
    # target: the scene through the same lens blur (LK-R does not deconvolve the lens)
    gt = np.stack([gaussian_filter(gt[..., c], 2 * a.blur) for c in range(3)], -1)
    g = gt if s == 2 else gt.reshape(S, 2, S, 2, 3).mean((1, 3))
    up = lambda m: (zoom(m.astype('f4'), s, order=0) > 0.5)
    ref = up(masks[0])
    others = up(np.any(masks[1:], 0))
    pad = 3 * s
    trail = others & ~binary_dilation(ref, iterations=pad)
    static = ~binary_dilation(ref | others, iterations=pad)
    inner = np.zeros_like(static)
    inner[8 * s:-8 * s, 8 * s:-8 * s] = True
    inner &= (g.max(-1) < 0.9)   # the sensor clips at 1: compare unclipped pixels only
    # one global gain (exposure / look scaling of the output), fitted on static pixels
    m0 = static & inner
    gain = float((o[m0] * g[m0]).sum() / max((o[m0] * o[m0]).sum(), 1e-12))
    o = o * gain
    # best sub-grid alignment of output to gt (half-pixel conventions)
    best = None
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            sh = np.roll(np.roll(o, dy, 0), dx, 1)
            p = psnr(sh, g, static & inner)
            if best is None or p > best[0]:
                best = (p, dy, dx)
    o = np.roll(np.roll(o, best[1], 0), best[2], 1)
    # sub-pixel: cross-correlate the high-passed green channels, parabolic peak, Fourier shift
    from scipy.ndimage import fourier_shift
    a0 = g[..., 1] - gaussian_filter(g[..., 1], 2)
    b0 = o[..., 1] - gaussian_filter(o[..., 1], 2)
    X = np.fft.ifft2(np.fft.fft2(a0) * np.conj(np.fft.fft2(b0))).real
    X = np.fft.fftshift(X)
    cy, cx = np.unravel_index(np.argmax(X), X.shape)
    par = lambda m, c, p: 0.5 * (m - p) / (m - 2 * c + p) if (m - 2 * c + p) != 0 else 0.0
    sy = cy - X.shape[0] // 2 + par(X[cy - 1, cx], X[cy, cx], X[cy + 1, cx])
    sx = cx - X.shape[1] // 2 + par(X[cy, cx - 1], X[cy, cx], X[cy, cx + 1])
    o = np.stack([np.fft.ifft2(fourier_shift(np.fft.fft2(o[..., c]), (sy, sx))).real for c in range(3)], -1)
    shift_px = (best[1] + sy, best[2] + sx)
    # Detail score (dB): fine-detail energy of the target over the error in it,
    # both high-passed (sigma 1.5 output px removes flat areas and gain/shading
    # errors). 0 dB = no correct fine detail; higher is better.
    # luminance band-pass (DoG 0.7 / 2.5 output px): fine detail without per-pixel colour noise
    hp = lambda v: gaussian_filter(v.mean(-1), 0.7) - gaussian_filter(v.mean(-1), 2.5)
    hg, ho = hp(g), hp(o)

    def detail(m):
        m = m & inner
        if m.sum() < 50:
            return float('nan')
        return 10 * np.log10((hg[m] ** 2).sum() / max(((ho[m] - hg[m]) ** 2).sum(), 1e-20))

    egt = gaussian_filter(hg ** 2, 4)
    textured = static & (egt > np.median(egt[static & inner]))
    rail = up(L['rail'].reshape(2 * S, 2 * S).reshape(S, 2, S, 2).max((1, 3))) if s == 1 and 'rail' in L \
        else (L['rail'] if 'rail' in L else np.zeros_like(static))
    bars = binary_dilation(rail, iterations=3) & static
    res = dict(tex=detail(textured), bars=detail(bars), trail=detail(trail), person=detail(ref),
               sup_static=sup[static & inner].mean(), sup_trail=sup[trail & inner].mean(),
               sup_person=sup[ref & inner].mean())
    print(f"{a.tag:<14} K={K}  detail dB: texture {res['tex']:.2f}  bars {res['bars']:.2f}  "
          f"trail {res['trail']:.2f}  person {res['person']:.2f} | gain {gain:.3f} shift ({shift_px[0]:+.2f},{shift_px[1]:+.2f})"
          f" | frames static {res['sup_static']:.1f}  trail {res['sup_trail']:.1f}  person {res['sup_person']:.1f}")
    if a.save:
        from PIL import Image
        gm = lambda v: np.uint8(np.clip(v, 0, 1) ** (1 / 2.2) * 255)
        sp = np.stack([np.uint8(np.clip(sup / K, 0, 1) * 255)] * 3, -1)
        Image.fromarray(np.concatenate([gm(g), gm(o), sp], 1)).save(a.save)


if __name__ == '__main__':
    main()
