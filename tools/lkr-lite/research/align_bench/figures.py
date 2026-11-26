# SPDX-License-Identifier: Apache-2.0
"""Error-map and comparison figures. figures.py synth|real OUT.jpg base=BIN old8=BIN new=BIN CASE...
synth CASE: SCENE_DIR:SCALE   columns: truth, results, |error|, motion error of the worst frame.
real CASE:  NAME=BURST_DIR:X,Y:SCALE  columns: results (no truth), |new - base|."""
import os, subprocess, sys, tempfile
from pathlib import Path
import cv2, numpy as np
import bench

W = 420
mode, out = sys.argv[1:3]
vs = [a.split('=', 1) for a in sys.argv[3:6]]
E = lambda n: {'LKRL_ALIGN2': '0'} if n == 'base' else {}
tone = lambda a, w: (255 * np.clip(a / w, 0, 1) ** (1 / 2.2)).astype('u1')[..., ::-1]
def heat(a, top, label):
    h = cv2.applyColorMap((255 * np.clip(a / top, 0, 1)).astype('u1'), cv2.COLORMAP_INFERNO)
    return h
def cell(im, txt):
    im = cv2.resize(im, (W, W), interpolation=cv2.INTER_AREA if im.shape[0] > W else cv2.INTER_NEAREST)
    bar = np.full((26, W, 3), 25, 'u1'); cv2.putText(bar, txt, (6, 18), cv2.FONT_HERSHEY_SIMPLEX, .45, (235, 235, 235), 1, cv2.LINE_AA)
    return np.vstack([bar, im])
rows = []
for case in sys.argv[6:]:
    with tempfile.TemporaryDirectory() as td:
        if mode == 'synth':
            d, s = case.rsplit(':', 1); s = int(s); lab = dict(np.load(d + '/labels.npz')); n = bench.TILE[s]; o = (bench.S - n) // 2
            gt = lab['gt'][2 * o:2 * (o + n), 2 * o:2 * (o + n)]
            if s == 1: gt = gt.reshape(n, 2, n, 2, 3).mean((1, 3))
            ims, fl, sc = {}, {}, {}
            for k, b in vs:
                tr = f'{td}/{k}'
                ims[k], _ = bench.run(b, d, f'{td}/o.dng', s, E(k), [], lab, tr)
                pre = next(Path(td).glob(f'{k}_tile*-affine.f32')).as_posix()[:-11]
                fl[k] = bench.motion(pre, n, s); sc[k] = bench.score(ims[k], fl[k], None, lab, s, o)
            # zoom on the window whose result changes most between base and new
            z = n * s // 2; df = cv2.boxFilter(np.abs(ims['new'] - ims['base']).mean(-1), -1, (z, z)); m = z // 2
            yx = np.unravel_index(np.argmax(df[m:-m, m:-m]), df[m:-m, m:-m].shape); y, x = yx; c = np.s_[y:y + z, x:x + z]
            w = max(np.quantile(gt, .995), 1e-3); err = {k: np.abs(ims[k] - gt).mean(-1) for k in ims}; top = max(np.quantile(err['base'][c], .995), 1e-4)
            tru = lab['flow'][1:, o:o + n, o:o + n] - o; vis = lab['vis'][1:, o:o + n, o:o + n]
            epe = {k: np.linalg.norm(fl[k][1:] - tru, axis=-1) * vis for k in fl}
            kw = int(np.argmax([np.clip(epe['base'][i], 0, 3).mean() for i in range(len(tru))]))
            row = [cell(tone(gt[c], w), f'{Path(d).name} {s}x  truth')]
            row += [cell(tone(ims[k][c], w), f'{k}  {sc[k]["psnr_all"]:.2f} dB') for k, _ in vs]
            row += [cell(heat(err[k][c], top, k), f'|error| {k} (max {top:.3f})') for k in ('base', 'new')]
            row += [cell(heat(epe[k][kw], 3, k), f'motion error {k}, frame {kw + 1} (max 3 px)') for k in ('base', 'new')]
        else:
            name, rest = case.split('=', 1); d, xy, s = rest.rsplit(':', 2); s = int(s); x0, y0 = map(int, xy.split(','))
            nn = 640; ims = {}
            for k, b in vs:
                q = subprocess.run([b, os.path.expanduser(d), f'{td}/{k}.dng', '--s', str(s), '--tile', '640', '--workers', '1', '--prior', '0', '--crop', str(nn), str(nn), '--at', str(x0), str(y0)],
                                   env={**os.environ, **E(k)}, capture_output=True, text=True)
                p = f'{td}/{k}.dng.f32'
                if not os.path.exists(p): print(q.stderr[-400:], q.stdout[-400:]); break
                a = np.fromfile(p, '<f4'); h = int(round((a.size / 3) ** .5))
                ims[k] = a.reshape(h, h, 3) if a.size == 3 * h * h else None
            if len(ims) < len(vs): continue
            g = np.array([np.quantile(ims['base'][..., i], .5) for i in range(3)]); wb = g[1] / np.maximum(g, 1e-4)
            z = 320; h = ims['base'].shape[0]; df = cv2.boxFilter(np.abs(ims['new'] - ims['base']).mean(-1), -1, (z, z)); m = z // 2
            y, x = np.unravel_index(np.argmax(df[m:-m, m:-m]), df[m:-m, m:-m].shape); c = np.s_[y:y + z, x:x + z]
            w = np.quantile(ims['base'][c] * wb, .995); dd = np.abs(ims['new'] - ims['base'])[c].mean(-1); top = max(np.quantile(dd, .995), 1e-4)
            row = [cell(tone(ims[k][c] * wb, w), f'{name} {s}x {k}') for k, _ in vs] + [cell(heat(dd, top, ''), f'|new - base| (max {top:.3f})')]
        rows.append(np.hstack(row))
cv2.imwrite(out, np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 88]); print(out, len(rows))
