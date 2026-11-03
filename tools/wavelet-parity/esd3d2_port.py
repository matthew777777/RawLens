"""NumPy port of PhotonCamera's ESD3D2 denoiser, for side-by-side comparison.

Source (GPL-3.0, github.com/eszdman/PhotonCamera):
  processing/opengl/postpipeline/ESD3D2.java (driver, default tunables)
  assets/shaders/denoise/esd3d2_steered.glsl (default "steered" version)
  assets/shaders/denoise/guidedupsample.glsl, utils/convdiff.glsl,
  GLUtils.gaussdown.

Input: camera-linear demosaiced RGB (PhotonCamera runs ESD3D2 right after
demosaic, before WB, the same place as RawLens' wavelet stage).
noise_s / noise_o: Poisson-Gaussian variance slope/offset in normalized units
(PhotonCamera's averaged NoiseModeler model; noiseRstr shift 0).

Out-of-range texel fetches clamp to the edge (GLSL leaves them undefined).
"""
import numpy as np

F32 = np.float32
W3 = np.array([0.25, 0.5, 0.25], F32)

# ESD3D2.java defaults
NOISE_TO_KERNEL = 24.0
NOISE_TARGET = 1.0 / 256.0
LUMA = 0.8
MAX_SIZE = 21
MIN_SIZE = 7
MOIRE = 1.5


def _pad(img, k):
    return np.pad(img, ((k, k), (k, k), (0, 0)), mode="edge")


def _sh(p, k, h, w, dy, dx):
    return p[k + dy:k + dy + h, k + dx:k + dx + w]


def convdiff(img):
    """utils/convdiff.glsl TEXSIZE 3: central differences of Rec.601 luma / 2."""
    l = (img * np.array([0.299, 0.587, 0.114], F32)).sum(-1)
    p = np.pad(l, 1, mode="reflect")
    gx = (p[1:-1, :-2] - p[1:-1, 2:]) / 2
    gy = (p[:-2, 1:-1] - p[2:, 1:-1]) / 2
    return np.stack([gx, gy], -1).astype(F32)


