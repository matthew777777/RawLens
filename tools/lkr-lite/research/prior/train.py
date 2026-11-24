# SPDX-License-Identifier: Apache-2.0
"""Train the LK-R Lite post-fit prior (small residual CNN, NPU friendly: 3x3 conv + ReLU + add only).

Input: Lite's hybrid result (linear camera RGB, before finishing) in sqrt space + a constant noise
plane; output: the corrected image (input + predicted residual). Target: the synthetic burst's truth.
Pairs from gen_pairs.py. Loss: L1 in sqrt space + L1 on the high-pass (so blurring does not win).

  train.py PAIRS_DIR OUT.pt [--steps 20000] [--ch 24] [--layers 6] [--patch 128] [--batch 16]
"""
import argparse, glob, os, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


class Prior(nn.Module):
    def __init__(self, ch=24, layers=6, cond=1):
        super().__init__()
        self.cond = cond   # 1: sigma; 2: sigma + grain share (denoiser with organic luma grain, --grain)
        seq = [nn.Conv2d(3 + cond, ch, 3, padding=1), nn.ReLU()]
        for _ in range(layers - 2):
            seq += [nn.Conv2d(ch, ch, 3, padding=1), nn.ReLU()]
        seq += [nn.Conv2d(ch, 3, 3, padding=1)]
        self.net = nn.Sequential(*seq)
        nn.init.zeros_(self.net[-1].weight); nn.init.zeros_(self.net[-1].bias)   # starts as identity

    def forward(self, x, sigma):   # x: B x 3 x H x W (sqrt space), sigma: B x cond x H x W
        # centred, unit-scale inputs (x ~ 0.2..0.8, sigma ~ 1e-3..1e-2): a dead-ReLU collapse otherwise
        c = [sigma[:, :1] * 100.0] + ([sigma[:, 1:2] * 4.0] if self.cond > 1 else [])
        return x + 0.1 * self.net(torch.cat([(x - 0.5) * 4.0] + c, 1))


def load_pairs(d, clean=False):
    out = []
    # comma-separated dirs; a pair is its .npz or (after cleanup) its memory-mapped .x.npy / .y.npy / .n.npy
    files = sorted({f[:-len('.x.npy')] + '.npz' if f.endswith('.x.npy') else f for dd in d.split(',')
                    for f in glob.glob(os.path.join(dd, 'pair_*.npz')) + glob.glob(os.path.join(dd, 'pair_*.x.npy'))})
    for f in files:
        # Memory-mapped float16 caches (8 GB Macs): only the crops in use are read; sqrt + float32 per crop.
        cx, cy, cn = f[:-4] + '.x.npy', f[:-4] + '.y.npy', f[:-4] + '.n.npy'
        if not (os.path.exists(cx) and os.path.exists(cy) and os.path.exists(cn)):
            z = np.load(f)
            np.save(cx, z['x']); np.save(cy, z['y']); np.save(cn, np.float32(z['noise']))
            if 'c' in z: np.save(f[:-4] + '.c.npy', z['c'])
            if 'ns' in z: np.save(f[:-4] + '.ns.npy', z['ns'])
            del z
        x = np.load(cx, mmap_mode='r'); y = np.load(cy, mmap_mode='r')
        c = None
        if clean:   # denoiser: clean truth (gen_pairs 'c'); pairs without it are skipped
            cc = f[:-4] + '.c.npy'
            if not os.path.exists(cc): continue
            c = np.load(cc, mmap_mode='r')
        s = np.float32(np.sqrt(float(np.load(cn)) * 0.18))   # noise sigma at mid grey (relative)
        hf = f[:-4] + '.h.npy'   # hard-patch mining map (add_hardness.py / gen_pairs.hardness)
        h = np.load(hf) if os.path.exists(hf) else None
        nf = f[:-4] + '.ns.npy'   # per-pixel noise gain where the merge lost frames (gen_pairs v2), else 1
        if not os.path.exists(nf) and os.path.exists(cn):   # cache made before the ns field existed
            zz = np.load(f)
            if 'ns' in zz.files: np.save(nf, zz['ns'])
        ns = np.load(nf, mmap_mode='r') if os.path.exists(nf) else None
        out.append((x, y, s, h, c, ns))
    return out


