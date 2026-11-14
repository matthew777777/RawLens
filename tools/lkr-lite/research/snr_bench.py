"""SNR benchmark for LK-R / Lite outputs (camera-linear interleaved .dng.f32).

  python -I snr_bench.py syn OUT.f32 SYN_DIR --s 1|2        # vs ground truth (lkr_synth_burst.py)
  python -I snr_bench.py flat OUT.f32 W H                    # real burst: flat-patch noise

syn: PSNR (peak 1) vs labels.npz gt (2x box-downsampled for s=1), after an integer
     registration search (+-3 px) and a global per-channel gain fit; split into static
     (person mask dilated, excluded) and person regions; plus luma / chroma error dB.
flat: 32 px blocks, the 10 % with the least gradient energy (texture-free) per brightness
     tercile; noise = std of (block - gaussian(3) smooth) in luma Y=(R+2G+B)/4 and chroma
     (R-G, B-G); SNR dB = 20 log10(mean Y / std). Higher is cleaner.
Prints one JSON line.
"""
import json, os, sys
import numpy as np
from scipy.ndimage import gaussian_filter, binary_dilation, zoom


def load(p, W=None, H=None):
    a = np.fromfile(p, 'f4')
    if W is None:
        S = int(round((a.size / 3) ** .5)); W = H = S
    return a.reshape(H, W, 3).astype(np.float64)


def ych(x):
    Y = (x[..., 0] + 2 * x[..., 1] + x[..., 2]) / 4
    return Y, x[..., 0] - x[..., 1], x[..., 2] - x[..., 1]


def psnr(a, b, m=None):
    d = (a - b) ** 2
    if m is not None: d = d[m]
    return float(10 * np.log10(1 / max(d.mean(), 1e-20)))


