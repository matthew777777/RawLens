"""High-frequency detail retention vs the clean reference, on the textured half of each crop.
keep  = regression slope of output HF on clean HF (1 = all real detail kept, <1 = smeared)
noise = rms of the HF part of the output that is NOT explained by the clean image, relative to clean HF rms
Bands on display-referred luma: fine = 1-2 px (x - gauss 1), mid = 2-4 px (gauss 1 - gauss 2.5)."""
import sys, os, numpy as np
from scipy.ndimage import gaussian_filter, uniform_filter
import synth
from synth import *
def bands(y):
    g1 = gaussian_filter(y, 1.0); g2 = gaussian_filter(y, 2.5)
    return y - g1, g1 - g2
def run(methods, levels=('hi', 'mid', 'lo')):
    rng = np.random.default_rng(0); cl = cleans(); acc = {}
    for name, rgb, wb in cl:
        for lv in levels:
            a, b, sc = LEVELS[lv]; c = rgb * sc; cc = mosaic(c)
            noisy = (rng.poisson(np.maximum(cc, 0) / a) * a + rng.normal(0, np.sqrt(b), cc.shape)).astype(F32)
            norm = np.percentile(c[..., 1], 99) * 1.3
            yr = ycc(develop(c, wb, norm))[0]; fr, mr = bands(yr)
            act = uniform_filter(fr * fr, 9); tex = act > np.median(act); tex[:16] = tex[-16:] = False; tex[:, :16] = tex[:, -16:] = False
            for mn, fn in methods.items():
                o = demosaic(mosaic(c)) if fn is None else fn(noisy, a, b, wb)
                fo, mo = bands(ycc(develop(o, wb, norm))[0])
                r = []
                for ro, rr in ((fo, fr), (mo, mr)):
                    x = rr[tex]; y = ro[tex]; k = float((x * y).sum() / (x * x).sum())
                    r += [k, float(np.sqrt(((y - k * x) ** 2).mean()) / np.sqrt((x * x).mean()))]
                acc.setdefault((lv, mn), []).append(r)
    print(f"{'level':5s} {'method':26s} {'fine keep':>9s} {'fine noise':>10s} {'mid keep':>9s} {'mid noise':>10s}")
    for (lv, mn), v in acc.items():
        m = np.mean(v, 0); print(f"{lv:5s} {mn:26s} {m[0]:9.2f} {m[1]:10.2f} {m[2]:9.2f} {m[3]:10.2f}")
if __name__ == '__main__':
    run({'clean CFA demosaiced': None, 'noisy': m_noisy, 'ESD3D2': m_esd, 'rawdn grain 0': m_pk(), 'rawdn grain .35': m_pk(grain=0.35),
         'rawdn luma .5 g.35': m_pk(grain=0.35, luma=0.5), 'rawdn luma .5 fine .5 g.35': m_pk(grain=0.35, luma=0.5, chroma1=0.5),
         'rawdn lam 2.2 luma .5 f .5': m_pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2)})
