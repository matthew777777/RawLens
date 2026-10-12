#!/usr/bin/env python3
"""Faithful port of darktable denoiseprofile.c wavelets path (new VST, Y0U0V0).

Reference sources (cloned to ./tmp/upstream/darktable):
  src/iop/denoiseprofile.c  process_wavelets, precondition_Y0U0V0,
                            backtransform_Y0U0V0, variance_stabilizing_xform,
                            set_up_conversion_matrices, compute_wb_factors
  src/common/eaw.c          eaw_dn_decompose (edge-aware a-trous, B3 spline),
                            eaw_synthesize/accumulate (soft threshold)

Defaults mirror DT: strength=1, shadows=1, bias=0,
wavelet_color_mode=Y0U0V0 (compensate_strength=2.5), use_new_vst=TRUE,
wb_adaptive_anscombe=TRUE.

Frozen v1 curve (from experiment v3 on device DNGs): FORCE_Y=0.25,
FORCE_UV=0.75 — chroma-first split that dominated uniform 0.5 on the
texture axis at equal-or-better grain (see tmp/denoise_visuals/v3/).

PRECISION: everything is float32 end-to-end (F32). This is deliberate:
the oracle is the parity reference for float32 Vulkan compute, so stage
boundaries cast to float32 to match GPU arithmetic ordering as closely
as a NumPy port allows. Residual C-vs-GPU differences are covered by
the parity thresholds in verify_denoise_parity.py, not by float64.

DNG mapping (Camera2 SENSOR_NOISE_PROFILE == DT a,b in form V=S*x+O):
  raw x_raw in [black, white]; normalized x=(x_raw-black)/range gives
  a_norm = S/range, b_norm = (S*black+O)/range^2.
  WB coeffs from AsShotNeutral: wb_c = neutral_g / neutral_c.

Run with: uv run --no-project --with numpy --with numba --with tifffile --with pillow
"""
from __future__ import annotations

import numpy as np
import numba

F32 = np.float32
BANDS = 7
P_FULCRUM = F32(0.05)
# Frozen v1 force table (experiment v3): gentle-Y / firm-UV split.
FORCE_Y = F32(0.25)
FORCE_UV = F32(0.75)
B3 = np.array([1, 4, 6, 4, 1], dtype=F32) / F32(16.0)  # outer product /256 via 2 passes
VARF = F32(np.sqrt(2.0 + 2.0 * 16.0 + 36.0) / 16.0)  # ~0.5, denoiseprofile.c:1563


# ---------------------------------------------------------------- matrices
def base_y0u0v0() -> np.ndarray:
    return np.array(
        [[1 / 3, 1 / 3, 1 / 3],
         [0.5, 0.0, -0.5],
         [0.25, -0.5, 0.25]],
        dtype=F32,
    )


def setup_conversion_matrices(wb: np.ndarray):
    """Port of set_up_conversion_matrices (WB-adaptive rows). F32."""
    wb = np.asarray(wb, dtype=F32)
    m = base_y0u0v0()
    sum_invwb = F32((1 / wb[0] + 1 / wb[1] + 1 / wb[2]) * np.sqrt(F32(3.0)))
    m[0, 0] = sum_invwb / wb[0]
    m[0, 1] = sum_invwb / wb[1]
    m[0, 2] = sum_invwb / wb[2]
    std_u0 = F32(np.sqrt(0.25 * wb[0] ** 2 + 0.25 * wb[2] ** 2))
    std_v0 = F32(np.sqrt(0.0625 * wb[0] ** 2 + 0.25 * wb[1] ** 2 + 0.0625 * wb[2] ** 2))
    m[1, :] /= std_u0
    m[2, :] /= std_v0
    try:
        to_rgb = np.linalg.inv(m).astype(F32)
    except np.linalg.LinAlgError:
        std_y0 = F32(np.sqrt((wb[0] ** 2 + wb[1] ** 2 + wb[2] ** 2) / 9.0))
        m[0, :] = F32(1.0 / (3.0 * std_y0))
        to_rgb = np.linalg.inv(m).astype(F32)
    return m.astype(F32), to_rgb


def max_scale_for(w: int, h: int) -> int:
    """Port of the max_scale loop in process_wavelets (in_scale=1)."""
    supp0 = min(2 * (2 << (BANDS - 1)) + 1, max(w, h) * 0.2)
    i0 = np.log2((supp0 - 1.0) * 0.5)
    ms = 0
    while ms < BANDS:
        supp = 2 * (2 << ms) + 1
        i_in = np.log2((supp - 1) * 0.5) - 1.0
        if 1.0 - (i_in + 0.5) / i0 < 0.0:
            break
        ms += 1
    return max(ms, 1)


