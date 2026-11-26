"""Textured day crops for the day fine-tune: the most detailed 512 px CFA crops of each clean frame.
Frames: CACHE/files.txt (make_crops.py) + Redmi CFA frames ISO<=200 under DIRS (one per burst), minus the eval photos.
Packed RGGB float16 (4,256,256), PER crops/file: the highest-detail of 6*PER random candidates.
  make_day_crops.py OUT CACHE DIR... [--per 12]"""
import sys, os, re, subprocess, numpy as np, tifffile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import load_dng
args = sys.argv[1:]; per = 12
if '--per' in args: k = args.index('--per'); per = int(args[k + 1]); del args[k:k + 2]
out, cache, dirs = args[0], args[1], args[2:]
EVAL = ('185731_891', '132932_323', '20260721_185613', '20260525_213344', '194618_520')   # eval_bias.py photos stay unseen
fs = [l for l in open(os.path.join(cache, 'files.txt')).read().split('\n') if l]
seen = set(fs); bursts = set()
for f in sorted(l for l in subprocess.run(['find', *[os.path.expanduser(d) for d in dirs], '-iname', '*.dng'],
                                           capture_output=True, text=True).stdout.split('\n') if l):
    b = os.path.basename(f).upper()
    if f in seen or any(k in b for k in ('HDR', 'LINEAR', 'MFSR', 'LKR', 'MERGE', 'DENO')): continue
    if '_F' in b and not b.endswith('_F00.DNG'): continue
    key = re.sub(r'(_BURST)?_\d+\.DNG$', '', b) if ('BURST' in b or re.search(r'_\d{3}_\d{3}\.DNG$', b)) else b
    if key != b and (os.path.dirname(f), key) in bursts: continue
    try:
        t = tifffile.TiffFile(f); tg = {x.name: x for x in t.pages[0].tags.values()}
        model = str(tg['Model'].value) if 'Model' in tg else ''
        iso = int(tg['ISOSpeedRatings'].value) if 'ISOSpeedRatings' in tg else 999
        if t.pages[0].photometric != 32803: continue
    except Exception: continue
    if ('REDMI' not in model.upper() and '25080RABDG' not in model) or iso > 200: continue
    if key != b: bursts.add((os.path.dirname(f), key))
    fs.append(f); seen.add(f)
fs = [f for f in fs if not any(k in os.path.basename(f) for k in EVAL)]
os.makedirs(out, exist_ok=True)
N = len(fs) * per
C = np.lib.format.open_memmap(os.path.join(out, 'crops.npy.tmp'), 'w+', np.float16, (N, 4, 256, 256))
rng = np.random.default_rng(11); n = 0; names = []
def detail(p):   # mean |high-pass| over the planes, relative to brightness (texture, not noise: frames are clean)
    h = p - 0.25 * (np.roll(p, 1, 1) + np.roll(p, -1, 1) + np.roll(p, 1, 2) + np.roll(p, -1, 2))
    return np.abs(h[:, 1:-1, 1:-1]).mean() / max(p.mean(), 1e-3)
for f in fs:
    try: x, wb, _, _ = load_dng(f)
    except Exception: continue
    H, W = x.shape; cand = []
    for _ in range(6 * per):
        y = int(rng.integers(0, (H - 512) // 2)) * 2; z = int(rng.integers(0, (W - 512) // 2)) * 2
        c = x[y:y + 512, z:z + 512]
        if c.mean() < 0.02 or (c > 0.95).mean() > 0.02: continue   # skip dark / clipped
        p = np.stack([c[0::2, 0::2], c[0::2, 1::2], c[1::2, 0::2], c[1::2, 1::2]])
        cand.append((detail(p), p))
    cand.sort(key=lambda s: -s[0])
    for _, p in cand[:per]: C[n] = p.astype(np.float16); n += 1
    names.append(f); print(len(names), n, os.path.basename(f), flush=True)
C.flush(); del C
A = np.load(os.path.join(out, 'crops.npy.tmp'), mmap_mode='r')[:n]
np.save(os.path.join(out, 'crops.npy'), A); del A; os.remove(os.path.join(out, 'crops.npy.tmp'))
open(os.path.join(out, 'files.txt'), 'w').write('\n'.join(names))
print('crops', n, 'files', len(names))
