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
    def __init__(self, ch=24, layers=6):
        super().__init__()
        seq = [nn.Conv2d(4, ch, 3, padding=1), nn.ReLU()]
        for _ in range(layers - 2):
            seq += [nn.Conv2d(ch, ch, 3, padding=1), nn.ReLU()]
        seq += [nn.Conv2d(ch, 3, 3, padding=1)]
        self.net = nn.Sequential(*seq)
        nn.init.zeros_(self.net[-1].weight); nn.init.zeros_(self.net[-1].bias)   # starts as identity

    def forward(self, x, sigma):   # x: B x 3 x H x W (sqrt space), sigma: B x 1 x H x W
        # centred, unit-scale inputs (x ~ 0.2..0.8, sigma ~ 1e-3..1e-2): a dead-ReLU collapse otherwise
        return x + 0.1 * self.net(torch.cat([(x - 0.5) * 4.0, sigma * 100.0], 1))


def load_pairs(d):
    out = []
    files = sorted(f for dd in d.split(',') for f in glob.glob(os.path.join(dd, 'pair_*.npz')))   # comma-separated dirs
    for f in files:
        # Memory-mapped float16 caches (8 GB Macs): only the crops in use are read; sqrt + float32 per crop.
        cx, cy, cn = f[:-4] + '.x.npy', f[:-4] + '.y.npy', f[:-4] + '.n.npy'
        if not (os.path.exists(cx) and os.path.exists(cy) and os.path.exists(cn)):
            z = np.load(f)
            np.save(cx, z['x']); np.save(cy, z['y']); np.save(cn, np.float32(z['noise']))
            del z
        x = np.load(cx, mmap_mode='r'); y = np.load(cy, mmap_mode='r')
        s = np.float32(np.sqrt(float(np.load(cn)) * 0.18))   # noise sigma at mid grey (relative)
        hf = f[:-4] + '.h.npy'   # hard-patch mining map (add_hardness.py / gen_pairs.hardness)
        h = np.load(hf) if os.path.exists(hf) else None
        out.append((x, y, s, h))
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


def batch(pairs, rng, n, p, dev, hard=None, hard_frac=0.0):
    xs, ys, ss = [], [], []
    for _ in range(n):
        if hard is not None and hard.p is not None and rng.random() < hard_frac:
            pi, cy, cx = hard.pick(rng)
            x, y, s, _ = pairs[pi]; H = x.shape[0]
            jit = lambda c: int(np.clip(c - p // 2 + rng.integers(-p // 4, p // 4 + 1), 32, H - 32 - p))
            i, j = jit(cy) // 4 * 4, jit(cx) // 4 * 4
        else:
            x, y, s, _ = pairs[rng.integers(len(pairs))]
            H = x.shape[0]
            # even offsets keep the Bayer / 2x lattice phase (the zipper is lattice-locked)
            i = int(rng.integers(32, H - 32 - p) // 4 * 4); j = int(rng.integers(32, H - 32 - p) // 4 * 4)
        a = np.sqrt(np.clip(x[i:i + p, j:j + p].astype(np.float32), 0, None))
        b = np.sqrt(np.clip(y[i:i + p, j:j + p].astype(np.float32), 0, None))
        k = int(rng.integers(4)); a, b = np.rot90(a, k), np.rot90(b, k)
        if rng.random() < 0.5: a, b = a[:, ::-1], b[:, ::-1]
        xs.append(np.ascontiguousarray(a).transpose(2, 0, 1)); ys.append(np.ascontiguousarray(b).transpose(2, 0, 1)); ss.append(s)
    x = torch.from_numpy(np.stack(xs)).to(dev); y = torch.from_numpy(np.stack(ys)).to(dev)
    s = torch.tensor(ss, device=dev).view(-1, 1, 1, 1).expand(-1, 1, p, p).contiguous()
    return x, y, s


def highpass(t):
    k = torch.tensor([1., 4., 6., 4., 1.], device=t.device); k = (k[:, None] * k[None, :]) / 256.0
    k = k.view(1, 1, 5, 5).repeat(t.shape[1], 1, 1, 1)
    return t - F.conv2d(F.pad(t, (2, 2, 2, 2), mode='reflect'), k, groups=t.shape[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('pairs'); ap.add_argument('out')
    ap.add_argument('--steps', type=int, default=20000); ap.add_argument('--ch', type=int, default=24)
    ap.add_argument('--layers', type=int, default=6); ap.add_argument('--patch', type=int, default=128)
    ap.add_argument('--batch', type=int, default=16); ap.add_argument('--lr', type=float, default=5e-4)
    ap.add_argument('--val', type=int, default=10, help='pairs held out for validation')
    ap.add_argument('--init', default=None, help='start from this checkpoint (fine-tune)')
    ap.add_argument('--hard', type=float, default=0.0, help='share of crops from hard-patch mining (Gharbi 2016), e.g. 0.5')
    a = ap.parse_args()
    dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
    pairs = load_pairs(a.pairs)
    val, tr = pairs[:a.val], pairs[a.val:]
    print(f'{len(tr)} train / {len(val)} val pairs, device {dev}')
    m = Prior(a.ch, a.layers).to(dev)
    if a.init: m.load_state_dict(torch.load(a.init, map_location='cpu')['state']); print('init from', a.init)
    print('parameters', sum(p.numel() for p in m.parameters()))
    opt = torch.optim.Adam(m.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.steps)
    rng = np.random.default_rng(0); vrng = np.random.default_rng(123)
    vb = [batch(val, vrng, 16, 256, dev) for _ in range(4)]
    hidx = HardIndex(tr) if a.hard > 0 else None
    if hidx is not None: print('hard-patch cells', len(hidx.keys))
    t0 = time.time()
    for it in range(1, a.steps + 1):
        x, y, s = batch(tr, rng, a.batch, a.patch, dev, hidx, a.hard)
        o = m(x, s)
        loss = F.l1_loss(o, y) + 2.0 * F.l1_loss(highpass(o), highpass(y))
        opt.zero_grad(); loss.backward(); torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0); opt.step(); sched.step()
        if it % 500 == 0 or it == a.steps:
            with torch.no_grad():
                vin = sum(F.l1_loss(x, y).item() for x, y, s in vb) / len(vb)
                vout = sum(F.l1_loss(m(x, s), y).item() for x, y, s in vb) / len(vb)
                hin = sum(F.l1_loss(highpass(x), highpass(y)).item() for x, y, s in vb) / len(vb)
                hout = sum(F.l1_loss(highpass(m(x, s)), highpass(y)).item() for x, y, s in vb) / len(vb)
            print(f'step {it} loss {loss.item():.5f} | val L1 Lite {vin:.5f} -> prior {vout:.5f} | fine-scale {hin:.5f} -> {hout:.5f} | {time.time() - t0:.0f} s', flush=True)
            torch.save({'state': m.state_dict(), 'ch': a.ch, 'layers': a.layers}, a.out)


if __name__ == '__main__':
    main()
