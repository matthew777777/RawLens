#!/usr/bin/env python3
"""Prototype: RawLens tuned wavelet shrinkage vs RAWR soft threshold.

Same VST + Y0U0V0 + edge-aware a-trous as denoiseprofile_oracle (RAWR),
only the per-coefficient shrink rule differs. Every rule here is designed to
be one fused GPU pass per scale reading a 3x3 detail neighbourhood: no extra
full-resolution buffers, no second decomposition.

Benchmark: clean ground truth = low-ISO DNG, demosaiced and 2x binned;
noisy = Poisson-Gaussian noise from a high-ISO profile (S, O) added in raw
units. Metrics in a gamma-encoded opponent space (perceptual-ish).
"""
from __future__ import annotations
import json, sys
import numpy as np
import numba
from scipy.ndimage import uniform_filter

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import denoiseprofile_oracle as O

F32 = np.float32


def box3(x):
    return uniform_filter(x, size=(3, 3, 1), mode="nearest").astype(F32)


def shrink(rule, d, sb2, scale, prm):
    """d: HxWx3 detail (Y,U,V) in VST domain, noise var per band ~ sb2."""
    if rule == "rawr":
        fy, fuv = prm.get("fy", 0.25), prm.get("fuv", 0.75)
        sum2 = (d.astype(np.float64) ** 2).mean((0, 1))
        std_x = np.sqrt(np.maximum(1e-6, sum2 - sb2))
        adj = np.array([32 * fy * fy, 32 * fuv * fuv, 32 * fuv * fuv])
        t = (adj * sb2 / std_x).astype(F32)
        return np.maximum(d - t, 0) + np.minimum(d + t, 0)
    # local Wiener family: signal energy from 3x3 window of d^2
    ay = prm["ay"][scale]; auv = prm["auv"][scale]
    alpha = np.array([ay, auv, auv], F32)
    n2 = (sb2 * alpha).astype(F32)                      # effective noise var
    d2 = d * d
    e9 = box3(d2)
    loo = (e9 * 9 - d2) / 8                             # leave-one-out 3x3 energy
    mode = prm.get("loo", "uv")
    e = loo if mode == "all" else e9.copy()
    if mode == "uv": e[..., 1:] = loo[..., 1:]
    s2 = np.maximum(e - n2, 0)                          # stage-1 signal var
    if rule in ("wiener2", "wiener2g", "guided"):  # guided = shipped shader: 2nd stage + guide
        # closed-form 2nd stage: energy of the stage-1 estimate
        # E[(g d)^2] ~= s2^2/(s2+n2)  -> gain from a cleaner signal estimate
        s2 = (s2 * s2 / (s2 + n2 + 1e-12)).astype(F32)
    if rule in ("wiener2g", "guided"):
        # luma guidance for chroma: real luma structure here => keep chroma
        # multiplicative: luma structure only amplifies chroma's OWN evidence
        s2 = s2.copy()
        if "gate" in prm:
            # additive luma prior, gated by chroma's own SNR (flat chroma noise: e~n2)
            gate = np.clip(e[..., 1:] / n2[1:] - prm["gate"], 0, 1)
            s2[..., 1:] += prm["guide"] * gate * s2[..., :1] * (n2[1:] / n2[0])
        else:
            s2[..., 1:] *= 1 + prm.get("guide", 1.0) * s2[..., :1] / n2[0]
    g = s2 / (s2 + n2)
    keep = np.array([prm["keep"][scale], 0, 0], F32)
    if rule == "guided":
        out = guided_chroma(d, g, n2, prm)               # regress on pre-grain luma
        out[..., 0] += keep[0] * (d[..., 0] - out[..., 0])
        return out.astype(F32)
    g = g + keep * (1 - g)                               # luma grain retention
    return (g * d).astype(F32)


def boxk(x, k):
    return uniform_filter(x, size=(k, k) + (1,) * (x.ndim - 2), mode="nearest").astype(F32)


