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
ap.add_argument('--init', default=None); ap.add_argument('--resume', action='store_true', help='continue from OUT.state if it exists'); ap.add_argument('--lf', type=float, default=1.0, help='low-frequency colour error weight (4 / 16 px plane blocks, noise units)'); ap.add_argument('--struct', type=float, default=0.0, help='structured noise: row + LF chroma, measured ratios (1 = Redmi night max)'); ap.add_argument('--amax', type=float, default=2e-3)
ap.add_argument('--day', default=None, help='make_day_crops.py cache: part of each batch is bright, low-noise, textured'); ap.add_argument('--dayfrac', type=float, default=0.5); ap.add_argument('--dayamax', type=float, default=3e-4)
ap.add_argument('--val', default=None, help='validate on the first 64 crops of this cache instead (same held-out set across datasets)'); ap.add_argument('--qat', default=None, help='quantize.py .q.pt: train through the INT8 simulation (weights + ranges from it); OUT is then a .q.pt')
A = ap.parse_args()
dev = 'mps' if torch.backends.mps.is_available() else 'cpu'
C = np.load(os.path.join(A.cache, 'crops.npy'), mmap_mode='r')
nval = 64; tr = np.arange(nval, len(C)); va = np.arange(nval)
if A.val:   # held-out crops from another cache: everything in CACHE trains
    V = np.load(os.path.join(A.val, 'crops.npy'), mmap_mode='r'); tr = np.arange(len(C))
