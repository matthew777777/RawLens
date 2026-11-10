"""Prototype: raw-domain (CFA -> CFA) multiscale two-stage DCT denoiser.

z      = sqrt(wb_c) * GAT(x)            (variance stabilised, grey == flat mosaic)
level0 = sliding 8x8 DCT on the mosaic, stride 2 (hard threshold pilot -> Wiener)
level1+= (Y, Ca, Cb) 2x2 Hadamard planes, box pyramid, same DCT core per level;
          coarse levels correct the low frequencies of the finer one,
          chroma is taken from the opponent pyramid.
grain  = g * binomial3x3(z - denoised)  (carrier-free -> achromatic)
"""
import numpy as np, torch
import torch.nn.functional as Fn

F32 = np.float32
T = torch.float32


def dct_mat(n=8):
    k = np.arange(n)[:, None]; i = np.arange(n)[None, :]
    m = np.cos(np.pi * (2 * i + 1) * k / (2 * n)) * np.sqrt(2.0 / n)
    m[0] /= np.sqrt(2.0)
    return torch.tensor(m, dtype=T)


D8 = dct_mat(8)
WIN = {n: torch.tensor(np.outer(np.sin(np.pi * (np.arange(n) + 0.5) / n) ** 2, np.sin(np.pi * (np.arange(n) + 0.5) / n) ** 2), dtype=T) for n in (4, 6, 8, 12, 16)}
DM = {n: dct_mat(n) for n in (4, 6, 8, 12, 16)}


def gat(x, a, b):
    return (2.0 / a) * np.sqrt(np.maximum(a * x + 0.375 * a * a + b, 0.0))


def gat_inv(z, a, b):
    """Closed-form approximation of the exact unbiased inverse (Makitalo & Foi 2013)."""
    z = np.maximum(z, 1.0)
    i0 = 0.25 * z * z + 0.25 * np.sqrt(1.5) / z - 11.0 / 8.0 / (z * z) + 5.0 / 8.0 * np.sqrt(1.5) / (z ** 3) - 0.125
    return a * i0 - b / a


def blocks(u, n, s):
    """u: (C,H,W) -> (C, nby, nbx, n, n) views of all n x n blocks at stride s."""
    return u.unfold(1, n, s).unfold(2, n, s)


def aggregate(est, wgt, shape, n, s, win=None):
    """est: (C,nby,nbx,n,n) weighted block estimates, wgt: (C,nby,nbx) -> (C,H,W)."""
    C, nby, nbx = wgt.shape
    H, W = shape
    cols = (est * wgt[..., None, None]).reshape(C, nby * nbx, n * n).permute(0, 2, 1)
    num = Fn.fold(cols, (H, W), kernel_size=n, stride=s)
    wc = wgt.reshape(C, 1, nby * nbx).expand(C, n * n, nby * nbx)
    if win is not None:
        wc = wc * win.reshape(1, n * n, 1)
    den = Fn.fold(wc, (H, W), kernel_size=n, stride=s)
    return (num / den.clamp_min(1e-12)).squeeze(1)


