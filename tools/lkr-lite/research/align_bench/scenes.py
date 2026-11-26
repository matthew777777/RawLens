# SPDX-License-Identifier: Apache-2.0
"""Ground-truth RAW bursts for the LK-R Lite alignment benchmark.

Layers with their own plane (inverse depth + slant) and motion are rendered through a pinhole camera
with a known SE(3) pose per frame (per row with rolling shutter). Output per scene: minimal Bayer DNGs
(frame 0 = reference) and labels.npz:
  gt     (2S, 2S, 3) reference image through the lens, linear camera RGB, output scale 2
  flow   (K, S, S, 2) frame-k sensor px -> reference sensor px (pixel centres at +0.5, Lite's direction)
  vis    (K, S, S) that surface is visible in the reference
  moving (2S, 2S), boundary (2S, 2S), hidden (2S, 2S: hidden in >= 1 other frame), texture (2S, 2S)
  depth  (S, S), poses (K, 4, 4), f
"""
import argparse, os, sys
import cv2
import numpy as np
from scipy.ndimage import gaussian_filter, binary_dilation

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from lkr_synth_burst import half_rgb  # noqa: E402

S, F, K = 640, 2800.0, 8
HR = 2 * S
PAD = 160            # texture margin, HR px
CATEGORIES = ['planar', 'shake', 'parallax', 'ground', 'foliage', 'fence', 'person', 'vehicle', 'multi',
              'water', 'repeat', 'weak', 'night', 'night_motion', 'rolling', 'blur', 'hdr', 'hdr_motion']


def write_dng(path, codes, black=64, white=1023, exposure=(1, 100)):
    import tifffile
    h, w = codes.shape
    cm = [(6, 10), (0, 10), (0, 10), (0, 10), (10, 10), (0, 10), (0, 10), (0, 10), (7, 10)]
    r = lambda v: [x for p in v for x in p]
    tags = [(33421, 'H', 2, (2, 2), False), (33422, 'B', 4, (0, 1, 1, 2), False),
            (50706, 'B', 4, (1, 4, 0, 0), False), (50707, 'B', 4, (1, 1, 0, 0), False),
            (50713, 'H', 2, (2, 2), False), (50714, 'H', 4, (black,) * 4, False), (50717, 'H', 1, white, False),
            (50721, '2i', 9, r(cm), False), (50728, '2I', 3, (5, 10, 10, 10, 6, 10), False),
            (50778, 'H', 1, 21, False), (33434, '2I', 1, exposure, False), (274, 'H', 1, 1, False)]
    tifffile.imwrite(path, codes.astype('<u2'), photometric=32803, extratags=tags, metadata=None)


def rodrigues(v):
    v = np.asarray(v, float)
    a = np.linalg.norm(v)
    if a < 1e-12:
        return np.eye(3)
    k = v / a
    X = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + np.sin(a) * X + (1 - np.cos(a)) * X @ X


class Layer:
    """plane: n with n.X = 1 in the reference camera (inverse depth as seen on the axis = n[2])."""
    def __init__(self, tex, plane, alpha=None, motion=None, moving=False):
        self.tex, self.plane, self.alpha, self.motion, self.moving = tex, np.asarray(plane, float), alpha, motion, moving


def texture(src, rng):
    n = HR + 2 * PAD
    h, w = src.shape[:2]
    if h < n or w < n:
        src = np.tile(src, (2, 2, 1))
        src = np.concatenate([np.concatenate([src[:h, :w], src[:h, :w][:, ::-1]], 1),
                              np.concatenate([src[:h, :w][::-1], src[:h, :w][::-1, ::-1]], 1)], 0)
        h, w = src.shape[:2]
    y, x = rng.integers(0, h - n + 1), rng.integers(0, w - n + 1)
    t = src[y:y + n, x:x + n].copy()
    return t * (0.22 / max(np.median(t[..., 1]), 1e-6))


def fit(t):
    return np.clip(t * min(1.0, 0.8 / max(float(np.percentile(t, 99.8)), 1e-6)), 0, None).astype('f4')


def soft(shape_d):      # signed distance (HR px, > 0 inside) -> antialiased alpha
    return np.clip(shape_d + 0.5, 0, 1).astype('f4')


