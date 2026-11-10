import os, sys, numpy as np
from lib import *
import cfadct as C, joined
import esd3d2_port as E
from PIL import Image, ImageDraw
x, wb, iso, lsb = load_dng(os.path.expanduser('~/Downloads/My/Bursts/IMG_20260921_194618_520/IMG_20260921_194618_520_F00.dng'))
a, b, _ = estimate_noise(x)
pk = lambda **p: (lambda c: demosaic(C.denoise_packed(c, a, b, wb, p)))
G0 = dict(grain=0.0)
M = {'ESD3D2': lambda c: E.esd3d2(np.clip(demosaic(c), 0, None), a, b)[0],
     'DNG binom .6': pk(grain=0.6, grain_y=False), 'DNG binom .5 + raw .2': pk(grain=0.5, grain_y=False, grain_raw=0.2),
     'JPEG blend .4': lambda c: joined.blend(c, a, b, wb, G0, 0.4), 'JPEG blend .55': lambda c: joined.blend(c, a, b, wb, G0, 0.55)}
T = 256; Z = 2; rows = []
for nm, y0, x0, oy, ox in [('detail-dark', 1424, 784, 60, 100), ('flat-dark', 400, 528, 128, 128), ('detail', 1424, 1424, 20, 150)]:
    c = x[y0:y0 + 512, x0:x0 + 512]; outs = {k: f(c) for k, f in M.items()}
    norm = max(np.percentile(outs['DNG binom .6'][..., 1], 99.5) * 1.15, 1e-4)
    rows.append((nm, [(k, np.flipud(develop(v, wb, norm))[oy:oy + T, ox:ox + T]) for k, v in outs.items()]))
W = T * Z; G = Image.new('RGB', (len(M) * W, len(rows) * (W + 14)), (20, 24, 26)); d = ImageDraw.Draw(G)
for r, (nm, tl) in enumerate(rows):
    for ci, (lab, im) in enumerate(tl):
        G.paste(Image.fromarray((im * 255 + 0.5).astype(np.uint8)).resize((W, W), Image.NEAREST), (ci * W, r * (W + 14) + 14)); d.text((ci * W + 3, r * (W + 14) + 1), f'{lab} | {nm}', fill=(255, 230, 0))
G.save(sys.argv[1])
