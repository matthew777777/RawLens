# SPDX-License-Identifier: GPL-3.0-or-later
"""Schedule sweep for LK-Reloaded on ground truth built from our own sensor.

GT: a large crop of the real reference frame, binned 2x2 per Bayer cell to a
clean half-res RGB image (R, mean G, B). Bursts are synthesised from it with
the forward model (Eq. 1), random handheld affine motion, and Poisson-Gaussian
noise whose sigma comes from our own estimator on the real frame (never the
Camera2/DNG profile). Reports PSNR and a texture-retention score (energy of
GT high-pass kept in the output) so over-smoothing is visible, not just PSNR.

  python -I tune.py BURST_DIR --cx 1281 --cy 2240 --weights drunet_color.pth
"""
import argparse
import itertools
import os
import sys
import time

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lkr  # noqa: E402
import lkr_torch  # noqa: E402
from run import load_burst, noise_sigma, noise_ab  # noqa: E402


def make_gt(raw):
    r = raw[0::2, 0::2]
    g = 0.5 * (raw[0::2, 1::2] + raw[1::2, 0::2])
    b = raw[1::2, 1::2]
    return np.stack([r, g, b])


def make_gt_temporal(frames):
    """Night GT: 2x2-binned RGB of all frames, LK-aligned to frame 0, averaged
    (truth noise ~ sigma/2/sqrt(K) instead of sigma/2 for a single frame)."""
    bins = [make_gt(f) for f in frames]
    g0 = bins[0].mean(0)
    acc = bins[0].copy()
    for b in bins[1:]:
        p = lkr.lk_affine(g0, b.mean(0))        # g0(A u + t) ~ b(u): sample b at A^-1
        A = np.array([[p[0], p[1]], [p[3], p[4]]])
        Ai = np.linalg.inv(A)
        q = np.r_[Ai[0], -Ai[0] @ p[[2, 5]], Ai[1], -Ai[1] @ p[[2, 5]]]
        acc += np.stack([lkr._resample(c, q)[0] for c in b])
    return acc / len(bins)


def simulate(gt, s, K, a_n, b_n, rng):
    """Poisson-Gaussian noise from OUR fit: var = a_n * x + b_n."""
    h = gt.shape[1] // s
    p = lkr.affine_identity(K)
    for k in range(1, K):
        th = rng.uniform(-0.005, 0.005)
        p[k] = [np.cos(th), -np.sin(th), rng.uniform(-3, 3),
                np.sin(th), np.cos(th), rng.uniform(-3, 3)]
    ops = lkr_torch.Ops(h, h, s, 'cpu').setup(torch.tensor(p, dtype=torch.float32))
    clean = ops.fwd(torch.tensor(gt, dtype=torch.float32)).double().numpy()
    noisy = clean + np.sqrt(a_n * np.clip(clean, 0, None) + b_n) * rng.standard_normal(clean.shape)
    return [f for f in noisy], p