D = np.load(os.path.join(A.day, 'crops.npy'), mmap_mode='r') if A.day else None
if D is not None: dtr = np.arange(32, len(D)); dva = np.arange(32)
rng = np.random.default_rng(1)
def gat(x, a, b): return (2.0 / a) * torch.sqrt(torch.clamp(a * x + 0.375 * a * a + b, min=0))
def sample(idx, p, rng, day=None, val=False):
    # day[k]: sample k comes from the day cache, not darkened, low noise (a <= --dayamax)
    B = len(idx); X = np.empty((B, 4, p, p), np.float32); day = np.zeros(B, bool) if day is None else day
    for k, i in enumerate(idx):
        c = (D if day[k] else (V if val else C))[i]; y = rng.integers(0, c.shape[1] - p + 1); z = rng.integers(0, c.shape[2] - p + 1)
        q = np.asarray(c[:, y:y + p, z:z + p], np.float32)
        if rng.random() < 0.5: q = q[[0, 2, 1, 3]].transpose(0, 2, 1)     # CFA transpose: swap G1/G2
        X[k] = q
    e = np.exp(rng.uniform(0, np.log(16), (B, 1, 1, 1))).astype(np.float32)
    a = np.exp(rng.uniform(np.log(1e-5), np.log(A.amax), (B, 1, 1, 1))).astype(np.float32)
    if day.any():
        e[day] = np.exp(rng.uniform(0, np.log(1.5), (int(day.sum()), 1, 1, 1))).astype(np.float32)
        a[day] = np.exp(rng.uniform(np.log(1e-5), np.log(A.dayamax), (int(day.sum()), 1, 1, 1))).astype(np.float32)
    b = (a * np.exp(rng.uniform(np.log(1e-4), np.log(0.6), (B, 1, 1, 1)))).astype(np.float32)
    t = torch.from_numpy(np.maximum(X / e, 0)); at = torch.from_numpy(a); bt = torch.from_numpy(b)
    n = at * torch.poisson(t / at) + torch.randn_like(t) * torch.sqrt(bt)
    if A.struct > 0:   # extra structured noise, std relative to the local white sigma (measure_struct.py:
        sw = torch.sqrt(at * t + bt)   # row/white 1.2-2.6, LF chroma/white 1.0-3.0 at 64/8 px => extra 0.08-0.30 sw)
        kr = torch.from_numpy(rng.uniform(0, 0.30, (B, 1, 1, 1)).astype(np.float32)) * A.struct
        kc = torch.from_numpy(rng.uniform(0, 0.35, (B, 1, 1, 1)).astype(np.float32)) * A.struct
        row = torch.randn(B, 4, p, 1)                                   # per-row, per-plane offsets
        lf = F.interpolate(torch.randn(B, 4, p // 8 + 2, p // 8 + 2), size=(p + 16, p + 16), mode='bicubic', align_corners=False)[:, :, 8:8 + p, 8:8 + p]
        lf = lf / lf.std((2, 3), keepdim=True).clamp(min=1e-6)
        n = n + sw * (kr * row + kc * lf)
    g0 = gat(torch.zeros_like(at), at, bt); g1 = gat(torch.ones_like(at), at, bt) - g0   # black -> 0, white -> 1 for any b/a
    u = (gat(n, at, bt) - g0) / g1; ut = (gat(t, at, bt) - g0) / g1
    sig = (1.0 / g1).expand(-1, 1, p, p)
    return u, sig, ut   # CPU tensors: built on the prefetch thread, moved to the GPU by the loop
def hp(x): return x - F.avg_pool2d(F.pad(x, (1, 1, 1, 1), mode='replicate'), 3, 1)
ch = tuple(max(8, int(c * A.width)) for c in (16, 64, 128, 256, 512))
m = PMRID(ch).to(dev)
if A.init: m.load_state_dict(torch.load(A.init, map_location=dev))
net = m
if A.qat:   # QAT: same weights, every graph tensor and weight fake-quantized as on the NPU; ranges follow by EMA
    import qnet
    net = qnet.QNet(m); net.load_state_dict(torch.load(A.qat, map_location=dev)); net.to(dev); net.mode = 'q'; net.ema = 0.001
print('params', sum(p.numel() for p in m.parameters()), 'ch', ch, 'dev', dev, 'qat' if A.qat else '', flush=True)
opt = torch.optim.Adam(m.parameters(), A.lr); sch = torch.optim.lr_scheduler.CosineAnnealingLR(opt, A.steps, A.lr * 0.02)
vrng = np.random.default_rng(7); VB = [tuple(t.to(dev) for t in sample(vrng.choice(va, 8), 256, vrng, None, bool(A.val))) for _ in range(4)]
VD = [tuple(t.to(dev) for t in sample(vrng.choice(dva, 8), 256, vrng, np.ones(8, bool))) for _ in range(4)] if D is not None else []
def batch(rng):
    if D is None: return sample(rng.choice(tr, A.batch), A.patch, rng)
    day = np.arange(A.batch) < round(A.batch * A.dayfrac)
    idx = np.where(day, rng.choice(dtr, A.batch), rng.choice(tr, A.batch))
    return sample(idx, A.patch, rng, day)
t0 = time.time(); best = 1e9; start = 0; ST = A.out + '.state'
def save(obj, path): torch.save(obj, path + '.tmp'); os.replace(path + '.tmp', path)   # a kill never leaves half a file
if A.resume and os.path.exists(ST):
    S = torch.load(ST, map_location=dev, weights_only=True)
    assert S['steps'] == A.steps, 'resume needs the same --steps (cosine schedule)'
    net.load_state_dict(S['model']); opt.load_state_dict(S['opt']); sch.load_state_dict(S['sch']); start = S['it']; best = S['best']
    rng = np.random.default_rng([1, start]); torch.manual_seed(start); print('resumed at it', start, flush=True)
import threading, queue
Q = queue.Queue(maxsize=3)   # the next batches are synthesised on the CPU while the GPU trains
def producer():
    while True: Q.put(batch(rng))
threading.Thread(target=producer, daemon=True).start()
for it in range(start + 1, A.steps + 1):
    u, s, ut = (t.to(dev) for t in Q.get())
    o = net(u, s); w = 1.0 / s[:, :1, :1, :1]       # error in noise-std units: ISO-balanced
    ew = w * (o - ut)
    loss = ew.abs().mean() + sum(A.lf * F.avg_pool2d(ew, k).abs().mean() for k in (4, 16)) + A.hp * (w * (hp(o) - hp(ut))).abs().mean()
    opt.zero_grad(); loss.backward()
    torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0)
    if it <= 1000:
        for g in opt.param_groups: g['lr'] = A.lr * it / 1000
    opt.step()
    if it > 1000: sch.step()
    if it % 500 == 0 or it == 1:
        net.eval()
        with torch.no_grad():
            err = lambda S: float(np.mean([float(((net(a, b) - c).abs() / b[:, :1, :1, :1]).mean()) for a, b, c in S]))
            vl = err(VB); vd = err(VD) if VD else 0.0
            v0 = np.mean([float(((a - c).abs() / b[:, :1, :1, :1]).mean()) for a, b, c in VB])
        net.train()
        score = vl + vd   # with --day: general and day validation count equally
        if score < best: best = score; save(net.state_dict(), A.out)
        print('it %d loss %.4f val %.4f%s (noisy %.4f) best %.4f  %.1f it/s' % (it, loss.item(), vl, ' day %.4f' % vd if VD else '', v0, best, (it - start) / (time.time() - t0)), flush=True)
        save(dict(model=net.state_dict(), opt=opt.state_dict(), sch=sch.state_dict(), it=it, best=best, steps=A.steps), ST)
