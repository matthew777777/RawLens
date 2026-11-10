"""Real frame grid: noisy | ESD3D2 | candidates. usage: real.py <dng> <out.png> [ncrops]"""
import sys, os, numpy as np
sys.path.insert(0, '/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/wavelet-parity')
import esd3d2_port as E
from lib import *
import cfadct as C
from PIL import Image, ImageDraw

def pick(x, S, n):
    g = uniform_filter(x, 2)[::2, ::2]
    gy, gx = np.gradient(uniform_filter(g, 5))
    dens = uniform_filter(np.hypot(gy, gx), S // 2); lum = uniform_filter(g, S // 2)
    st = 64; h = S // 4 + 8
    dens = dens[h:-h:st, h:-h:st]; lum = lum[h:-h:st, h:-h:st]
    med = np.median(lum); out = []
    def take(score, name):
        sc = score.copy()
        for (_, yy, xx) in out:
            sc[max(0, yy - 4):yy + 5, max(0, xx - 4):xx + 5] = -1e9
        iy, ix = np.unravel_index(np.argmax(sc), sc.shape); out.append((name, iy, ix))
    take(dens / dens.mean() - 2 * np.abs(np.log(np.maximum(lum, 1e-4) / med)) * 0.2, 'detail')
    take(-dens / dens.mean() - np.abs(np.log(np.maximum(lum, 1e-4) / med)), 'flat-mid')
    take(-dens / dens.mean() - np.abs(np.log(np.maximum(lum, 1e-4) / np.percentile(lum, 15))), 'flat-dark')
    take(dens / dens.mean() * (lum > np.percentile(lum, 70)), 'detail-bright')
    take(dens / dens.mean() * (lum < np.percentile(lum, 40)), 'detail-dark')
    return [(nm, ((iy * st + h) * 2 - S // 2) & ~1, ((ix * st + h) * 2 - S // 2) & ~1) for nm, iy, ix in out[:n]]

def main(path, outp, methods, S=512, V=384, n=5, crops=None):
    x, wb, iso, lsb = load_dng(path)
    a, b, _ = estimate_noise(x)
    print(os.path.basename(path), 'iso', iso, f'a={a:.3g} b={b:.3g}')
    rows = []
    for nm, y0, x0 in (crops or pick(x, S, n)):
        y0 = max(0, min(y0, x.shape[0] - S)) & ~1; x0 = max(0, min(x0, x.shape[1] - S)) & ~1
        c = x[y0:y0 + S, x0:x0 + S]
        outs = {k: f(c, a, b, wb) for k, f in methods.items()}
        ref = outs[list(outs)[-1]]
        norm = max(np.percentile(ref[..., 1], 99.5) * 1.15, 1e-4)
        o = (S - V) // 2
        rows.append((f'{nm} y{y0} x{x0}', [(k, develop(v, wb, norm)[o:o + V, o:o + V]) for k, v in outs.items()]))
    G = Image.new('RGB', (len(methods) * V, len(rows) * (V + 14)), (20, 24, 26)); dr = ImageDraw.Draw(G)
    for r, (nm, tl) in enumerate(rows):
        for c, (lab, im) in enumerate(tl):
            G.paste(Image.fromarray((im * 255 + 0.5).astype(np.uint8)), (c * V, r * (V + 14) + 14))
            dr.text((c * V + 3, r * (V + 14) + 1), f'{lab} | {nm}', fill=(255, 230, 0))
    G.save(outp)

noisy = lambda x, a, b, wb: demosaic(x)
esd = lambda x, a, b, wb: E.esd3d2(np.clip(demosaic(x), 0, None), a, b)[0]
pk = lambda **p: (lambda x, a, b, wb: demosaic(C.denoise_packed(x, a, b, wb, p)))
if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], {'noisy': noisy, 'ESD3D2': esd, 'rawdn grain 0': pk(), 'rawdn grain .35': pk(grain=0.35)},
         n=int(sys.argv[3]) if len(sys.argv) > 3 else 5)