# ---------------------------------------------------------------- VST
def precondition_y0u0v0(img: np.ndarray, a: float, p: np.ndarray, b: float,
                        to_yuv: np.ndarray) -> np.ndarray:
    img = np.asarray(img, dtype=F32)
    p = np.asarray(p, dtype=F32)
    a, b = F32(a), F32(b)
    expon = (F32(1.0) - p / F32(2.0)).astype(F32)
    scale = (F32(2.0) / ((F32(2.0) - p) * np.sqrt(a))).astype(F32)
    tmp = np.maximum(img + b, F32(0.0)) ** expon[None, None, :] * scale[None, None, :]
    return (tmp @ to_yuv.T).astype(F32)


def backtransform_y0u0v0(yuv: np.ndarray, a: float, p: np.ndarray, b: float,
                         bias: float, to_rgb: np.ndarray) -> np.ndarray:
    yuv = np.asarray(yuv, dtype=F32)
    p = np.asarray(p, dtype=F32)
    a, b, bias = F32(a), F32(b), F32(bias)
    rgb = (yuv @ to_rgb.T).astype(F32)
    expon = (F32(1.0) / (F32(1.0) - p / F32(2.0))).astype(F32)
    scale = ((np.sqrt(a) * (F32(2.0) - p)) / F32(4.0)).astype(F32)
    x = np.maximum(rgb, F32(0.0))
    z1 = (x + np.sqrt(np.maximum(x * x + bias, F32(0.0)))) * scale[None, None, :]
    return (z1 ** expon[None, None, :] - b).astype(F32)


# ---------------------------------------------------------------- edge-aware a-trous
@numba.njit(fastmath=True)
def dn_decompose(coarse: np.ndarray, fine: np.ndarray, detail: np.ndarray,
                 scale: int, inv_sigma2: float):
    """Port of eaw_dn_decompose: B3-spline a-trous, stride 2**scale,
    clamped edges, bilateral weight 2**(-max(0, dist*inv_sigma2*0.02 - 9)).
    F32: callers must pass float32 arrays; all temporaries are float32."""
    h, w, _ = fine.shape
    mult = 1 << scale
    f1 = np.empty(5, dtype=numba.float32)
    f1[0], f1[1], f1[2], f1[3], f1[4] = 0.0625, 0.25, 0.375, 0.25, 0.0625
    sum_sq = np.zeros(3, dtype=numba.float32)
    for y in range(h):
        for x in range(w):
            wgt = np.zeros(3, dtype=numba.float32)
            acc = np.zeros(3, dtype=numba.float32)
            for jj in range(5):
                yy = y + mult * (jj - 2)
                if yy < 0:
                    yy = 0
                elif yy >= h:
                    yy = h - 1
                for ii in range(5):
                    xx = x + mult * (ii - 2)
                    if xx < 0:
                        xx = 0
                    elif xx >= w:
                        xx = w - 1
                    f = f1[jj] * f1[ii]
                    d0 = fine[y, x, 0] - fine[yy, xx, 0]
                    d1 = fine[y, x, 1] - fine[yy, xx, 1]
                    d2 = fine[y, x, 2] - fine[yy, xx, 2]
                    dot = (d0 * d0 + d1 * d1 + d2 * d2) * inv_sigma2
                    e = dot * numba.float32(0.02) - numba.float32(9.0)
                    # Deep-tail sparsification: must match denoise_img_atrous.comp
                    # exactly (bit-identical zero region tames cross-ALU
                    # exp divergence; impact < 1e-6 relative).
                    if e > 20.0:
                        wp = numba.float32(0.0)
                    else:
                        wp = numba.float32(2.0) ** (-e) if e > 0.0 else numba.float32(1.0)
                    ww = f * wp
                    for c in range(3):
                        wgt[c] += ww
                        acc[c] += ww * fine[yy, xx, c]
            for c in range(3):
                v = acc[c] / wgt[c]
                coarse[y, x, c] = v
                dd = fine[y, x, c] - v
                detail[y, x, c] = dd
                sum_sq[c] += dd * dd
    return sum_sq


