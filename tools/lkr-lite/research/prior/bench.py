# SPDX-License-Identifier: Apache-2.0
"""Objective zipper / moire benchmark for LK-R Lite (+ post-fit prior) on a standard demosaicking test
set (Kodak 24): each image -> synthetic 8-frame raw burst (fixed seed), noise-free (pure zipper /
moire, as in IPOL demosaicking benchmarks) and at a realistic noise level; Lite 2x; each model applied
to Lite's pre-finishing result; scored against the truth (coordinate offset corrected).

  bench.py IMAGES_DIR TEMPLATE.dng WORK_DIR [MODEL.pt ...]
Scores per variant (means over images): luma PSNR, chroma PSNR (R-G, B-G), and the relative error in
the period-4 band (the 2x Bayer lattice: zipper / maze), luma and chroma. Higher PSNR / lower band
error is better.
"""
import glob, os, subprocess, sys
import numpy as np
import torch
from scipy.ndimage import shift

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from train import Prior
from gen_pairs import align_truth

SYN = os.path.join(HERE, '..', 'lkr_synth_burst.py')
LITE = os.path.join(HERE, '..', '..', 'build', 'host', 'lkrl_full_run')
NOISE = {'clean': (0.0, 0.0), 'noisy': (1.2e-4, 6e-6)}


def band_mask(n):
    f = np.fft.fftfreq(n) * n
    FY, FX = np.meshgrid(f, f, indexing='ij')
    near = lambda a, t: np.abs(np.abs(a) - t) <= max(2, n // 170)
    return near(FX, n // 4) | near(FY, n // 4)


def scores(x, g):
    c = slice(24, -24)
    x = x * (g[c, c] * x[c, c]).sum() / max(float((x[c, c] ** 2).sum()), 1e-12)
    peak = 1.0
    ps = lambda a, b: 10 * np.log10(peak ** 2 / max(float(((a[c, c] - b[c, c]) ** 2).mean()), 1e-12))
    Y, Yg = x.mean(-1), g.mean(-1)
    C = np.stack([x[..., 0] - x[..., 1], x[..., 2] - x[..., 1]]); Cg = np.stack([g[..., 0] - g[..., 1], g[..., 2] - g[..., 1]])
    n = x.shape[0]; bm = band_mask(n)
    def band(a, b):
        A = np.fft.fft2(a - a.mean()) * bm; B = np.fft.fft2(b - b.mean()) * bm
        return np.sqrt((np.abs(A - B) ** 2).sum() / max(float((np.abs(B) ** 2).sum()), 1e-12))
    return ps(Y, Yg), 0.5 * (ps(C[0], Cg[0]) + ps(C[1], Cg[1])), band(Y, Yg), 0.5 * (band(C[0], Cg[0]) + band(C[1], Cg[1]))


def main():
    imgs, tmpl, work = (os.path.abspath(os.path.expanduser(v)) for v in sys.argv[1:4])
    models = sys.argv[4:]
    os.makedirs(work, exist_ok=True)
    nets = {}
    for mp in models:
        ck = torch.load(mp, map_location='cpu'); m = Prior(ck['ch'], ck['layers'], ck.get('cond', 1)); m.load_state_dict(ck['state']); m.eval()
        nets[os.path.basename(mp)[:-3]] = m
    res = {}
    files = sorted(glob.glob(os.path.join(imgs, '*.png')))
    for k, f in enumerate(files):
        for cond, (na, nb) in NOISE.items():
            b = os.path.join(work, f'{os.path.basename(f)[:-4]}_{cond}')
            if not os.path.exists(os.path.join(b, 'labels.npz')):
                subprocess.run([sys.executable, '-I', SYN, tmpl, os.path.abspath(f), b, '--frames', '8', '--size', '240',
                                '--src-at', '0', '0', '--no-person', '--no-rail', '--no-clip', '--person-speed', '0', '--seed', str(1000 + k),
                                '--tremor', '2.5', '--blur', '0.3', '--noise-a', f'{na:.3g}', '--noise-b', f'{nb:.3g}'],
                               check=True, capture_output=True, cwd=work)
            pre = b + '_t'
            if not os.path.exists(pre + '_tile_0_0-hybrid.f32'):
                subprocess.run([LITE, b, b + '.dng', '--s', '2', '--crop', '240', '240', '--at', '1536', '1024',
                                '--noise', f'{max(na, 1e-6):.6g}', f'{max(nb, 5e-8):.6g}'],
                               check=True, capture_output=True, env=dict(os.environ, LKRL_TRACE_PREFIX=pre), cwd=work)
                for e in ('.dng', '.dng.f32'):
                    if os.path.exists(b + e): os.remove(b + e)
            n = 480
            z = np.fromfile(pre + '_tile_0_0-hybrid.f32', np.float32).reshape(3, n, n).transpose(1, 2, 0)
            g = align_truth(z, np.load(os.path.join(b, 'labels.npz'))['gt'].astype(np.float32))
            res.setdefault((cond, 'lite'), []).append(scores(z.astype(np.float64), g.astype(np.float64)))
            for nm, m in nets.items():
                x = torch.from_numpy(np.sqrt(np.clip(z, 0, None)).transpose(2, 0, 1).copy())[None]
                s = torch.full((1, 1, n, n), float(np.sqrt(max(na, 1e-6) * 0.18)))
                with torch.no_grad(): o = (m(x, s)[0].clamp(min=0).numpy() ** 2).transpose(1, 2, 0)
                res.setdefault((cond, nm), []).append(scores(o.astype(np.float64), g.astype(np.float64)))
        print(f'{k + 1}/{len(files)}', flush=True)
    print(f"\n{'condition':8s} {'variant':14s} luma PSNR  chroma PSNR | band err luma  chroma")
    for (cond, nm), v in sorted(res.items()):
        a = np.array(v).mean(0)
        print(f'{cond:8s} {nm:14s} {a[0]:8.2f}  {a[1]:10.2f}  | {a[2]:12.3f}  {a[3]:6.3f}')


if __name__ == '__main__':
    main()
