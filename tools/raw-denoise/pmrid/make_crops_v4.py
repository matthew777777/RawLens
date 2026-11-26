"""v4 clean training crops from every phone DNG on the Mac: Redmi (the target sensor) and, as extra scene content,
iPhone 11 and other phones. Bayer data is read from IFD0 or a SubIFD (iPhone / some Redmi saves keep it there).
Redmi: single native frames only (white level <= 4095, not HDR+): merged / linear / HDR saves are left out.
Clean truth: Redmi ISO<=200 at full resolution; noisier frames (Redmi ISO 201-800, other phones ISO<=400) are
binned 2x2 per CFA colour (half resolution, half the noise std). One frame per burst; eval_bias.py photos left out.
Packed RGGB float16 (4,256,256) = 512 px CFA crops: per frame random + most-detailed (flats keep the noise levels honest).
  make_crops_v4.py OUT DIR...      -> OUT/crops.npy, files.txt (one line per crop: kind, binned, file)"""
import sys, os, re, subprocess, numpy as np, tifffile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import vals
out, dirs = sys.argv[1], sys.argv[2:]
EVAL = ('185731_891', '132932_323', '20260721_185613', '20260525_213344', '194618_520')
# The old cache's validation crops (first 64, the test set of vs_classic / train_pmrid) come from its first frames: keep them unseen.
_old = os.path.expanduser('~/Downloads/pmrid_cache/files.txt')
HELD = set(open(_old).read().split('\n')[:15]) if os.path.exists(_old) else set()
SKIP = ('HDRPLUS', 'LINEAR', 'MFSR', 'LKR', 'MERGE', 'DENO', 'HDR')
ORDER = {(0, 1, 1, 2): (slice(None), slice(None)), (1, 2, 0, 1): (slice(None, None, -1), slice(None)),
         (2, 1, 1, 0): (slice(None, None, -1), slice(None, None, -1)), (1, 0, 2, 1): (slice(None), slice(None, None, -1))}

def cfa_page(t):
    pages = [t.pages[0]] + list(t.pages[0].pages or [])
    c = [p for p in pages if p.photometric == 32803]
    return max(c, key=lambda p: p.shape[0] * p.shape[1]) if c else None

def header(f):
    t = tifffile.TiffFile(f); tg = {x.name: x for x in t.pages[0].tags.values()}
    model = str(tg['Model'].value) if 'Model' in tg else ''
    exif = tg['ExifTag'].value if 'ExifTag' in tg and isinstance(tg['ExifTag'].value, dict) else {}
    v = tg['ISOSpeedRatings'].value if 'ISOSpeedRatings' in tg else exif.get('ISOSpeedRatings', exif.get('PhotographicSensitivity', 9999))
    iso = int(v[0] if isinstance(v, (tuple, list)) else v)
    return t, tg, model, iso

