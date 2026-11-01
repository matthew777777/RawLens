"""RawTherapee Capture Sharpening, NumPy port.

Parity target: RawTherapee b2ee504, rtengine/capturesharpening.cc
RawImageSource::captureSharpening, Bayer path, noise options off, mask display off,
scalar (non-SIMD) code path, single thread. Float evaluation order follows the C++
source so results are bit-identical to the reference harness (tools/rt_harness).

Input scale follows RT: rawData and RGB are black-subtracted, white-balanced and
scaled so that sensor white is ~65535 (RT's hardcoded thresholds assume this).

Known deviation: with radius_offset != 0 (corner boost) RT reuses a per-thread
scratch tile (tmpThr) whose outer ring keeps values from the previously processed
tile, so RT's own output there depends on OpenMP scheduling. This port resets the
scratch per tile, which matches the harness built with RING_RESET=1.
"""
from __future__ import annotations

import ctypes
import ctypes.util
import math
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

f32 = np.float32
_libm = ctypes.CDLL(ctypes.util.find_library("m"))
_libm.logf.restype = ctypes.c_float
_libm.logf.argtypes = [ctypes.c_float]


def _logf(x) -> np.float32:
    """C logf, as used by std::log(float) in the reference."""
    return f32(_libm.logf(float(x)))

MAXVALF = f32(65535.0)
ACTIVE_LOG: list | None = None  # optional instrumentation: active tile count per iteration
# Worker threads for independent tile batches (NumPy releases the GIL on array
# ops). Per-tile arithmetic is unchanged, so results stay bit-identical.
_LOG_LOCK = threading.Lock()
WORKERS = int(os.environ.get("CAPSHARP_THREADS", os.cpu_count() or 1))


def _pmap(fn, items):
    items = list(items)
    if WORKERS <= 1 or len(items) <= 1:
        return [fn(x) for x in items]
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        return list(ex.map(fn, items))
MAXVALD = 65535.0


@dataclass
class Params:
    """CaptureSharpeningParams defaults (rtengine/params/raw.cc:33-46)."""

    auto_contrast: bool = True
    auto_radius: bool = True
    contrast: float = 10.0
    radius: float = 0.75
    radius_offset: float = 0.0
    iterations: int = 20
    iter_check: bool = True


@dataclass
class Result:
    r: np.ndarray
    g: np.ndarray
    b: np.ndarray
    radius: float
    contrast: float
    clip_mask: np.ndarray | None = None
    L: np.ndarray | None = None
    blend: np.ndarray | None = None
    y_new: np.ndarray | None = None


# ---------------------------------------------------------------- clip mask / radius


def build_clip_mask_bayer(raw: np.ndarray, whites) -> np.ndarray:
    """capturesharpening.cc:47-75"""
    H, W = raw.shape
    clip = np.empty((H, W), f32)
    clip[0::2, 0::2] = whites[0][0]
    clip[0::2, 1::2] = whites[0][1]
    clip[1::2, 0::2] = whites[1][0]
    clip[1::2, 1::2] = whites[1][1]
    hit = np.zeros((H, W), bool)
    hit[2:H - 2, 2:W - 2] = raw[2:H - 2, 2:W - 2] >= clip[2:H - 2, 2:W - 2]
    out = np.zeros((H, W), bool)
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            if abs(dy) == 2 and abs(dx) == 2:
                continue
            out[2 + dy:H - 2 + dy, 2 + dx:W - 2 + dx] |= hit[2:H - 2, 2:W - 2]
    return np.where(out, f32(0), f32(1)).astype(f32)