def guided_chroma(d, g, n2, prm):
    """Luma-guided local regression of chroma detail (GALOSH-style, wavelet domain).
    Chroma = a*Y_den + b over a kxk window, slope regularised by eps*n2_y; the
    unexplained (isoluminant) remainder is Wiener-shrunk by its own evidence;
    result clamped to the local range of the noisy chroma."""
    k = prm.get("win", 5)
    yd = (g[..., 0] * d[..., 0])[..., None]              # denoised luma detail
    out = np.empty_like(d)
    out[..., 0] = yd[..., 0]
    my = boxk(yd, k); vy = np.maximum(boxk(yd * yd, k) - my * my, 0)
    for c in (1, 2):
        dc = d[..., c:c + 1]
        mc = boxk(dc, k); cov = boxk(yd * dc, k) - my * mc
        a = cov / (vy + prm.get("eps", 1.0) * n2[0])
        if prm.get("slope_k"):
            # Shrink the slope by its own reliability: OLS slope noise variance
            # ~ n2_c / (k*k * var_y). James-Stein style: a *= max(0, 1 - K var_a / a^2).
            var_a = n2[c] / (k * k * (vy + prm.get("eps", 1.0) * n2[0]))
            a = a * np.maximum(0, 1 - prm["slope_k"] * var_a / np.maximum(a * a, 1e-20))
        bb = mc - a * my
        if not prm.get("cheap"):
            a, bb = boxk(a, k), boxk(bb, k)              # guided-filter averaging
        bb = bb * prm.get("bshrink", 0.0)                # intercept = low-freq chroma: shrink
        reg = a * yd + bb
        res = dc - reg                                   # unexplained chroma
        if prm.get("cheap"):
            gr = g[..., c:c + 1]                         # chroma's own Wiener gain
        else:
            e = np.maximum(boxk(res * res, 3) - n2[c], 0)
            gr = (e * e / (e + n2[c])) ; gr = gr / (gr + n2[c])
        est = reg + gr * res
        if prm.get("clamp", 1):
            lo = -boxk(-dc, 1)  # placeholder replaced below
            from scipy.ndimage import minimum_filter, maximum_filter
            lo = minimum_filter(dc, size=(3, 3, 1), mode="nearest")
            hi = maximum_filter(dc, size=(3, 3, 1), mode="nearest")
            est = np.clip(est, lo, hi)
        out[..., c] = est[..., 0]
    return out


def denoise(img, a_g, b_g, wb, rule, prm, strength=1.0, shadows=1.0, scales=6):
    wb = np.asarray(wb, F32)
    p = np.maximum(shadows + 0.1 * np.log(1 / wb), 0).astype(F32)
    a = F32(a_g * (O.P_FULCRUM / O.P_FULCRUM ** F32(shadows)))
    to_yuv, to_rgb = O.setup_conversion_matrices(wb.copy())
    f = F32(strength * 2.5)
    buf = O.precondition_y0u0v0(img, a, p, b_g, (to_yuv / f).astype(F32))
    out = np.zeros_like(buf)
    for s in range(scales):
        sb2 = F32(O.VARF ** s) ** 2
        coarse = np.empty_like(buf); det = np.empty_like(buf)
        O.dn_decompose(coarse, np.ascontiguousarray(buf), det, s, F32(1 / sb2))
        out += shrink(rule, det, sb2, s, prm)
        buf = coarse
    out += buf
    return O.backtransform_y0u0v0(out, a, p, b_g, F32(0), (to_rgb * f).astype(F32))


# ------------------------------------------------------------- benchmark
def load_clean(path, crop=1024):
    norm, full, wb, a, b, meta = O.load_dng_linear(path)
    H, W, _ = full.shape
    y0, x0 = (H - 2 * crop) // 2, (W - 2 * crop) // 2
    c = full[y0:y0 + 2 * crop, x0:x0 + 2 * crop]
    c = c.reshape(crop, 2, crop, 2, 3).mean((1, 3)).astype(F32)   # 2x bin
    return c, wb


def add_noise(clean, S, Ov, rng):
    var = np.maximum(S * clean + Ov, 0)
    return (clean + rng.standard_normal(clean.shape).astype(F32) * np.sqrt(var)).astype(F32)


