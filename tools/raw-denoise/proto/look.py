"""Real night crops incl. the flat ones: ESD3D2 | base | lighter DNG presets | joined JPEG. usage: look.py out.png"""
import os, sys, numpy as np
from lib import *
import cfadct as C, joined
import esd3d2_port as E
from PIL import Image, ImageDraw
x, wb, iso, lsb = load_dng(os.path.expanduser('~/Downloads/My/Bursts/IMG_20260921_194618_520/IMG_20260921_194618_520_F00.dng'))
a, b, _ = estimate_noise(x)
pk = lambda **p: (lambda c: demosaic(C.denoise_packed(c, a, b, wb, p)))
M = {'ESD3D2': lambda c: E.esd3d2(np.clip(demosaic(c), 0, None), a, b)[0], 'base g.35': pk(grain=0.35),
     'DNG light (lam2.2 l.5 f.5)': pk(grain=0.35, luma=0.5, chroma1=0.5, lam=2.2),
     'JPEG joined hp2 g.35': lambda c: joined.joined(c, a, b, wb, dict(grain=0.35)),
     'JPEG joined hp2 g0': lambda c: joined.joined(c, a, b, wb, dict(grain=0.0))}
T = 384; S = 512; rows = []
for nm, y0, x0 in [('flat-mid', 784, 1296), ('flat-dark', 400, 528), ('detail-dark', 1424, 784), ('detail', 1424, 1424)]:
    c = x[y0:y0 + S, x0:x0 + S]; outs = {k: f(c) for k, f in M.items()}
    norm = max(np.percentile(outs['base g.35'][..., 1], 99.5) * 1.15, 1e-4); o = (S - T) // 2
    rows.append((nm, [(k, np.flipud(develop(v, wb, norm)[o:o + T, o:o + T])) for k, v in outs.items()]))
G = Image.new('RGB', (len(M) * T, len(rows) * (T + 14)), (20, 24, 26)); d = ImageDraw.Draw(G)
for r, (nm, tl) in enumerate(rows):
    for ci, (lab, im) in enumerate(tl):
        G.paste(Image.fromarray((im * 255 + 0.5).astype(np.uint8)), (ci * T, r * (T + 14) + 14)); d.text((ci * T + 3, r * (T + 14) + 1), f'{lab} | {nm}', fill=(255, 230, 0))
G.save(sys.argv[1])
