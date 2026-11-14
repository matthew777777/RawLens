"""rawdn + full-resolution luma refinement (GALOSH-style stride-1 2x2 luma), prototype.

  base  = rawdn packed result (half-res Y,Ca,Cb,D + opponent pyramid)      colour, low/mid frequencies
  L(u)  = stride-1 2x2 box of the equalised GAT mosaic (achromatic at every pixel, full res)
  Lden  = Wiener 8x8 DCT of L(z), with L(base) as the pilot (no hard-threshold stage needed)
  d     = Lden - L(base)    (optionally only its high-pass)  added equally to all sites -> achromatic
  grain = uniform fraction of the mosaic residual, binomial 3x3 (the approved v4 DNG look)
"""
import numpy as np, torch
import torch.nn.functional as Fn
import cfadct as C
from cfadct import T, F32

_VAR = {}


def box2s(z):
    """(H,W) -> stride-1 2x2 mean anchored top-left, reflect at the far edge."""
    p = Fn.pad(z[None, None], (0, 1, 0, 1), mode='replicate')[0, 0]
    return 0.25 * (p[:-1, :-1] + p[:-1, 1:] + p[1:, :-1] + p[1:, 1:])


def lvar(gain, n=8, s=2, trials=6):
    """Noise variance of each DCT coefficient of box2s(white noise * site gain), by Monte Carlo (cached)."""
    key = (tuple(np.round(gain.ravel(), 4)), n, s)
    if key not in _VAR:
        g = torch.from_numpy(np.tile(gain, (64, 64)).astype(np.float32))
        acc = torch.zeros(n, n)
        gen = torch.Generator().manual_seed(1)
        for _ in range(trials):
            L = box2s(torch.randn(g.shape, generator=gen) * g)
            B = C.blocks(L[None], n, s)[0]
            X = torch.einsum('ki,yxij,lj->yxkl', C.DM[n], B, C.DM[n])
            acc += (X * X).mean((0, 1))
        _VAR[key] = (acc / trials)[None]
    return _VAR[key]


DEFAULT = dict(fl=1.0,      # Wiener noise multiplier of the full-res luma pass (lower keeps more)
               fl_amt=1.0,  # fraction of the full-res correction applied
               fl_hp=0,     # >0: keep only the high-pass of the correction (binomial blur count)
               fl_s=2,      # DCT stride of the full-res pass
               fl_pilot='own',  # 'own' = hard-threshold pilot on L(z) (independent of base), 'base' = L(base)
               fl_lam=2.7,
               grain=0.6)   # uniform mosaic-residual grain (v4 DNG pick)


def denoise(x, a, b, wb, prm=None, base_prm=None):
    p = dict(DEFAULT); p.update(prm or {})
    bp = dict(base_prm or {}); bp['grain'] = 0.0
    H, W = x.shape
    s = np.sqrt(np.array([wb[0], wb[1], wb[1], wb[2]], np.float64) / wb[1])
    gain = np.array([[s[0], s[1]], [s[2], s[3]]], F32)
    gmap = np.tile(gain, (H // 2, W // 2))
    zt = torch.from_numpy((C.gat(x.astype(np.float64), a, b) * gmap).astype(F32))
    base_x = C.denoise_packed(x, a, b, wb, bp)
    base = torch.from_numpy((C.gat(base_x.astype(np.float64), a, b) * gmap).astype(F32))
    out = base
    if p['fl_amt'] > 0:
        Lz, Lb = box2s(zt), box2s(base)
        v = lvar(gain, 8, p['fl_s'])
        pil = None if p['fl_pilot'] == 'own' else Lb[None]
        Ld = C.dct2stage(Lz[None], v, p['fl_s'], lam=p['fl_lam'], wmul=p['fl'], pilot=pil)[0]
        d = Ld - Lb
        if p['fl_hp'] > 0:
            d = d - C.blur(d[None], p['fl_hp'])[0]
        out = base + p['fl_amt'] * d
    if p['grain'] > 0:
        out = out + p['grain'] * C.blur((zt - out)[None], 1)[0]
    return C.gat_inv((out.numpy() / gmap).astype(np.float64), a, b).astype(F32)