def gaussdown(img, k):
    """GLUtils.gaussdown(in, k): 5x5 taps, stride k/2, sigma (k/2)/stride."""
    stride = max(1, k // 2)
    sig = (k * 0.5) / stride
    h, w, _ = img.shape
    oh, ow = int(h / k + 0.5), int(w / k + 0.5)
    kern = np.exp(-0.5 * (np.arange(-2, 3) / sig) ** 2)
    pad = 2 * stride
    p = _pad(img, pad)
    ys = np.arange(oh) * k + pad
    xs = np.arange(ow) * k + pad
    ys = np.minimum(ys, p.shape[0] - 1 - pad)
    xs = np.minimum(xs, p.shape[1] - 1 - pad)
    out = np.zeros((oh, ow, img.shape[2]), F32)
    tot = 1e-5
    for i in range(-2, 3):
        for j in range(-2, 3):
            wgt = kern[i + 2] * kern[j + 2]
            out += wgt * p[np.ix_(ys + j * stride, xs + i * stride)]
            tot += wgt
    return (out / tot).astype(F32)


def esd3d_run(img, grad, noise_s, noise_o, moire):
    """One esd3d2_steered.glsl pass."""
    noise_mpy = max((noise_s + noise_o) / NOISE_TARGET, 1e-7)
    ksize_f = 1.0 + np.sqrt(noise_mpy) * NOISE_TO_KERNEL
    msize = min(MIN_SIZE + int(ksize_f) - int(ksize_f) % 2, MAX_SIZE)
    K = (msize - 1) // 2
    h, w, _ = img.shape
    P = _pad(img, K + 1)
    G = _pad(grad, 1)
    cin = img
    s = lambda dy, dx: _sh(P, K + 1, h, w, dy, dx)
    cavg = (cin + s(0, 1) + s(1, 0) + s(1, 1)) / 4
    nf = (cin * W3).sum(-1)
    xD = np.zeros((h, w), F32); yD = np.zeros((h, w), F32)
    sxx = np.zeros((h, w), F32); sxy = np.zeros((h, w), F32); syy = np.zeros((h, w), F32)
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            g = _sh(G, 1, h, w, j, i)
            ln = np.hypot(g[..., 0], g[..., 1])
            xD += i * ln; yD += j * ln
            sxx += g[..., 0] ** 2; sxy += g[..., 0] * g[..., 1]; syy += g[..., 1] ** 2
    tr = sxx + syy
    dlt = np.sqrt(np.maximum((sxx - syy) ** 2 + 4 * sxy * sxy, 0))
    lam1 = 0.5 * (tr + dlt); lam2 = np.maximum(0.5 * (tr - dlt), 0)
    coh = ((lam1 - lam2) / np.maximum(lam1 + lam2, 1e-6)) ** 2
    tx, ty = sxy, lam1 - sxx
    deg = (tx * tx + ty * ty) <= 1e-12
    tx = np.where(deg, 1.0, tx); ty = np.where(deg, 0.0, ty)
    tl = np.maximum(np.hypot(tx, ty), 1e-6); tx /= tl; ty /= tl
    xD /= 3; yD /= 3
    sigY = np.maximum(noise_s * nf + noise_o, 1e-7)[..., None]
    cd = (np.abs(cavg - s(0, 1)) + np.abs(cavg - s(1, 0)) + np.abs(cavg - s(1, 1)) + np.abs(cavg - cin)) / 4
    cd = cd * np.maximum(np.abs(xD), np.abs(yD))[..., None]
    cn = cd.max(-1) - cd.min(-1)
    sigZ = np.maximum(sigY[..., 0], np.minimum(np.abs(cn) * moire, 0.2))[..., None]
    # self-guided 3x3 reference
    gref = np.zeros_like(img); gW = np.zeros((h, w, 1), F32)
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            c = s(j, i)
            dd2 = ((c - cin) ** 2).sum(-1, keepdims=True)
            ww = np.maximum(0, 1 - dd2 / (dd2 + sigY))
            gref += c * ww; gW += ww
    cref = gref / np.maximum(gW, 1e-4)
    npdf = lambda x: 0.39894 * np.exp(-0.5 * x * x / (ksize_f * ksize_f)) / ksize_f
    fc = cin * 0.01; fc2 = cin * 0.01
    Z = np.full((h, w, 1), 0.01, F32); Z2 = Z.copy()
    for i in range(K + 1):
        for j in range(K + 1):
            pos = [(i, j), (-i, -j), (i, -j), (-i, j)]   # (x, y)
            cc = [s(y, x) for x, y in pos]
            d2 = [((c - cref) ** 2).sum(-1, keepdims=True) for c in cc]
            wv = [1 - d / (d + sigY) for d in d2]
            w2 = [1 - d / (d + sigZ) for d in d2]
            wm = np.minimum(np.minimum(wv[0], wv[1]), np.minimum(wv[2], wv[3]))
            ws = [x - wm for x in wv]
            n1 = np.sqrt(sum(x * x for x in ws)) + 1e-6
            w2s = [x - wm for x in w2]
            n2 = np.sqrt(sum(x * x for x in w2s)) + 1e-6
            ut = i * tx + j * ty
            f1 = (npdf(float(i)) * npdf(float(j)) * (1 - coh) + npdf(ut) * coh)[..., None]
            for q in range(4):
                a = f1 * wv[q] * ws[q] / n1
                b = f1 * w2[q] * w2s[q] / n2
                fc += a * cc[q]; fc2 += b * cc[q]
                Z += a; Z2 += b
    br = ((fc / Z) * W3).sum(-1)
    br = (1 - LUMA) * nf + LUMA * br
    res = fc2 / Z2
    res = res / np.maximum(1e-6, (res * W3).sum(-1, keepdims=True))
    return np.maximum(res * br[..., None], 0).astype(F32), msize


def guided_upsample(low, guide_high, scale):
    """denoise/guidedupsample.glsl: Gaussian-weighted LS of low-res colour
    on full-res lightness, radius SCALE, then lightness restored to the guide."""
    R = scale; sig = 0.4 * R
    h, w, _ = guide_high.shape
    L = guide_high.mean(-1)
    yi = np.minimum(np.arange(h) // scale, low.shape[0] - 1)
    xi = np.minimum(np.arange(w) // scale, low.shape[1] - 1)
    lowup = low[np.ix_(yi, xi)]
    Lp = np.pad(L, R, mode="edge"); Up = _pad(lowup, R)
    mX = np.zeros((h, w), F32); mY = np.zeros((h, w, 3), F32)
    mX2 = np.zeros((h, w), F32); mXY = np.zeros((h, w, 3), F32); ws = 0.0
    for i in range(-R, R + 1):
        for j in range(-R, R + 1):
            wt = np.exp(-(i * i) / (2 * sig * sig)) * np.exp(-(j * j) / (2 * sig * sig))
            l = Lp[R + j:R + j + h, R + i:R + i + w]
            u = Up[R + j:R + j + h, R + i:R + i + w]
            mX += wt * l; mY += wt * u; mX2 += wt * l * l; mXY += wt * l[..., None] * u; ws += wt
    mX /= ws; mY /= ws; mX2 /= ws; mXY /= ws
    var = mX2 - mX * mX
    a = (mXY - mX[..., None] * mY) / (np.maximum(var, 0) + 3e-4)[..., None]
    b = mY - a * mX[..., None]
    out = a * L[..., None] + b
    outL = out.mean(-1)
    return (out * (L / np.maximum(outL, 0.25 * L + 1e-12))[..., None]).astype(F32)


def esd3d2(img, noise_s, noise_o, color=True):
    """ESD3D2.Run with default tunables. Returns (output, info dict)."""
    img = img.astype(F32)
    N = np.sqrt(0.5 * noise_s + noise_o)
    scaleF = float(np.clip(N / NOISE_TARGET, 1.0, 4.0))
    scale = int(scaleF + 0.5)
    info = dict(scale=scale)
    if not color:
        outp = img
    elif scale != 1:
        low = gaussdown(img, scale)
        lowd, info["low_msize"] = esd3d_run(low, convdiff(low), noise_s / (scaleF * 0.75),
                                            noise_o / (scaleF * 0.75), 0.0)
        outp = guided_upsample(lowd, img, scale)
    else:
        d, info["low_msize"] = esd3d_run(img, convdiff(img), noise_s, noise_o, 0.0)
        outp = guided_upsample(d, img, 1)
    out, info["msize"] = esd3d_run(outp, convdiff(outp), noise_s, noise_o, MOIRE)
    return out, info