def process_wavelets_y0u0v0(img: np.ndarray, a_green: float, b_green: float,
                            wb: np.ndarray, strength: float = 1.0,
                            shadows: float = 1.0, bias: float = 0.0,
                            force: float | list = FORCE_Y,
                            force_uv: float | list = FORCE_UV,
                            max_scale: int | None = None):
    """Full wavelets denoise on pre-WB linear RGB (F32 in/out). Returns (denoised, scales).

    force / force_uv mirror DT's per-band Y0 / U0V0 curves. Each may be a
    scalar or a per-scale list (scale 0 = finest). Defaults are the frozen
    v1 table (FORCE_Y=0.25, FORCE_UV=0.75). Pass force_uv=... explicitly to
    override (None also falls back to force, mirroring DT's single-curve UI).
    """
    img = np.ascontiguousarray(img, dtype=F32)
    wb = np.asarray(wb, dtype=F32)
    strength, shadows, bias = F32(strength), F32(shadows), F32(bias)
    a_green, b_green = F32(a_green), F32(b_green)
    h, w, _ = img.shape
    in_scale = F32(1.0)
    if max_scale is None:
        max_scale = max_scale_for(w, h)

    def band_force(f, scale: int) -> float:
        return F32(f[scale]) if isinstance(f, (list, tuple)) else F32(f)

    if force_uv is None:
        force_uv = force
    p = np.maximum(shadows + F32(0.1) * np.log(in_scale / wb), F32(0.0)).astype(F32)
    compensate_p = F32(P_FULCRUM / (P_FULCRUM ** shadows))
    a = F32(a_green * compensate_p)
    b = b_green

    to_yuv, to_rgb = setup_conversion_matrices(wb.copy())
    comp_strength = F32(2.5)  # Y0U0V0 mode
    f = F32(strength * comp_strength * in_scale)
    to_yuv = (to_yuv / f).astype(F32)
    to_rgb = (to_rgb * f).astype(F32)
    wb_s = (wb * f).astype(F32)
    _ = wb_s  # wb_s folds into to_yuv/to_rgb scaling; kept for formula parity

    precond = precondition_y0u0v0(img, a, p, b, to_yuv)
    out = np.zeros_like(precond)
    buf1 = precond
    npixels = w * h

    for scale in range(max_scale):
        sigma_band = F32(VARF ** scale)
        sb2 = F32(sigma_band ** 2)
        coarse = np.empty_like(buf1)
        detail = np.empty_like(buf1)
        sum_y2 = dn_decompose(coarse, np.ascontiguousarray(buf1),
                              detail, scale, F32(F32(1.0) / sb2))
        var_y = (sum_y2 / F32(npixels - 1.0)).astype(F32)
        std_x = np.sqrt(np.maximum(F32(1e-6), var_y - sb2)).astype(F32)
        # DT: adjt = 8 * force^2 * 4, per Y0 / U0V0 curves
        fy, fuv = band_force(force, scale), band_force(force_uv, scale)
        adjt = np.array([F32(8.0) * fy * fy * F32(4.0),
                         F32(8.0) * fuv * fuv * F32(4.0),
                         F32(8.0) * fuv * fuv * F32(4.0)], dtype=F32)
        thrs = (adjt * sb2 / std_x).astype(F32)  # BayesShrink, per channel
        # soft threshold (accumulate, boost=1)
        amt = (np.maximum(detail - thrs[None, None, :], F32(0.0)) +
               np.minimum(detail + thrs[None, None, :], F32(0.0))).astype(F32)
        out = (out + amt).astype(F32)
        buf1 = coarse
    out = (out + buf1).astype(F32)  # residue
    denoised = backtransform_y0u0v0(out, a, p, b, F32(bias - F32(0.5) * np.log(in_scale)),
                                    to_rgb)
    return denoised.astype(F32), max_scale


