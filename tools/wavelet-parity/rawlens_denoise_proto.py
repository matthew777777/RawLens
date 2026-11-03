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
    if rule in ("wiener2", "wiener2g"):
        # closed-form 2nd stage: energy of the stage-1 estimate
        # E[(g d)^2] ~= s2^2/(s2+n2)  -> gain from a cleaner signal estimate
        s2 = (s2 * s2 / (s2 + n2 + 1e-12)).astype(F32)
    if rule == "wiener2g":
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
