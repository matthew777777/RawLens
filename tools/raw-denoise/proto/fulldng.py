"""Denoise a whole DNG with the Vulkan host and write a patched copy. usage: fulldng.py in.dng out.dng [grain]"""
import sys, os, shutil, subprocess, numpy as np, tifffile
from lib import vals
HOST = '/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/raw-denoise/host/build'
src, dst = sys.argv[1], sys.argv[2]; grain = sys.argv[3] if len(sys.argv) > 3 else '0.35'
t = tifffile.TiffFile(src); pg = t.pages[0]; tg = {x.name: x for x in pg.tags.values()}
raw = pg.asarray(); h, w = raw.shape
assert pg.compression == 1 and raw.dtype == np.uint16 and pg.is_contiguous, 'need one uncompressed strip run'
off = pg.dataoffsets[0]
black = float(np.mean(vals(tg['BlackLevel']))); white = float(vals(tg['WhiteLevel'])[0])
asn = np.array(vals(tg['AsShotNeutral'])); wb = asn[1] / asn
cfa = {(0, 1, 1, 2): 0, (1, 0, 2, 1): 1, (1, 2, 0, 1): 2, (2, 1, 1, 0): 3}[tuple(tg['CFAPattern'].value)]
raw.tofile('f_in.u16')
r = subprocess.run([HOST + '/rawdn_host', HOST, 'f_in.u16', str(w), str(h), str(cfa), str(black), str(white), str(wb[0]), str(wb[2]), 'f_out.u16',
                    '0', '0', grain, '4', '3'], capture_output=True, text=True)
print(os.path.basename(src), f'{w}x{h} cfa {cfa}', r.stdout.replace('\n', ' | '), r.stderr[-300:])
out = np.fromfile('f_out.u16', np.uint16)
shutil.copyfile(src, dst)
with open(dst, 'r+b') as f:
    f.seek(off); f.write(out.astype('<u2' if t.byteorder == '<' else '>u2').tobytes())
chk = tifffile.imread(dst)
print('  written', dst, 'mean in/out', raw.mean().round(3), chk.mean().round(3), 'std of change', (chk.astype(float) - raw).std().round(2))
