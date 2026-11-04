"""RawLens denoise v2 prototype: luminance / colour split (not RAWR-derived).

Design (2026-11-03), from the ESD3D2 comparison lessons:
  - RAWR stabilises each colour channel separately and mixes luma/chroma in
    that domain; luma grain then turns into colour (teal specks, colour noise)
    and needs patches. v2 separates brightness from colour first, in linear:
  1. WB'd camera-linear RGB -> luminance L = sum q_c * wb_c * x_c (q = 1/4,1/2,1/4).
     Noise of L for a neutral pixel: var = aL * L + bL with
     aL = a * sum(wb_c q_c^2 k_c), bL = b * sum(wb_c^2 q_c^2 k_c) (k: RCD factor).
  2. Single-channel generalized Anscombe on L (unit noise).
  3. Luma: B3 a-trous, per-scale noise = white-noise model x factor measured
     from the image (robust, flattest blocks; covers RCD / merged / processed
     correlated noise), Wiener with leave-one-out energy + closed-form 2nd stage.
  4. Edge-steered 5x5 pass (structure tensor) on the denoised luma.
  5. Grain: fixed fraction of the noisy fine bands, added everywhere (one floor).
  6. Inverse: closed-form unbiased GAT inverse (Makitalo-Foi).
  7. Colour: 1/4-grid block means of WB'd RGB and L, per-channel guided linear
     model rgb = A*L + B (box radius r blocks), evaluated per pixel at the clean
     luma, then scaled so the pixel's luminance equals the final luma
     (grain stays neutral by construction).
Output: camera-linear (pre-WB) RGB, same as the GPU path.
"""
import numpy as np
from scipy.ndimage import uniform_filter, convolve1d

F32 = np.float32
Q = np.array([0.25, 0.5, 0.25])
RCD_KAPPA = np.array([0.82, 0.71, 0.84])
B3 = np.array([1, 4, 6, 4, 1], np.float64) / 16


def _atrous_smooth(x, s):
    k = np.zeros(4 * 2 ** s + 1)
    k[:: 2 ** s] = B3
    y = convolve1d(x, k, axis=0, mode="reflect")
    return convolve1d(y, k, axis=1, mode="reflect")


_WHITE = {}


def white_sigmas(scales):
    """Per-scale std of B3 a-trous detail for unit white noise (simulated once)."""
    if scales not in _WHITE:
        rng = np.random.default_rng(0)
        x = rng.standard_normal((512, 512))
        out = []
        for s in range(scales):
            c = _atrous_smooth(x, s)
            out.append(np.std((x - c)[64:-64, 64:-64]))
            x = c
        _WHITE[scales] = np.array(out)
    return _WHITE[scales]


def gat(L, aL, bL):
    return 2.0 / aL * np.sqrt(np.maximum(aL * L + 0.375 * aL * aL + bL, 0.0))


def gat_inv(D, aL, bL):
    """Closed-form approximation of the exact unbiased inverse (Makitalo-Foi 2013)."""
    D = np.maximum(D, 1e-3)
    s2 = bL / (aL * aL)
    r = np.sqrt(1.5)
    z = 0.25 * D * D + 0.25 * r / D - 11.0 / 8.0 / D ** 2 + 5.0 / 8.0 * r / D ** 3 - 0.125 - s2
    # Below D ~ 1 the closed form is meaningless: fall back to the algebraic inverse.
    alg = 0.25 * D * D - 0.375 - s2
    z = np.where(D < 1.5, alg, z)
    return aL * z


