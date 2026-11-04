# SPDX-License-Identifier: GPL-3.0-or-later
"""Run LK-Reloaded on a crop of a real RawLens burst, or the synthetic self-test.

  python -I run.py synth
  python -I run.py burst DIR --crop 512 --scale 2 --prior drunet --weights drunet_color.pth
"""
import argparse
import glob
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lkr  # noqa: E402


def to_png(path, rgb, wb, exposure=1.0):
    import imageio.v3 as iio
    x = np.clip(rgb * np.asarray(wb).reshape(3, 1, 1) * exposure, 0, 1)
    x = np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055)
    iio.imwrite(path, (x.transpose(1, 2, 0) * 255 + 0.5).astype(np.uint8))


def load_burst(d, crop, cx=None, cy=None):
    import rawpy
    files = sorted(glob.glob(os.path.join(d, '*.dng')))
    frames = []
    for f in files:
        with rawpy.imread(f) as r:
            raw = r.raw_image_visible.astype(np.float64)
            black = float(np.mean(r.black_level_per_channel))
            white = float(r.white_level)
            pat = ''.join('RGBG'[c] for c in r.raw_pattern.ravel())
            wb = np.array(r.camera_whitebalance[:3], float)
        H, W = raw.shape
        # RGGB origin: GRBG -> x odd; other patterns analogous
        ox, oy = {'RGGB': (0, 0), 'GRBG': (1, 0), 'GBRG': (0, 1), 'BGGR': (1, 1)}[pat]
        x0 = ((W // 2 if cx is None else cx) - crop // 2) // 2 * 2 + ox
        y0 = ((H // 2 if cy is None else cy) - crop // 2) // 2 * 2 + oy
        frames.append((raw[y0:y0 + crop, x0:x0 + crop] - black) / (white - black))
    wb = wb / wb[1]
    return frames, wb, files


def noise_ab(raw, block=8, bins=16):
    """Our own Poisson-Gaussian fit var = a*x + b from one RGGB frame (never
    the Camera2/DNG profile). Both green planes; per 8x8 block a robust noise
    variance from a 5-point Laplacian (median of d^2 / 0.4549, white noise ->
    var(d)/20 = var), binned by block mean, lower quartile per bin (flat
    blocks), weighted non-negative least squares."""
    ms, vs = [], []
    for g in (raw[0::2, 1::2], raw[1::2, 0::2]):
        d = (4 * g[1:-1, 1:-1] - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]) / np.sqrt(20)
        c = g[1:-1, 1:-1]
        H, W = (d.shape[0] // block) * block, (d.shape[1] // block) * block
        dd = (d[:H, :W] ** 2).reshape(H // block, block, W // block, block).transpose(0, 2, 1, 3).reshape(-1, block * block)
        cc = c[:H, :W].reshape(H // block, block, W // block, block).transpose(0, 2, 1, 3).reshape(-1, block * block)
        ms.append(cc.mean(1))
        vs.append(np.median(dd, 1) / 0.4549)
    m, v = np.concatenate(ms), np.concatenate(vs)
    keep = (m > 0.002) & (m < 0.9)
    m, v = m[keep], v[keep]
    edges = np.quantile(m, np.linspace(0, 1, bins + 1))
    X, Y, Wt = [], [], []
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = (m >= lo) & (m <= hi)
        if sel.sum() < 20:
            continue
        X.append(np.median(m[sel]))
        Y.append(np.quantile(v[sel], 0.05) / 0.6045)   # flattest 5% (texture never flatter than noise), exact bias
        Wt.append(np.sqrt(sel.sum()))
    X, Y, Wt = map(np.asarray, (X, Y, Wt))
    from scipy.optimize import nnls
    A = np.stack([X, np.ones_like(X)], 1) * (Wt / Y)[:, None]   # relative error weighting
    (a, b), _ = nnls(A, Wt)                                      # fits Y = aX + b in relative terms
    return float(a), float(max(b, 1e-10))


def noise_sigma(raw):
    """MAD of a 2x2-cell Laplacian on the green-R quincunx row: robust sigma."""
    g = raw[0::2, 1::2]
    d = g[1:-1, 1:-1] * 4 - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]
    return 1.4826 * np.median(np.abs(d - np.median(d))) / np.sqrt(20)


def solver(args):
    if args.torch:
        import lkr_torch
        return lkr_torch
    return lkr


def dev(args):
    return {'device': args.device} if args.torch else {}


def synth(args):
    """Known-motion synthetic check: recover p and beat single-frame bilinear."""
    from scipy.ndimage import gaussian_filter
    rng = np.random.default_rng(0)
    s, h = 2, 96
    HRs = (s * h, s * h)
    # band-limited random texture + edges
    gt = np.stack([gaussian_filter(rng.random(HRs), 1.2) for _ in range(3)])
    gt = (gt - gt.min()) / (gt.max() - gt.min()) * 0.8 + 0.1
    gt[:, 60:130, 40:44] = 0.9
    K = 8
    p_true = lkr.affine_identity(K)
    for k in range(1, K):
        th = rng.uniform(-0.01, 0.01)
        p_true[k] = [np.cos(th), -np.sin(th), rng.uniform(-3, 3),
                     np.sin(th), np.cos(th), rng.uniform(-3, 3)]
    sig = 0.005
    frames = [lkr.FrameOp(p_true[k], h, h, s, HRs).fwd(gt) + sig * rng.standard_normal((h, h))
              for k in range(K)]
    prior = lkr.Prior(args.prior, [1, 1, 1], args.weights)
    g = [lkr.grey(f) for f in frames]
    pc = np.stack([lkr.lk_affine(g[0], g[k]) for k in range(K)])
    pc[0] = p_true[0]
    x, p = solver(args).lk_reloaded(frames, s, prior, T=args.T, sigma_noise=sig,
                           p_init=pc, z_steps=args.z_steps, **dev(args))
    from scipy.ndimage import zoom
    base = zoom(lkr.demosaic_bilinear(frames[0]), (1, s, s), order=1)
    m = (slice(None), slice(16, -16), slice(16, -16))

    def psnr(a):
        return 10 * np.log10(1 / np.mean((a[m] - gt[m]) ** 2))
    cerr = np.abs(pc[:, [2, 5]] - p_true[:, [2, 5]]).max()
    perr = np.abs(p[:, [2, 5]] - p_true[:, [2, 5]]).max()
    print(f'motion err (LR px) coarse {cerr:.3f} -> refined {perr:.3f}  '
          f'PSNR bilinear {psnr(base):.2f}  LKR {psnr(x):.2f}')
    # paper Table 1: refinement must improve registration; burst must beat single frame
    ok = perr < 0.5 * cerr and psnr(x) > psnr(base) + 3
    print('SELFTEST', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


def burst(args):
    import torch
    torch.set_num_threads(args.threads)
    frames, wb, files = load_burst(args.dir, args.crop, args.cx, args.cy)
    if args.frames:
        frames = frames[:args.frames]
    sig = noise_sigma(frames[0])
    print(f'{len(frames)} frames, crop {args.crop}, wb {np.round(wb, 3)}, sigma_n {sig:.5f}')
    os.makedirs(args.out, exist_ok=True)
    prior = lkr.Prior(args.prior, wb, args.weights, args.device)
    t0 = time.time()
    x, p = solver(args).lk_reloaded(frames, args.scale, prior, **dev(args), T=args.T, sigma_noise=sig,
                           sigma_max=args.sigma_max, z_steps=args.z_steps,
                           **({'sigma_end': args.end_x * sig} if args.torch else {}),
                           refine_motion=not args.no_refine)
    print(f'solve {time.time() - t0:.1f}s')
    from scipy.ndimage import zoom
    tag = f'x{args.scale}_{args.prior}_T{args.T}'
    base = zoom(lkr.demosaic_bilinear(frames[0]), (1, args.scale, args.scale), order=3)
    to_png(os.path.join(args.out, f'ref_bilinear_x{args.scale}.png'), base, wb, args.exposure)
    to_png(os.path.join(args.out, f'lkr_{tag}.png'), x, wb, args.exposure)
    np.save(os.path.join(args.out, f'lkr_{tag}.npy'), x.astype(np.float32))
    np.save(os.path.join(args.out, f'lkr_{tag}_p.npy'), p)
    print('wrote', args.out)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name in ('synth', 'burst'):
        sp = sub.add_parser(name)
        sp.add_argument('--prior', default='none', choices=['none', 'tv', 'wavelet', 'ffdnet', 'drunet'])
        sp.add_argument('--weights', default=None)
        sp.add_argument('--T', type=int, default=8)
        sp.add_argument('--z-steps', type=int, default=1)
        sp.add_argument('--torch', action='store_true', help='torch solver (fast)')
        sp.add_argument('--device', default='cpu')
        if name == 'burst':
            sp.add_argument('dir')
            sp.add_argument('--crop', type=int, default=512)
            sp.add_argument('--cx', type=int)
            sp.add_argument('--cy', type=int)
            sp.add_argument('--scale', type=int, default=2)
            sp.add_argument('--frames', type=int, default=0)
            sp.add_argument('--sigma-max', type=float, default=0.05)
            sp.add_argument('--end-x', type=float, default=1.0, help='final prior sigma / estimated noise')
            sp.add_argument('--threads', type=int, default=2)
            sp.add_argument('--no-refine', action='store_true')
            sp.add_argument('--exposure', type=float, default=1.0)
            sp.add_argument('--out', default='out')
    a = ap.parse_args()
    sys.exit(synth(a) if a.cmd == 'synth' else burst(a))