def calc_radius_bayer(raw: np.ndarray, lower: float, upper: float, fc) -> np.float32:
    """capturesharpening.cc:134-186 (max over accepted green/green diagonal ratios)."""
    H, W = raw.shape
    lower, upper = f32(lower), f32(upper)
    best = f32(1.0)
    for parity in (0, 1):
        rows = np.arange(4, H - 4)
        rows = rows[(rows & 1) == parity]
        if rows.size == 0:
            continue
        c0 = 5 + (fc[parity] & 1)
        cols = np.arange(c0, W - 4, 2)
        if cols.size == 0:
            continue
        R = rows[:, None]
        C = cols[None, :]
        v = lambda dr, dc: raw[R + dr, C + dc]
        val00 = v(0, 0)
        val1m1 = v(1, -1)
        val1p1 = v(1, 1)
        base = val00 > 0
        # branch with (row+1, col-1)
        maxv = np.maximum(val00, val1m1)
        minv = np.minimum(val00, val1m1)
        ok = base & (val1m1 > 0) & (maxv > lower)
        clip_a = np.maximum(np.maximum(v(-1, -1), v(-1, 1)), val1p1) >= upper
        clip_b = np.maximum(np.maximum(np.maximum(v(0, -2), val00), v(2, -2)), v(2, 0)) >= upper
        clipped = np.where(maxv == val00, clip_a, clip_b)
        ok &= ~clipped
        if ok.any():
            best = max(best, (maxv[ok] / minv[ok]).max())
        # branch with (row+1, col+1)
        maxv = np.maximum(val00, val1p1)
        minv = np.minimum(val00, val1p1)
        ok = base & (val1p1 > 0) & (maxv > lower)
        clip_b = np.maximum(np.maximum(np.maximum(val00, v(0, 2)), v(2, 0)), v(2, 2)) >= upper
        clipped = np.where(maxv == val00, clip_a, clip_b)
        ok &= ~clipped
        if ok.any():
            best = max(best, (maxv[ok] / minv[ok]).max())
    return f32(np.sqrt(f32(1.0) / _logf(f32(best))))


# ---------------------------------------------------------------- luminance


def _labY_lut() -> np.ndarray:
    """Color::cachefy (color.cc:174-235)."""
    eps = 216.0 / 24389.0
    kappa = 24389.0 / 27.0
    epsmaxint = int(MAXVALD * eps)
    out = np.empty(65536, f32)
    for i in range(65536):
        if i <= epsmaxint:
            out[i] = 327.68 * (kappa * i / float(MAXVALF))
        else:
            out[i] = 327.68 * (116.0 * math.cbrt(i / float(MAXVALF)) - 16.0)
    return out


_LUT = None


def compute_xyz2labY(y: np.ndarray) -> np.ndarray:
    """Color::computeXYZ2LabY (color.h:177) with LUTf linear lookup (LUT.h:461-482).
    The f > 65535 branch uses float32 cbrt (RT uses sleef xcbrtf, max 2 ulp)."""
    global _LUT
    if _LUT is None:
        _LUT = _labY_lut()
    out = np.empty_like(y)
    neg = y < 0
    over = y > f32(65535.0)
    mid = ~(neg | over)
    kappa = 24389.0 / 27.0
    out[neg] = (float(f32(327.68)) * (kappa * y[neg].astype(np.float64) / float(MAXVALF))).astype(f32)
    yo = y[over]
    out[over] = f32(327.68) * (f32(116.0) * np.cbrt(yo / MAXVALF) - f32(16.0))
    ym = y[mid]
    idx = ym.astype(np.int32)  # truncation, ym >= 0
    idx = np.where(ym > f32(65534.0), 65534, idx)
    diff = ym - idx.astype(f32)
    p1 = _LUT[idx]
    p2 = _LUT[idx + 1] - p1
    out[mid] = p1 + p2 * diff
    return out


_XYZ_RGB_Y = (f32(0.212671), f32(0.715160), f32(0.072169))


def rgb2L(r, g, b):
    """Color::RGB2L scalar path (color.cc:1818-1825)."""
    y = _XYZ_RGB_Y[0] * r + _XYZ_RGB_Y[1] * g + _XYZ_RGB_Y[2] * b
    return compute_xyz2labY(y)