def measured_factors(y, scales, model, fmax=3.0):
    """Image-measured noise / white model, per scale (flattest 10% of 16x16 blocks)."""
    f = []
    c = y
    for s in range(scales):
        sm = _atrous_smooth(c, s)
        d = c - sm
        c = sm
        B = 16
        h, w = (d.shape[0] // B) * B, (d.shape[1] // B) * B
        v = (d[:h, :w] ** 2).reshape(h // B, B, w // B, B).mean((1, 3)).ravel()
        f.append(np.sqrt(np.percentile(v, 10) / 0.85) / model[s])  # 10% quantile ~0.85 of mean
    f = np.array(f)
    out = np.clip(f, 0.7, fmax)
    for s in range(4, scales):  # coarse bands: signal dominates the estimate
        out[s] = out[3]
    return out


def wiener(d, n2):
    d2 = d * d
    e9 = uniform_filter(d2, 3, mode="nearest")
    loo = (e9 * 9 - d2) / 8
    s2 = np.maximum(loo - n2, 0)
    s2 = s2 * s2 / (s2 + n2)
    return s2 / (s2 + n2) * d


def steer(y, sig, st=1.5, sn_flat=1.2, sn_edge=0.4, rs=1.5):
    gx = np.gradient(y, axis=1)
    gy = np.gradient(y, axis=0)
    sxx = uniform_filter(gx * gx, 3); sxy = uniform_filter(gx * gy, 3); syy = uniform_filter(gy * gy, 3)
    tr = sxx + syy
    dl = np.sqrt(np.maximum((sxx - syy) ** 2 + 4 * sxy * sxy, 0))
    l1 = 0.5 * (tr + dl); l2 = np.maximum(0.5 * (tr - dl), 0)
    coh = ((l1 - l2) / np.maximum(l1 + l2, 1e-12)) ** 2
    tx, ty = sxy, l1 - sxx
    n = np.hypot(tx, ty); deg = n < 1e-10
    tx = np.where(deg, 1.0, tx / np.maximum(n, 1e-30)); ty = np.where(deg, 0.0, ty / np.maximum(n, 1e-30))
    sn = sn_flat + (sn_edge - sn_flat) * coh
    P = np.pad(y, 2, mode="edge"); h, w = y.shape
    acc = np.zeros_like(y); ws = np.zeros_like(y)
    for j in range(-2, 3):
        for i in range(-2, 3):
            v = P[2 + j:2 + j + h, 2 + i:2 + i + w]
            u = i * tx + j * ty; q = i * ty - j * tx
            wt = np.exp(-0.5 * (u * u / st ** 2 + q * q / sn ** 2) - 0.5 * ((v - y) / (rs * sig)) ** 2)
            acc += wt * v; ws += wt
    return acc / ws


def colour(rgbw, Lnoisy, Lref, Lfinal, bvar, k=4, r=4, eps=1.0):
    """Guided colour: per-channel linear model rgb = A*L + B at 1/k resolution
    (box radius r blocks), evaluated at the clean luma, scaled to Lfinal."""
    H, W = Lnoisy.shape; h, w = -(-H // k), -(-W // k)
    def blocks(x):
        pad = np.pad(x, ((0, h * k - H), (0, w * k - W)), mode="edge")
        return pad.reshape(h, k, w, k).mean((1, 3))
    box = lambda x: uniform_filter(x, 2 * r + 1, mode="nearest")
    Lb = blocks(Lnoisy); my = box(Lb); vy = np.maximum(box(Lb * Lb) - my * my, 0)
    uy = (np.arange(H) - (k - 1) / 2) / k; ux = (np.arange(W) - (k - 1) / 2) / k
    y0 = np.floor(uy).astype(int); x0 = np.floor(ux).astype(int)
    fy = (uy - y0)[:, None]; fx = (ux - x0)[None, :]
    cy0, cy1 = np.clip(y0, 0, h - 1), np.clip(y0 + 1, 0, h - 1)
    cx0, cx1 = np.clip(x0, 0, w - 1), np.clip(x0 + 1, 0, w - 1)
    up = lambda m: ((m[cy0][:, cx0] * (1 - fx) + m[cy0][:, cx1] * fx) * (1 - fy)
                    + (m[cy1][:, cx0] * (1 - fx) + m[cy1][:, cx1] * fx) * fy)
    model = np.empty((H, W, 3))
    for c in range(3):
        C = blocks(rgbw[..., c]); mc = box(C); cov = box(Lb * C) - my * mc
        A = cov / (vy + eps * bvar); Bc = mc - A * my
        model[..., c] = up(box(A)) * Lref + up(box(Bc))
    model = np.maximum(model, 0)
    lm = (model * Q).sum(-1)
    scale = np.where(lm > 1e-9, Lfinal / np.maximum(lm, 1e-9), 0.0)
    out = model * np.clip(scale, 0, 8)[..., None]
    # where the model collapses (black), fall back to neutral grey at Lfinal
    return np.where((lm > 1e-9)[..., None], out, np.repeat(Lfinal[..., None], 3, -1))


def denoise_v2(img, a, b, wb, prm=None):
    """img: camera-linear RGB (HxWx3), a/b: normalized green noise model."""
    prm = prm or {}
    wb = np.asarray(wb, np.float64)
    kappa = np.asarray(prm.get("kappa", RCD_KAPPA))
    rgbw = img.astype(np.float64) * wb
    L = (rgbw * Q).sum(-1)
    aL = a * float(np.sum(wb * Q * Q * kappa))
    bL = b * float(np.sum(wb * wb * Q * Q * kappa))
    aL = max(aL, 1e-9)
    y = gat(L, aL, bL)
    scales = prm.get("scales", 7)
    model = white_sigmas(scales)
    fac = measured_factors(y, scales, model, prm.get("fmax", 3.0)) if prm.get("measure", True) else np.ones(scales)
    luma = prm.get("luma", 1.0)
    keep = prm.get("grain", 0.45)
    c = y; acc = np.zeros_like(y)
    for s in range(scales):
        sm = _atrous_smooth(c, s)
        d = c - sm
        n2 = (model[s] * fac[s]) ** 2 * luma
        acc += wiener(d, n2)
        c = sm
    yd = acc + c
    if prm.get("steer", True):
        yd = steer(yd, model[0] * fac[0])
    # Grain = fine bands of the removed part (input - clean): noise only where
    # texture survived, so one floor everywhere without amplifying detail.
    res = y - yd
    r1 = _atrous_smooth(res, 0); r2 = _atrous_smooth(r1, 1)
    grain = keep * ((res - r1) + 0.4 * (r1 - r2))
    Lclean = gat_inv(yd, aL, bL)
    Lfinal = gat_inv(yd + grain, aL, bL)
    # Colour reference luma: 5x5 mean of the clean VST luma on flat areas, the
    # clean luma itself within 2 sigma of edges; colour is constant across fine
    # texture (every channel carries the same relative detail).
    m5 = uniform_filter(yd, 5, mode="nearest")
    yref = np.where(np.abs(m5 - yd) < 2 * model[0] * fac[0], m5, yd)
    Lref = gat_inv(yref, aL, bL)
    # block-mean luma noise variance (regulariser), linear units, at the median level
    med = float(np.median(L))
    bvar = (aL * med + bL) / 16.0
    rgb_out = colour(rgbw, L, np.maximum(Lref, 0), np.maximum(Lfinal, 0), bvar,
                     r=prm.get("cr", 4), eps=prm.get("ceps", 1.0))
    return (rgb_out / wb).astype(F32), dict(aL=aL, bL=bL, fac=np.round(fac, 2).tolist())