def opp(rgb, wb):
    x = np.clip(rgb * wb[None, None, :], 0, None)
    x = x / np.percentile(x[..., 1], 99.5)
    g = np.clip(x, 0, 2) ** (1 / 2.2)
    Y = g @ np.array([0.2126, 0.7152, 0.0722], F32)
    return Y, np.stack([g[..., 0] - g[..., 1], g[..., 2] - g[..., 1]], -1)


def metrics(den, clean, wb):
    Yc, Cc = opp(clean, wb); Yd, Cd = opp(den, wb)
    gm = np.hypot(*np.gradient(uniform_filter(Cc, (3, 3, 1)), axis=(0, 1))).sum(-1)
    edge = gm > np.percentile(gm, 90); flat = gm < np.percentile(gm, 40)
    hp = lambda C: C - uniform_filter(C, (9, 9, 1))
    chroma_detail = float(np.std(hp(Cd)[edge]) / np.std(hp(Cc)[edge]))
    yres = (Yd - Yc)
    # grain character: fraction of residual luma noise energy at finest scale
    fine = yres - uniform_filter(yres, 3)
    return dict(
        y_rmse=float(np.sqrt(np.mean(yres ** 2))),
        c_rmse=float(np.sqrt(np.mean((Cd - Cc) ** 2))),
        c_rmse_edge=float(np.sqrt(np.mean((Cd - Cc)[edge] ** 2))),
        c_noise_flat=float(np.std((Cd - Cc)[flat])),
        chroma_detail=chroma_detail,
        y_fine_frac=float(np.mean(fine ** 2) / max(np.mean(yres ** 2), 1e-12)),
    )


if __name__ == "__main__":
    import itertools
    path, S, Ov = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    clean, wb = load_clean(path)
    rng = np.random.default_rng(1)
    noisy = add_noise(clean, S, Ov, rng)
    a_g, b_g = F32(S), F32(Ov)
    res = {"noisy": metrics(noisy, clean, wb)}
    res["rawr_default"] = metrics(denoise(noisy, a_g, b_g, wb, "rawr", {}), clean, wb)
    for fuv in (1.0, 1.5):
        res[f"rawr_fuv{fuv}"] = metrics(denoise(noisy, a_g, b_g, wb, "rawr", {"fy": 0.25, "fuv": fuv}), clean, wb)
    z = [0] * 6
    for rule, ay, uvc in itertools.product(("wiener", "wiener2", "wiener2g"), (0.5, 1.0), ((1, 2), (1.5, 3))):
        auv = [uvc[0]] * 2 + [uvc[1]] * 4
        prm = {"ay": [ay] * 6, "auv": auv, "keep": z}
        res[f"{rule}_ay{ay}_uv{uvc}"] = metrics(denoise(noisy, a_g, b_g, wb, rule, prm), clean, wb)
    for keep in ((0.35, 0.15, 0, 0, 0, 0),):
        prm = {"ay": [1.0] * 6, "auv": [1.5] * 2 + [3] * 4, "keep": list(keep)}
        res["wiener2g_grain"] = metrics(denoise(noisy, a_g, b_g, wb, "wiener2g", prm), clean, wb)
    for k, v in res.items():
        print(f"{k:34s} " + " ".join(f"{m}={x:.4f}" for m, x in v.items()))


# ------------------------------------------------------- steps 1-3 (2026-11-03)
from scipy.ndimage import maximum_filter as _maxf, median_filter as _medf, zoom as _zoom

_RING = np.ones((3, 3, 1), bool); _RING[1, 1, 0] = False


def impulse_filter(img, A, B, T=3.0, halo=1.0):
    """Step 3: isolated single-pixel specks (hot/impulse pixels survive demosaic).
    Score per channel = (v - max of 8 neighbours) / sigma(median), averaged over
    RGB. Impulse if score > T and the neighbours show no PSF halo
    ((max8 - med8)/sigma averaged < halo): real point lights spread."""
    med = _medf(img, footprint=_RING, mode="nearest")
    mx = _maxf(img, footprint=_RING, mode="nearest")
    sig = np.sqrt(np.maximum(A * np.maximum(med, 0) + B, 1e-12))
    s = ((img - mx) / sig).mean(-1)
    spread = ((mx - med) / sig).mean(-1)
    hit = (s > T) & (spread < halo)
    out = img.copy(); out[hit] = med[hit]
    return out.astype(F32), int(hit.sum())