def rgb2Y(r, g, b):
    """Color::RGB2Y (color.h:2003)."""
    z = f32(0)
    return f32(0.2627) * np.maximum(r, z) + f32(0.6780) * np.maximum(g, z) + f32(0.0593) * np.maximum(b, z)


# ---------------------------------------------------------------- blend mask (rt_algo.cc)

_CONTRAST_SCALE = f32(f32(0.0625) / f32(327.68))


def _calc_blend_factor(val, threshold):
    """rt_algo.cc:49-55"""
    x = f32(-16.0) + (f32(16.0) / threshold) * val
    return f32(0.5) * (f32(1.0) + x / np.sqrt(f32(1.0) + x * x))


def _contrast(L, y0, y1, x0, x1):
    """Gradient magnitude used by buildBlendMask/calcContrastThreshold for rows y0..y1, cols x0..x1."""
    sq = lambda a: a * a
    t = (sq(L[y0:y1, x0 + 1:x1 + 1] - L[y0:y1, x0 - 1:x1 - 1])
         + sq(L[y0 + 1:y1 + 1, x0:x1] - L[y0 - 1:y1 - 1, x0:x1])
         + sq(L[y0:y1, x0 + 2:x1 + 2] - L[y0:y1, x0 - 2:x1 - 2])
         + sq(L[y0 + 2:y1 + 2, x0:x1] - L[y0 - 2:y1 - 2, x0:x1]))
    return np.sqrt(t) * _CONTRAST_SCALE


def _seq_sum(a: np.ndarray) -> np.ndarray:
    """Sequential float32 left-to-right sum over the last axis (matches a scalar C loop)."""
    return np.cumsum(a, axis=-1, dtype=f32)[..., -1]


