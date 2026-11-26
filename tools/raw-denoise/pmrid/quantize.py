# SPDX-License-Identifier: Apache-2.0
"""Post-training INT8 calibration of a PMRID (qnet.py: NNAPI-exact simulation) and the float vs INT8 loss.
  quantize.py NET.pt OUT.q.pt [--batches 48]
Calibration data = the training synthesis (same noise model), half general (darkened 1..16x), half day crops.
Prints validation error in noise-sigma units (as train_pmrid.py) for float and INT8, general and day sets."""
import argparse, os, sys, numpy as np, torch, torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PMRID
from qnet import QNet
ap = argparse.ArgumentParser(); ap.add_argument('net'); ap.add_argument('out'); ap.add_argument('--batches', type=int, default=48)
ap.add_argument('--cache', default='~/Downloads/pmrid_cache'); ap.add_argument('--day', default='~/Downloads/pmrid_cache_day')
A = ap.parse_args()
C = np.load(os.path.join(os.path.expanduser(A.cache), 'crops.npy'), mmap_mode='r')
D = np.load(os.path.join(os.path.expanduser(A.day), 'crops.npy'), mmap_mode='r')
def gat(x, a, b): return (2.0 / a) * torch.sqrt(torch.clamp(a * x + 0.375 * a * a + b, min=0))
def sample(src, idx, p, rng, day):
    """train_pmrid.sample without structured noise: the ranges depend on the noise level, not its structure."""
    B = len(idx); X = np.empty((B, 4, p, p), np.float32)
    for k, i in enumerate(idx):
        c = src[i]; y = rng.integers(0, c.shape[1] - p + 1); z = rng.integers(0, c.shape[2] - p + 1)
        X[k] = np.asarray(c[:, y:y + p, z:z + p], np.float32)
    e = np.exp(rng.uniform(0, np.log(1.5 if day else 16), (B, 1, 1, 1))).astype(np.float32)
    a = np.exp(rng.uniform(np.log(1e-5), np.log(3e-4 if day else 6e-3), (B, 1, 1, 1))).astype(np.float32)
    b = (a * np.exp(rng.uniform(np.log(1e-4), np.log(0.6), (B, 1, 1, 1)))).astype(np.float32)
    t = torch.from_numpy(np.maximum(X / e, 0)); at = torch.from_numpy(a); bt = torch.from_numpy(b)
    n = at * torch.poisson(t / at) + torch.randn_like(t) * torch.sqrt(bt)
    g0 = gat(torch.zeros_like(at), at, bt); g1 = gat(torch.ones_like(at), at, bt) - g0
    return (gat(n, at, bt) - g0) / g1, (1.0 / g1).expand(-1, 1, p, p), (gat(t, at, bt) - g0) / g1
m = PMRID(); m.load_state_dict(torch.load(A.net, map_location='cpu')); m.eval()
q = QNet(m); q.eval()
rng = np.random.default_rng(5)
# validation: the same held-out crops as training (first 64 general, first 32 day)
vr = np.random.default_rng(7)
VB = [sample(C, vr.choice(64, 8), 256, vr, False) for _ in range(4)]
VD = [sample(D, vr.choice(32, 8), 256, vr, True) for _ in range(4)]
def err(S):
    with torch.no_grad(): return float(np.mean([float(((q(u, s) - t).abs() / s[:, :1, :1, :1]).mean()) for u, s, t in S]))
def noisy(S): return float(np.mean([float(((u - t).abs() / s[:, :1, :1, :1]).mean()) for u, s, t in S]))
torch.set_num_threads(4)
q.mode = 'float'; fb, fd = err(VB), err(VD)
q.mode = 'calib'; q.ema = 0.1
with torch.no_grad():
    for i in range(A.batches):
        day = i % 2 == 1
        u, s, _ = sample(D if day else C, rng.choice(np.arange(32 if day else 64, len(D if day else C)), 8), 256, rng, day)
        q(u, s)
q.mode = 'q'; qb, qd = err(VB), err(VD)
print('val general: noisy %.4f float %.4f int8 %.4f (%+.1f%%)' % (noisy(VB), fb, qb, 100 * (qb / fb - 1)))
print('val day:     noisy %.4f float %.4f int8 %.4f (%+.1f%%)' % (noisy(VD), fd, qd, 100 * (qd / fd - 1)))
for n in q.order:
    sc, zp = q.range(n).qparams()
    if n in ('in', 'inc.sb', 'out') or n.endswith('.add') and n.count('.') == 1: print('  %-12s lo %+.4f hi %+.4f scale %.2e zp %d' % (n, float(q.range(n).lo), float(q.range(n).hi), sc, zp))
torch.save(q.state_dict(), A.out)
print('saved', A.out, len(q.order), 'tensors')
