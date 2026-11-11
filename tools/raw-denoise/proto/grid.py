"""1:1 comparison grids (lossless PNG). usage: grid.py <out_dir> [noise_store [real]]
With a noise store, real frames take their noise from the learned model (rawdn_choose_noise, host build).
Synthetic: clean reference | noisy | ESD3D2 | rawdn.  Real frames: input | ESD3D2 | rawdn."""
import sys, os, numpy as np
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../wavelet-parity'))
import esd3d2_port as E
from lib import *
import cfadct as C
import synth, real, joined
from PIL import Image, ImageDraw
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
STORE = sys.argv[2] if len(sys.argv) > 2 else ''
ONLY_REAL = len(sys.argv) > 3 and sys.argv[3] == 'real'
HOST = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../host/build')

def model_noise(path):
    """(a, b) from the learned model; works on a copy of the store so rendering does not train it."""
    import subprocess, shutil, tempfile, re, tifffile
    t = tifffile.TiffFile(path); pg = t.pages[0]; tg = {x.name: x for x in pg.tags.values()}
    raw = pg.asarray(); h, w = raw.shape
    black = float(np.mean(vals(tg['BlackLevel']))); white = float(vals(tg['WhiteLevel'])[0]); asn = np.array(vals(tg['AsShotNeutral'])); wbt = asn[1] / asn
    cfa = {(0, 1, 1, 2): 0, (1, 0, 2, 1): 1, (1, 2, 0, 1): 2, (2, 1, 1, 0): 3}[tuple(tg['CFAPattern'].value)]
    with tempfile.TemporaryDirectory() as d:
        raw.tofile(d + '/in.u16'); shutil.copyfile(STORE, d + '/store.txt')
        o = subprocess.run([HOST + '/rawdn_host', HOST, d + '/in.u16', str(w), str(h), str(cfa), str(black), str(white), str(wbt[0]), str(wbt[2]), '-',
                            '0', '0', '0.35', '4', '1', str(float(tg['ISOSpeedRatings'].value)), d + '/store.txt', '0'], capture_output=True, text=True).stdout
    print('  ', o.strip())
    m = re.search(r'source=(\d) a=(\S+) b=(\S+)', o)
    assert m and m.group(1) == '2', 'learned model not active: ' + o
    return float(m.group(2)), float(m.group(3))
BEST = dict(grain=0.35)          # everything else at cfadct.DEFAULT
B = os.path.expanduser('~/Downloads/My/Bursts/')
T = 512; BAR = 18

def save(rows, path):
    n = len(rows[0][1])
    G = Image.new('RGB', (n * T + (n - 1) * 4, len(rows) * (T + BAR)), (20, 24, 26)); d = ImageDraw.Draw(G)
    for r, (nm, tl) in enumerate(rows):
        for c, (lab, im) in enumerate(tl):
            G.paste(Image.fromarray((np.flipud(im) * 255 + 0.5).astype(np.uint8)), (c * (T + 4), r * (T + BAR) + BAR))
            d.text((c * (T + 4) + 4, r * (T + BAR) + 3), f'{lab}  |  {nm}', fill=(255, 230, 0))
    G.save(path); print('wrote', path, G.size)

ours = lambda x, a, b, wb: demosaic(C.denoise_packed(x, a, b, wb, BEST))
DNG_GRAIN = dict(grain=0.6, grain_y=False)   # base + uniform band-limited luma grain (carrier-free, stays in the CFA)
light = lambda x, a, b, wb: demosaic(C.denoise_packed(x, a, b, wb, DNG_GRAIN))
jpeg = lambda x, a, b, wb: joined.blend(x, a, b, wb, dict(grain=0.0), 0.45)   # base + uniform full-band luma after demosaic
esd = lambda x, a, b, wb: E.esd3d2(np.clip(demosaic(x), 0, None), a, b)[0]

# synthetic, with a true clean reference
rng = np.random.default_rng(0)
cl = synth.cleans()
for lv in (() if ONLY_REAL else ('hi', 'mid')):
    a, b, sc = synth.LEVELS[lv]; rows = []
    for name, rgb, wb in cl:
        c = rgb * sc; cc = mosaic(c)
        noisy = (rng.poisson(np.maximum(cc, 0) / a) * a + rng.normal(0, np.sqrt(b), cc.shape)).astype(F32)
        norm = np.percentile(c[..., 1], 99) * 1.3
        rows.append((f'{name} synthetic {lv}', [('clean reference', develop(c, wb, norm)), ('noisy', develop(demosaic(noisy), wb, norm)),
                                               ('ESD3D2', develop(esd(noisy, a, b, wb), wb, norm)), ('rawdn DNG (luma grain .6)', develop(light(noisy, a, b, wb), wb, norm)), ('rawdn JPEG (uniform luma .45)', develop(jpeg(noisy, a, b, wb), wb, norm))]))
    save(rows, f'{OUT}/v4_synthetic_{lv}_reference_noisy_esd3d2_dng_jpeg.png')

# real frames: no clean reference exists, the input is the reference
for tag, f, n in (('night_iso4653', B + 'IMG_20260921_194618_520/IMG_20260921_194618_520_F00.dng', 5),
                  ('dusk_iso119', B + 'IMG_20260928_185731_891/IMG_20260928_185731_891_F00.dng', 4)):
    x, wb, iso, lsb = load_dng(f); a, b = model_noise(f) if STORE else estimate_noise(x)[:2]; S = 640; rows = []
    for nm, y0, x0 in real.pick(x, S, n):
        y0 = max(0, min(y0, x.shape[0] - S)) & ~1; x0 = max(0, min(x0, x.shape[1] - S)) & ~1
        c = x[y0:y0 + S, x0:x0 + S]; o = (S - T) // 2
        outs = [('input', demosaic(c)), ('ESD3D2', esd(c, a, b, wb)), ('rawdn DNG (luma grain .6)', light(c, a, b, wb)), ('rawdn JPEG (uniform luma .45)', jpeg(c, a, b, wb))]
        norm = max(np.percentile(outs[2][1][..., 1], 99.5) * 1.15, 1e-4)
        rows.append((f'{tag} {nm}', [(k, develop(v, wb, norm)[o:o + T, o:o + T]) for k, v in outs]))
    save(rows, f'{OUT}/v4_real_{tag}_input_esd3d2_dng_jpeg.png')