# Noise variance after the app's RCD relative to the clipped raw samples
# (flat-field, RawTherapee RCD oracle, 2026-11-03): mean is preserved.
RCD_KAPPA = (0.82, 0.71, 0.84)


def exact_inverse_luts(a, b, p, A, B, n=1024, nodes=48, kappa=RCD_KAPPA, target="truth"):
    """Step 2: exact unbiased inverse (Makitalo-Foi idea) of the whole shadow
    chain: raw z ~ N(x, A x + B) clipped at 0 (photo_rcd_input), RCD (mean kept,
    variance x kappa_c), then the generalized-Anscombe VST incl. max(y+b,0).
    Per channel y = E[f(RCD out)] for true scene level x, inverted onto a
    uniform y grid. Recovers x (unclipped truth). Returns (y0, dy, table[n])."""
    from scipy.stats import norm as _n
    # Fixed quadrature (C++ host mirrors it): z in [-6, 6] step 0.125, normal pdf.
    gh_x = np.linspace(-6.0, 6.0, 97); gh_w = np.exp(-0.5 * gh_x * gh_x); gh_w = gh_w / gh_w.sum()
    xs = np.concatenate([np.linspace(0, 0.02, 1500), np.linspace(0.02, 1.5, 1500)[1:]])
    sd = np.sqrt(np.maximum(A * xs + B, 1e-14)); t = xs / sd
    m = xs * _n.cdf(t) + sd * _n.pdf(t)                         # E[max(z,0)]
    m2 = (xs * xs + sd * sd) * _n.cdf(t) + xs * sd * _n.pdf(t)  # E[max(z,0)^2]
    v = np.maximum(m2 - m * m, 1e-14)
    luts = []
    for c in range(3):
        pc = float(p[c]); e = 1 - pc / 2; sc = 2 / ((2 - pc) * np.sqrt(a))
        y = m[:, None] + np.sqrt(kappa[c] * v)[:, None] * gh_x[None, :]
        F = (np.maximum(np.maximum(y, 0) + b, 0) ** e * sc) @ gh_w
        F = np.maximum.accumulate(F)
        y0, y1 = F[0], F[-1]
        yg = np.linspace(y0, y1, n)
        # target "input": the denoiser preserves the local mean of its input
        # (robust: does not try to undo the raw clip, which is sensitive to the
        # read-noise floor); "truth": recover the unclipped scene level.
        tgt = m if target == "input" else xs
        luts.append((y0, (y1 - y0) / (n - 1), np.interp(yg, F, tgt)))
    return luts


def backtransform_exact(yuv, to_rgb_f, luts, a, p, b, gate=None):
    """Backtransform with the exact unbiased inverse; algebraic above the table."""
    t = yuv @ to_rgb_f.T
    out = np.empty_like(t)
    for c in range(3):
        y0, dy, tab = luts[c]
        pc = float(p[c]); e = 1 - pc / 2
        alg = (np.maximum(t[..., c], 0) * (2 - pc) * np.sqrt(a) / 2) ** (1 / e) - b
        u = (t[..., c] - y0) / dy
        i = np.clip(np.floor(u), 0, len(tab) - 2).astype(int); fr = u - i
        lut = tab[i] * (1 - fr) + tab[i + 1] * fr
        slope0 = (tab[1] - tab[0])                              # extrapolate below y0
        lut = np.where(u < 0, tab[0] + u * slope0, lut)
        ex = np.where(u > len(tab) - 1, alg, lut)
        if gate is not None:
            # Fade the correction out where clipping dominates (x < ~2 sigma):
            # there the clip bias depends on the shaky read-noise floor.
            xa = np.maximum(alg, 0)
            r = xa / np.sqrt(np.maximum(gate[0] * xa + gate[1], 1e-14))
            w = np.clip((r - 1.5) / 1.5, 0, 1); w = w * w * (3 - 2 * w)
            ex = alg + w * (ex - alg)
        out[..., c] = ex
    return out.astype(F32)


