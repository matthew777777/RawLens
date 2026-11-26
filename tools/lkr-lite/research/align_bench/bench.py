# SPDX-License-Identifier: Apache-2.0
"""Run alignment variants on scenes.py bursts with identical reconstruction settings and score them.

  bench.py OUT.json --data DIR --runner BIN --variants base search1 ... --scale 1 2
Variants: NAME=BIN|ENV=V,ENV=V|args   (any part may be empty), or a replay field:
  NAME=BIN|replay:gt|  /  replay:dis  /  replay:geo  /  replay:geo_oracle   (single tile, LKRL_DENSE_FIELD)
One solver tile (424 px at 1x, 320 px at 2x) in the middle of the scene, so every variant, replay
included, sees the same tile. Noise model = the scene's true one (--noise), HDR off/on by category.
"""
import argparse, json, os, re, subprocess, sys, tempfile
from pathlib import Path
import numpy as np
from scipy.ndimage import gaussian_filter, uniform_filter, binary_erosion

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[3] / 'python/lkr'))
TILE = {1: 424, 2: 320}
S = 640


DEV = '/data/local/tmp/lkrl_align'
PUSHED = set()


def adb(*a):
    r = subprocess.run(['adb', *a], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(' '.join(a)[-600:] + '\n' + (r.stdout + r.stderr)[-1500:])
    return r.stdout


def run(binary, scene, out, s, env, args, lab, trace=None, crop=None):
    """binary 'phone:NAME' runs /data/local/tmp/lkrl_align/NAME on the connected device."""
    n = TILE[s]
    o = (S - n) // 2 if crop is None else crop
    tail = ['--s', str(s), '--tile', str(n), '--workers', '1', '--prior', '0',
            '--hdr', '1' if np.any(lab['ev'] != 0) else '0', '--noise', f'{lab["noise"][0]}', f'{lab["noise"][1]}',
            '--crop', str(n), str(n), '--at', str(o), str(o)] + args
    if binary.startswith('phone:'):
        rs = f'{DEV}/scenes/{Path(scene).name}'
        if rs not in PUSHED:
            if 'f007' not in subprocess.run(['adb', 'shell', f'ls {rs}'], capture_output=True, text=True).stdout:
                adb('shell', f'mkdir -p {DEV}/scenes {rs}')
                adb('push', *[str(p) for p in sorted(Path(scene).glob('*.dng'))], rs)
            PUSHED.add(rs)
        env = dict(env)
        if 'LKRL_DENSE_FIELD' in env:
            adb('push', env['LKRL_DENSE_FIELD'], f'{DEV}/w/field.bin') if adb('shell', f'mkdir -p {DEV}/w') is not None else None
            env['LKRL_DENSE_FIELD'] = f'{DEV}/w/field.bin'
        if trace:
            env['LKRL_TRACE_PREFIX'] = f'{DEV}/w/t'
        ev = ' '.join(f'{k}={v}' for k, v in env.items())
        log = adb('shell', f'mkdir -p {DEV}/w; cd {DEV}; rm -f w/t_*; LD_LIBRARY_PATH=. LKRL_PCACHE={DEV}/pc_{binary[6:]}.bin {ev} ./{binary[6:]} {rs} w/o.dng {" ".join(tail)} 2>&1; echo')
        if 'TOTAL(merge)' not in log:
            raise RuntimeError(log[-1500:])
        adb('pull', f'{DEV}/w/o.dng.f32', f'{out}.f32')
        if trace:
            for f in adb('shell', f'ls {DEV}/w').split():
                if f.startswith('t_') and f.endswith(('affine.f32', 'local.f32', 'initial.f32', 'weights.f32', '-raw.f32')):
                    adb('pull', f'{DEV}/w/{f}', f'{trace}{f[1:]}')
    else:
        e = dict(os.environ, DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib', LKRL_PCACHE=f'{binary}.pcache', **env)
        if trace:
            e['LKRL_TRACE_PREFIX'] = str(trace)
        r = subprocess.run([binary, str(scene), str(out)] + tail, env=e, capture_output=True, text=True)
        log = r.stdout + r.stderr
        if r.returncode:
            raise RuntimeError(log[-1500:])
    img = np.fromfile(f'{out}.f32', '<f4').reshape(s * n, s * n, 3)
    m = re.search(r'HDR white ([0-9.]+)', log)
    return img * (float(m[1]) if m else 1.0), log


def motion(trace, n, s, stage=''):
    """Frame-k tile px -> reference tile px, bilinear block field, as lite_common.glsl. (K, n, n, 2)"""
    p = np.fromfile(f'{trace}-affine{stage}.f32', '<f4').reshape(-1, 6)
    K = len(p)
    d = np.fromfile(f'{trace}-local{stage}.f32', '<f4')
    LB = 16 if d.size == K * ((n + 15) // 16) ** 2 * 2 else 8
    nb = (n + LB - 1) // LB
    d = d.reshape(K, nb, nb, 2)
    y, x = np.mgrid[0:n, 0:n] + 0.5
    cx, cy = x / LB - 0.5, y / LB - 0.5
    x0f, y0f = np.floor(cx), np.floor(cy)
    fx, fy = (cx - x0f)[..., None], (cy - y0f)[..., None]
    cl = lambda v: np.clip(v.astype(int), 0, nb - 1)
    x0, x1, y0, y1 = cl(x0f), cl(x0f + 1), cl(y0f), cl(y0f + 1)
    out = np.empty((K, n, n, 2))
    for k in range(K):
        D = (d[k, y0, x0] * (1 - fx) + d[k, y0, x1] * fx) * (1 - fy) + (d[k, y1, x0] * (1 - fx) + d[k, y1, x1] * fx) * fy
        out[k, ..., 0] = p[k, 0] * x + p[k, 1] * y + p[k, 2] + D[..., 0]
        out[k, ..., 1] = p[k, 3] * x + p[k, 4] * y + p[k, 5] + D[..., 1]
    return out


def coarse_warp(f, B, how, raw):
    """Per-sample field -> one offset per B px block (median) -> per sample: b bilinear (Lite), s cubic B-spline
    fit, n nearest block, g guided (local linear in the frame's own grey, the edge-aware upsampling)."""
    import cv2
    from scipy.ndimage import zoom, map_coordinates
    K, n = f.shape[:2]
    nb = (n + B - 1) // B
    c = (np.arange(n) + 0.5) / B - 0.5
    yy, xx = np.meshgrid(c, c, indexing='ij')
    out = np.empty_like(f)
    for k in range(K):
        A = np.empty((nb, nb, 2))
        for i in range(nb):
            for j in range(nb):
                A[i, j] = np.median(f[k, i * B:(i + 1) * B, j * B:(j + 1) * B].reshape(-1, 2), 0)
        for d in range(2):
            if how == 'n':
                out[k, ..., d] = np.repeat(np.repeat(A[..., d], B, 0), B, 1)[:n, :n]
            elif how == 's':
                out[k, ..., d] = map_coordinates(A[..., d], [yy, xx], order=3, mode='nearest')
            else:
                out[k, ..., d] = map_coordinates(A[..., d], [yy, xx], order=1, mode='nearest')
        if how == 'g':
            h = n // 2
            I = raw[k].reshape(h, 2, h, 2).mean((1, 3)).astype('f4')
            I = np.sqrt(np.clip(I, 0, None))
            r = (2 * (B // 2) + 1, 2 * (B // 2) + 1)
            box = lambda z: cv2.blur(z, r)
            mi, vi = box(I), None
            vi = box(I * I) - mi * mi
            eps = 4e-4
            for d in range(2):
                p = cv2.resize(np.repeat(np.repeat(A[..., d], B, 0), B, 1)[:n, :n].astype('f4'), (h, h), interpolation=cv2.INTER_AREA)
                mp = box(p)
                a = (box(I * p) - mi * mp) / (vi + eps)
                q = box(a) * I + box(mp - a * mi)
                out[k, ..., d] = cv2.resize(q, (n, n), interpolation=cv2.INTER_LINEAR)
    return out


def dense_field(kind, trace, lab, n, o, field, vis):
    """Per-sample displacement (reference - frame position), (K, n, n, 2)."""
    K = len(lab['flow'])
    y, x = np.mgrid[0:n, 0:n] + 0.5
    pos = np.stack([x, y], -1)
    if kind.startswith('gt'):
        f = lab['flow'][:, o:o + n, o:o + n] - o - pos
        v = lab['vis'][:, o:o + n, o:o + n] if kind.startswith('gtvis') else None
        m = re.match(r'gt(?:vis)?(\d+)([bsgn]?)$', kind)   # the true motion through a coarse warp model
        if m:
            f = coarse_warp(f, int(m[1]), m[2] or 'b', np.fromfile(f'{trace}-raw.f32', '<f4').reshape(K, n, n))
        return f, v
    import torch, cv2, torch.nn.functional as TF
    raw = np.fromfile(f'{trace}-raw.f32', '<f4').reshape(K, n, n)
    cell = raw.reshape(K, n // 2, 2, n // 2, 2).mean((2, 4))
    sc = max(0.01, float(np.quantile(cell[0], 0.99)))
    if kind == 'dis':
        up = np.stack([cv2.resize(c, (n, n), interpolation=cv2.INTER_LINEAR) for c in cell])
        g = np.round(np.clip(up / sc, 0, 1) * 255).astype('u1')
        est = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        f = np.zeros((K, n, n, 2), 'f4')
        for k in range(1, K):
            f[k] = est.calc(g[k], g[0], None)
        return f, None
    from dense_alignment import align
    from dense_warp import reverse_grid
    h = n // 2
    guide = torch.from_numpy(np.clip(cell / sc, 0, 1.2))[:, None].float()
    f_ = float(lab['f']) / 2
    c = (S / 2 - o) / 2 - 0.5
    Kc = torch.tensor([[f_, 0, c], [0, f_, c], [0, 0, 1.0]])
    aff = np.fromfile(f'{trace}-affine-initial.f32', '<f4').reshape(K, 2, 3).astype('f8')
    poses = torch.eye(4).repeat(K - 1, 1, 1)
    z0 = 3.0
    if 'oracle' in kind:
        dep = torch.from_numpy(lab['depth'][o:o + n, o:o + n].copy())[None, None]
        seed = TF.adaptive_avg_pool2d(1 / dep, (16, 16))[0, 0].reciprocal().float()
        z0 = float(seed.median())
    else:
        seed = torch.full((16, 16), z0)
    ctr = np.array([S / 2 - o, S / 2 - o])
    for k in range(1, K):         # native affine (frame -> ref) inverted at the principal point, as rotation; plus roll
        A, t = aff[k, :, :2], aff[k, :, 2]
        src = np.linalg.solve(A, ctr - t) - ctr
        Ai = np.linalg.inv(A); roll = np.arctan2(Ai[1, 0] - Ai[0, 1], Ai[0, 0] + Ai[1, 1])
        from scenes import rodrigues
        R = rodrigues([-src[1] / (2 * f_), src[0] / (2 * f_), roll])
        poses[k - 1, :3, :3] = torch.from_numpy(R).float()
    R, T, planes = align(guide, Kc, seed, alternations=3, plane_steps=6, patch_radius=1, robust_scale=0.03, damping=1e-3,
                         plane_lr=0.01, initial_poses=poses)
    grid, valid = reverse_grid(planes, R, T, Kc, tolerance=0.25)
    src = ((grid + 1) * h / 2)                      # cell px, edge convention
    f = 2 * src - torch.from_numpy(np.stack(np.meshgrid(np.arange(h) + .5, np.arange(h) + .5), -1)).float() * 2
    # invalid projections keep the native start affine
    yy, xx = np.mgrid[0:h, 0:h] * 2 + 1.0
    for k in range(1, K):
        A, t = aff[k, :, :2], aff[k, :, 2]
        nat = np.stack([A[0, 0] * xx + A[0, 1] * yy + t[0] - xx, A[1, 0] * xx + A[1, 1] * yy + t[1] - yy], -1)
        bad = valid[k].numpy() < 0.5
        f[k][torch.from_numpy(bad)] = torch.from_numpy(nat[bad]).float()
    f[0] = 0
    up = TF.interpolate(f.movedim(-1, 1), (n, n), mode='bilinear', align_corners=False).movedim(1, -1).numpy()
    v = valid[:, None].float()
    dense_field.valid = float(v[1:].mean())
    return up, None


def score(img, flow, wt, lab, s, o):
    n = TILE[s]
    sl = slice(2 * o, 2 * (o + n))
    cr = lambda a: a[sl, sl]
    gt, mov, bnd, hid, tex = (cr(lab[k]) for k in ('gt', 'moving', 'boundary', 'hidden', 'texture'))
    if s == 1:
        dn = lambda a: a.reshape(n, 2, n, 2, *a.shape[2:]).mean((1, 3))
        gt, mov, bnd, hid, tex = dn(gt), dn(mov.astype('f4')) > .5, dn(bnd.astype('f4')) > .5, dn(hid.astype('f4')) > .5, dn(tex)
    h = s * n
    inn = np.zeros((h, h), bool); m = 16 * s; inn[m:-m, m:-m] = True
    use = inn & (gt.max(-1) < (0.85 if not np.any(lab['ev'] != 0) else 1e9))
    G = np.array([.25, .5, .25])
    err, g = img - gt, gt @ G
    e = err @ G
    hp = lambda a: a - gaussian_filter(a, 1.0 * s)
    ehp = hp(e)
    reg = {'all': use, 'static': use & ~mov & ~bnd, 'boundary': use & bnd, 'moving': use & mov & ~bnd, 'hidden': use & hid,
           'texture': use & ~mov & ~bnd & (tex > 0.01), 'flat': use & ~mov & ~bnd & (tex < 0.004)}
    r = {}
    for k, mk in reg.items():
        if mk.sum() > 200:
            r['psnr_' + k] = float(-10 * np.log10(np.mean(err[mk] ** 2)))
            r['detail_' + k] = float(np.sqrt(np.mean(ehp[mk] ** 2)))
    d = use & ~mov & ~bnd & (tex > 0.01)
    if d.sum() > 200:
        a, b = hp(img @ G)[d], hp(g)[d]
        r['sharp_texture'] = float((a * b).sum() / (b * b).sum())
    u, v = img @ G, g
    ma, mb = uniform_filter(u, 7), uniform_filter(v, 7)
    c, va, vb = uniform_filter(u * v, 7) - ma * mb, uniform_filter(u * u, 7) - ma ** 2, uniform_filter(v * v, 7) - mb ** 2
    ss = ((2 * ma * mb + 1e-4) * (2 * c + 9e-4)) / ((ma ** 2 + mb ** 2 + 1e-4) * (va + vb + 9e-4))
    r['ssim'] = float(ss[use].mean())
    ch = np.stack([err[..., 0] - err[..., 1], err[..., 2] - err[..., 1]], -1)
    r['chroma_rmse'] = float(np.sqrt(np.mean(ch[use] ** 2)))
    # false-colour / zipper energy: 2 HR px-period component of the luma error
    ck = (-1.0) ** np.add.outer(np.arange(h), np.arange(h))
    r['checker'] = float(np.sqrt(np.mean(gaussian_filter(e * ck, 2 * s)[use] ** 2)))
    if flow is not None:
        tr = lab['flow'][1:, o:o + n, o:o + n] - o
        vis = lab['vis'][1:, o:o + n, o:o + n].copy()
        ins = (tr > 4).all(-1) & (tr < n - 4).all(-1)
        q = np.clip(tr.astype(int), 0, n - 1) * s
        at = lambda a: a[q[..., 1], q[..., 0]]
        d = np.linalg.norm(flow[1:] - tr, axis=-1)
        vis &= ins
        tx = at(tex) > 0.004
        for k, mk in {'all': vis, 'texture': vis & tx, 'static': vis & ~at(mov) & ~at(bnd), 'boundary': vis & at(bnd),
                      'moving': vis & at(mov) & ~at(bnd)}.items():
            if mk.sum() > 500:
                x = d[mk]
                r['epe_' + k] = dict(mean=float(x.mean()), median=float(np.median(x)), p95=float(np.quantile(x, .95)),
                                     p99=float(np.quantile(x, .99)), fail=float((x > 0.5).mean()))
        if wt is not None:
            W = wt.reshape(-1, n, n)[1:]
            for k, mk in {'static': vis & ~at(mov) & ~at(bnd), 'moving': vis & at(mov), 'boundary': vis & at(bnd), 'occluded': ~lab['vis'][1:, o:o + n, o:o + n] & ins}.items():
                if mk.sum() > 500:
                    r['use_' + k] = float(W[mk].mean())
            bad = vis & (d > 0.5)
            r['misaligned_weight'] = float((W * bad).sum() / max((W * vis).sum(), 1e-6))
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('out'); ap.add_argument('--data', required=True); ap.add_argument('--variants', nargs='+', required=True)
    ap.add_argument('--scale', type=int, nargs='+', default=[1, 2]); ap.add_argument('--scenes', nargs='*')
    ap.add_argument('--keep', help='directory for output images')
    a = ap.parse_args()
    res = json.load(open(a.out)) if os.path.exists(a.out) else {}
    scenes = sorted(p.name for p in Path(a.data).iterdir() if (p / 'labels.npz').exists())
    if a.scenes:
        scenes = [x for x in scenes if any(x.startswith(q) for q in a.scenes)]
    for sc in scenes:
        lab = dict(np.load(f'{a.data}/{sc}/labels.npz'))
        for s in a.scale:
            n, o = TILE[s], (S - TILE[s]) // 2
            for spec in a.variants:
                name, rest = spec.split('=', 1)
                binary, envs, args = (rest.split('|') + ['', ''])[:3]
                key = f'{sc}|{s}|{name}'
                if key in res: continue
                env = dict(x.split('=') for x in envs.split(',') if '=' in x)
                with tempfile.TemporaryDirectory() as td:
                    tr = f'{td}/t'
                    if envs.startswith('replay:'):
                        kind = envs[7:]
                        if np.any(lab['ev'] != 0):
                            continue                       # the native replay has no bracket support
                        run(binary, f'{a.data}/{sc}', f'{td}/b.dng', s, {}, [], lab, tr)
                        tr = next(Path(td).glob('t_tile*-raw.f32')).as_posix()[:-8]
                        fl, vis = dense_field(kind, tr, lab, n, o, None, None)
                        K = len(fl)
                        with open(f'{td}/field.bin', 'wb') as f:
                            np.array([K, n, n], '<i4').tofile(f); fl.astype('<f4').tofile(f)
                            if vis is not None: vis.astype('<f4').tofile(f)
                        y, x = np.mgrid[0:n, 0:n] + 0.5
                        flow = fl + np.stack([x, y], -1)
                        for q in Path(td).glob('t_*'): q.unlink()
                        img, log = run(binary, f'{a.data}/{sc}', f'{td}/o.dng', s, {'LKRL_DENSE_FIELD': f'{td}/field.bin'}, [], lab, f'{td}/u')
                        tr = next(Path(td).glob('u_tile*-weights.f32')).as_posix()[:-12]
                    else:
                        img, log = run(binary, f'{a.data}/{sc}', f'{td}/o.dng', s, env, args.split(), lab, tr)
                        tr = next(Path(td).glob('t_tile*-affine.f32')).as_posix()[:-11]
                        flow = motion(tr, n, s)
                    wt = np.fromfile(f'{tr}-weights.f32', '<f4')
                    res[key] = score(img, flow, wt, lab, s, o)
                    if not envs.startswith('replay:'):   # motion before the Gauss-Newton steps
                        res[key]['seed'] = {k: v for k, v in score(img, motion(tr, n, s, '-initial'), None, lab, s, o).items() if k.startswith('epe')}
                    if a.keep:
                        os.makedirs(a.keep, exist_ok=True); np.save(f'{a.keep}/{sc}_{s}_{name}.npy', img.astype('f2'))
                json.dump(res, open(a.out, 'w'))
        print(sc, flush=True)


if __name__ == '__main__':
    main()
