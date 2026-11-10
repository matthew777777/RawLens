"""Full-resolution luma detail path on top of cfadct.denoise_packed.

base   = packed opponent-pyramid DCT estimate (chroma, blotches, coarse luma)
rL     = binomial3x3(z - base): carrier-free (achromatic) full-res luma residual = noise + lost detail
detail = rL filtered along the local edge direction (structure tensor of the noisy luma),
         then shrunk by a local Wiener gain against the filtered-noise level
out    = base + detail + grain * (rL - detail)
"""
import numpy as np, torch
import torch.nn.functional as Fn
import cfadct as C
from cfadct import T, F32

SDEF = dict(st_len=2.5, st_taps=5, tensor_sigma=2.5, grad_sigma=1.0, kappa=1.0, coh_pow=0.0, e_sigma=1.5, detail=1.0, passes=1, pre='binom')


def gauss(u, sigma):
    r = max(1, int(3 * sigma + 0.5)); k = torch.exp(-0.5 * (torch.arange(-r, r + 1, dtype=T) / sigma) ** 2); k /= k.sum()
    u = Fn.conv2d(Fn.pad(u[None, None], (r, r, 0, 0), mode='reflect'), k.view(1, 1, 1, -1))
    return Fn.conv2d(Fn.pad(u, (0, 0, r, r), mode='reflect'), k.view(1, 1, -1, 1))[0, 0]


def binom(u):
    return C.blur(u[None], 1)[0]


def orientation(L, sp, noise_var):
    """unit vector along the edge + coherence, from the structure tensor of L."""
    g = gauss(L, sp['grad_sigma'])
    gp = Fn.pad(g[None, None], (1, 1, 1, 1), mode='replicate')[0, 0]
    gx = 0.5 * (gp[1:-1, 2:] - gp[1:-1, :-2]); gy = 0.5 * (gp[2:, 1:-1] - gp[:-2, 1:-1])
    jxx = gauss(gx * gx, sp['tensor_sigma']); jyy = gauss(gy * gy, sp['tensor_sigma']); jxy = gauss(gx * gy, sp['tensor_sigma'])
    tr = jxx + jyy; df = torch.sqrt((jxx - jyy) ** 2 + 4 * jxy * jxy)
    ang = 0.5 * torch.atan2(2 * jxy, jxx - jyy)          # gradient direction
    tx, ty = -torch.sin(ang), torch.cos(ang)             # along the edge
    coh = df / (tr + noise_var)
    return tx, ty, coh, tr


def steer_filter(u, tx, ty, sp):
    H, W = u.shape
    ys, xs = torch.meshgrid(torch.arange(H, dtype=T), torch.arange(W, dtype=T), indexing='ij')
    acc = torch.zeros_like(u); wsum = 0.0
    K = sp['st_taps']
    for k in range(-K, K + 1):
        w = float(np.exp(-0.5 * (k / sp['st_len']) ** 2))
        gx = (xs + k * tx + 0.5) / W * 2 - 1; gy = (ys + k * ty + 0.5) / H * 2 - 1
        acc += w * Fn.grid_sample(u[None, None], torch.stack([gx, gy], -1)[None], mode='bilinear', padding_mode='reflection', align_corners=False)[0, 0]
        wsum += w
    return acc / wsum


def core(zt, base, gmap, sp, calib=None):
    r = zt - base
    rL = binom(r)
    Lg = binom(zt)                                         # noisy full-res luma (carrier-free)
    nv = calib['grad'] if calib else 0.0
    tx, ty, coh, tr = orientation(Lg, sp, nv)
    if sp['pre'] == 'binom':
        d = rL
    elif sp['pre'] == 'green':
        # green-site residual only (no R/B carrier noise); R/B sites filled from the 4 green
        # neighbours, weighted towards the pair lying along the edge
        rp = Fn.pad(r[None, None], (1, 1, 1, 1), mode='reflect')[0, 0]
        hz = 0.5 * (rp[1:-1, 2:] + rp[1:-1, :-2]); vt = 0.5 * (rp[2:, 1:-1] + rp[:-2, 1:-1])
        wh = tx * tx
        fill = wh * hz + (1 - wh) * vt
        H, W = r.shape
        ys, xs = torch.meshgrid(torch.arange(H), torch.arange(W), indexing='ij')
        d = torch.where(((ys + xs) & 1) == 1, r, fill)      # RGGB: green where (y + x) is odd
    else:
        d = r
    for _ in range(sp['passes']):
        d = steer_filter(d, tx, ty, sp)
    if calib is None:
        return d, tr
    E = gauss(d * d, sp['e_sigma'])
    w = torch.clamp(E - sp['kappa'] * calib['d2'], min=0) / E.clamp_min(1e-12)
    if sp['coh_pow'] > 0:
        w = w * coh.clamp(0, 1) ** sp['coh_pow']
    return sp['detail'] * w * d, rL


_cal = {}


def calibrate(gain, sp):
    """filtered-noise power of the detail path for pure noise with the per-site gains (cached)."""
    key = (tuple(np.round(gain.ravel(), 3)), tuple(sorted(sp.items())))
    if key not in _cal:
        rng = np.random.default_rng(1); S = 256
        gm = np.tile(gain, (S // 2, S // 2))
        n = torch.from_numpy((rng.standard_normal((S, S)) * gm).astype(F32))
        d, tr = core(n + 30.0, torch.full((S, S), 30.0), gm, sp)
        _cal[key] = dict(d2=float((d[16:-16, 16:-16] ** 2).mean()), grad=float(tr[16:-16, 16:-16].mean()))
    return _cal[key]


def denoise(x, a, b, wb, prm=None, sprm=None):
    p = dict(C.DEFAULT); p.update(prm or {}); sp = dict(SDEF); sp.update(sprm or {})
    grain = p['grain']; p['grain'] = 0.0
    H, W = x.shape
    z = C.gat(x.astype(np.float64), a, b).astype(F32)
    s = np.sqrt(np.array([wb[0], wb[1], wb[1], wb[2]], np.float64) / wb[1])
    gain = np.array([[s[0], s[1]], [s[2], s[3]]], F32); gmap = np.tile(gain, (H // 2, W // 2))
    zt = torch.from_numpy(z * gmap)
    sr2, sg2, sb2 = float(s[0] ** 2), 1.0, float(s[3] ** 2)
    var4 = torch.tensor([(sr2 + 2 * sg2 + sb2) / 16, (sr2 + sb2) / 4, (sr2 + sb2 + 2 * sg2) / 16, sg2 / 2], dtype=T)
    base = C.had_up(C.ms3(C.had_down(zt), var4, 1, p))
    det, rL = core(zt, base, gmap, sp, calibrate(gain, sp))
    out = base + det + grain * (rL - det)
    return C.gat_inv((out.numpy() / gmap).astype(np.float64), a, b).astype(F32)
