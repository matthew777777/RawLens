#!/usr/bin/env python3
"""Independent NumPy oracle of the Jamy-L SR core for parity proofs.

Transcribes the reference pipeline stages from
https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution
(commit 07bc3f2) into float32 NumPy, stage by stage, with no RawLens code:

  tuning      params.update_snr_config + utils_image.estimate_image_snr
  guide       robustness.cuda_compute_guide_image (Alg. 7)
  stats       robustness.cuda_compute_local_stats (Alg. 8, unfloored)
  warp        robustness.cuda_warp_dogson (Dogson biquadratic, half-away centers)
  d/sigma     robustness.cuda_compute_d_sigma (analytic path, no LUT)
  S           robustness.cuda_compute_s (raw-unit spread vs Mt)
  threshold   robustness.cuda_robustness_threshold (Alg. 6 tail)
  local_min   robustness.cuda_compute_local_min (Alg. 9)
  GAT         utils_image.cuda_GAT + cuda_decimate_to_grey
  kernels     kernels.cuda_estimate_kernel + linalg eigen (Alg. 5, linear law)
  merge       merge.accumulate (Alg. 4, nearest flow + nearest r)
  divide      utils.cuda_divide

Generates synthetic static bursts (analytic textured scene, per-phase noise,
known shifts) and writes inputs + golden stage outputs as little-endian raw
binaries plus a JSON manifest. The committed JVM test JamyCoreParityTest runs
the RawLens CPU chain on the same inputs and compares stage by stage.

No sampling deviation remains: warp/merge flow lookup and merge robustness
fetch are reference-verbatim nearest on both sides, so every stage must agree
to float-noise levels (~1e-6) on the full field.

Usage: python3 jamy_parity_oracle.py --out <dir>
"""
import argparse
import json
import struct
from pathlib import Path

import numpy as np

F32 = np.float32

# ---------------------------------------------------------------------------
# Scene + fixtures
# ---------------------------------------------------------------------------

RAW_W, RAW_H = 128, 96
BLACK_EXIF = (64.0, 65.0, 66.0, 67.0)  # DNG order R, G1, B, G2
WHITE = 4000.0
# DNG 6-coefficient profile R,G,B pairs (normalized domain S*v + O).
DNG_PROFILE = (0.018, 0.0016, 0.011, 0.0011, 0.024, 0.0021)
TILE_RAW = 16  # quad tile 8
T_S1_S2 = (0.12, 2.0, 12.0)
MT_RAW = 0.8
SNR_DB = 18.0


def snr_lerp(snr, low, high):
    c = min(max(snr, 6.0), 30.0)
    return low + (c - 6.0) / 24.0 * (high - low)


def tuning_for_snr(snr):
    return {
        "k_detail": snr_lerp(snr, 0.33, 0.25),
        "k_denoise": snr_lerp(snr, 5.0, 3.0),
        "d_th": snr_lerp(snr, 0.81, 0.71),
        "d_tr": snr_lerp(snr, 1.24, 1.0),
        "k_stretch": 4.0,
        "k_shrink": 2.0,
    }


def rgbg_from_dng(profile):
    s_r, o_r, s_g, o_g, s_b, o_b = profile
    return (s_r, s_g, s_b, s_g), (o_r, o_g, o_b, o_g)


def base_scene(y, x):
    """Analytic textured scene in [0.05, 0.85], vectorized over float grids."""
    v = 0.45
    v += 0.18 * np.sin(2 * np.pi * (x * 0.061 + y * 0.023) + 1.0)
    v += 0.12 * np.sin(2 * np.pi * (x * 0.023 - y * 0.079) + 2.0)
    v += 0.07 * np.sin(2 * np.pi * (x * 0.15 + y * 0.11) + 0.5)
    v += 0.10 / (1.0 + np.exp(-((x - 0.62 * RAW_W) * 0.9 + (y - 0.5 * RAW_H) * 0.35)))
    v += 0.06 * (x / RAW_W)
    return np.clip(v, 0.05, 0.85)