def build(cat, rng, srcs):
    n = HR + 2 * PAD
    yy, xx = np.mgrid[0:n, 0:n].astype('f4')
    X, Y = xx - PAD, yy - PAD               # HR reference coordinates
    bg = fit(texture(srcs[rng.integers(len(srcs))], rng))
    fg = lambda: fit(texture(srcs[rng.integers(len(srcs))], rng))
    rot_px, trans_mm, roll_deg = 5.0, 0.6, 0.08
    far, near = 1 / 8.0, 1 / 0.9
    layers = [Layer(bg, (0, 0, far))]
    opt = dict(a=2.05e-5, b=1.4e-6, rs=0.0, blur=1, ev=[0.0] * K)

    def blob(cx, cy, rx, ry, wob=0.06):
        th = np.arctan2(Y - cy, X - cx)
        r = np.hypot((X - cx) / rx, (Y - cy) / ry)
        edge = 1 + wob * np.sin(5 * th + rng.uniform(0, 6)) + wob * 0.5 * np.sin(11 * th + rng.uniform(0, 6))
        return soft((edge - r) * min(rx, ry))

    def mover(vx, vy, amp=0.0, lam=200.0, ph=0.0):
        def m(t, x, y):
            s = np.sin(2 * np.pi * (x + 0.6 * y) / lam + 1.1 * t + ph) - np.sin(2 * np.pi * (x + 0.6 * y) / lam + ph)
            return vx * t + amp * s, vy * t + 0.6 * amp * s
        return m

    def sway(amp, seed):
        g = np.random.default_rng(seed)
        fx = gaussian_filter(g.normal(size=(S + 64, S + 64)), 25); fx *= amp / fx.std()
        fy = gaussian_filter(g.normal(size=(S + 64, S + 64)), 25); fy *= 0.5 * amp / fy.std()
        ph = g.normal(size=K + 2); ph[0] = 0
        def m(t, x, y):
            i = int(np.floor(t)); c = ph[i] * (1 - (t - i)) + ph[i + 1] * (t - i)
            sx = cv2.remap(fx.astype('f4'), (x + 32).astype('f4'), (y + 32).astype('f4'), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            sy = cv2.remap(fy.astype('f4'), (x + 32).astype('f4'), (y + 32).astype('f4'), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            return c * sx, c * sy
        return m

    def leaves(scale, cover, seed):
        g = np.random.default_rng(seed)
        v = gaussian_filter(g.normal(size=(n, n)), scale) - 0.5 * gaussian_filter(g.normal(size=(n, n)), 2 * scale)
        v /= v.std()
        q = np.quantile(v, 1 - cover)
        env = gaussian_filter(g.normal(size=(n, n)), 90); env /= env.std()
        return soft((v - q) * scale * 0.9) * (env > -0.4)

    cx, cy = HR * 0.5, HR * 0.5
    if cat == 'shake':
        rot_px, roll_deg, trans_mm = 22.0, 0.5, 2.0
    elif cat == 'parallax':
        trans_mm = 1.6
        layers += [Layer(fg(), (0.02, 0.01, 1 / 2.5), blob(0.36 * HR, 0.42 * HR, 250, 210)),
                   Layer(fg(), (0, 0, near), blob(0.62 * HR, 0.64 * HR, 180, 220))]
    elif cat == 'ground':           # strongly slanted plane: depth 1..20 m over the frame
        trans_mm = 1.6
        layers = [Layer(bg, (0.1, 4.2, 0.5))]
    elif cat == 'foliage':
        trans_mm = 1.0
        for i, d in enumerate((1 / 4.0, 1 / 2.0)):
            t = fg() * np.array([0.6, 0.9, 0.5], 'f4')
            layers.append(Layer(t, (0, 0, d), leaves(7 - 2 * i, 0.35 - 0.1 * i, int(rng.integers(1 << 30))),
                                sway(0.9 + 0.5 * i, int(rng.integers(1 << 30))), True))
    elif cat == 'fence':
        trans_mm = 1.4
        t = np.deg2rad(rng.uniform(-20, 20))
        u = X * np.cos(t) + Y * np.sin(t); w = -X * np.sin(t) + Y * np.cos(t)
        bar = lambda q, p, wd: wd / 2 - np.abs((q + p / 2) % p - p / 2)
        layers.append(Layer(fg() * 0.4 + 0.03, (0, 0, 1 / 1.5), np.maximum(soft(bar(u, 26, 5)), soft(bar(w, 130, 7)))))
    elif cat == 'person':
        layers += [Layer(fg(), (0, 0, 1 / 3.0), blob(0.42 * HR, 0.42 * HR, 110, 150), mover(1.4, 0.2, 0.8, 120), True),
                   Layer(fg(), (0, 0, 1 / 3.0), blob(0.45 * HR, 0.66 * HR, 70, 170), mover(1.4, 0.1, 1.8, 90, 1.0), True)]
    elif cat == 'vehicle':
        layers.append(Layer(fg(), (0, 0, 1 / 6.0), blob(0.40 * HR, 0.55 * HR, 300, 140, 0.02), mover(7.0, 0.5), True))
    elif cat == 'multi':
        trans_mm = 1.2
        layers += [Layer(fg(), (0, 0, 1 / 5.0), blob(0.3 * HR, 0.35 * HR, 130, 110), mover(-2.2, 0.8), True),
                   Layer(fg(), (0, 0, 1 / 2.5), blob(0.64 * HR, 0.38 * HR, 110, 150), mover(1.1, -0.6, 0.6), True),
                   Layer(fg(), (0, 0, 1 / 1.5), blob(0.5 * HR, 0.72 * HR, 190, 110)),
                   Layer(fg(), (0, 0, 1 / 1.2), blob(0.25 * HR, 0.7 * HR, 70, 60), mover(0.5, 3.0), True)]
    elif cat == 'water':
        def wave(t, x, y):
            p = lambda v: np.sin(2 * np.pi * (0.7 * x + y) / 48 + v) + 0.6 * np.sin(2 * np.pi * (x - 0.5 * y) / 31 - 1.4 * v)
            d = 0.9 * (p(1.3 * t) - p(0.0))
            return 0.4 * d, d
        layers.append(Layer(fg(), (0, 0.6, 1 / 5.0), soft(Y - 0.5 * HR + 18 * np.sin(X / 90)), wave, True))
    elif cat == 'repeat':
        tile = 0.5 + 0.5 * np.sign(np.sin(2 * np.pi * X / 19.0 + (np.floor(Y / 13.0) % 2) * np.pi)) * np.sign(np.sin(2 * np.pi * Y / 26.0))
        p = gaussian_filter(tile, 0.8)[..., None]
        layers = [Layer((bg * 0.3 + p * 0.25 * np.array([1, .8, .6]) + 0.05).astype('f4'), (0, 0.3, 1 / 5.0))]
        rot_px = 9.0
    elif cat == 'weak':
        layers = [Layer((0.2 + 0.06 * (bg - bg.mean())).astype('f4'), (0, 0, far))]
    elif cat in ('night', 'night_motion'):
        opt.update(a=1.2e-3, b=1.2e-5)
        layers = [Layer(bg * 0.3, (0, 0, far)), Layer(fg() * 0.3, (0, 0, 1 / 2.0), blob(0.62 * HR, 0.6 * HR, 200, 180))]
        trans_mm = 1.2
        if cat == 'night_motion':
            layers.append(Layer(fg() * 0.3, (0, 0, 1 / 3.0), blob(0.38 * HR, 0.42 * HR, 120, 190), mover(2.0, 0.3, 0.8), True))
    elif cat == 'rolling':
        opt['rs'] = 1.0
        rot_px, roll_deg = 9.0, 0.25
    elif cat == 'blur':
        opt['blur'] = 5
        layers.append(Layer(fg(), (0, 0, 1 / 3.0), blob(0.45 * HR, 0.5 * HR, 150, 200), mover(2.5, 0.4), True))
    elif cat in ('hdr', 'hdr_motion'):
        opt['ev'] = [0, -2, 0, -2, 1, 0, -2, 1]
        boost = 1 + 5 * soft(blob(0.55 * HR, 0.45 * HR, 260, 200) - 0.5)
        layers = [Layer(bg * gaussian_filter(boost, 3)[..., None], (0, 0, far)),
                  Layer(fg(), (0, 0, 1 / 2.5), blob(0.3 * HR, 0.62 * HR, 170, 170))]
        trans_mm = 1.2
        if cat == 'hdr_motion':
            layers.append(Layer(fg(), (0, 0, 1 / 3.0), blob(0.45 * HR, 0.4 * HR, 120, 170), mover(2.0, 0.4), True))
    poses, vel = [np.eye(4)], [np.zeros(6)]
    rw = np.zeros(6)
    for k in range(1, K):    # handheld drift: random walk + jitter
        sig = np.array([rot_px / F, rot_px / F, np.deg2rad(roll_deg), trans_mm * 1e-3, trans_mm * 1e-3, trans_mm * 0.5e-3])
        rw = 0.85 * rw + rng.normal(size=6) * sig * 0.6
        v = rw + rng.normal(size=6) * sig * 0.4
        T = np.eye(4); T[:3, :3] = rodrigues(v[:3]); T[:3, 3] = v[3:]
        poses.append(T); vel.append(rng.normal(size=6) * np.array([4 / F, 4 / F, 1e-3, 0, 0, 0]))
    return layers, poses, vel, opt


KM = np.array([[F, 0, S / 2], [0, F, S / 2], [0, 0, 1.0]])   # LR px, centres at +0.5
KI = np.linalg.inv(KM)


def to_ref(layer, pose, vel, rs, t, qx, qy, iters=6):
    """Frame sensor coords (LR px) -> reference coords of this layer's surface; also inverse depth there."""
    R, T = pose[:3, :3], pose[:3, 3]
    c = -R.T @ T                                            # camera centre in the reference frame
    n = layer.plane
    M = KM @ (np.outer(c, n) + (1 - n @ c) * np.eye(3)) @ R.T   # ray -> homogeneous reference px
    if rs and np.any(vel):
        rows = (qy / S - 0.5) * rs                          # rotation varies over the rows (small angle)
        d = np.stack([qx, qy, np.ones_like(qx)], -1) @ KI.T
        d = d - np.cross(rows[..., None] * vel[:3], d)
        P = d @ M.T
        u, v = P[..., 0] / P[..., 2], P[..., 1] / P[..., 2]
    else:
        H = M @ KI
        w = 1 / (H[2, 0] * qx + H[2, 1] * qy + H[2, 2])
        u = (H[0, 0] * qx + H[0, 1] * qy + H[0, 2]) * w
        v = (H[1, 0] * qx + H[1, 1] * qy + H[1, 2]) * w
    if layer.motion is None or t == 0:
        return u, v
    mu, mv = u, v
    for _ in range(iters):
        ddx, ddy = layer.motion(t, mu, mv)
        mu, mv = u - ddx, v - ddy
    return mu, mv


def samp(img, u, v):        # u, v LR reference coords -> HR texture with margin, bicubic
    x = (2 * u - 0.5 + PAD).astype('f4'); y = (2 * v - 0.5 + PAD).astype('f4')
    return cv2.remap(img, x, y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT_101)


def render(layers, pose, vel, rs, t, qx, qy):
    out = None
    idx = np.zeros(qx.shape, int)
    U = np.zeros(qx.shape + (2,), 'f4')
    for i, L in enumerate(layers):
        u, v = to_ref(L, pose, vel, rs, t, qx, qy)
        c = samp(L.tex, u, v)
        if L.alpha is None:
            out, idx[:] = c, i
            U[..., 0], U[..., 1] = u, v
        else:
            a = np.clip(samp(L.alpha, u, v), 0, 1)
            out = out * (1 - a[..., None]) + c * a[..., None]
            m = a > 0.5
            idx[m] = i
            U[m, 0], U[m, 1] = u[m], v[m]
    return out, idx, U


def generate(cat, seed, sources, out):
    import zlib
    rng = np.random.default_rng([seed, zlib.crc32(cat.encode())])
    layers, poses, vel, opt = build(cat, rng, sources)
    os.makedirs(out, exist_ok=True)
    hy, hx = (np.mgrid[0:HR, 0:HR].astype('f8') + 0.5) / 2
    ly, lx = np.mgrid[0:S, 0:S].astype('f8') + 0.5
    flow, vis = [], []
    ref_img, ref_idx, _ = render(layers, poses[0], vel[0], 0, 0.0, hx, hy)
    _, ref_lidx, _ = render(layers, poses[0], vel[0], 0, 0.0, lx, ly)
    blur = lambda im: cv2.GaussianBlur(np.maximum(im, 0).astype('f4'), (0, 0), 0.6)
    gt = blur(ref_img)
    hidden = np.zeros((S, S), bool)
    for k in range(K):
        n = opt['blur'] if k else 1
        acc = 0
        for j in range(n):
            t = k + (0.5 * ((j + 0.5) / n - 0.5) if n > 1 else 0.0)
            acc = acc + render(layers, poses[k], vel[k], opt['rs'], float(t), hx, hy)[0]
        img = blur(acc / n)
        lr = img.reshape(S, 2, S, 2, 3).mean((1, 3))
        _, idx, U = render(layers, poses[k], vel[k], opt['rs'], float(k), lx, ly)
        # visible in the reference: same layer on top there
        ux = np.clip(np.floor(U[..., 0]).astype(int), 0, S - 1); uy = np.clip(np.floor(U[..., 1]).astype(int), 0, S - 1)
        inside = (U[..., 0] > 2) & (U[..., 0] < S - 2) & (U[..., 1] > 2) & (U[..., 1] < S - 2)
        flow.append(U.astype('f4')); vis.append(inside & (ref_lidx[uy, ux] == idx))
        if k:                   # reference surfaces with no matching sample in this frame
            seen = np.zeros((S, S), bool); seen[uy[vis[-1]], ux[vis[-1]]] = True
            seen = binary_dilation(seen, iterations=1)
            hidden |= ~seen
        bay = np.empty((S, S), 'f4')
        bay[0::2, 0::2] = lr[0::2, 0::2, 0]; bay[0::2, 1::2] = lr[0::2, 1::2, 1]
        bay[1::2, 0::2] = lr[1::2, 0::2, 1]; bay[1::2, 1::2] = lr[1::2, 1::2, 2]
        e = 2.0 ** opt['ev'][k]
        clean = np.clip(bay * e, 0, 1.02)
        noisy = clean + rng.normal(size=clean.shape) * np.sqrt(opt['a'] * np.clip(clean, 0, 1) + opt['b'])
        codes = np.clip(np.round(noisy * (1023 - 64) + 64), 0, 1023)
        write_dng(f'{out}/f{k:03}.dng', codes, exposure=(int(round(1000 * e)), 100000))
    up = lambda m: m.repeat(2, 0).repeat(2, 1)
    edges = np.zeros((HR, HR), bool)
    for ax in (0, 1):
        edges |= ref_idx != np.roll(ref_idx, 1, ax)
    g = gt @ np.array([.25, .5, .25])
    hp = g - gaussian_filter(g, 2)
    mov = np.isin(ref_idx, [i for i, L in enumerate(layers) if L.moving])
    dep = np.zeros((S, S), 'f4')
    for i, L in enumerate(layers):
        r = np.stack([lx, ly, np.ones_like(lx)], -1) @ KI.T
        dep[ref_lidx == i] = (1 / np.maximum(r @ L.plane, 1e-3))[ref_lidx == i]
    np.savez_compressed(f'{out}/labels.npz', gt=gt.astype('f4'), flow=np.array(flow), vis=np.array(vis), moving=mov,
                        boundary=binary_dilation(edges, iterations=8), hidden=up(hidden),
                        texture=np.sqrt(gaussian_filter(hp ** 2, 4)).astype('f4'), depth=dep, poses=np.array(poses), f=F,
                        noise=np.array([opt['a'], opt['b']]), ev=np.array(opt['ev']))


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('out')
    ap.add_argument('--sources', nargs='+', required=True, help='DNGs used as textures (half resolution)')
    ap.add_argument('--seeds', type=int, nargs='+', default=[1])
    ap.add_argument('--categories', nargs='+', default=CATEGORIES)
    a = ap.parse_args()
    srcs = [half_rgb(p) for p in a.sources]
    for c in a.categories:
        for s in a.seeds:
            generate(c, s, srcs, f'{a.out}/{c}_{s}')
            print(c, s, flush=True)