def _tile_stats(L, ys, xs, tilesize):
    """tileAverage + tileVariance (rt_algo.cc:67-112) for many tiles; returns (avg, var)."""
    win = sliding_window_view(L, (tilesize, tilesize))
    n2 = tilesize * tilesize
    avg = np.empty(len(ys), f32)
    var = np.empty(len(ys), f32)
    chunk = max(1, (1 << 20) // n2)

    def work(s):
        t = win[ys[s:s + chunk], xs[s:s + chunk]].reshape(-1, n2)
        a = _seq_sum(t) / f32(n2)
        avg[s:s + chunk] = a
        d = t - a[:, None]
        var[s:s + chunk] = _seq_sum(d * d) / (f32(n2) * a)

    _pmap(work, range(0, len(ys), chunk))
    return avg, var


def _tile_variances(L, ys, xs, tilesize):
    avg, var = _tile_stats(L, ys, xs, tilesize)
    bad = (avg < f32(2000.0)) | (avg > f32(20000.0))
    var = np.where(bad, np.inf, var).astype(f32)
    var = np.where(var < f32(0.5), np.inf, var).astype(f32)
    return var


def _calc_contrast_threshold(L, tileY, tileX, tilesize) -> np.float32:
    """rt_algo.cc:177-235"""
    blend = _contrast(L, tileY + 2, tileY + tilesize - 2, tileX + 2, tileX + tilesize - 2).ravel()
    limit = f32(f32((tilesize - 4) ** 2) / f32(100.0))
    c = 1
    while c < 100:
        thr = f32(f32(c) / f32(100.0))
        s = _seq_sum(_calc_blend_factor(blend, thr))
        if s <= limit:
            break
        c += 1
    return f32(f32(c + 1) / f32(100.0))


def _auto_contrast(L) -> np.float32 | None:
    """Auto contrast search of buildBlendMask (rt_algo.cc:382-479). None = keep given threshold."""
    H, W = L.shape
    for p in range(2):
        tilesize = 80 // (p + 1)
        skip = tilesize if p == 0 else tilesize // 4
        nW = W // skip - 3 * p
        nH = H // skip - 3 * p
        if nW <= 0 or nH <= 0:
            var = np.full((max(nH, 0), max(nW, 0)), np.inf, f32)
        else:
            gi, gj = np.meshgrid(np.arange(nH), np.arange(nW), indexing="ij")
            var = _tile_variances(L, (gi * skip).ravel(), (gj * skip).ravel(), tilesize).reshape(nH, nW)
        if var.size:
            k = int(np.argmin(var))
            minvar = var.ravel()[k]
            minI, minJ = divmod(k, nW)
        else:
            minvar, minI, minJ = f32(np.inf), 0, 0
        if minvar <= 1.0 or p == 1:
            minY, minX = skip * minI, skip * minJ
            if p == 0:
                return _calc_contrast_threshold(L, minY, minX, tilesize)
            y0 = max(minY - skip, 0)
            x0 = max(minX - skip, 0)
            y1 = min(minY + skip, H - tilesize)
            x1 = min(minX + skip, W - tilesize)
            nH2, nW2 = y1 - y0 + 1, x1 - x0 + 1
            gi, gj = np.meshgrid(np.arange(nH2), np.arange(nW2), indexing="ij")
            var2 = _tile_variances(L, (y0 + gi).ravel(), (x0 + gj).ravel(), tilesize)
            k = int(np.argmin(var2))
            mv = var2[k]
            if mv <= 8.0:
                i, j = divmod(k, nW2)
                return _calc_contrast_threshold(L, y0 + i, x0 + j, tilesize)
            return f32(0.0)
    return None


def _yvv_factors(sigma: float):
    """calculateYvVFactors<double> (gauss.cc:75-110), M pre-scaled as in gaussHorizontal/Vertical."""
    if sigma < 2.5:
        q = 3.97156 - 4.14554 * math.sqrt(1.0 - 0.26891 * sigma)
    else:
        q = 0.98711 * sigma - 0.96330
    b0 = 1.57825 + 2.44413 * q + 1.4281 * q * q + 0.422205 * q * q * q
    b1 = 2.44413 * q + 2.85619 * q * q + 1.26661 * q * q * q
    b2 = -1.4281 * q * q - 1.26661 * q * q * q
    b3 = 0.422205 * q * q * q
    B = 1.0 - (b1 + b2 + b3) / b0
    b1 /= b0
    b2 /= b0
    b3 /= b0
    M = [[0.0] * 3 for _ in range(3)]
    M[0][0] = -b3 * b1 + 1.0 - b3 * b3 - b2
    M[0][1] = (b3 + b1) * (b2 + b3 * b1)
    M[0][2] = b3 * (b1 + b3 * b2)
    M[1][0] = b1 + b3 * b2
    M[1][1] = -(b2 - 1.0) * (b2 + b3 * b1)
    M[1][2] = -(b3 * b1 + b3 * b3 + b2 - 1.0) * b3
    M[2][0] = b3 * b1 + b2 + b1 * b1 - b2 * b2
    M[2][1] = b1 * b2 + b3 * b2 * b2 - b1 * b3 * b3 - b3 * b3 * b3 - b3 * b2 + b3
    M[2][2] = b3 * (b1 + b3 * b2)
    d = (1.0 + b1 - b2 + b3) * (1.0 + b2 + (b1 - b3) * b3)
    M = [[M[i][j] / d for j in range(3)] for i in range(3)]
    return B, b1, b2, b3, M


def _yvv_1d(src: np.ndarray, sigma: float) -> np.ndarray:
    """gaussHorizontal (gauss.cc:646-690) along axis 1 of a float32 image; returns float32.
    gaussVertical uses the identical recursion per column, so the caller transposes."""
    B, b1, b2, b3, M = _yvv_factors(sigma)
    s = src.astype(np.float64)
    n = s.shape[1]
    t = np.empty_like(s)
    t[:, 0] = B * s[:, 0] + b1 * s[:, 0] + b2 * s[:, 0] + b3 * s[:, 0]
    t[:, 1] = B * s[:, 1] + b1 * t[:, 0] + b2 * s[:, 0] + b3 * s[:, 0]
    t[:, 2] = B * s[:, 2] + b1 * t[:, 1] + b2 * t[:, 0] + b3 * s[:, 0]
    for j in range(3, n):
        t[:, j] = B * s[:, j] + b1 * t[:, j - 1] + b2 * t[:, j - 2] + b3 * t[:, j - 3]
    last = s[:, n - 1]
    tWm1 = last + M[0][0] * (t[:, n - 1] - last) + M[0][1] * (t[:, n - 2] - last) + M[0][2] * (t[:, n - 3] - last)
    tW = last + M[1][0] * (t[:, n - 1] - last) + M[1][1] * (t[:, n - 2] - last) + M[1][2] * (t[:, n - 3] - last)
    tWp1 = last + M[2][0] * (t[:, n - 1] - last) + M[2][1] * (t[:, n - 2] - last) + M[2][2] * (t[:, n - 3] - last)
    t[:, n - 1] = tWm1
    t[:, n - 2] = B * t[:, n - 2] + b1 * t[:, n - 1] + b2 * tW + b3 * tWp1
    t[:, n - 3] = B * t[:, n - 3] + b1 * t[:, n - 2] + b2 * t[:, n - 1] + b3 * tW
    for j in range(n - 4, -1, -1):
        t[:, j] = B * t[:, j] + b1 * t[:, j + 1] + b2 * t[:, j + 2] + b3 * t[:, j + 3]
    return t.astype(f32)


def gaussian_blur_yvv(img: np.ndarray, sigma: float) -> np.ndarray:
    """gaussianBlur for 1.15 < sigma < 25, scalar path: gaussHorizontal then gaussVertical."""
    h = _yvv_1d(img, sigma)
    return np.ascontiguousarray(_yvv_1d(np.ascontiguousarray(h.T), sigma).T)


def build_blend_mask(L, clip_mask, contrast: np.float32, auto_contrast: bool):
    """buildBlendMask (rt_algo.cc:380-566) with clipMask == blend. Returns (blend, contrast)."""
    H, W = L.shape
    if auto_contrast:
        c = _auto_contrast(L)
        if c is not None:
            contrast = c
    contrast = f32(contrast)
    if contrast == 0:
        return np.ones((H, W), f32), contrast
    blend = clip_mask.copy()
    blend[2:H - 2, 2:W - 2] = clip_mask[2:H - 2, 2:W - 2] * _calc_blend_factor(_contrast(L, 2, H - 2, 2, W - 2), contrast)
    blend[0:2, 2:W - 2] = blend[2, 2:W - 2]
    blend[H - 2:H, 2:W - 2] = blend[H - 3, 2:W - 2]
    blend[:, 0] = blend[:, 2]
    blend[:, 1] = blend[:, 2]
    blend[:, W - 2] = blend[:, W - 3]
    blend[:, W - 1] = blend[:, W - 3]
    return gaussian_blur_yvv(blend, 2.0), contrast


# ---------------------------------------------------------------- deconvolution (capturesharpening.cc:299-534)

def _kernel(sigma: np.float32, n: int) -> np.ndarray:
    """compute{3,5,7,9,13}x{..}kernel2 (boxblur.cc:32-136)."""
    r = n // 2
    limit = {13: (3.0 * 2.0) ** 2, 9: (3.0 * 1.5) ** 2, 7: (3.0 * 1.15) ** 2, 5: (3.0 * 0.84) ** 2, 3: None}[n]
    sigma = f32(sigma)
    temp = float(f32(-2.0) * (sigma * sigma))
    k = np.zeros((n, n), f32)
    s = f32(0.0)
    for i in range(-r, r + 1):
        for j in range(-r, r + 1):
            d = i * i + j * j
            if limit is None or d <= limit:
                k[i + r, j + r] = f32(math.exp(d / temp))
                s = f32(s + k[i + r, j + r])
    return (k / s).astype(f32)


def _axis(a):
    return ("sum", [(-a, 0), (0, -a), (0, a), (a, 0)])


def _diag(a):
    return ("sum", [(-a, -a), (-a, a), (a, -a), (a, a)])


def _pairs(a, b):
    return ("sum", [("sum", [(-a, -b), (-a, b)]), ("sum", [(-b, -a), (-b, a)]),
                    ("sum", [(b, -a), (b, a)]), ("sum", [(a, -b), (a, b)])])


# (coefficient class (a, b) -> kernel[r-a][r-b], expression) in the exact order of boxblur.cc gaussNxN{div,mult}2.
_TERMS = {
    3: [((1, 1), _diag(1)), ((1, 0), _axis(1)), ((0, 0), (0, 0))],
    5: [((2, 1), _pairs(2, 1)), ((2, 0), _axis(2)), ((1, 1), _diag(1)), ((1, 0), _axis(1)), ((0, 0), (0, 0))],
    7: [((3, 1), _pairs(3, 1)), ((3, 0), _axis(3)), ((2, 2), _diag(2)), ((2, 1), _pairs(2, 1)), ((2, 0), _axis(2)),
        ((1, 1), _diag(1)), ((1, 0), _axis(1)), ((0, 0), (0, 0))],
    9: [((4, 2), _pairs(4, 2)), ((4, 1), _pairs(4, 1)), ((4, 0), _axis(4)), ((3, 3), _diag(3)), ((3, 2), _pairs(3, 2)),
        ((3, 1), _pairs(3, 1)), ((3, 0), _axis(3)), ((2, 2), _diag(2)), ((2, 1), _pairs(2, 1)), ((2, 0), _axis(2)),
        ((1, 1), _diag(1)), ((1, 0), _axis(1)), ((0, 0), (0, 0))],
    13: [((6, 0), _axis(6)), ((5, 3), _pairs(5, 3)), ((5, 2), _pairs(5, 2)), ((5, 1), _pairs(5, 1)),
         ((5, 0), ("sum", [_axis(5), _pairs(4, 3)])), ((4, 4), _diag(4)), ((4, 2), _pairs(4, 2)), ((4, 1), _pairs(4, 1)),
         ((4, 0), _axis(4)), ((3, 3), _diag(3)), ((3, 2), _pairs(3, 2)), ((3, 1), _pairs(3, 1)), ((3, 0), _axis(3)),
         ((2, 2), _diag(2)), ((2, 1), _pairs(2, 1)), ((2, 0), _axis(2)), ((1, 1), _diag(1)), ((1, 0), _axis(1)),
         ((0, 0), (0, 0))],
}


def _conv(src: np.ndarray, kernel: np.ndarray, n: int) -> np.ndarray:
    """Interior [r, F-r) of the RT tile convolution for a batch of tiles (T, F, F)."""
    r = n // 2
    F = src.shape[-1]

    def ev(e):
        if e[0] == "sum":
            acc = None
            for c in e[1]:
                v = ev(c)
                acc = v if acc is None else acc + v
            return acc
        di, dj = e
        return src[:, r + di:F - r + di, r + dj:F - r + dj]

    total = None
    for (a, b), e in _TERMS[n]:
        t = kernel[r - a, r - b] * ev(e)
        total = t if total is None else total + t
    return total


def _kernel_size_fixed(sigma):
    if sigma < 0.6:
        return 3
    if sigma <= 0.84:
        return 5
    if sigma <= 1.15:
        return 7
    if sigma <= 1.5:
        return 9
    return 13


def _kernel_size_offset(sigma_tile):
    if sigma_tile > 1.5:
        return 13
    if sigma_tile > 1.15:
        return 9
    if sigma_tile > 0.84:
        return 7
    return 5


def _run_tiles(lum0, check, kernel, n, iterations, iter_check, border):
    """Richardson-Lucy iterations for a batch of tiles; returns final tmpIThr (T, F, F)."""
    T, F, _ = lum0.shape
    r = n // 2
    tmpI = lum0.copy()
    tmp = np.ones_like(lum0)
    lo, hi = r, F - r
    floor = f32(0.00001)
    active = np.arange(T)
    for k in range(iterations):
        if active.size == 0:
            break
        if active.size == T:
            # All tiles still iterating: operate on the arrays in place (no gather/scatter).
            if ACTIVE_LOG is not None:
                with _LOG_LOCK:
                    while len(ACTIVE_LOG) <= k:
                        ACTIVE_LOG.append(0)
                    ACTIVE_LOG[k] += int(T)
            tmp[:, lo:hi, lo:hi] = lum0[:, lo:hi, lo:hi] / np.maximum(_conv(tmpI, kernel, n), floor)
            tmpI[:, lo:hi, lo:hi] *= _conv(tmp, kernel, n)
            if iter_check and k < iterations - 1:
                core = tmpI[:, border:F - border, border:F - border]
                stop = (core < check).reshape(T, -1).any(axis=1)
                active = active[~stop]
            continue
        if ACTIVE_LOG is not None:
            with _LOG_LOCK:
                while len(ACTIVE_LOG) <= k:
                    ACTIVE_LOG.append(0)
                ACTIVE_LOG[k] += int(active.size)
        cur = tmpI[active]
        tmp_a = tmp[active]
        tmp_a[:, lo:hi, lo:hi] = lum0[active][:, lo:hi, lo:hi] / np.maximum(_conv(cur, kernel, n), floor)
        tmp[active] = tmp_a
        cur[:, lo:hi, lo:hi] *= _conv(tmp_a, kernel, n)
        tmpI[active] = cur
        if iter_check and k < iterations - 1:
            core = cur[:, border:F - border, border:F - border]
            stop = (core < check[active]).reshape(len(active), -1).any(axis=1)
            active = active[~stop]
    return tmpI


def capture_deconv_sharpening(y_new, y_old, blend, sigma, sigma_corner_offset, iterations, iter_check, batch=1024):
    """CaptureDeconvSharpening; updates and returns y_new."""
    H, W = y_old.shape
    sigma = f32(sigma)
    off = f32(sigma_corner_offset)
    fixed = off == 0
    n_fixed = _kernel_size_fixed(sigma) if fixed else None
    tile = 32
    if fixed and n_fixed in (3, 5, 7):
        border = 5 if iterations <= 30 else 7
    else:
        border = 8
    F = tile + 2 * border
    corner_radius = f32(min(f32(2.0), f32(sigma + off)))
    corner_distance = f32(np.sqrt(f32(W * f32(0.5)) ** 2 + f32(H * f32(0.5)) ** 2))
    distance_factor = f32((corner_radius - sigma) / corner_distance)
    min_blend = f32(0.01)

    # Enumerate tiles in RT order and their source window origins.
    tiles = []
    for i in range(border, H - border, tile):
        for j in range(border, W - border, tile):
            end_col = i + tile + border >= H
            end_row = j + tile + border >= W
            oy = H - F if end_col else i - border
            ox = W - F if end_row else j - border
            tiles.append((i, j, oy, ox))
    if not tiles:
        return y_new
    ti = np.array(tiles)
    cy, cx = ti[:, 2] + border, ti[:, 3] + border
    bwin = sliding_window_view(blend, (tile, tile))
    keep = bwin[cy, cx].reshape(len(ti), -1).max(axis=1) >= min_blend

    # Kernel per tile.
    if fixed:
        sizes = np.full(len(ti), n_fixed)
        sig_tile = np.full(len(ti), sigma, f32)
    else:
        dist = np.sqrt(((ti[:, 0] + tile // 2 - H // 2) ** 2 + (ti[:, 1] + tile // 2 - W // 2) ** 2).astype(np.float64)).astype(f32)
        sig_tile = (sigma + distance_factor * dist).astype(f32)
        sizes = np.array([_kernel_size_offset(s) if s >= f32(0.4) else 0 for s in sig_tile])

    owin = sliding_window_view(y_old, (F, F))
    result = {}
    groups = {}
    for idx in np.nonzero(keep)[0]:
        key = (int(sizes[idx]), float(sig_tile[idx]))
        groups.setdefault(key, []).append(idx)
    jobs = []
    for (n, st), idxs in groups.items():
        idxs = np.array(idxs)
        for s in range(0, len(idxs), batch):
            jobs.append((n, st, idxs[s:s + batch]))

    cwin = sliding_window_view((y_old * blend) * f32(0.5), (tile, tile)) if iter_check else None

    def work(job):
        n, st, part = job
        lum0 = owin[ti[part, 2], ti[part, 3]].copy()
        if n == 0:
            return part, lum0
        kern = _kernel(f32(st), n)
        check = cwin[ti[part, 2] + border, ti[part, 3] + border] if iter_check else None
        return part, _run_tiles(lum0, check, kern, n, iterations, iter_check, border)

    for part, out in _pmap(work, jobs):
        for t, p in enumerate(part):
            result[int(p)] = out[t]

    # Write back in RT's tile order. Only the last tile of a row/column overlaps
    # earlier ones, so four phases (interior, row ends, bottom row, corner) give
    # the same per-pixel sequence; tiles within a phase are disjoint.
    end_col = ti[:, 0] + tile + border >= H
    end_row = ti[:, 1] + tile + border >= W
    phase = end_row.astype(int) + 2 * end_col.astype(int)
    ar = np.arange(tile)
    for ph in range(4):
        ps = [p for p in np.nonzero(phase == ph)[0] if int(p) in result]
        if not ps:
            continue
        ps = np.array(ps)
        R = (ti[ps, 2] + border)[:, None, None] + ar[None, :, None]
        C = (ti[ps, 3] + border)[:, None, None] + ar[None, None, :]
        core = np.stack([result[int(p)][border:F - border, border:F - border] for p in ps])
        b = blend[R, C]
        cur = y_new[R, C]
        y_new[R, C] = b * (core - cur) + cur
    return y_new


# ---------------------------------------------------------------- top level


def capture_sharpening(raw, r, g, b, whites, clip_val, fc, params: Params, debug=False) -> Result | None:
    """RawImageSource::captureSharpening Bayer path (capturesharpening.cc:593-972).
    raw: CFA plane (H, W); r/g/b: demosaiced planes; whites[2][2]: per-CFA-position clip levels
    (white - black) * scale_mul * 0.95; clip_val: green white level (white - black) * scale_mul;
    fc: CFA colour of (0,0) and (1,0) (0=R, 1=G, 2=B). Returns None when RT would skip."""
    raw, r, g, b = (np.ascontiguousarray(a, f32) for a in (raw, r, g, b))
    contrast = f32(params.contrast / 100.0)
    clip = build_clip_mask_bayer(raw, whites)
    radius = float(params.radius)
    if params.auto_radius:
        radius = float(min(calc_radius_bayer(raw, 1000.0, clip_val, fc), f32(2.0)))
    if math.isnan(radius):
        return None
    L = rgb2L(r, g, b)
    y_old = rgb2Y(r, g, b)
    y_new = y_old.copy()
    blend, contrast = build_blend_mask(L, clip, contrast, params.auto_contrast)
    capture_deconv_sharpening(y_new, y_old, blend, f32(radius), f32(params.radius_offset), params.iterations, params.iter_check)
    factor = y_new / np.maximum(y_old, f32(0.00001))
    res = Result(r * factor, g * factor, b * factor, radius, float(contrast * f32(100.0)))
    if debug:
        res.clip_mask, res.L, res.blend, res.y_new = clip, L, blend, y_new
    return res