def compute_thresholds(img: np.ndarray, a_green: float, b_green: float,
                       wb: np.ndarray, strength: float = 1.0,
                       shadows: float = 1.0,
                       force: float | list = FORCE_Y,
                       force_uv: float | list = FORCE_UV,
                       max_scale: int | None = None):
    """Run the precondition + decompose loop only; return per-scale (thrs[3], sums).

    Used by verify_denoise_parity.py so the GPU runner consumes the oracle's
    own float32 BayesShrink thresholds (passed via --thrs). This keeps every
    DT formula in one place; the C++ binary stays mechanical.
    """
    img = np.ascontiguousarray(img, dtype=F32)
    wb = np.asarray(wb, dtype=F32)
    strength, shadows = F32(strength), F32(shadows)
    a_green, b_green = F32(a_green), F32(b_green)
    h, w, _ = img.shape
    in_scale = F32(1.0)
    if max_scale is None:
        max_scale = max_scale_for(w, h)

    def band_force(f, scale: int) -> float:
        return F32(f[scale]) if isinstance(f, (list, tuple)) else F32(f)

    if force_uv is None:
        force_uv = force
    p = np.maximum(shadows + F32(0.1) * np.log(in_scale / wb), F32(0.0)).astype(F32)
    compensate_p = F32(P_FULCRUM / (P_FULCRUM ** shadows))
    a = F32(a_green * compensate_p)
    b = b_green
    to_yuv, _ = setup_conversion_matrices(wb.copy())
    f = F32(strength * F32(2.5) * in_scale)
    to_yuv = (to_yuv / f).astype(F32)
    buf1 = precondition_y0u0v0(img, a, p, b, to_yuv)
    npixels = w * h
    out = []
    for scale in range(max_scale):
        sigma_band = F32(VARF ** scale)
        sb2 = F32(sigma_band ** 2)
        coarse = np.empty_like(buf1)
        detail = np.empty_like(buf1)
        sum_y2 = dn_decompose(coarse, np.ascontiguousarray(buf1),
                              detail, scale, F32(F32(1.0) / sb2))
        var_y = (sum_y2 / F32(npixels - 1.0)).astype(F32)
        std_x = np.sqrt(np.maximum(F32(1e-6), var_y - sb2)).astype(F32)
        fy, fuv = band_force(force, scale), band_force(force_uv, scale)
        adjt = np.array([F32(8.0) * fy * fy * F32(4.0),
                         F32(8.0) * fuv * fuv * F32(4.0),
                         F32(8.0) * fuv * fuv * F32(4.0)], dtype=F32)
        thrs = (adjt * sb2 / std_x).astype(F32)
        out.append(([float(v) for v in thrs], [float(v) for v in sum_y2]))
        buf1 = coarse
    return out


# ---------------------------------------------------------------- DNG helpers
def load_dng_linear(path: str):
    """Returns dict(normalized raw, linear RGB bilinear, wb, a_norm, b_norm, meta)."""
    import tifffile
    with tifffile.TiffFile(path) as t:
        page = t.pages[0]
        raw = page.asarray().astype(F32)
        tags = {tg.name: tg.value for tg in page.tags.values()}
    black = np.mean(
        [tags["BlackLevel"][i] / tags["BlackLevel"][i + 1]
         for i in range(0, len(tags["BlackLevel"]), 2)]
    ) if isinstance(tags["BlackLevel"], tuple) else float(tags["BlackLevel"])
    white = float(tags["WhiteLevel"])
    if isinstance(white, tuple):
        white = white[0]
    asn = tags["AsShotNeutral"]  # flat (num, den) pairs
    neutral = np.array([asn[i] / asn[i + 1] for i in range(0, len(asn), 2)],
                       dtype=F32)
    wb = np.array([neutral[1] / neutral[0], 1.0, neutral[1] / neutral[2]],
                  dtype=F32)
    npf = tags["NoiseProfile"]
    S = float(npf[0])
    O = float(npf[1])
    rng = white - black
    norm = np.clip((raw - black) / rng, 0.0, 1.0).astype(F32)
    a_norm = F32(S / rng)
    b_norm = F32((S * black + O) / (rng ** 2))
    # bilinear RGGB demosaic
    r = norm[0::2, 0::2]
    gr = norm[0::2, 1::2]
    gl = norm[1::2, 0::2]
    bch = norm[1::2, 1::2]
    g = (gr + gl) / 2.0
    H, W = r.shape
    rgb = np.empty((H, W, 3), dtype=F32)
    rgb[:, :, 0] = r
    rgb[:, :, 1] = g
    rgb[:, :, 2] = bch
    # upscale chroma-naive: bilinear upsample each to full via repeat+average
    full = np.zeros((H * 2, W * 2, 3), dtype=F32)
    full[0::2, 0::2, 0] = r
    full[0::2, 0::2, 1] = g
    full[0::2, 0::2, 2] = bch
    for c in range(3):
        ch = full[:, :, c]
        ch[0::2, 1::2] = (np.roll(ch, -1, 1) + np.roll(ch, 1, 1))[0::2, 1::2] / 2
        ch[1::2, :] = (np.roll(ch, -1, 0) + np.roll(ch, 1, 0))[1::2, :] / 2
        full[:, :, c] = ch
    meta = dict(path=path, black=black, white=white, wb=wb.tolist(),
                S=S, O=O, a_norm=a_norm, b_norm=b_norm,
                shape=list(raw.shape))
    return norm, full, wb, a_norm, b_norm, meta
