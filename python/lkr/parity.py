# SPDX-License-Identifier: GPL-3.0-or-later
"""Vulkan solver parity vs the torch reference (tools/lkr-pipeline).

  python -I parity.py dump OUT BURST_DIR --cx X --cy Y [--crop 256] [--T 16 --z-steps 3 ...]
      writes frames.npy, p_init.npy, params.npy + the torch result x_ref.npy, p_ref.npy
  lkr_parity OUT            (C++/Vulkan, writes x_vk.npy, p_vk.npy)
  python -I parity.py check OUT
      prints max/RMS differences and PSNR(vk vs ref)

Prior is 'none' (paper's L2-only ablation), so the comparison isolates the
solver: forward model, exact adjoint, z-steps, Gauss-Newton motion, schedule.
L (operator norm) is computed by torch and passed to both, so the step sizes
match exactly; the Vulkan power iteration is checked separately (L_vk).
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def dump(a):
    import torch
    import lkr
    import lkr_torch
    from run import load_burst, noise_sigma, noise_ab
    torch.set_num_threads(2)
    frames, wb, _ = load_burst(a.burst, a.crop, a.cx, a.cy)
    frames = [f.astype(np.float32).astype(np.float64) for f in frames]
    sig = noise_sigma(frames[0])
    g = [lkr.grey(f) for f in frames]
    pc = np.stack([lkr.lk_affine(g[0], g[k]) for k in range(len(frames))])
    pc[0] = lkr.affine_identity(1)[0]
    pc = pc.astype(np.float32).astype(np.float64)
    h, w = frames[0].shape
    ops = lkr_torch.Ops(h, w, a.s, 'cpu').setup(torch.tensor(pc, dtype=torch.float32))
    L = 'bound' if a.bound else lkr_torch._power_norm(ops, (3, a.s * h, a.s * w), it=30)
    prior_at = None if a.every <= 1 else set(range(a.every - 1, a.T, a.every))
    if a.ffdnet:
        na, nb = noise_ab(frames[0])
        prior = lkr.Prior('ffdnet', wb, a.ffdnet, 'cpu', domain='gat', ab=(na, nb), sig_ref=sig)
    else:
        prior = lkr.Prior('none', wb)
    x, p = lkr_torch.lk_reloaded(frames, a.s, prior, T=a.T, sigma_noise=sig,
                                 sigma_max=a.smax * sig, sigma_end=a.end * sig, z_steps=a.z_steps,
                                 p_init=pc, L=L, prior_at=prior_at, step_scale=a.step, log=lambda *_: None)
    os.makedirs(a.out, exist_ok=True)
    np.save(os.path.join(a.out, 'frames.npy'), np.stack(frames).astype(np.float32))
    np.save(os.path.join(a.out, 'p_init.npy'), pc.astype(np.float32))
    np.save(os.path.join(a.out, 'params.npy'), np.array(
        [a.s, a.T, a.z_steps, sig, a.smax * sig, a.end * sig, 0.0 if a.bound else L, 1, a.every, a.step],
        np.float32))
    if a.ffdnet:
        np.save(os.path.join(a.out, 'prior.npy'), np.array([na, nb, sig, *wb], np.float32))
    elif os.path.exists(os.path.join(a.out, 'prior.npy')):
        os.remove(os.path.join(a.out, 'prior.npy'))
    np.save(os.path.join(a.out, 'x_ref.npy'), x.astype(np.float32))
    np.save(os.path.join(a.out, 'p_ref.npy'), p.astype(np.float32))
    print(f'dumped {len(frames)}x{h}x{w} s={a.s} T={a.T} zs={a.z_steps} sigma={sig:.5f} L={L}')


def check(a):
    ld = lambda n: np.load(os.path.join(a.out, n)).astype(np.float64)
    xr, xv, pr, pv = ld('x_ref.npy'), ld('x_vk.npy'), ld('p_ref.npy'), ld('p_vk.npy')
    m = (slice(None), slice(8, -8), slice(8, -8))
    d = xv[m] - xr[m]
    psnr = 10 * np.log10(xr[m].max() ** 2 / np.mean(d ** 2))
    print(f'x: max |d| {np.abs(d).max():.3g}  rms {np.sqrt(np.mean(d ** 2)):.3g}  '
          f'PSNR(vk vs torch) {psnr:.1f} dB')
    print(f'p: max |d| translation {np.abs(pv[:, [2, 5]] - pr[:, [2, 5]]).max():.3g} px  '
          f'linear {np.abs(pv[:, [0, 1, 3, 4]] - pr[:, [0, 1, 3, 4]]).max():.3g}')
    ok = psnr > (a.min_psnr) and np.abs(pv[:, [2, 5]] - pr[:, [2, 5]]).max() < 2e-3
    print('PARITY', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    d = sub.add_parser('dump')
    d.add_argument('out')
    d.add_argument('burst')
    d.add_argument('--cx', type=int)
    d.add_argument('--cy', type=int)
    d.add_argument('--crop', type=int, default=256)
    d.add_argument('--s', type=int, default=2)
    d.add_argument('--T', type=int, default=16)
    d.add_argument('--z-steps', type=int, default=3)
    d.add_argument('--smax', type=float, default=10.0)
    d.add_argument('--end', type=float, default=1.0)
    d.add_argument('--every', type=int, default=2)
    d.add_argument('--step', type=float, default=1.8)
    d.add_argument('--ffdnet', help='ffdnet_color.pth: FFDNet-GAT prior (else none)')
    d.add_argument('--power', dest='bound', action='store_false', help='torch power-iteration L instead of the bound')
    c = sub.add_parser('check')
    c.add_argument('out')
    c.add_argument('--min-psnr', type=float, default=60.0)
    a = ap.parse_args()
    sys.exit(dump(a) if a.cmd == 'dump' else check(a))