def syn(out, d, s):
    L = np.load(d + '/labels.npz')
    gt = L['gt'].astype(np.float64)
    if s == 1: gt = gt.reshape(gt.shape[0] // 2, 2, gt.shape[1] // 2, 2, 3).mean((1, 3))
    x = load(out)
    n = gt.shape[0]
    pm = L['masks'].any(0)
    pm = binary_dilation(pm, iterations=4)
    if s == 2: pm = np.kron(pm, np.ones((2, 2), bool))
    best = None
    m = 8
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            xa = x[m + dy:m + dy + n - 2 * m, m + dx:m + dx + n - 2 * m]
            if xa.shape[:2] != (n - 2 * m,) * 2: continue
            g = gt[m:n - m, m:n - m]
            e = ((xa - g) ** 2).mean()
            if best is None or e < best[0]: best = (e, dy, dx)
    _, dy, dx = best
    xa = x[m + dy:m + dy + n - 2 * m, m + dx:m + dx + n - 2 * m]
    g = gt[m:n - m, m:n - m]; p = pm[m:n - m, m:n - m]
    gain = (xa * g).sum((0, 1)) / np.maximum((xa * xa).sum((0, 1)), 1e-12)
    xa = xa * gain
    Yx, Ux, Vx = ych(xa); Yg, Ug, Vg = ych(g)
    st = ~p
    r = dict(shift=[dy, dx], gain=[round(float(v), 4) for v in gain],
             psnr=psnr(xa, g), static=psnr(xa, g, st), person=psnr(xa, g, p) if p.any() else None,
             luma_static=psnr(Yx, Yg, st), chroma_static=psnr(np.stack([Ux, Vx], -1), np.stack([Ug, Vg], -1), st),
             luma_person=psnr(Yx, Yg, p) if p.any() else None,
             chroma_person=psnr(np.stack([Ux, Vx], -1), np.stack([Ug, Vg], -1), p) if p.any() else None)
    return r


def flat(out, W, H):
    x = load(out, W, H)
    Y, U, V = ych(x)
    B = 32
    hb, wb = H // B, W // B
    res = []
    for comp in (Y, U, V):
        hp = comp - gaussian_filter(comp, 3)
        res.append(hp[:hb * B, :wb * B].reshape(hb, B, wb, B).std((1, 3)))
    gy, gx = np.gradient(gaussian_filter(Y, 1.5))
    tex = (gy ** 2 + gx ** 2)[:hb * B, :wb * B].reshape(hb, B, wb, B).mean((1, 3))
    mY = Y[:hb * B, :wb * B].reshape(hb, B, wb, B).mean((1, 3))
    ok = mY > 0.004
    tex_rel = tex / np.maximum(mY, 1e-4) ** 2
    out = {}
    qs = np.quantile(mY[ok], [0, 1 / 3, 2 / 3, 1])
    for t, nm in enumerate(('dark', 'mid', 'bright')):
        sel = ok & (mY >= qs[t]) & (mY <= qs[t + 1])
        th = np.quantile(tex_rel[sel], 0.1)
        sel &= tex_rel <= th
        y = np.median(mY[sel])
        out[nm] = dict(level=round(float(y), 4),
                       luma_snr=round(float(20 * np.log10(y / np.median(res[0][sel]))), 2),
                       chroma_snr=round(float(20 * np.log10(y / np.median(np.hypot(res[1][sel], res[2][sel])))), 2))
    return out


def split(noisy, clean, d, s):
    # noise = noisy - clean output (same burst, same motion, same fixed noise model): what the
    # pipeline lets through, in dB (20 log10 mean level / std), static and person regions;
    # detail = clean output vs truth (PSNR), i.e. resolution / aliasing / bias without noise.
    L = np.load(d + '/labels.npz')
    a, b = load(noisy), load(clean)
    n = a.shape[0]
    pm = binary_dilation(L['masks'].any(0), iterations=4)
    if s == 2: pm = np.kron(pm, np.ones((2, 2), bool))
    m = 8
    a, b, pm = a[m:n - m, m:n - m], b[m:n - m, m:n - m], pm[m:n - m, m:n - m]
    Ya, Ua, Va = ych(a); Yb, Ub, Vb = ych(b)
    lvl = Yb.mean()
    def db(x, sel): return round(float(20 * np.log10(lvl / max(np.sqrt((x[sel] ** 2).mean()), 1e-9))), 2)
    dY, dC = Ya - Yb, np.hypot(Ua - Ub, Va - Vb)
    det = syn(clean, d, s)
    # trail: where the person was in other frames but not in the reference (ghosts live there)
    M = L['masks']
    tr = binary_dilation(M[1:].any(0), iterations=2) & ~binary_dilation(M[0], iterations=3)
    if s == 2: tr = np.kron(tr, np.ones((2, 2), bool))
    tr = tr[m:n - m, m:n - m]
    gt = L['gt'].astype(np.float64)
    if s == 1: gt = gt.reshape(gt.shape[0] // 2, 2, gt.shape[1] // 2, 2, 3).mean((1, 3))
    g = gt[m:n - m, m:n - m]
    dy, dx = det['shift']
    cb = load(clean)[m + dy:m + dy + n - 2 * m, m + dx:m + dx + n - 2 * m] * np.array(det['gain'])
    extra = dict(ghost_trail=psnr(cb, g, tr), luma_trail=db(dY, tr), chroma_trail=db(dC, tr))
    if len(sys.argv) > 7 and os.path.exists(sys.argv[7]):   # LKR_DEBUG_MAPS of the noisy run: plane 0 = effective frames
        e = np.fromfile(sys.argv[7], 'f4')[:n * n].reshape(n, n)[m:n - m, m:n - m]
        extra.update(frames_static=round(float(e[~pm].mean()), 2), frames_person=round(float(e[pm].mean()), 2),
                     frames_trail=round(float(e[tr].mean()), 2))
    return dict(**{k: round(v, 2) if isinstance(v, float) else v for k, v in extra.items()},luma_static=db(dY, ~pm), chroma_static=db(dC, ~pm), luma_person=db(dY, pm), chroma_person=db(dC, pm),
                detail_static=round(det['static'], 2), detail_person=round(det['person'], 2))


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'split':
        s = int(sys.argv[sys.argv.index('--s') + 1]) if '--s' in sys.argv else 2
        print(json.dumps(split(sys.argv[2], sys.argv[3], sys.argv[4], s)))
    elif mode == 'syn':
        s = int(sys.argv[sys.argv.index('--s') + 1]) if '--s' in sys.argv else 2
        print(json.dumps(syn(sys.argv[2], sys.argv[3], s)))
    else:
        print(json.dumps(flat(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))))