def scores(x, gt, m=24):
    from scipy.ndimage import gaussian_filter
    a, b = x[:, m:-m, m:-m], gt[:, m:-m, m:-m]
    psnr = 10 * np.log10(b.max() ** 2 / np.mean((a - b) ** 2))
    hp = lambda v: v - gaussian_filter(v, (0, 1.5, 1.5))
    hb = hp(b)
    # texture kept: projection of output high-pass onto GT high-pass (1 = all, <1 smoothed)
    tex = float((hp(a) * hb).sum() / (hb * hb).sum())
    return psnr, tex


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--cx', type=int)
    ap.add_argument('--cy', type=int)
    ap.add_argument('--gt', type=int, default=384, help='GT (HR) size')
    ap.add_argument('--weights', required=True, help='drunet_color.pth')
    ap.add_argument('--ffdnet', help='ffdnet_color.pth: enables --priors mode')
    ap.add_argument('--threads', type=int, default=2)
    ap.add_argument('--device', default='mps')
    ap.add_argument('--quick', action='store_true')
    ap.add_argument('--grid', help='JSON dict overriding the sweep grid')
    ap.add_argument('--sweep-prior', default='drunet', choices=['drunet', 'ffdnet', 'ffdnet-gat'])
    ap.add_argument('--gt-mode', default='bin', choices=['bin', 'temporal'])
    ap.add_argument('--relative', action='store_true', help='grid smax/end as multiples of sigma_n')
    ap.add_argument('--budget', action='store_true', help='FFDNet-GAT quality vs number of prior calls')
    ap.add_argument('--lmode', action='store_true', help='with --budget: operator-norm rule comparison')
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    s, K = 2, 8
    frames, wb, _ = load_burst(a.dir, 2 * a.gt, a.cx, a.cy)
    sig = noise_sigma(frames[0])
    gt = make_gt(frames[0]) if a.gt_mode == 'bin' else make_gt_temporal(frames)          # binning averages 4 px of G-ish -> ~sig/sqrt(2..4)
    rng = np.random.default_rng(1)
    a_n, b_n = noise_ab(frames[0])
    print(f'noise fit (ours): a={a_n:.3g} b={b_n:.3g}  sigma@median={np.sqrt(a_n * np.median(frames[0]) + b_n):.5f}')
    y, p_true = simulate(gt, s, K, a_n, b_n, rng)
    sig = noise_sigma(y[0])                     # schedule scale, re-estimated on the simulated frame
    print(f'GT {gt.shape}, sigma_n(est) {sig:.5f}, wb {np.round(wb, 3)}')
    g = [lkr.grey(f) for f in y]
    pc = np.stack([lkr.lk_affine(g[0], g[k]) for k in range(K)])
    pc[0] = p_true[0]
    print(f'coarse motion err {np.abs(pc[:, [2, 5]] - p_true[:, [2, 5]]).max():.3f} px')
    base = torch.nn.functional.interpolate(torch.tensor(lkr.demosaic_bilinear(y[0]))[None],
                                           scale_factor=s, mode='bicubic', align_corners=False)[0].numpy()
    print('bicubic          PSNR %.2f tex %.2f' % scores(base, gt))
    q = lambda *_: None
    drunet = lkr.Prior('drunet', wb, a.weights, a.device)
    none = lkr.Prior('none', wb)

    def run(prior, label, **kw):
        t0 = time.time()
        x, p = lkr_torch.lk_reloaded(y, s, prior, sigma_noise=sig, device=a.device, log=q, **{'p_init': pc, **kw})
        ps, tx = scores(x, gt)
        perr = np.abs(p[:, [2, 5]] - p_true[:, [2, 5]]).max()
        print(f'{label:46s} PSNR {ps:.2f} tex {tx:.2f} motion {perr:.3f} ({time.time() - t0:.1f}s)', flush=True)
        return ps, tx

    if a.budget:
        pr = lkr.Prior('ffdnet', wb, a.ffdnet, a.device, domain='gat', ab=(a_n, b_n), sig_ref=sig)
        base = dict(sigma_max=10 * sig, sigma_end=1 * sig, z_steps=3)
        cfgs = [('T16 all (16 calls)', dict(T=16)),
                ('T8 all (8)', dict(T=8)), ('T6 all (6)', dict(T=6)), ('T4 all (4)', dict(T=4)),
                ('T16 last 8 (8)', dict(T=16, prior_at=set(range(8, 16)))),
                ('T16 last 6 (6)', dict(T=16, prior_at=set(range(10, 16)))),
                ('T16 last 4 (4)', dict(T=16, prior_at=set(range(12, 16)))),
                ('T16 every 2nd (8)', dict(T=16, prior_at=set(range(1, 16, 2)))),
                ('T16 every 4th (4)', dict(T=16, prior_at=set(range(3, 16, 4)))),
                ('T8 zs6 all (8)', dict(T=8, z_steps=6)),
                ('T6 zs8 all (6)', dict(T=6, z_steps=8)),
                ('T4 zs12 all (4)', dict(T=4, z_steps=12))]
        if a.lmode:
            cfgs = [('T16 e2 L=power15 c=1.0', dict(T=16, prior_at=set(range(1, 16, 2)))),
                    ('T16 e2 L=bound c=1.0', dict(T=16, prior_at=set(range(1, 16, 2)), L='bound')),
                    ('T16 e2 L=bound c=1.5', dict(T=16, prior_at=set(range(1, 16, 2)), L='bound', step_scale=1.5)),
                    ('T16 e2 L=bound c=1.8', dict(T=16, prior_at=set(range(1, 16, 2)), L='bound', step_scale=1.8))]
        for label, kw in cfgs:
            run(pr, label, **{**base, **kw})
        return
    if a.ffdnet and not a.relative:
        AB = dict(ab=(a_n, b_n), sig_ref=sig)
        priors = [('none', none),
                  ('wavelet-old', lkr.Prior('wavelet', wb)),
                  ('ffdnet-gamma', lkr.Prior('ffdnet', wb, a.ffdnet, a.device)),
                  ('ffdnet-gat', lkr.Prior('ffdnet', wb, a.ffdnet, a.device, domain='gat', **AB)),
                  ('drunet-gamma', drunet),
                  ('drunet-gat', lkr.Prior('drunet', wb, a.weights, a.device, domain='gat', **AB))]
        for luma in (0.25, 0.5, 1.0):
            for steer in (False,):
                for cr in (1, 2):
                    priors.append((f'wav l{luma} s{int(steer)} c{cr}',
                                   lkr.Prior('wavelet', wb, domain='gat', wav_prm=dict(luma=luma, steer=steer, cr=cr), **AB)))
        sched = {'tuned T=16 smax=10x end=2x zs=3': dict(T=16, sigma_max=10 * sig, sigma_end=2 * sig, z_steps=3)}
        z = torch.rand(3, gt.shape[1], gt.shape[2], device=a.device)
        for name, pr in priors:
            if name != 'none':
                for _ in range(2):
                    pr.torch(z, 0.01)                         # warm-up
                if a.device == 'mps':
                    torch.mps.synchronize()
                t0 = time.time()
                for _ in range(3):
                    pr.torch(z, 0.01)
                if a.device == 'mps':
                    torch.mps.synchronize()
                print(f'{name:13s} prior call {1000 * (time.time() - t0) / 3:.0f} ms at {gt.shape[1]}^2')
            for sl, kw in sched.items():
                run(pr, f'{name:13s} {sl}', **kw)
        return
    run(none, 'L2 only (paper ablation) T=8', T=8)
    run(drunet, 'drunet T=8 oracle motion', T=8, refine_motion=False, p_init=p_true)
    grid = dict(T=[4, 8, 16], sigma_max=[0.01, 0.03, 0.1], end=[0.5, 1.0, 2.0], z_steps=[1, 3])
    if a.relative:
        grid = dict(T=[8, 16, 24], sigma_max=[3, 10, 30], end=[0.25, 0.5, 1.0, 2.0, 3.0], z_steps=[3])
    sweep = {'drunet': lambda: drunet,
             'ffdnet': lambda: lkr.Prior('ffdnet', wb, a.ffdnet, a.device),
             'ffdnet-gat': lambda: lkr.Prior('ffdnet', wb, a.ffdnet, a.device, domain='gat',
                                             ab=(a_n, b_n), sig_ref=sig)}[a.sweep_prior]()
    if a.grid:
        import json
        grid = {**grid, **json.loads(a.grid)}
    elif a.quick:
        grid = dict(T=[8], sigma_max=[0.03], end=[0.5, 1.0, 2.0], z_steps=[1])
    res = []
    for T, sm, e, zs in itertools.product(*grid.values()):
        smv = sm * sig if a.relative else sm
        ps, tx = run(sweep, f'{a.sweep_prior} T={T} smax={sm}{"x" if a.relative else ""} end={e}x zs={zs}', T=T, sigma_max=smv,
                     sigma_end=e * sig, z_steps=zs)
        res.append((ps, tx, T, sm, e, zs))
    res.sort(reverse=True)
    print('\nTOP 5 by PSNR:')
    for r in res[:5]:
        print('  PSNR %.2f tex %.2f  T=%d smax=%g end=%gx zs=%d' % r)


if __name__ == '__main__':
    main()