def make_burst(seed=1234):
    """Static burst: ref + 2 shifted frames, RGGB mosaic, per-phase noise."""
    rng = np.random.default_rng(seed)
    alpha, beta = rgbg_from_dng(DNG_PROFILE)
    # Spatial RGGB alpha/beta per raw pixel.
    a_map = np.empty((RAW_H, RAW_W))
    b_map = np.empty((RAW_H, RAW_W))
    a_map[0::2, 0::2] = alpha[0]
    a_map[0::2, 1::2] = alpha[1]
    a_map[1::2, 0::2] = alpha[3]
    a_map[1::2, 1::2] = alpha[2]
    b_map[0::2, 0::2] = beta[0]
    b_map[0::2, 1::2] = beta[1]
    b_map[1::2, 0::2] = beta[3]
    b_map[1::2, 1::2] = beta[2]
    # Mild chroma so channels are not interchangeable.
    chroma = np.ones((RAW_H, RAW_W))
    chroma[0::2, 0::2] = 1.02
    chroma[1::2, 1::2] = 0.96
    bl, wh = BLACK_EXIF, WHITE
    black_spatial = np.empty((RAW_H, RAW_W))
    black_spatial[0::2, 0::2] = bl[0]
    black_spatial[0::2, 1::2] = bl[1]
    black_spatial[1::2, 0::2] = bl[3]
    black_spatial[1::2, 1::2] = bl[2]
    shifts = [(0.0, 0.0), (1.7, -0.6), (-0.9, 2.3)]
    yy, xx = np.mgrid[0:RAW_H, 0:RAW_W].astype(np.float64)
    codes = []
    for dx, dy in shifts:
        scene = base_scene(yy - dy, xx - dx) * chroma
        noise = rng.standard_normal((RAW_H, RAW_W)) * np.sqrt(a_map * scene + b_map)
        v = np.clip(scene + noise, -0.02, 1.02)
        code = np.clip(np.round(v * (wh - black_spatial) + black_spatial), 0, 65535).astype(np.uint16)
        codes.append(code)
    return codes, shifts, a_map, b_map, black_spatial


# ---------------------------------------------------------------------------
# Reference stages (float32, transcribed op-for-op where it matters)
# ---------------------------------------------------------------------------

def normalize(codes, black_spatial):
    ref = codes.astype(np.float64)
    v = (ref - black_spatial) / (WHITE - black_spatial)
    return v.astype(F32)


def estimate_snr_sequential(v64):
    """utils_image.estimate_image_snr with sequential double accumulation.

    Takes the UN-narrowed float64 normalized frame (the JVM estimator divides
    in double straight from codes), so the comparison is bitwise.
    """
    alpha, beta = rgbg_from_dng(DNG_PROFILE)
    v = v64
    top = 0.0
    bot = 0.0
    # Same per-sample order as RawSrTuning.fromReference (row-major full
    # frame); bitwise comparison target for the JVM estimator.
    h, w = v.shape
    for y in range(h):
        for x in range(w):
            vv = float(v[y, x])
            if 0.0 < vv < 1.0:
                ph = (y & 1, x & 1)
                if ph == (0, 0):
                    a, o = alpha[0], beta[0]
                elif ph == (0, 1):
                    a, o = alpha[1], beta[1]
                elif ph == (1, 1):
                    a, o = alpha[2], beta[2]
                else:
                    a, o = alpha[3], beta[3]
                top += vv * vv
                bot += a * vv + o
    return float(np.sqrt(top / bot)), float(20.0 * np.log10(np.sqrt(top / bot)))


def gat(v):
    """utils_image.cuda_GAT in float32 (RGGB spatial mapping)."""
    alpha, beta = rgbg_from_dng(DNG_PROFILE)
    out = np.empty_like(v)
    quads = [((0, 0), 0), ((0, 1), 1), ((1, 1), 2), ((1, 0), 3)]
    for (py, px), i in quads:
        a = F32(alpha[i])
        o = F32(beta[i])
        x = v[py::2, px::2]
        vst = np.maximum(F32(a * x + F32(0.375) * F32(a * a) + o), F32(0))
        out[py::2, px::2] = F32(F32(2.0 / a) * np.sqrt(vst))
    return out