class HardIndex:
    """Gharbi et al. 2016 hard-patch mining: cells sampled in proportion to Lite's reconstruction error
    energy (luma + chroma), over all pairs."""
    def __init__(self, pairs, cell=32):
        self.cell = cell; keys, w = [], []
        for pi, pr in enumerate(pairs):
            h = pr[3]
            if h is None: continue
            e = (h[0] ** 2 + h[1] ** 2).ravel()
            for ci in np.nonzero(e > 0)[0]: keys.append((pi, ci, h.shape[2])); w.append(e[ci])
        w = np.array(w, np.float64); self.keys = keys; self.p = w / w.sum() if len(w) else None

    def pick(self, rng):
        pi, ci, nx = self.keys[rng.choice(len(self.keys), p=self.p)]
        return pi, (ci // nx) * self.cell + self.cell // 2, (ci % nx) * self.cell + self.cell // 2


def batch(pairs, rng, n, p, dev, hard=None, hard_frac=0.0, grain=None, clean_merge=False):
    """grain = (lo, hi): denoiser targets. Target = clean truth + g x (luma of Lite's own grain), g drawn per
    crop and fed to the network as a 2nd condition channel: real detail is always in the target (no credit
    for smoothing), colour noise never is, and the amount of organic luma grain is a run-time input."""
    xs, ys, ss = [], [], []
    for _ in range(n):
        if hard is not None and hard.p is not None and rng.random() < hard_frac:
            pi, cy, cx = hard.pick(rng)
            x, y, s, _, c, ns = pairs[pi]; H = x.shape[0]
            jit = lambda c: int(np.clip(c - p // 2 + rng.integers(-p // 4, p // 4 + 1), 32, H - 32 - p))
            i, j = jit(cy) // 4 * 4, jit(cx) // 4 * 4
        else:
            x, y, s, _, c, ns = pairs[rng.integers(len(pairs))]
            H = x.shape[0]
            # even offsets keep the Bayer / 2x lattice phase (the zipper is lattice-locked)
            i = int(rng.integers(32, H - 32 - p) // 4 * 4); j = int(rng.integers(32, H - 32 - p) // 4 * 4)
        a = np.sqrt(np.clip(x[i:i + p, j:j + p].astype(np.float32), 0, None))
        g = 0.0
        if grain is not None:
            cl = c[i:i + p, j:j + p].astype(np.float32); gr = y[i:i + p, j:j + p].astype(np.float32) - cl   # Lite's grain
            g = float(rng.uniform(*grain))
            yy = cl + g * gr.mean(-1, keepdims=True)   # luma part only (equal in R, G, B: no colour)
        else:
            target = c if c is not None else y
            yy = target[i:i + p, j:j + p].astype(np.float32)
        if clean_merge:
            # Stored target = truth + noisy merge - clean merge, with matched geometry.
            yy = yy + (x[i:i + p, j:j + p].astype(np.float32)
                       - y[i:i + p, j:j + p].astype(np.float32))
        b = np.sqrt(np.clip(yy, 0, None))
        # noise channel: sigma x the merge's per-pixel noise gain (lost frames -> more noise there), so the
        # network does not over-smooth moving foliage nor under-clean it
        sm = s * (np.asarray(ns[i:i + p, j:j + p], np.float32) if ns is not None and not clean_merge else np.ones((p, p), np.float32))
        k = int(rng.integers(4)); a, b, sm = np.rot90(a, k), np.rot90(b, k), np.rot90(sm, k)
        if rng.random() < 0.5: a, b, sm = a[:, ::-1], b[:, ::-1], sm[:, ::-1]
        xs.append(np.ascontiguousarray(a).transpose(2, 0, 1)); ys.append(np.ascontiguousarray(b).transpose(2, 0, 1)); ss.append((np.ascontiguousarray(sm), g))
    x = torch.from_numpy(np.stack(xs)).to(dev); y = torch.from_numpy(np.stack(ys)).to(dev)
    sig = torch.from_numpy(np.stack([v[0] for v in ss]))[:, None]
    if grain is not None:
        gch = torch.tensor([v[1] for v in ss], dtype=torch.float32).view(-1, 1, 1, 1).expand(-1, 1, p, p)
        sig = torch.cat([sig, gch], 1)
    return x, y, sig.contiguous().to(dev)


def highpass(t):
    k = torch.tensor([1., 4., 6., 4., 1.], device=t.device); k = (k[:, None] * k[None, :]) / 256.0
    k = k.view(1, 1, 5, 5).repeat(t.shape[1], 1, 1, 1)
    return t - F.conv2d(F.pad(t, (2, 2, 2, 2), mode='reflect'), k, groups=t.shape[1])


def local_energy(t, r=4):
    """Local RMS of the fine scale (luma and colour per channel), box 2 r + 1. Matching it to the target's
    means the network cannot win by smoothing texture away (L1 alone prefers the median = waxy)."""
    h = highpass(t)
    return torch.sqrt(F.avg_pool2d(h * h, 2 * r + 1, stride=1, padding=r, count_include_pad=False) + 1e-6)


def luma_texture(t, r=4):
    """Local RMS of the fine scale of luma (the mean of R, G, B in sqrt space)."""
    return local_energy(t.mean(1, keepdim=True), r)


def detail_loss(output, target):
    """Penalize missing signed luma detail in linear camera space."""
    weights = target.new_tensor([0.25, 0.5, 0.25]).view(1, 3, 1, 1)
    truth = highpass((target.square() * weights).sum(1, keepdim=True))
    estimate = highpass((output.clamp_min(0).square() * weights).sum(1, keepdim=True))
    energy = F.avg_pool2d(truth.square(), 8, stride=4)
    missing = F.avg_pool2d(truth * (truth - estimate), 8, stride=4)
    return (F.relu(missing) / (energy + 1e-6).sqrt()).mean()


def shadow_loss(output, target):
    """Penalize camera-linear shadow errors and local brightness/color bias."""
    error = output.clamp_min(0).square() - target.square()
    weights = target.new_tensor([0.25, 0.5, 0.25]).view(1, 3, 1, 1)
    luma = (target.square() * weights).sum(1, keepdim=True)
    weight = (0.09 / luma.clamp_min(0.01)).clamp(max=1.0)
    residual = error * weight
    bias = F.avg_pool2d(residual, 8, stride=4)
    return (residual.square().mean() + 1e-12).sqrt() + (bias.square().mean() + 1e-12).sqrt()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('pairs'); ap.add_argument('out')
    ap.add_argument('--steps', type=int, default=20000); ap.add_argument('--ch', type=int, default=24)
    ap.add_argument('--layers', type=int, default=6); ap.add_argument('--patch', type=int, default=128)
    ap.add_argument('--batch', type=int, default=16); ap.add_argument('--lr', type=float, default=5e-4)
    ap.add_argument('--val', type=int, default=10, help='pairs held out for validation')
    ap.add_argument('--init', default=None, help='start from this checkpoint (fine-tune)')
    ap.add_argument('--clean', action='store_true', help='denoiser: train to the clean truth (needs gen_pairs c)')
    ap.add_argument('--grain', type=float, nargs=2, default=None, help='with --clean: luma grain share range, e.g. 0 0.6 (2nd input)')
    ap.add_argument('--texfloor', type=float, default=0.0, help='one-sided luma texture term: penalise only output texture BELOW the target (anti-smearing that cannot be won by adding noise), e.g. 1')
    ap.add_argument('--energy', type=float, default=0.0, help='weight of the local fine-energy match (anti-smearing), e.g. 1')
    ap.add_argument('--hard', type=float, default=0.0, help='share of crops from hard-patch mining (Gharbi 2016), e.g. 0.5')
    ap.add_argument('--clean-merge', action='store_true',
                    help='target the paired noise-free Lite reconstruction and use scalar runtime conditioning')
    ap.add_argument('--shadow-loss', type=float, default=0.0,
                    help='weight of camera-linear shadow and local bias loss (clean targets)')
    ap.add_argument('--detail-loss', type=float, default=0.0,
                    help='weight of the signed camera-linear luma detail penalty')
    ap.add_argument('--noise-dropout', type=float, default=0.0,
                    help='probability of conditioning on the deployed shot-noise floor')
    a = ap.parse_args()
    if a.clean_merge and (not a.clean or a.grain is not None):
        ap.error('--clean-merge requires --clean and is incompatible with --grain')
    if a.grain is not None and not a.clean:
        ap.error('--grain requires --clean')
    if a.shadow_loss < 0 or (a.shadow_loss and not a.clean):
        ap.error('--shadow-loss must be nonnegative and requires --clean')
    if a.detail_loss < 0:
        ap.error('--detail-loss must be nonnegative')
    if not 0 <= a.noise_dropout <= 1:
        ap.error('--noise-dropout must be between 0 and 1')
    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    pairs = load_pairs(a.pairs, a.clean)
    # fixed shuffle before the split: with several pair dirs the held-out set covers all of them
    pairs = [pairs[i] for i in np.random.default_rng(7).permutation(len(pairs))]
    val, tr = pairs[:a.val], pairs[a.val:]
    print(f'{len(tr)} train / {len(val)} val pairs, device {dev}')
    torch.manual_seed(2021)
    m = Prior(a.ch, a.layers, 2 if a.grain else 1).to(dev)
    if a.init: m.load_state_dict(torch.load(a.init, map_location='cpu')['state']); print('init from', a.init)
    print('parameters', sum(p.numel() for p in m.parameters()))
    opt = torch.optim.Adam(m.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps)
    rng = np.random.default_rng(0); vrng = np.random.default_rng(123)
    vb = [batch(val, vrng, 16, 256, dev, grain=a.grain, clean_merge=a.clean_merge) for _ in range(4)]
    hidx = HardIndex(tr) if a.hard > 0 else None
    if hidx is not None: print('hard-patch cells', len(hidx.keys))
    t0 = time.time(); nskip = 0; best = float('inf')
    for it in range(1, a.steps + 1):
        x, y, s = batch(tr, rng, a.batch, a.patch, dev, hidx, a.hard, a.grain, a.clean_merge)
        if a.noise_dropout:
            dropped = rng.random(a.batch) < a.noise_dropout
            s[torch.as_tensor(dropped, device=dev), :1] = np.sqrt(0.18e-6)
        o = m(x, s)
        loss = F.l1_loss(o, y) + 2.0 * F.l1_loss(highpass(o), highpass(y))
        if a.detail_loss: loss = loss + a.detail_loss * detail_loss(o, y)
        if a.shadow_loss: loss = loss + a.shadow_loss * shadow_loss(o, y)
        if a.energy > 0: loss = loss + a.energy * F.l1_loss(local_energy(o), local_energy(y))
        if a.texfloor > 0: loss = loss + a.texfloor * F.relu(luma_texture(y) - luma_texture(o)).mean()
        opt.zero_grad(); loss.backward()
        gn = torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0)
        if not (torch.isfinite(loss) and torch.isfinite(gn)):   # skip a bad step (a NaN run 2026-11-20 at step ~4300)
            nskip += 1; opt.zero_grad(); sched.step(); continue
        opt.step(); sched.step()
        if it % 500 == 0 or it == a.steps:
            with torch.no_grad():
                vin = sum(F.l1_loss(x, y).item() for x, y, s in vb) / len(vb)
                vout = sum(F.l1_loss(m(x, s), y).item() for x, y, s in vb) / len(vb)
                hin = sum(F.l1_loss(highpass(x), highpass(y)).item() for x, y, s in vb) / len(vb)
                hout = sum(F.l1_loss(highpass(m(x, s)), highpass(y)).item() for x, y, s in vb) / len(vb)
                eout = sum((local_energy(m(x, s)).mean() / local_energy(y).mean()).item() for x, y, s in vb) / len(vb)
            print(f'texture energy out/target {eout:.3f} | ', end='')
            print(f'step {it} loss {loss.item():.5f} | val L1 Lite {vin:.5f} -> prior {vout:.5f} | fine-scale {hin:.5f} -> {hout:.5f} | skipped {nskip} | {time.time() - t0:.0f} s', flush=True)
            rank = vout
            if a.shadow_loss:
                with torch.no_grad():
                    shadow_in = sum(shadow_loss(x, y).item() for x, y, s in vb) / len(vb)
                    shadow_out = sum(shadow_loss(m(x, s), y).item() for x, y, s in vb) / len(vb)
                print(f'validation linear shadow loss {shadow_in:.6f} -> {shadow_out:.6f}', flush=True)
                rank += a.shadow_loss * shadow_out
            if a.detail_loss:
                with torch.no_grad():
                    detail_out = sum(detail_loss(m(x, s), y).item() for x, y, s in vb) / len(vb)
                print(f'validation signed detail loss {detail_out:.6f}', flush=True)
                rank += a.detail_loss * detail_out
            if not np.isfinite(rank) or rank >= best: continue   # keep the best finite checkpoint
            best = rank
            torch.save({'state': m.state_dict(), 'ch': a.ch, 'layers': a.layers, 'cond': m.cond, 'step': it}, a.out)


if __name__ == '__main__':
    main()