def load(t, tg0):
    p = cfa_page(t); tg = {x.name: x for x in p.tags.values()}
    raw = p.asarray().astype(np.float32)
    if 'ActiveArea' in tg:
        y0, x0, y1, x1 = [int(v) for v in vals(tg['ActiveArea'])]; raw = raw[y0:y1, x0:x1]
    bl = np.array(vals(tg['BlackLevel']), np.float32) if 'BlackLevel' in tg else np.zeros(1, np.float32)
    white = float(vals(tg['WhiteLevel'])[0])
    if bl.size == 4: black = np.tile(bl.reshape(2, 2), (raw.shape[0] // 2 + 1, raw.shape[1] // 2 + 1))[:raw.shape[0], :raw.shape[1]]
    else: black = float(bl.mean())
    x = (raw - black) / (white - float(np.mean(bl)))
    cfa = tuple(int(v) for v in (tg['CFAPattern'].value if 'CFAPattern' in tg else tg0['CFAPattern'].value))
    if cfa not in ORDER: raise ValueError(cfa)
    x = x[ORDER[cfa]]
    return np.ascontiguousarray(x[: x.shape[0] & ~1, : x.shape[1] & ~1])

def pack(c): return np.stack([c[0::2, 0::2], c[0::2, 1::2], c[1::2, 0::2], c[1::2, 1::2]])
def detail(p):
    h = p - 0.25 * (np.roll(p, 1, 1) + np.roll(p, -1, 1) + np.roll(p, 1, 2) + np.roll(p, -1, 2))
    return np.abs(h[:, 1:-1, 1:-1]).mean() / max(p.mean(), 1e-3)

# pass 1: which frames, how many crops
fs = sorted(l for l in subprocess.run(['find', *[os.path.expanduser(d) for d in dirs], '-iname', '*.dng', '-not', '-path', '*/.local/*',
                                        '-not', '-path', '*/build/*', '-not', '-path', '*/.cxx/*'], capture_output=True, text=True).stdout.split('\n') if l)
plan, bursts = [], set()
for f in fs:
    b = os.path.basename(f).upper()
    if any(k in b for k in SKIP) or any(k in os.path.basename(f) for k in EVAL) or f in HELD: continue
    if re.search(r'_F\d\d\.DNG$', b) and not b.endswith('_F00.DNG'): continue   # burst frames (not '_f' inside Lightroom IDs)
    # one frame per burst / RAW video clip
    key = re.sub(r'(_BURST)?_\d+\.DNG$', '', b) if ('BURST' in b or b.startswith('VID_') or re.search(r'_\d{3}_\d{3}\.DNG$', b)) else b
    if key != b and (os.path.dirname(f), key) in bursts: continue
    try:
        t, tg, model, iso = header(f)
        cp = cfa_page(t)
        if cp is None: continue
        wl = {x.name: x for x in cp.tags.values()}['WhiteLevel'].value; wl = int(wl[0] if isinstance(wl, (tuple, list)) else wl)
        sw = str(tg['Software'].value) if 'Software' in tg else ''
    except Exception: continue
    redmi = 'REDMI' in model.upper() or '25080RABDG' in model
    # Single native frames only: the Redmi sensor writes 10-bit (1023, 12-bit 4095); a higher white level is a merge
    # (PhotonCamera, stock multi-frame RAW, RawLens HDR+/MOSAIC, MotionCam) and HDR+ ORIGINALs are merged too.
    if redmi and (wl > 4095 or sw.startswith('HDR+')): continue
    if redmi and iso <= 200: kind, binned, nr, nt = 'redmi', False, 3, 3
    elif redmi and iso <= 800: kind, binned, nr, nt = 'redmi', True, 2, 1
    elif not redmi and iso <= 400: kind, binned, nr, nt = ('iphone' if 'iPhone' in model else 'other'), True, 2, 1
    else: continue
    if key != b: bursts.add((os.path.dirname(f), key))
    plan.append((f, kind, binned, nr, nt))
print('frames', len(plan), {k: sum(1 for p in plan if p[1] == k) for k in ('redmi', 'iphone', 'other')}, flush=True)
os.makedirs(out, exist_ok=True)
N = sum(p[3] + p[4] for p in plan)
C = np.lib.format.open_memmap(os.path.join(out, 'crops.npy.tmp'), 'w+', np.float16, (N, 4, 256, 256))
rng = np.random.default_rng(4); n = 0; lines = []
for k, (f, kind, binned, nr, nt) in enumerate(plan):
    try:
        t, tg, _, _ = header(f); x = load(t, tg)
    except Exception as e:
        print('skip', os.path.basename(f), e, flush=True); continue
    if binned:   # 2x2 average per CFA colour: half resolution, half the noise
        P = pack(x); h, w = P.shape[1] // 2 * 2, P.shape[2] // 2 * 2
        P = P[:, :h, :w].reshape(4, h // 2, 2, w // 2, 2).mean((2, 4))
        x = np.empty((h, w), np.float32); x[0::2, 0::2], x[0::2, 1::2], x[1::2, 0::2], x[1::2, 1::2] = P
    H, W = x.shape
    if H < 600 or W < 600: continue
    cand = []
    for _ in range(4 * (nr + nt)):
        y = int(rng.integers(0, (H - 512) // 2)) * 2; z = int(rng.integers(0, (W - 512) // 2)) * 2
        c = x[y:y + 512, z:z + 512]
        if c.mean() < 0.01 or (c > 0.95).mean() > 0.05: continue   # black / clipped
        p = pack(c); cand.append((detail(p), p))
    pick = cand[:nr] + sorted(cand[nr:], key=lambda s: -s[0])[:nt]
    for _, p in pick:
        C[n] = p.astype(np.float16); n += 1; lines.append('%s %d %s' % (kind, binned, f))
    if k % 25 == 0: print(k, len(plan), n, kind, os.path.basename(f), flush=True)
C.flush(); del C
A = np.load(os.path.join(out, 'crops.npy.tmp'), mmap_mode='r')[:n]
np.save(os.path.join(out, 'crops.npy'), A); del A; os.remove(os.path.join(out, 'crops.npy.tmp'))
open(os.path.join(out, 'files.txt'), 'w').write('\n'.join(lines))
print('crops', n, 'frames', len(plan))
