# SPDX-License-Identifier: Apache-2.0
"""Train the PMRID-style raw denoiser on clean Redmi crops + synthetic Poisson-Gaussian noise.
  train_pmrid.py CACHE OUT.pt [--steps 40000] [--batch 16] [--patch 128] [--hp 1.0] [--width 1.0]
Domain: GAT(x;a,b) / GAT(1) per sample -> noise std = 1/GAT(1) (fed as the 5th plane). Truth darkened 1..16x."""
import argparse, os, time, numpy as np, torch, torch.nn.functional as F
import sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PMRID
ap = argparse.ArgumentParser(); ap.add_argument('cache'); ap.add_argument('out')
ap.add_argument('--steps', type=int, default=40000); ap.add_argument('--batch', type=int, default=16)
ap.add_argument('--patch', type=int, default=128); ap.add_argument('--hp', type=float, default=1.0)
ap.add_argument('--lr', type=float, default=3e-4); ap.add_argument('--width', type=float, default=1.0)
ap.add_argument('--init', default=None)
A = ap.parse_args()
dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
C = np.load(os.path.join(A.cache, 'crops.npy'), mmap_mode='r')
nval = 64; tr = np.arange(nval, len(C)); va = np.arange(nval)
rng = np.random.default_rng(1)
def gat(x, a, b): return (2.0 / a) * torch.sqrt(torch.clamp(a * x + 0.375 * a * a + b, min=0))
def sample(idx, p, rng):
    B = len(idx); X = np.empty((B, 4, p, p), np.float32)
    for k, i in enumerate(idx):
        c = C[i]; y = rng.integers(0, c.shape[1] - p + 1); z = rng.integers(0, c.shape[2] - p + 1)
        q = np.asarray(c[:, y:y + p, z:z + p], np.float32)
        if rng.random() < 0.5: q = q[[0, 2, 1, 3]].transpose(0, 2, 1)     # CFA transpose: swap G1/G2
        X[k] = q
    e = np.exp(rng.uniform(0, np.log(16), (B, 1, 1, 1))).astype(np.float32)
    a = np.exp(rng.uniform(np.log(3e-5), np.log(2e-3), (B, 1, 1, 1))).astype(np.float32)
    b = (a * np.exp(rng.uniform(np.log(5e-4), np.log(5e-2), (B, 1, 1, 1)))).astype(np.float32)
    t = torch.from_numpy(np.maximum(X / e, 0)); at = torch.from_numpy(a); bt = torch.from_numpy(b)
    n = at * torch.poisson(t / at) + torch.randn_like(t) * torch.sqrt(bt)
    g1 = gat(torch.ones_like(at), at, bt)
    u = gat(n, at, bt) / g1; ut = gat(t, at, bt) / g1
    sig = (1.0 / g1).expand(-1, 1, p, p)
    return u.to(dev), sig.to(dev), ut.to(dev)
def hp(x): return x - F.avg_pool2d(F.pad(x, (1, 1, 1, 1), mode='replicate'), 3, 1)
ch = tuple(max(8, int(c * A.width)) for c in (16, 64, 128, 256, 512))
m = PMRID(ch).to(dev)
if A.init: m.load_state_dict(torch.load(A.init, map_location=dev))
print('params', sum(p.numel() for p in m.parameters()), 'ch', ch, 'dev', dev, flush=True)
opt = torch.optim.Adam(m.parameters(), A.lr); sch = torch.optim.lr_scheduler.CosineAnnealingLR(opt, A.steps, A.lr * 0.02)
vrng = np.random.default_rng(7); VB = [sample(vrng.choice(va, 8), 256, vrng) for _ in range(4)]
t0 = time.time(); best = 1e9
for it in range(1, A.steps + 1):
    u, s, ut = sample(rng.choice(tr, A.batch), A.patch, rng)
    o = m(u, s); w = 1.0 / s[:, :1, :1, :1]       # error in noise-std units: ISO-balanced
    loss = (w * (o - ut)).abs().mean() + A.hp * (w * (hp(o) - hp(ut))).abs().mean()
    opt.zero_grad(); loss.backward()
    torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0)
    if it <= 1000:
        for g in opt.param_groups: g['lr'] = A.lr * it / 1000
    opt.step()
    if it > 1000: sch.step()
    if it % 500 == 0 or it == 1:
        m.eval()
        with torch.no_grad():
            vl = np.mean([float(((m(a, b) - c).abs() / b[:, :1, :1, :1]).mean()) for a, b, c in VB])
            v0 = np.mean([float(((a - c).abs() / b[:, :1, :1, :1]).mean()) for a, b, c in VB])
        m.train()
        if vl < best: best = vl; torch.save(m.state_dict(), A.out)
        print('it %d loss %.4f val %.4f (noisy %.4f) best %.4f  %.1f it/s' % (it, loss.item(), vl, v0, best, it / (time.time() - t0)), flush=True)