def decimate(g):
    """utils_image.cuda_decimate_to_grey: sequential fp32 2x2 mean."""
    h, w = g.shape
    out = np.empty((h // 2, w // 2), dtype=F32)
    for y in range(h // 2):
        for x in range(w // 2):
            c = F32(g[2 * y, 2 * x] + g[2 * y, 2 * x + 1])
            c = F32(c + g[2 * y + 1, 2 * x])
            c = F32(c + g[2 * y + 1, 2 * x + 1])
            out[y, x] = F32(c / F32(4))
    return out


def gradients(g):
    """Fused form of the kernels.py two-stage conv (fp32)."""
    h, w = g.shape
    gx = np.empty((h - 1, w - 1), dtype=F32)
    gy = np.empty((h - 1, w - 1), dtype=F32)
    for y in range(h - 1):
        for x in range(w - 1):
            a = g[y, x]
            b = g[y, x + 1]
            c = g[y + 1, x]
            d = g[y + 1, x + 1]
            gx[y, x] = F32(F32(0.25) * F32(-a + b - c + d))
            gy[y, x] = F32(F32(0.25) * F32(-a - b + c + d))
    return gx, gy


def estimate_kernels(v, tuning):
    """kernels.cuda_estimate_kernel + linalg eigen, linear law (fp32)."""
    g = decimate(gat(v))
    gx, gy = gradients(g)
    gh, gw = g.shape
    cov = np.empty((gh, gw, 2, 2), dtype=F32)
    kd = F32(tuning["k_detail"])
    kn = F32(tuning["k_denoise"])
    dth = F32(tuning["d_th"])
    dtr = F32(tuning["d_tr"])
    ks = F32(tuning["k_stretch"])
    kh = F32(tuning["k_shrink"])
    for qy in range(gh):
        for qx in range(gw):
            t00 = F32(0)
            t01 = F32(0)
            t11 = F32(0)
            for i in range(2):
                for j in range(2):
                    x = qx - 1 + j
                    y = qy - 1 + i
                    if 0 <= y < gh - 1 and 0 <= x < gw - 1:
                        xx = gx[y, x]
                        yy = gy[y, x]
                        t00 = F32(t00 + F32(xx * xx))
                        t01 = F32(t01 + F32(xx * yy))
                        t11 = F32(t11 + F32(yy * yy))
            # linalg.get_eigen_val_2x2 + get_eigen_vect_2x2
            b = F32(-(t00 + t11))
            c = F32(t00 * t11 - t01 * t01)
            delta = max(F32(b * b - F32(4) * c), F32(0))
            root = np.sqrt(delta)
            r1 = F32(F32(-b + root) / F32(2))
            r2 = F32(F32(-b - root) / F32(2))
            if abs(r1) >= abs(r2):
                l1, l2 = r1, r2
            else:
                l1, l2 = r2, r1
            if t01 == 0 and t00 == t11:
                e1x, e1y, e2x, e2y = F32(1), F32(0), F32(0), F32(1)
            else:
                v1x = F32(t00 + t01 - l2)
                v1y = F32(t01 + t11 - l2)
                if v1x == 0:
                    e1x, e1y, e2x, e2y = F32(0), F32(1), F32(1), F32(0)
                elif v1y == 0:
                    e1x, e1y, e2x, e2y = F32(1), F32(0), F32(0), F32(1)
                else:
                    n = np.sqrt(F32(v1x * v1x + v1y * v1y))
                    e1x = F32(v1x / n)
                    e1y = F32(v1y / n)
                    s = F32(np.copysign(1, e1x))
                    e2y = F32(abs(e1x))
                    e2x = F32(-e1y * s)
            # compute_k, linear law
            ratio = F32(F32(l1 - l2) / F32(l1 + l2))
            aniso = F32(1 + np.sqrt(ratio))
            det = F32(1 - F32(np.sqrt(l1) / dtr) + dth)
            den = F32(min(max(det, F32(0)), F32(1)))
            a1 = F32(F32(2 - aniso) + F32(F32(aniso - 1) / kh))
            a2 = F32(F32(2 - aniso) + F32(F32(aniso - 1) * ks))
            k1 = F32(kd * F32(F32(F32(1 - den) * a1) + F32(den * kn)))
            k2 = F32(kd * F32(F32(F32(1 - den) * a2) + F32(den * kn)))
            k1s = F32(k1 * k1)
            k2s = F32(k2 * k2)
            cov[qy, qx, 0, 0] = F32(k1s * e1x * e1x + k2s * e2x * e2x)
            cov[qy, qx, 0, 1] = F32(k1s * e1x * e1y + k2s * e2x * e2y)
            cov[qy, qx, 1, 0] = cov[qy, qx, 0, 1]
            cov[qy, qx, 1, 1] = F32(k1s * e1y * e1y + k2s * e2y * e2y)
    return cov


def guide_image(v):
    """robustness.cuda_compute_guide_image (Alg. 7), fp32."""
    h, w = v.shape
    g = np.empty((3, h // 2, w // 2), dtype=F32)
    for ty in range(h // 2):
        for tx in range(w // 2):
            g[0, ty, tx] = np.sqrt(max(v[2 * ty, 2 * tx], F32(0)))
            g[1, ty, tx] = np.sqrt(max(F32(F32(0.5) * F32(v[2 * ty, 2 * tx + 1] + v[2 * ty + 1, 2 * tx])), F32(0)))
            g[2, ty, tx] = np.sqrt(max(v[2 * ty + 1, 2 * tx + 1], F32(0)))
    return g


def local_stats(g):
    """robustness.cuda_compute_local_stats (Alg. 8), fp32, unfloored."""
    _, h, w = g.shape
    mean = np.empty_like(g)
    var = np.empty_like(g)
    for c in range(3):
        for y in range(h):
            for x in range(w):
                m = F32(0)
                q = F32(0)
                for i in (-1, 0, 1):
                    for j in (-1, 0, 1):
                        yy = min(max(y + i, 0), h - 1)
                        xx = min(max(x + j, 0), w - 1)
                        col = g[c, yy, xx]
                        m = F32(m + col)
                        q = F32(q + F32(col * col))
                m = F32(m / F32(9))
                mean[c, y, x] = m
                var[c, y, x] = F32(F32(q / F32(9)) - F32(m * m))
    return mean, var


def dogson(x):
    a = abs(float(x))
    if a <= 0.5:
        return -2.0 * a * a + 1.0
    if a <= 1.5:
        return a * a - 2.5 * a + 1.5
    return 0.0


def round_half_away(x):
    """utils.round_half_away (CUDA round()): halves away from zero."""
    import math
    return math.floor(x + 0.5) if x >= 0 else math.ceil(x - 0.5)


def warp_means(mov_mean, flow_raw, tile_raw):
    """robustness.cuda_warp_dogson: nearest tile, flow*0.5, half-away centers."""
    _, h, w = mov_mean.shape
    ts = tile_raw // 2
    out = np.empty_like(mov_mean)
    oob = np.zeros((h, w), dtype=bool)
    for y in range(h):
        for x in range(w):
            fx = float(flow_raw[y // ts, x // ts, 0])
            fy = float(flow_raw[y // ts, x // ts, 1])
            xm = x + fx * 0.5
            ym = y + fy * 0.5
            if not (0 <= xm < w and 0 <= ym < h):
                oob[y, x] = True
                out[:, y, x] = np.inf
                continue
            cx = int(round_half_away(xm))
            cy = int(round_half_away(ym))
            wacc = 0.0
            buf = [0.0, 0.0, 0.0]
            for i in (-1, 0, 1):
                yy = min(max(cy + i, 0), h - 1)
                wy = dogson(yy - ym)
                for j in (-1, 0, 1):
                    xx = min(max(cx + j, 0), w - 1)
                    wt = wy * dogson(xx - xm)
                    for c in range(3):
                        buf[c] += float(mov_mean[c, yy, xx]) * wt
                    wacc += wt
            for c in range(3):
                out[c, y, x] = F32(buf[c] / wacc)
    return out, oob


def robustness_map(ref_mean, ref_var, mov_mean_warped, flow_raw, tile_raw):
    """d/sigma + S + threshold + local_min (no LUT), fp32/float mix."""
    _, h, w = ref_mean.shape
    ts = tile_raw // 2
    t, s1, s2 = (F32(v) for v in T_S1_S2)
    # S over the tile lattice (raw-unit spread vs Mt).
    nty, ntx, _ = flow_raw.shape
    smap = np.empty((nty, ntx), dtype=F32)
    for py in range(nty):
        for px in range(ntx):
            mnx = float("inf")
            mny = float("inf")
            mxx = float("-inf")
            mxy = float("-inf")
            for i in (-1, 0, 1):
                for j in (-1, 0, 1):
                    y, x = py + i, px + j
                    if 0 <= y < nty and 0 <= x < ntx:
                        fx = float(flow_raw[y, x, 0])
                        fy = float(flow_raw[y, x, 1])
                        mnx = min(mnx, fx)
                        mny = min(mny, fy)
                        mxx = max(mxx, fx)
                        mxy = max(mxy, fy)
            ers = (mxx - mnx) ** 2 + (mxy - mny) ** 2
            smap[py, px] = F32(s1 if ers > MT_RAW ** 2 else s2)
    r = np.empty((h, w), dtype=F32)
    for y in range(h):
        for x in range(w):
            if np.isinf(mov_mean_warped[0, y, x]):
                r[y, x] = F32(0)
                continue
            d2 = F32(0)
            s2v = F32(0)
            for c in range(3):
                e = F32(ref_mean[c, y, x] - mov_mean_warped[c, y, x])
                d2 = F32(d2 + F32(e * e))
                s2v = F32(s2v + ref_var[c, y, x])
            assert float(s2v) > 0.0, f"non-positive sigma2 at {(y, x)}: fixture too flat"
            val = float(smap[y // ts, x // ts]) * float(np.exp(-float(d2) / float(s2v))) - float(t)
            val = min(max(val, 0.0), 1.0)
            r[y, x] = F32(0.0 if np.isnan(val) else val)
    # Alg. 9 local min, 5x5 clamp.
    out = np.empty_like(r)
    for y in range(h):
        for x in range(w):
            m = float("inf")
            for i in range(-2, 3):
                for j in range(-2, 3):
                    yy = min(max(y + i, 0), h - 1)
                    xx = min(max(x + j, 0), w - 1)
                    m = min(m, float(r[yy, xx]))
            out[y, x] = F32(m)
    return out, smap


def accumulate(comp, flow_raw, cov, rmap, num, den, tile_raw):
    """merge.accumulate (Alg. 4), scale 1, RGGB, fp32."""
    lr_h, lr_w = comp.shape
    for hy in range(lr_h):
        for hx in range(lr_w):
            lr_x = (hx + 0.5)
            lr_y = (hy + 0.5)
            px = int(lr_x // tile_raw)
            py = int(lr_y // tile_raw)
            fx = float(flow_raw[py, px, 0])
            fy = float(flow_raw[py, px, 1])
            ir = min(int(lr_y // 2 - 0.5), lr_h // 2 - 1)
            jr = min(int(lr_x // 2 - 0.5), lr_w // 2 - 1)
            local_r = float(rmap[ir, jr])
            if local_r == 0.0:
                continue
            mx = lr_x + fx
            my = lr_y + fy
            if not (0 <= mx < lr_w and 0 <= my < lr_h):
                continue
            kx = mx / 2 - 0.5
            ky = my / 2 - 0.5
            import math
            frac_x, int_x = math.modf(kx)
            frac_y, int_y = math.modf(ky)
            fx0 = max(int(int_x), 0)
            fy0 = max(int(int_y), 0)
            fx1 = min(fx0 + 1, cov.shape[1] - 1)
            fy1 = min(fy0 + 1, cov.shape[0] - 1)
            cxx = _lerp2(cov[fy0, fx0, 0, 0], cov[fy0, fx1, 0, 0],
                         cov[fy1, fx0, 0, 0], cov[fy1, fx1, 0, 0], frac_x, frac_y)
            cxy = _lerp2(cov[fy0, fx0, 0, 1], cov[fy0, fx1, 0, 1],
                         cov[fy1, fx0, 0, 1], cov[fy1, fx1, 0, 1], frac_x, frac_y)
            cyy = _lerp2(cov[fy0, fx0, 1, 1], cov[fy0, fx1, 1, 1],
                         cov[fy1, fx0, 1, 1], cov[fy1, fx1, 1, 1], frac_x, frac_y)
            det = cxx * cyy - cxy * cxy
            assert det > 0 and np.isfinite(det), f"bad det {det} at {(hy, hx)}"
            inv = 1.0 / det
            ixx, ixy, iyy = inv * cyy, -inv * cxy, inv * cxx
            cx, cy = int(mx), int(my)
            jx, jy = mx - 0.5, my - 0.5
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    j, i = cx + dj, cy + di
                    if not (0 <= j < lr_w and 0 <= i < lr_h):
                        continue
                    ch = (i % 2) + (j % 2)
                    c = float(comp[i, j])
                    dx = j - jx
                    dy = i - jy
                    z = max(ixx * dx * dx + 2 * ixy * dx * dy + iyy * dy * dy, 0.0)
                    wgt = float(np.exp(-0.5 * z)) * local_r
                    num[hy, hx, ch] += wgt * c
                    den[hy, hx, ch] += wgt


def _lerp2(c00, c10, c01, c11, fx, fy):
    top = float(c00) + fx * (float(c10) - float(c00))
    bot = float(c01) + fx * (float(c11) - float(c01))
    return top + fy * (bot - top)


def const_flow(dx, dy, nty=6, ntx=8):
    f = np.empty((nty, ntx, 2), dtype=F32)
    f[:, :, 0] = F32(dx)
    f[:, :, 1] = F32(dy)
    return f


def step_flow():
    """Left half shift A, right half shift B (S exercises s1 and s2)."""
    f = np.empty((6, 8, 2), dtype=F32)
    f[:, 0:4, 0] = F32(1.7)
    f[:, 0:4, 1] = F32(-0.6)
    f[:, 4:8, 0] = F32(-2.9)
    f[:, 4:8, 1] = F32(2.3)
    return f


def write_f32(path, a):
    np.ascontiguousarray(a, dtype=F32).tofile(str(path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    tuning = tuning_for_snr(SNR_DB)
    codes, shifts, _, _, black_spatial = make_burst()
    norms = [normalize(c, black_spatial) for c in codes]

    manifest = {
        "raw_width": RAW_W, "raw_height": RAW_H,
        "black_exif_rgbg": list(BLACK_EXIF), "white": WHITE,
        "dng_profile_6": list(DNG_PROFILE),
        "tile_raw": TILE_RAW, "snr_db": SNR_DB, "tuning": tuning,
        "t_s1_s2_mt": [0.12, 2.0, 12.0, 0.8],
        "shifts": shifts,
        "files": {},
    }

    def emit(name, a, fmt):
        p = out / name
        if fmt == "u16":
            np.ascontiguousarray(a, dtype=np.uint16).tofile(str(p))
        elif fmt == "f32":
            write_f32(p, a)
        manifest["files"][name] = {"shape": list(a.shape), "dtype": fmt}
        return a

    for i, c in enumerate(codes):
        emit(f"codes_f{i}.u16", c, "u16")

    # -- Scenario A: constant forced flows, full chain incl. merge ----------
    covs = [estimate_kernels(v, tuning) for v in norms]
    for i, cov in enumerate(covs):
        emit(f"A_cov_f{i}.f32", cov.reshape(-1, 4), "f32")
    guides = [guide_image(v) for v in norms]
    ref_mean, ref_var = local_stats(guides[0])
    emit("A_stats_mean_ref.f32", ref_mean.reshape(3, -1).T.reshape(-1), "f32")
    emit("A_stats_var_ref.f32", ref_var.reshape(3, -1).T.reshape(-1), "f32")
    flows = [const_flow(0.0, 0.0)] + [const_flow(dx, dy) for dx, dy in shifts[1:]]
    for i, f in enumerate(flows):
        emit(f"A_flowraw_f{i}.f32", f.reshape(-1, 2), "f32")
    num = np.zeros((RAW_H, RAW_W, 3))
    den = np.zeros((RAW_H, RAW_W, 3))
    # Reference first (reference order; RawLens merges last: 1-ulp).
    ones = np.ones((RAW_H // 2, RAW_W // 2), dtype=F32)
    accumulate(norms[0], flows[0], covs[0], ones, num, den, TILE_RAW)
    for i in (1, 2):
        mov_mean, _ = local_stats(guides[i])
        emit(f"A_movstats_mean_f{i}.f32", mov_mean.reshape(3, -1).T.reshape(-1), "f32")
        warped, _ = warp_means(mov_mean, flows[i], TILE_RAW)
        rmap, smap = robustness_map(ref_mean, ref_var, warped, flows[i], TILE_RAW)
        emit(f"A_r_f{i}.f32", rmap, "f32")
        emit(f"A_s_f{i}.f32", smap, "f32")
        accumulate(norms[i], flows[i], covs[i], rmap, num, den, TILE_RAW)
    merged = num / den
    assert np.all(np.isfinite(merged)), "non-finite merged pixel"
    assert np.all(den > 0), "zero denominator"
    emit("A_merged.f32", merged.reshape(-1, 3), "f32")
    emit("A_den.f32", den.reshape(-1, 3), "f32")

    # -- Scenario B: step flow field (S map + photo term) --------------------
    mov_mean_b, _ = local_stats(guides[1])
    warped_b, _ = warp_means(mov_mean_b, step_flow(), TILE_RAW)
    rmap_b, smap_b = robustness_map(ref_mean, ref_var, warped_b, step_flow(), TILE_RAW)
    emit("B_flowraw.f32", step_flow().reshape(-1, 2), "f32")
    emit("B_r.f32", rmap_b, "f32")
    emit("B_s.f32", smap_b, "f32")

    # -- SNR estimator --------------------------------------------------------
    v64 = codes[0].astype(np.float64)
    v64 = (v64 - black_spatial) / (WHITE - black_spatial)
    linear, db = estimate_snr_sequential(v64)
    manifest["snr"] = {"linear": linear, "db": db}

    (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"wrote {len(manifest['files'])} goldens to {out}")
    print(f"SNR linear={linear:.12f} dB={db:.9f}")


if __name__ == "__main__":
    main()
