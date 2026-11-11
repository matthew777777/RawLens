"""Replay the burst DNGs through rawdn_choose_noise: train the learned model on frames 1.., test on frame 0
and on a busy crop. usage: noise_replay.py <scratch_dir>"""
import sys, os, glob, subprocess, numpy as np, tifffile
from lib import vals
HOST = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../host/build')
S = sys.argv[1]; store = S + '/rawdn_noise_store.txt'
if os.path.exists(store): os.remove(store)
B = os.path.expanduser('~/Downloads/My/Bursts/')
CFA = {(0, 1, 1, 2): 0, (1, 0, 2, 1): 1, (1, 2, 0, 1): 2, (2, 1, 1, 0): 3}
def run(path, use_store, cap, crop=None):
    t = tifffile.TiffFile(path); pg = t.pages[0]; tg = {x.name: x for x in pg.tags.values()}
    raw = pg.asarray()
    if crop: raw = np.ascontiguousarray(raw[crop[0]:crop[0] + crop[2], crop[1]:crop[1] + crop[2]])
    h, w = raw.shape; raw.tofile(S + '/n_in.u16')
    black = float(np.mean(vals(tg['BlackLevel']))); white = float(vals(tg['WhiteLevel'])[0]); iso = float(tg['ISOSpeedRatings'].value)
    asn = np.array(vals(tg['AsShotNeutral'])); wb = asn[1] / asn
    args = [HOST + '/rawdn_host', HOST, S + '/n_in.u16', str(w), str(h), str(CFA[tuple(tg['CFAPattern'].value)]), str(black), str(white),
            str(wb[0]), str(wb[2]), '-', '0', '0', '0.35', '4', '1'] + ([str(iso), store, str(cap)] if use_store else [])
    r = subprocess.run(args, capture_output=True, text=True)
    return iso, r.stdout.strip() or r.stderr.strip()
bursts = sorted(glob.glob(B + '*'))
cap = 1
for d in bursts:                       # training: every frame but the first
    for f in sorted(glob.glob(d + '/*.dng'))[1:]:
        run(f, True, cap); cap += 1
print('trained on', cap - 1, 'frames;', open(store).read().count('\n') - 1, 'samples stored')
for d in bursts:
    f = sorted(glob.glob(d + '/*.dng'))[0]
    iso, o = run(f, True, 0)
    print(f'ISO {iso:6.0f} frame0   {o}')
f = sorted(glob.glob(bursts[-1] + '/*.dng'))[0]
for nm, crop in (('busy crop 1024', (1000, 1500, 1024)), ('busy crop 1024 b', (1800, 300, 1024))):
    for d in (bursts[3], bursts[5]):
        f = sorted(glob.glob(d + '/*.dng'))[0]
        iso, o1 = run(f, False, 0, crop); iso, o2 = run(f, True, 0, crop)
        print(f'ISO {iso:6.0f} {nm}\n    estimate only: {o1}\n    with model   : {o2}')
