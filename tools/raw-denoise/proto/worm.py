import os, sys, numpy as np
from lib import *
import cfadct as C, steer
from PIL import Image, ImageDraw
x, wb, iso, lsb = load_dng(os.path.expanduser('~/Downloads/My/Bursts/IMG_20260921_194618_520/IMG_20260921_194618_520_F00.dng'))
a, b, _ = estimate_noise(x)
c = x[1424:1424 + 512, 784:784 + 512]; G = dict(grain=0.35)
V = {'base g.35': None, 'green k1': dict(pre='green'), 'green k.5 coh1': dict(pre='green', kappa=0.5, coh_pow=1.0),
     'green k1 coh2': dict(pre='green', kappa=1.0, coh_pow=2.0), 'green k2 e3': dict(pre='green', kappa=2.0, e_sigma=3.0)}
tiles = []
for k, sp in V.items():
    o = demosaic(C.denoise_packed(c, a, b, wb, G) if sp is None else steer.denoise(c, a, b, wb, G, sp))
    if not tiles: norm = np.percentile(o[..., 1], 99.5) * 1.15
    im = Image.fromarray((np.flipud(develop(o, wb, norm)[130:450, 60:380]) * 255 + 0.5).astype(np.uint8)); ImageDraw.Draw(im).text((3, 2), k, fill=(255, 230, 0)); tiles.append(np.asarray(im))
Image.fromarray(np.concatenate(tiles, 1)).save(sys.argv[1])
