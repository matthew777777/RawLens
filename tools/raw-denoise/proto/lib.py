"""Shared helpers: DNG load, noise estimate, demosaic, display, metrics."""
import numpy as np, tifffile, cv2
from scipy.ndimage import uniform_filter

F32 = np.float32


def vals(tag):
    v = tag.value
    v = v if isinstance(v, tuple) else (v,)
    return [v[i] / v[i + 1] for i in range(0, len(v), 2)] if 'RATIONAL' in str(tag.dtype) else list(v)


def load_dng(path, clip=False):
    """-> CFA (RGGB, float32, black-subtracted, normalised, NOT clipped at 0), wb (r,g,b gains), iso."""
    t = tifffile.TiffFile(path)
    pg = t.pages[0]
    tg = {x.name: x for x in pg.tags.values()}
    raw = pg.asarray().astype(F32)
    black = float(np.mean(vals(tg['BlackLevel'])))
    white = float(vals(tg['WhiteLevel'])[0])
    cfa = tuple(tg['CFAPattern'].value)
    asn = np.array(vals(tg['AsShotNeutral']))
    wb = (asn[1] / asn).astype(F32)
    iso = tg['ISOSpeedRatings'].value if 'ISOSpeedRatings' in tg else 0
    x = (raw - black) / (white - black)
    if cfa == (1, 2, 0, 1):      # G B / R G -> vertical flip -> RGGB
        x = x[::-1]
    elif cfa == (0, 1, 1, 2):
        pass
    elif cfa == (2, 1, 1, 0):    # BGGR
        x = x[::-1, ::-1]
    elif cfa == (1, 0, 2, 1):    # G R / B G
        x = x[:, ::-1]
    else:
        raise ValueError(cfa)
    x = np.ascontiguousarray(x[: x.shape[0] & ~1, : x.shape[1] & ~1])
    if clip:
        x = np.clip(x, 0, 1)
    return x, wb, iso, 1.0 / (white - black)


def estimate_noise(x, q=None):
    """Blind Poisson-Gaussian fit var = a*x + b from Gr-Gb differences on 16x16 px blocks."""
    g1 = x[0::2, 1::2]; g2 = x[1::2, 0::2]
    h, w = g1.shape
    B = 8
    h2, w2 = h // B * B, w // B * B
    d = (g1 - g2)[:h2, :w2].reshape(h2 // B, B, w2 // B, B)
    m = (0.5 * (g1 + g2))[:h2, :w2].reshape(h2 // B, B, w2 // B, B)
    mean = m.mean((1, 3)).ravel()
    var = (d.var((1, 3)) / 2).ravel()
    spread = m.std((1, 3)).ravel()          # texture indicator (std of block means of pixels)
    ok = (mean > 0.002) & (mean < 0.7)
    mean, var, spread = mean[ok], var[ok], spread[ok]
    edges = np.quantile(mean, np.linspace(0, 1, 25))
    xs, ys, ws = [], [], []
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = (mean >= lo) & (mean < hi)
        if s.sum() < 40:
            continue
        v = var[s]; sp = spread[s]
        keep = sp <= np.quantile(sp, 0.3)    # flattest blocks of this brightness
        xs.append(mean[s][keep].mean()); ys.append(np.median(v[keep]) / 0.98); ws.append(keep.sum())
    xs, ys = np.array(xs), np.array(ys)
    A = np.stack([xs, np.ones_like(xs)], 1)
    wgt = 1.0 / np.maximum(ys, 1e-12)        # relative error
    sol, *_ = np.linalg.lstsq(A * wgt[:, None], ys * wgt, rcond=None)
    a, b = float(max(sol[0], 1e-9)), float(max(sol[1], 0.0))
    return a, b, (xs, ys)


def demosaic(cfa):
    """RGGB CFA float -> RGB float (camera linear). OpenCV edge-aware on 16 bit."""
    lo = min(float(cfa.min()), 0.0)
    u = np.clip((cfa - lo) * 60000.0, 0, 65535).astype(np.uint16)
    rgb = cv2.cvtColor(u, cv2.COLOR_BayerBG2RGB_EA)   # OpenCV naming: BG == RGGB
    return rgb.astype(F32) / 60000.0 + lo


def mosaic(rgb):
    h, w, _ = rgb.shape
    c = np.empty((h, w), F32)
    c[0::2, 0::2] = rgb[0::2, 0::2, 0]
    c[0::2, 1::2] = rgb[0::2, 1::2, 1]
    c[1::2, 0::2] = rgb[1::2, 0::2, 1]
    c[1::2, 1::2] = rgb[1::2, 1::2, 2]
    return c


def develop(rgb, wb, norm=1.0, ev=0.0):
    """camera-linear RGB -> display-referred [0,1] (WB, exposure, sRGB curve)."""
    y = np.clip(rgb * wb / norm * 2.0 ** ev, 0, 1)
    return np.where(y <= 0.0031308, 12.92 * y, 1.055 * np.power(y, 1 / 2.4) - 0.055).astype(F32)


def ycc(d):
    y = 0.299 * d[..., 0] + 0.587 * d[..., 1] + 0.114 * d[..., 2]
    return y, (d[..., 2] - y) * 0.564, (d[..., 0] - y) * 0.713


def psnr(a, b):
    return float(-10 * np.log10(np.mean((a - b) ** 2) + 1e-12))


def metrics(out_disp, ref_disp, M=16):
    """PSNR on display-referred images: colour, luma, chroma; plus texture-kept ratio."""
    o = out_disp[M:-M, M:-M]; r = ref_disp[M:-M, M:-M]
    yo, cbo, cro = ycc(o); yr, cbr, crr = ycc(r)
    hp = lambda x: x - uniform_filter(x, 5)
    tex = np.std(hp(yo)) / max(np.std(hp(yr)), 1e-9)
    # low-frequency chroma error (blotches): blur 9x9 of the chroma error
    blot = np.sqrt(np.mean(uniform_filter(cbo - cbr, 9) ** 2 + uniform_filter(cro - crr, 9) ** 2))
    return dict(psnr=psnr(o, r), y=psnr(yo, yr),
                c=float(-10 * np.log10((np.mean((cbo - cbr) ** 2) + np.mean((cro - crr) ** 2)) / 2 + 1e-12)),
                blot=float(blot * 1000), tex=float(tex))
