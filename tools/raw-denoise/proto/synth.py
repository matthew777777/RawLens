"""Synthetic-noise scorecard: clean = 2x-binned ISO 50 frames, Poisson-Gaussian noise added to the CFA."""
import sys, os, time, json, numpy as np
sys.path.insert(0, '/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/wavelet-parity')
import esd3d2_port as E
from lib import *
import cfadct as C
from PIL import Image, ImageDraw
B = os.path.expanduser('~/Downloads/My/Bursts/')
SRC = [B + 'IMG_20260929_170823_957/IMG_20260929_170823_957_F00.dng', B + 'IMG_20261010_115802_466/IMG_20261010_115802_466_F00.dng',
       B + 'IMG_20261107_132932_323/IMG_20261107_132932_323_F00.dng', B + 'IMG_20260919_184641_979/IMG_20260919_184641_979_F00.dng']
LEVELS = {'hi': (1.3e-3, 7.5e-6, 0.3), 'mid': (4e-4, 5e-7, 0.5), 'lo': (1e-4, 1e-7, 1.0)}
S = 512

def cleans():
    out = []
    for p in SRC:
        x, wb, iso, _ = load_dng(p, clip=True)
        rgb = demosaic(x)
        h, w, _ = rgb.shape
        rgb = rgb.reshape(h // 2, 2, w // 2, 2, 3).mean((1, 3))
        g = rgb[..., 1]
        gy, gx = np.gradient(uniform_filter(g, 3))
        dens = uniform_filter(np.hypot(gy, gx), S)[S // 2:-S // 2:32, S // 2:-S // 2:32]
        lum = uniform_filter(g, S)[S // 2:-S // 2:32, S // 2:-S // 2:32]
        okl = (lum > 0.03) & (lum < 0.5)
        for name, sc in (('detail', np.where(okl, dens, -1)), ('flat', np.where(okl, -dens, -1e9))):
            iy, ix = np.unravel_index(np.argmax(sc), sc.shape)
            y0, x0 = (iy * 32) & ~1, (ix * 32) & ~1
            out.append((os.path.basename(p)[13:19] + name, rgb[y0:y0 + S, x0:x0 + S].copy(), wb))
    return out

def run(methods, levels=('hi', 'mid', 'lo'), grid=None, seed=0):
    rng = np.random.default_rng(seed)
    res = {}; tiles = []
    for name, rgb, wb in cleans():
        for lv in levels:
            a, b, sc = LEVELS[lv]
            clean_rgb = rgb * sc
            cc = mosaic(clean_rgb)
            noisy = (rng.poisson(np.maximum(cc, 0) / a) * a + rng.normal(0, np.sqrt(b), cc.shape)).astype(F32)
            norm = np.percentile(clean_rgb[..., 1], 99) * 1.3
            ref = develop(clean_rgb, wb, norm)
            row = []
            for mname, fn in methods.items():
                t = time.time(); o = fn(noisy, a, b, wb); dt = time.time() - t
                d = develop(o, wb, norm)
                m = metrics(d, ref); m['t'] = dt
                res.setdefault((lv, mname), []).append(m)
                row.append((mname, d))
            tiles.append((f'{name} {lv}', [('clean', ref)] + row))
    print(f"{'level':5s} {'method':22s} {'PSNR':>6s} {'Y':>6s} {'C':>6s} {'blot':>6s} {'tex':>5s} {'s':>5s}")
    for (lv, mname), ms in res.items():
        k = lambda q: np.mean([m[q] for m in ms])
        print(f"{lv:5s} {mname:22s} {k('psnr'):6.2f} {k('y'):6.2f} {k('c'):6.2f} {k('blot'):6.2f} {k('tex'):5.2f} {k('t'):5.1f}")
    if grid:
        Cc = 256
        for lv in levels:
            rows = [t for t in tiles if t[0].endswith(lv)]
            G = Image.new('RGB', (len(rows[0][1]) * Cc, len(rows) * (Cc + 14)), (20, 24, 26)); dr = ImageDraw.Draw(G)
            for r, (nm, tl) in enumerate(rows):
                for c, (lab, im) in enumerate(tl):
                    G.paste(Image.fromarray((im[128:128 + Cc, 128:128 + Cc] * 255 + 0.5).astype(np.uint8)), (c * Cc, r * (Cc + 14) + 14))
                    dr.text((c * Cc + 3, r * (Cc + 14) + 1), f'{lab} | {nm}', fill=(255, 230, 0))
            G.save(f'{grid}_{lv}.png')
    return res

def m_noisy(x, a, b, wb): return demosaic(x)
def m_esd(x, a, b, wb): return E.esd3d2(np.clip(demosaic(x), 0, None), a, b)[0]
def m_cfa(**prm):
    return lambda x, a, b, wb: demosaic(C.denoise_cfa(x, a, b, wb, prm))

def m_pk(**prm):
    return lambda x, a, b, wb: demosaic(C.denoise_packed(x, a, b, wb, prm))

if __name__ == '__main__':
    run({'noisy': m_noisy, 'ESD3D2': m_esd, 'cfadct': m_cfa(), 'cfadct L0only': m_cfa(levels=0)}, grid='g0')
