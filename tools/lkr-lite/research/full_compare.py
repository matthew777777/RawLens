"""Full-frame LK-R vs LK-R Lite: run both CLIs on one burst with identical flags, score, render.

python -I full_compare.py BURST_DIR OUT_DIR --s 2 [--extra "--guide 2 --msmooth 1 --msr 1"] [--keep]

Scores (camera-linear interleaved .dng.f32 of both): global PSNR (peak 1), and per 64 px block
PSNR; the 6 lowest blocks (most different) are rendered LK-R | Lite | |diff| x8 into
OUT_DIR/<name>-x<s>-worst.png, plus the centre block. Raw outputs are deleted unless --keep.
"""
import argparse, json, os, re, subprocess, sys, time
import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
BINS = {'lkr': ROOT + '/tools/lkr-pipeline/build/host/lkr_full_run',
        'lite': ROOT + '/tools/lkr-lite/build/host/lkrl_full_run'}


def run(tag, burst, out, s, extra):
    env = dict(os.environ, DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib')
    t = time.time()
    r = subprocess.run([BINS[tag], burst, out, '--s', str(s)] + extra, env=env, capture_output=True, text=True)
    wall = time.time() - t
    log = r.stdout + r.stderr
    if r.returncode:
        raise RuntimeError(tag + ' failed: ' + log[-1500:])
    m = re.search(r'TOTAL\(merge\) (\d+)', log)
    g = re.search(r'tiles \d+ \(gpu (\d+)', log)
    dims = re.search(r'-> (\d+)x(\d+) s=', log)
    return dict(merge_ms=int(m.group(1)) if m else -1, gpu_ms=int(g.group(1)) if g else -1, wall=wall,
                W=int(dims.group(1)), H=int(dims.group(2)), log=log[-600:])


def look(rgb, wb, ex):
    x = np.clip(rgb * wb * ex, 0, 1)
    return (np.where(x < 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055) * 255).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('burst'); ap.add_argument('out')
    ap.add_argument('--s', type=int, default=2)
    ap.add_argument('--extra', default='--guide 2 --msmooth 1 --msr 1')
    ap.add_argument('--keep', action='store_true')
    ap.add_argument('--keep-dng', action='store_true', help='keep both DNGs, delete only the float dumps')
    ap.add_argument('--reuse-lkr', action='store_true', help='keep an existing LK-R output (same flags)')
    ap.add_argument('--tag', default='', help='suffix for the png / json')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    name = os.path.basename(a.burst.rstrip('/'))
    extra = a.extra.split()
    res = {'burst': name, 's': a.s, 'extra': a.extra}
    paths = {}
    for tag in ('lkr', 'lite'):
        p = os.path.join(a.out, '%s-x%d-%s.dng' % (name, a.s, tag))
        if tag == 'lkr' and a.reuse_lkr and os.path.exists(p + '.f32') and os.path.exists(p + '.json'):
            res[tag] = json.load(open(p + '.json'))
        else:
            res[tag] = run(tag, a.burst, p, a.s, extra)
            if tag == 'lkr': json.dump(res[tag], open(p + '.json', 'w'))
        paths[tag] = p
    W, H = res['lkr']['W'], res['lkr']['H']
    A = np.memmap(paths['lkr'] + '.f32', 'f4', 'r', shape=(H, W, 3))
    B = np.memmap(paths['lite'] + '.f32', 'f4', 'r', shape=(H, W, 3))
    B_ = 64
    nby, nbx = H // B_, W // B_
    mse = np.zeros((nby, nbx)); mean = np.zeros((nby, nbx))
    for by in range(nby):
        a_ = np.asarray(A[by * B_:(by + 1) * B_, :nbx * B_]); b_ = np.asarray(B[by * B_:(by + 1) * B_, :nbx * B_])
        d = ((a_ - b_) ** 2).reshape(B_, nbx, B_, 3).mean((0, 2, 3))
        mse[by] = d; mean[by] = a_.reshape(B_, nbx, B_, 3).mean((0, 2, 3))
    blk = 10 * np.log10(1.0 / np.maximum(mse, 1e-20))
    res['psnr'] = float(10 * np.log10(1.0 / mse.mean()))
    res['block_p1'] = float(np.percentile(blk, 1)); res['block_p5'] = float(np.percentile(blk, 5))
    res['block_min'] = float(blk.min())
    # relative error in each block (darker blocks matter as much as bright ones after the look)
    rel = np.sqrt(mse) / np.maximum(mean, 1e-3)
    res['rel_p99'] = float(np.percentile(rel, 99)); res['rel_max'] = float(rel.max())
    # render worst blocks by relative error + centre
    order = np.argsort(rel.ravel())[::-1]
    picks, used = [], []
    for o in order:
        by, bx = divmod(int(o), nbx)
        if all(abs(by - y) > 2 or abs(bx - x) > 2 for y, x in used):
            used.append((by, bx)); picks.append((by, bx))
        if len(picks) == 6: break
    picks.append((nby // 2, nbx // 2))
    med = float(np.median(np.asarray(A[::16, ::16, 1])))
    ex = 0.35 / max(med, 1e-4)
    wb = np.array([1.0, 1.0, 1.0])
    rows = []
    for by, bx in picks:
        y0, x0 = max(by * B_ - 32, 0), max(bx * B_ - 32, 0)
        a_ = np.asarray(A[y0:y0 + 128, x0:x0 + 128]); b_ = np.asarray(B[y0:y0 + 128, x0:x0 + 128])
        la, lb = look(a_, wb, ex), look(b_, wb, ex)
        d = np.clip(np.abs(lb.astype(int) - la.astype(int)) * 8, 0, 255).astype(np.uint8)
        row = np.concatenate([la, np.full((la.shape[0], 3, 3), 255, np.uint8), lb,
                              np.full((la.shape[0], 3, 3), 255, np.uint8), d], 1)
        row = np.kron(row, np.ones((2, 2, 1), np.uint8))
        if row.shape[1] < 3 * 256 + 12:
            row = np.pad(row, ((0, 0), (0, 3 * 256 + 12 - row.shape[1]), (0, 0)), constant_values=255)
        rows += [row[:, :3 * 256 + 12], np.full((4, 3 * 256 + 12, 3), 255, np.uint8)]
    Image.fromarray(np.concatenate(rows, 0)).save(os.path.join(a.out, '%s-x%d%s-worst.png' % (name, a.s, a.tag)))
    res['picks'] = picks
    for k in ('lkr', 'lite'): res[k].pop('log', None)
    if not a.keep:
        for p in ([paths['lite']] if a.reuse_lkr else paths.values()):
            for f in ((p + '.f32',) if a.keep_dng else (p, p + '.f32')):
                if os.path.exists(f): os.remove(f)
    json.dump(res, open(os.path.join(a.out, '%s-x%d%s.json' % (name, a.s, a.tag)), 'w'), indent=1)
    print(json.dumps({k: res[k] for k in ('burst', 's', 'psnr', 'block_p1', 'block_min', 'rel_p99', 'rel_max')}),
          'LK-R %.1fs / Lite %.1fs merge' % (res['lkr']['merge_ms'] / 1e3, res['lite']['merge_ms'] / 1e3), flush=True)


if __name__ == '__main__':
    main()