def lowres_chroma(yuv, n2y, k=4, r=4, eps=1.0, weight=1.0, want_y=False):
    """Step 1: luma-guided chroma at 1/k resolution (guided filter, radius r),
    correction added bilinearly. Targets 3-50 px chroma mottle the per-band
    shrink leaves. n2y: luma noise variance of a k x k mean (regulariser).
    Same semantics as denoise_img_lowres_chroma.comp: global grid of
    ceil(W/k) blocks (partial edge blocks average in-frame pixels), boxes clamp
    to the frame's blocks, bilinear with block centres at k*i + (k-1)/2."""
    H, W, _ = yuv.shape; h, w = -(-H // k), -(-W // k)
    pad = np.pad(yuv.astype(np.float64), ((0, h * k - H), (0, w * k - W), (0, 0)))
    cnt = np.pad(np.ones((H, W)), ((0, h * k - H), (0, w * k - W)))
    lo = pad.reshape(h, k, w, k, 3).sum((1, 3)) / cnt.reshape(h, k, w, k).sum((1, 3))[..., None]
    box = lambda x: uniform_filter(x, size=2 * r + 1, mode="nearest")
    Y = lo[..., 0]; my = box(Y); vy = np.maximum(box(Y * Y) - my * my, 0)
    d = np.zeros((h, w, 2))
    for i, c in enumerate((1, 2)):
        C = lo[..., c]; mc = box(C); cov = box(Y * C) - my * mc
        aa = cov / (vy + eps * n2y); bb = mc - aa * my
        d[..., i] = box(aa) * Y + box(bb) - C
    uy = (np.arange(H) - (k - 1) / 2) / k; ux = (np.arange(W) - (k - 1) / 2) / k
    y0 = np.floor(uy).astype(int); x0 = np.floor(ux).astype(int)
    fy = (uy - y0)[:, None, None]; fx = (ux - x0)[None, :, None]
    cy0, cy1 = np.clip(y0, 0, h - 1), np.clip(y0 + 1, 0, h - 1)
    cx0, cx1 = np.clip(x0, 0, w - 1), np.clip(x0 + 1, 0, w - 1)
    up = ((d[cy0][:, cx0] * (1 - fx) + d[cy0][:, cx1] * fx) * (1 - fy)
          + (d[cy1][:, cx0] * (1 - fx) + d[cy1][:, cx1] * fx) * fy)
    res = yuv.astype(np.float64).copy(); res[..., 1:] += weight * up
    if want_y:  # bilinear 1/4-grid luma (step 5 neutral colour reference)
        L = Y[..., None]
        yup = ((L[cy0][:, cx0] * (1 - fx) + L[cy0][:, cx1] * fx) * (1 - fy)
               + (L[cy1][:, cx0] * (1 - fx) + L[cy1][:, cx1] * fx) * fy)[..., 0]
        return res.astype(F32), yup.astype(F32)
    return res.astype(F32)


_RING8 = [(dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx]


def despeckle_y(yuv, sig, T=2.5, H=1.5):
    """Step 5b: isolated luma specks in the VST domain (noise std sig): |Y -
    median8| > T sig while the 8 neighbours agree (median |n - med8| < H sig).
    Two-sided; mirrors denoise_img_backtransform_buffer_rl.comp."""
    Y = yuv[..., 0]
    P = np.pad(Y, 1, mode="edge"); h, w = Y.shape
    ring = np.stack([P[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy, dx in _RING8], 0)
    med = np.sort(ring, 0)[4]                     # rank 4 of 8 (= scipy / shader)
    mad = np.sort(np.abs(ring - med), 0)[4]
    hit = (np.abs(Y - med) > T * sig) & (mad < H * sig)
    out = yuv.copy(); out[..., 0] = np.where(hit, med, Y)
    return out.astype(F32), int(hit.sum())


def noise_gates(a, b, median_x):
    """Noise-dependent switches (host mirrors this): relative noise at the
    median exposure -> low-res chroma weight (0.04..0.10 smoothstep) and the
    impulse threshold (4 sigma at rel >= 0.10, else off)."""
    x = max(median_x, 1e-3)
    rel = np.sqrt(max(a * x + b, 0)) / x
    t = np.clip((rel - 0.04) / 0.06, 0, 1)
    return float(t * t * (3 - 2 * t)), (4.0 if rel >= 0.10 else 0.0), float(rel)


def denoise2(img, a_g, b_g, wb, prm, steps=(1, 2, 3), scales=7, strength=1.0):
    """RawLens production rule (guided, cheap) + steps 1-3."""
    wb = np.asarray(wb, F32)
    p = np.maximum(1.0 + 0.1 * np.log(1 / wb), 0).astype(F32)
    to_yuv, to_rgb = O.setup_conversion_matrices(wb.copy())
    f = F32(strength * 2.5)
    nimp = 0
    if 3 in steps:
        if prm.get("imp_T", 4.0) > 0:
            img, nimp = impulse_filter(img, a_g, b_g, prm.get("imp_T", 4.0), prm.get("imp_halo", 1.0))
    buf = O.precondition_y0u0v0(img, F32(a_g), p, F32(b_g), (to_yuv / f).astype(F32))
    out = np.zeros_like(buf)
    for s in range(scales):
        sb2 = F32(O.VARF ** s) ** 2
        coarse = np.empty_like(buf); det = np.empty_like(buf)
        O.dn_decompose(coarse, np.ascontiguousarray(buf), det, s, F32(1 / sb2))
        out += shrink("guided", det, sb2, s, prm)
        buf = coarse
    out += buf
    rowy = (to_yuv / f)[0]
    ysm = None
    if 1 in steps:
        out, ysm = lowres_chroma(out, float(rowy @ rowy) / 16.0, eps=prm.get("lr_eps", 1.0),
                                 weight=prm.get("lr_weight", 1.0), want_y=True)
    Yp = out[..., 0]; P_ = np.pad(Yp, 1, mode="edge"); h_, w_ = Yp.shape
    med8 = np.sort(np.stack([P_[1 + dy:1 + dy + h_, 1 + dx:1 + dx + w_] for dy, dx in _RING8], 0), 0)[4]
    if prm.get("despeck_T", 0) > 0:
        out, nds = despeckle_y(out, float(np.sqrt(rowy @ rowy)), prm["despeck_T"], prm.get("despeck_H", 1.5))
        nimp += nds
    if 2 in steps:
        luts = exact_inverse_luts(float(a_g), float(b_g), p, float(a_g), float(b_g),
                                  target=prm.get("inv_target", "truth"))
        gate = (float(a_g), float(b_g)) if prm.get("inv_gate", True) else None
        bt = lambda q: backtransform_exact(q, (to_rgb * f).astype(F32), luts, float(a_g), p, float(b_g), gate)
        res = bt(out)
        nw = prm.get("neutral", 0.0)
        if nw > 0 and ysm is not None:
            # Step 5a: colour from the pixel's chroma at the smooth (1/4-grid)
            # luma, brightness from the pixel. A luma dip through the per-channel
            # inverse VST would otherwise empty the weakest channel first.
            ref = out.copy()
            if prm.get("neutral_ref", "med8") == "med8":
                ref[..., 0] = med8
            else:
                ref[..., 0] = ysm
            rc = bt(ref)
            W3 = np.array([0.25, 0.5, 0.25], F32) * (wb if prm.get("neutral_wb", False) else 1)
            lp = (res * W3).sum(-1, keepdims=True); lr = (rc * W3).sum(-1, keepdims=True)
            k = np.clip(lp / np.maximum(lr, 1e-7), 0, 4)
            neu = np.where(lr > 1e-7, rc * k, res)
            if prm.get("neutral_dark", True):  # only luma dips empty a channel
                t = np.clip(1 - k, 0, 1) * 4; nw = nw * np.minimum(t, 1)
            res = (res + nw * (neu - res)).astype(F32)
        return res, nimp
    return O.backtransform_y0u0v0(out, F32(a_g), p, F32(b_g), F32(0), (to_rgb * f).astype(F32)), nimp
