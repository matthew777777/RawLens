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
        out.append((x, y, s, h, c))
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


def batch(pairs, rng, n, p, dev, hard=None, hard_frac=0.0, grain=None):
    """grain = (lo, hi): denoiser targets. Target = clean truth + g x (luma of Lite's own grain), g drawn per
    crop and fed to the network as a 2nd condition channel: real detail is always in the target (no credit
    for smoothing), colour noise never is, and the amount of organic luma grain is a run-time input."""
    xs, ys, ss = [], [], []
    for _ in range(n):
        if hard is not None and hard.p is not None and rng.random() < hard_frac:
            pi, cy, cx = hard.pick(rng)
            x, y, s, _, c = pairs[pi]; H = x.shape[0]
            jit = lambda c: int(np.clip(c - p // 2 + rng.integers(-p // 4, p // 4 + 1), 32, H - 32 - p))
            i, j = jit(cy) // 4 * 4, jit(cx) // 4 * 4
        else:
            x, y, s, _, c = pairs[rng.integers(len(pairs))]
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
            yy = y[i:i + p, j:j + p].astype(np.float32)
        b = np.sqrt(np.clip(yy, 0, None))
        k = int(rng.integers(4)); a, b = np.rot90(a, k), np.rot90(b, k)
        if rng.random() < 0.5: a, b = a[:, ::-1], b[:, ::-1]
        xs.append(np.ascontiguousarray(a).transpose(2, 0, 1)); ys.append(np.ascontiguousarray(b).transpose(2, 0, 1)); ss.append((s, g))
    x = torch.from_numpy(np.stack(xs)).to(dev); y = torch.from_numpy(np.stack(ys)).to(dev)
    nc = 2 if grain is not None else 1
    s = torch.tensor([v[:nc] for v in ss], device=dev, dtype=torch.float32).view(-1, nc, 1, 1).expand(-1, nc, p, p).contiguous()
    return x, y, s


def highpass(t):
    k = torch.tensor([1., 4., 6., 4., 1.], device=t.device); k = (k[:, None] * k[None, :]) / 256.0
    k = k.view(1, 1, 5, 5).repeat(t.shape[1], 1, 1, 1)
    return t - F.conv2d(F.pad(t, (2, 2, 2, 2), mode='reflect'), k, groups=t.shape[1])


def local_energy(t, r=4):
    """Local RMS of the fine scale (luma and colour per channel), box 2 r + 1. Matching it to the target's
    means the network cannot win by smoothing texture away (L1 alone prefers the median = waxy)."""
    h = highpass(t)
    return torch.sqrt(F.avg_pool2d(h * h, 2 * r + 1, stride=1, padding=r, count_include_pad=False) + 1e-6)


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
    ap.add_argument('--energy', type=float, default=0.0, help='weight of the local fine-energy match (anti-smearing), e.g. 1')
    ap.add_argument('--hard', type=float, default=0.0, help='share of crops from hard-patch mining (Gharbi 2016), e.g. 0.5')
    a = ap.parse_args()
    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    pairs = load_pairs(a.pairs, a.clean)
    # fixed shuffle before the split: with several pair dirs the held-out set covers all of them
    pairs = [pairs[i] for i in np.random.default_rng(7).permutation(len(pairs))]
    val, tr = pairs[:a.val], pairs[a.val:]
    print(f'{len(tr)} train / {len(val)} val pairs, device {dev}')
    m = Prior(a.ch, a.layers, 2 if a.grain else 1).to(dev)
    if a.init: m.load_state_dict(torch.load(a.init, map_location='cpu')['state']); print('init from', a.init)
    print('parameters', sum(p.numel() for p in m.parameters()))
    opt = torch.optim.Adam(m.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps)
    rng = np.random.default_rng(0); vrng = np.random.default_rng(123)
    vb = [batch(val, vrng, 16, 256, dev, grain=a.grain) for _ in range(4)]
    hidx = HardIndex(tr) if a.hard > 0 else None
    if hidx is not None: print('hard-patch cells', len(hidx.keys))
    t0 = time.time(); nskip = 0; best = float('inf')
    for it in range(1, a.steps + 1):
        x, y, s = batch(tr, rng, a.batch, a.patch, dev, hidx, a.hard, a.grain)
        o = m(x, s)
        loss = F.l1_loss(o, y) + 2.0 * F.l1_loss(highpass(o), highpass(y))
        if a.energy > 0: loss = loss + a.energy * F.l1_loss(local_energy(o), local_energy(y))
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
            if not np.isfinite(vout) or vout >= best: continue   # keep the best finite checkpoint
            best = vout
            torch.save({'state': m.state_dict(), 'ch': a.ch, 'layers': a.layers, 'cond': m.cond}, a.out)


if __name__ == '__main__':
    main()