def pad_to(u, n, s):
    C, H, W = u.shape
    ph = (-(H - n)) % s; pw = (-(W - n)) % s
    return Fn.pad(u[None], (n // 2, n // 2 + pw, n // 2, n // 2 + ph), mode='reflect')[0], (H, W)


def dct2stage(u, var, s, lam=2.7, wmul=1.0, pilot=None, stage2=True, n=8, win=False, s1=None):
    """Two-stage sliding DCT. u: (C,H,W); var: (C,8,8) noise variance of each DCT coefficient.
    wmul: (C,) multiplies the noise variance in the Wiener stage (denoise strength)."""
    D8 = DM[n]
    var = var[:, :n, :n]
    up, (H, W) = pad_to(u, n, s)
    B = blocks(up, n, s)
    X = torch.einsum('ki,cyxij,lj->cyxkl', D8, B, D8)
    v = var[:, None, None]
    if pilot is None:
        l2 = (lam * lam).view(-1, 1, 1, 1, 1) if torch.is_tensor(lam) else lam * lam
        keep = (X * X > l2 * v)
        keep[..., 0, 0] = True
        P = X * keep
        w1 = 1.0 / keep.sum((-1, -2)).clamp_min(1).to(T)
        est = torch.einsum('ik,cyxkl,jl->cyxij', D8.t(), P, D8.t())
        if win:
            est = est * WIN[n]; w1 = torch.ones_like(w1)
        pil = aggregate(est, w1, up.shape[1:], n, s, WIN[n] if win else None)
    else:
        pil = Fn.pad(pilot[None], (n // 2, up.shape[2] - W - n // 2, n // 2, up.shape[1] - H - n // 2), mode='reflect')[0]
    if not stage2:
        return pil[:, n // 2:n // 2 + H, n // 2:n // 2 + W]
    PB = torch.einsum('ki,cyxij,lj->cyxkl', D8, blocks(pil, n, s), D8)
    vm = v * wmul.view(-1, 1, 1, 1, 1) if torch.is_tensor(wmul) else v * wmul
    P2 = PB * PB
    Wn = P2 / (P2 + vm)
    Wn[..., 0, 0] = 1.0
    w2 = 1.0 / (Wn * Wn).sum((-1, -2)).clamp_min(1e-6)
    est = torch.einsum('ik,cyxkl,jl->cyxij', D8.t(), Wn * X, D8.t())
    if win:
        est = est * WIN[n]; w2 = torch.ones_like(w2)
    out = aggregate(est, w2, up.shape[1:], n, s, WIN[n] if win else None)
    return out[:, n // 2:n // 2 + H, n // 2:n // 2 + W]


def had_down(z):
    r = z[0::2, 0::2]; g1 = z[0::2, 1::2]; g2 = z[1::2, 0::2]; b = z[1::2, 1::2]
    return torch.stack([(r + g1 + g2 + b) / 4, (r - b) / 2, (r + b - g1 - g2) / 4, (g1 - g2) / 2])


def had_up(p):
    Y, Ca, Cb, Dd = p
    z = torch.empty((Y.shape[0] * 2, Y.shape[1] * 2), dtype=T)
    z[0::2, 0::2] = Y + Cb + Ca
    z[1::2, 1::2] = Y + Cb - Ca
    z[0::2, 1::2] = Y - Cb + Dd
    z[1::2, 0::2] = Y - Cb - Dd
    return z


def box_down(u):
    C, H, W = u.shape
    u = u[:, :H & ~1, :W & ~1]
    return 0.25 * (u[:, 0::2, 0::2] + u[:, 0::2, 1::2] + u[:, 1::2, 0::2] + u[:, 1::2, 1::2])


def up2(u, shape):
    return Fn.interpolate(u[None], size=shape, mode='bilinear', align_corners=False)[0]


def blur(u, k):
    """separable binomial blur applied k times (reflect)."""
    w = torch.tensor([0.25, 0.5, 0.25], dtype=T)
    for _ in range(k):
        u = Fn.conv2d(Fn.pad(u[:, None], (1, 1, 0, 0), mode='reflect'), w.view(1, 1, 1, 3))[:, 0]
        u = Fn.conv2d(Fn.pad(u[:, None], (0, 0, 1, 1), mode='reflect'), w.view(1, 1, 3, 1))[:, 0]
    return u


DEFAULT = dict(
    lam=2.7,            # hard threshold (sigmas), pilot stage
    lam_y=0.0,          # pilot threshold of the luma plane (0 = same as lam)
    luma=1.0,           # Wiener noise multiplier, luma (lower keeps more)
    chroma=3.0,         # Wiener noise multiplier, chroma planes (variance)
    levels=4,           # opponent pyramid levels below the mosaic level
    stride0=2,          # mosaic level stride (must be even)
    stride1=2,          # half-res level stride
    stride2=2,          # coarser levels stride
    chroma1=1.0,        # Wiener noise multiplier of Ca,Cb,D at the half-res level (fine luma lives there)
    yblur=0,            # blur of the luma low-frequency correction per level
    grain=0.0,          # fraction of removed luma noise put back
    n=8,                # block size of the pyramid levels
    grain_raw=0.0,      # fraction of the full mosaic residual put back
    grain_y=True,       # grain from the Y plane residual (False: binomial-blurred mosaic residual)
    win=False,          # fixed Hann overlap-add instead of adaptive block weights
    eq=True,            # white-balance equalisation in the VST domain
)


def ms3(u, var3, lvl, prm):
    """Multiscale denoise of opponent planes (Y,Ca,Cb[,D]). var3: (C,) white noise variance per plane."""
    C = u.shape[0]
    var = var3.view(C, 1, 1).expand(C, 16, 16)
    cm = prm['chroma1'] if lvl == 1 else prm['chroma']
    wm = torch.tensor([prm['luma']] + [cm] * (C - 1), dtype=T)
    st = prm['stride1'] if lvl == 1 else prm['stride2']
    lam = torch.tensor([prm['lam_y'] if prm['lam_y'] else prm['lam']] + [prm['lam']] * (C - 1), dtype=T)
    if lvl >= prm['levels'] or min(u.shape[1:]) < 4 * prm['n']:
        return dct2stage(u, var, st, lam, wm, n=prm['n'], win=prm['win'])
    coarse = ms3(box_down(u[:3]), var3[:3] / 4, lvl + 1, prm)
    pil = dct2stage(u, var, st, lam, wm, stage2=False, n=prm['n'], win=prm['win'])
    pil = fold(pil, coarse, prm)
    out = dct2stage(u, var, st, lam, wm, pilot=pil, n=prm['n'], win=prm['win'])
    return fold(out, coarse, prm)


def fold(fine, coarse, prm):
    """Replace the low frequencies of `fine` (first 3 planes) with those of `coarse` (half resolution)."""
    d = coarse - box_down(fine[:3])[:, :coarse.shape[1], :coarse.shape[2]]
    if prm['yblur'] > 0:
        d = blur(d, prm['yblur'])
    out = fine.clone()
    out[:3] += up2(d, fine.shape[1:])
    return out


def denoise_packed(x, a, b, wb, prm=None):
    """All-half-resolution variant: (Y,Ca,Cb,D) Hadamard planes + opponent pyramid, no mosaic-level pass."""
    p = dict(DEFAULT); p.update(prm or {})
    H, W = x.shape
    z = gat(x.astype(np.float64), a, b).astype(F32)
    s = np.sqrt(np.array([wb[0], wb[1], wb[1], wb[2]], np.float64) / wb[1]) if p['eq'] else np.ones(4)
    gain = np.array([[s[0], s[1]], [s[2], s[3]]], F32)
    gmap = np.tile(gain, (H // 2, W // 2))
    zt = torch.from_numpy(z * gmap)
    sr2, sg2, sb2 = float(s[0] ** 2), float(s[1] ** 2), float(s[3] ** 2)
    var4 = torch.tensor([(sr2 + 2 * sg2 + sb2) / 16, (sr2 + sb2) / 4, (sr2 + sb2 + 2 * sg2) / 16, sg2 / 2], dtype=T)
    hz = had_down(zt)
    pl = ms3(hz, var4, 1, p)
    if p['grain'] > 0 and p['grain_y']:
        pl[0] += p['grain'] * (hz[0] - pl[0])       # luma-plane residual only: constant per 2x2 cell
    out = had_up(pl)
    res = zt - out
    if p['grain'] > 0 and not p['grain_y']:
        out = out + p['grain'] * blur(res[None], 1)[0]
    if p['grain_raw'] > 0:
        out = out + p['grain_raw'] * res            # uniform fraction of everything removed (incl. colour noise)
    return gat_inv((out.numpy() / gmap).astype(np.float64), a, b).astype(F32)


def denoise_cfa(x, a, b, wb, prm=None, ret_all=False):
    """x: RGGB CFA (float, black-subtracted, may be negative). a, b: var = a*x + b. wb: (r,g,b) gains."""
    p = dict(DEFAULT); p.update(prm or {})
    H, W = x.shape
    z = gat(x.astype(np.float64), a, b).astype(F32)
    # equalise: grey -> flat mosaic. sites: R, G, G, B
    s = np.sqrt(np.array([wb[0], wb[1], wb[1], wb[2]], np.float64) / wb[1]) if p['eq'] else np.ones(4)
    gain = np.empty((2, 2), F32); gain[0, 0] = s[0]; gain[0, 1] = s[1]; gain[1, 0] = s[2]; gain[1, 1] = s[3]
    gmap = np.tile(gain, (H // 2, W // 2))
    zt = torch.from_numpy(z * gmap)
    # mosaic level: noise variance of each DCT coefficient for even-aligned blocks
    sv = torch.tensor(np.tile(gain * gain, (4, 4)), dtype=T)             # site variance in an 8x8 block
    basis = torch.einsum('ki,lj->klij', D8, D8)                           # (k,l,i,j)
    var0 = (basis * basis * sv).sum((-1, -2))[None]                       # (1,8,8)
    sr2, sg2, sb2 = float(s[0] ** 2), float(s[1] ** 2), float(s[3] ** 2)
    var3 = torch.tensor([(sr2 + 2 * sg2 + sb2) / 16, (sr2 + sb2) / 4, (sr2 + sb2 + 2 * sg2) / 16], dtype=T)

    u0 = zt[None]
    pyr = ms3(had_down(zt)[:3], var3, 1, p) if p['levels'] >= 1 else None
    wm0 = p['luma']
    pil0 = dct2stage(u0, var0, p['stride0'], p['lam'], wm0, stage2=False)
    if pyr is not None:
        pil0 = merge0(pil0[0], pyr, p)[None]
    out0 = dct2stage(u0, var0, p['stride0'], p['lam'], wm0, pilot=pil0)[0]
    if pyr is not None:
        out0 = merge0(out0, pyr, p)
    if p['grain'] > 0:
        res = blur((zt - out0)[None], 1)[0]
        out0 = out0 + p['grain'] * res
    zd = out0.numpy() / gmap
    y = gat_inv(zd.astype(np.float64), a, b).astype(F32)
    return (y, dict(z=zt.numpy(), zd=out0.numpy())) if ret_all else y


def merge0(m, pyr, prm):
    """Mosaic-level estimate + opponent pyramid: luma low frequencies and all chroma from the pyramid."""
    h = had_down(m)
    dY = pyr[0] - h[0]
    if prm['yblur'] > 0:
        dY = blur(dY[None], prm['yblur'])[0]
    return had_up(torch.stack([h[0] + dY, pyr[1], pyr[2], h[3]]))
