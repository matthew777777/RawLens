# SPDX-License-Identifier: Apache-2.0
"""Held-out RAW prediction error on real bursts, independent of the merge's own motion.

Frames 0..K-1 are merged, frame K never enters. Every 64 px cell of frame K is registered to each candidate's
image with its own affine (six parameters per 128 px window, Gauss-Newton through the CFA forward model), so
every method gets the same low-order registration freedom. Error = mean over the cell of min(r^2 / var, 25)
with r = predicted - measured sample and var = a y + b from the merge log of the first variant.
  holdout.py OUT.json --variants NAME=BIN|ENV|ARGS ... --cases NAME=DIR:X,Y ... [--frames 7] [--size 848] [--scale 1 2]
"""
import argparse, json, os, re, subprocess, sys, tempfile
from pathlib import Path
import numpy as np
import rawpy
from scipy.ndimage import gaussian_filter
import cv2
from bench import adb, DEV

KERNEL = os.environ.get('HO_KERNEL', 'bilinear')   # evaluator's own sampling kernel (neutral check: run both)


def rggb(path, x, y, n):
    with rawpy.imread(path) as r:
        raw = r.raw_image_visible; c = r.raw_colors_visible
        oy, ox = [(i, j) for i in (0, 1) for j in (0, 1) if c[i, j] == 0][0]
        x0, y0 = x - ((x - ox) & 1), y - ((y - oy) & 1)           # MergeFull's grid rule
        bl = np.array(r.black_level_per_channel, 'f4')
        p = (raw[y0:y0 + n, x0:x0 + n].astype('f4') - bl[c[y0:y0 + n, x0:x0 + n]]) / (r.white_level - bl[0])
    return p, x0 - x, y0 - y


def predict(img, A, ys, xs, s):
    """A: 2x3 affine frame px -> output px / s. Bilinear per CFA plane of the sub-sample mean, with gradient."""
    ch = (ys & 1) + (xs & 1)
    H, W = img.shape[:2]
    acc, gx, gy = 0.0, 0.0, 0.0
    ok = np.ones(ys.shape, bool)
    for oy in range(s):
        for ox in range(s):
            u, v = xs + (ox + .5) / s, ys + (oy + .5) / s
            q0 = s * (A[0, 0] * u + A[0, 1] * v + A[0, 2]) - .5; q1 = s * (A[1, 0] * u + A[1, 1] * v + A[1, 2]) - .5
            i, j = np.floor(q1).astype(int), np.floor(q0).astype(int)
            ok &= (i >= 0) & (j >= 0) & (i + 1 < H) & (j + 1 < W)
            i, j = np.clip(i, 0, H - 2), np.clip(j, 0, W - 2)
            fy, fx = q1 - i, q0 - j
            if KERNEL == 'cubic':
                W4 = lambda f: np.stack([-.5*f**3+f*f-.5*f, 1.5*f**3-2.5*f*f+1, -1.5*f**3+2*f*f+.5*f, .5*f**3-.5*f*f])
                D4 = lambda f: np.stack([-1.5*f*f+2*f-.5, 4.5*f*f-5*f, -4.5*f*f+4*f+.5, 1.5*f*f-f])
                wx, wy, dx, dy = W4(fx), W4(fy), D4(fx), D4(fy)
                for b4 in range(4):
                    for a4 in range(4):
                        t = img[np.clip(i - 1 + b4, 0, H - 1), np.clip(j - 1 + a4, 0, W - 1), ch]
                        acc = acc + t * wx[a4] * wy[b4]; gx = gx + t * dx[a4] * wy[b4] * s; gy = gy + t * wx[a4] * dy[b4] * s
                continue
            a, b, c, d = img[i, j, ch], img[i, j + 1, ch], img[i + 1, j, ch], img[i + 1, j + 1, ch]
            acc = acc + (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            gx = gx + ((b - a) * (1 - fy) + (d - c) * fy) * s; gy = gy + ((c - a) * (1 - fx) + (d - b) * fx) * s
    n = s * s
    return acc / n, gx / n, gy / n, ok


def cellscore(img, raw, off, s, a, b, init, cell=64):
    n = raw.shape[0]
    out = {}
    m = cell // 2
    for cy in range(cell, n - cell, cell):
        for cx in range(cell, n - cell, cell):
            ys, xs = np.mgrid[cy - m:cy + cell + m, cx - m:cx + cell + m]
            y = raw[ys, xs]
            A = init.copy()
            X, Y = xs + .5 - cx - m, ys + .5 - cy - m
            sig2 = a * np.maximum(y, 0) + b
            cl = y < 0.97
            best = None
            for it in range(8):
                p, gx, gy, ok = predict(img, A, ys, xs, s)
                r = p - y
                w = (ok & cl) / (sig2 * (1 + r * r / (9 * sig2 + 0.25 * (gx * gx + gy * gy))))
                J = np.stack([gx * X, gx * Y, gx, gy * X, gy * Y, gy], -1).reshape(-1, 6)
                N = J.T @ (J * w.reshape(-1, 1)); N += np.eye(6) * 1e-6 * np.trace(N) / 6
                try: d = np.linalg.solve(N, -(J * w.reshape(-1, 1)).T @ r.ravel())
                except np.linalg.LinAlgError: break
                d[[2, 5]] = np.clip(d[[2, 5]], -1, 1)
                D = d.reshape(2, 3); D = np.stack([D[:, 0], D[:, 1], D[:, 2] - D[:, 0] * (cx + m) - D[:, 1] * (cy + m)], -1)
                A = A + D
                if np.abs(d[[2, 5]]).max() < 5e-3: break
            c = (slice(m, m + cell), slice(m, m + cell))
            p, gx, gy, ok = predict(img, A, ys[c], xs[c], s)
            u = ok & cl[c]
            if u.mean() < 0.75: continue
            r2 = ((p - y[c]) ** 2 / sig2[c])[u]
            out[(cy, cx)] = float(np.minimum(r2, 25).mean())
    return out


def coarse(raw, ref):
    g = lambda r: cv2.GaussianBlur(r.reshape(r.shape[0] // 2, 2, r.shape[1] // 2, 2).mean((1, 3)), (0, 0), 1)
    a, b = g(raw).astype('f4'), g(ref).astype('f4')
    M = np.eye(2, 3, dtype='f4')
    (dx, dy), _ = cv2.phaseCorrelate(a.astype('f8'), b.astype('f8')); M[0, 2], M[1, 2] = dx, dy
    nrm = float(np.quantile(b, .99))
    try:
        _, M = cv2.findTransformECC(b / nrm, a / nrm, np.linalg.pinv(np.vstack([M, [0, 0, 1]]))[:2].astype('f4'), cv2.MOTION_AFFINE,
                                    (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 1e-5), None, 5)
        M = np.linalg.inv(np.vstack([M, [0, 0, 1]]))[:2]
    except cv2.error:
        pass
    A = M.astype('f8').copy()                    # integer-centred grey px -> edge-centred LR px
    A[:, 2] = 2 * (A[:, 2] + 0.5 - A[:, :2] @ np.array([.5, .5]))
    return A


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('out'); ap.add_argument('--variants', nargs='+', required=True); ap.add_argument('--cases', nargs='+', required=True)
    ap.add_argument('--frames', type=int, default=7); ap.add_argument('--size', type=int, default=848); ap.add_argument('--scale', type=int, nargs='+', default=[1, 2])
    ap.add_argument('--keep')
    a = ap.parse_args()
    res = json.load(open(a.out)) if os.path.exists(a.out) else {}
    for case in a.cases:
        name, rest = case.split('=', 1); d, xy = rest.rsplit(':', 1); x, y = map(int, xy.split(','))
        files = sorted(str(p) for p in Path(os.path.expanduser(d)).glob('*.dng'))
        raw, ox, oy = rggb(files[a.frames], x, y, a.size)
        ref, _, _ = rggb(files[0], x, y, a.size)
        init = coarse(raw, ref)
        rd = f'{DEV}/real/{Path(os.path.expanduser(d)).name}'
        for s in a.scale:
            scores, ab = {}, None
            for spec in a.variants:
                vn, r2 = spec.split('=', 1); binary, envs, args = (r2.split('|') + ['', ''])[:3]
                if f'{name}|{s}|{vn}' in res and a.keep is None: continue
                cmd = ['--s', str(s), '--frames', str(a.frames), '--workers', '1' if s == 2 else '2', '--prior', '0', '--hdr', '0',
                       '--crop', str(a.size), str(a.size), '--at', str(x), str(y)] + args.split()
                with tempfile.TemporaryDirectory() as td:
                    if binary.startswith('phone:'):
                        if len(adb('shell', f'mkdir -p {rd}; ls {rd}').split()) < a.frames: adb('push', *files[:a.frames], rd)
                        adb('shell', f'mkdir -p {DEV}/w')
                        log = adb('shell', f'cd {DEV}; LD_LIBRARY_PATH=. LKRL_PCACHE={DEV}/pc_{binary[6:]}.bin {envs.replace(",", " ")} ./{binary[6:]} {rd} w/h.dng {" ".join(cmd)} 2>&1; echo')
                        adb('pull', f'{DEV}/w/h.dng.f32', f'{td}/h.f32'); f = f'{td}/h.f32'
                    else:
                        e = dict(os.environ, DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib', LKRL_PCACHE=f'{binary}.pcache', **dict(q.split('=') for q in envs.split(',') if q))
                        r = subprocess.run([binary, os.path.expanduser(d), f'{td}/h.dng'] + cmd, env=e, capture_output=True, text=True); log = r.stdout + r.stderr; f = f'{td}/h.dng.f32'
                    img = np.fromfile(f, '<f4').reshape(s * a.size, s * a.size, 3)
                m = re.search(r'HDR white ([0-9.]+)', log); img = img * (float(m[1]) if m else 1.0)
                if ab is None:
                    m = re.search(r' a ([0-9.e+-]+) b ([0-9.e+-]+)', log); ab = (float(m[1]), max(float(m[2]), 1e-8))
                img = np.roll(img, (-s * oy, -s * ox), (0, 1))           # sensor crop -> RGGB grid origin
                c = cellscore(img.astype('f8'), raw, (ox, oy), s, *ab, init)
                scores[vn] = c
                if a.keep: os.makedirs(a.keep, exist_ok=True); np.save(f'{a.keep}/{name}_{s}_{vn}.npy', img.astype('f2'))
            if not scores: continue
            common = set.intersection(*[set(v) for v in scores.values()])
            k = sorted(common)
            for vn, v in scores.items():
                q = np.array([v[i] for i in k])
                res[f'{name}|{s}|{vn}'] = dict(cells=len(k), mean=float(q.mean()), p50=float(np.median(q)), p90=float(np.quantile(q, .9)), p99=float(np.quantile(q, .99)), values=q.tolist())
                print(name, s, vn, {t: round(u, 4) for t, u in res[f'{name}|{s}|{vn}'].items() if t != 'values'}, flush=True)
            json.dump(res, open(a.out, 'w'))


if __name__ == '__main__':
    main()
