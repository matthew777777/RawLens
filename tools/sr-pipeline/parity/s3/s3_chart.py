import sys, numpy as np
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import s3_proto as P
rng = np.random.default_rng(1)
N, SS = 512, 4
def scene(xs, ys):
    """Linear RGB in [0.05, 0.85]; xs, ys in pixel units (continuous)."""
    out = np.zeros(xs.shape + (3,))
    base = np.stack([0.35 + 0 * xs, 0.4 + 0 * xs, 0.3 + 0 * xs], -1)
    out[:] = base
    q = (xs < 256) & (ys < 256)          # vertical gratings, period 2.1..3.0
    per = 2.1 + 0.9 * (ys / 256.0)
    out[q] = (0.45 + 0.4 * np.sign(np.sin(2 * np.pi * xs / per)))[q][:, None] * np.array([1, 1, 1])
    q = (xs >= 256) & (ys < 256)         # diagonal gratings
    per = 2.3 + 0.7 * ((xs - 256) / 256.0)
    out[q] = (0.45 + 0.4 * np.sin(2 * np.pi * (xs + ys) / (per * 1.414)))[q][:, None] * np.array([0.9, 1, 0.8])
    q = (xs < 256) & (ys >= 256)         # zone plate
    r2 = (xs - 128) ** 2 + (ys - 384) ** 2
    out[q] = (0.45 + 0.4 * np.cos(np.pi * r2 / 300.0))[q][:, None] * np.array([1, 1, 1])
    q = (xs >= 256) & (ys >= 256)        # ordinary edges (squares)
    cell = ((np.floor(xs / 24) + np.floor(ys / 24)) % 2)
    out[q] = (0.15 + 0.55 * cell)[q][:, None] * np.array([0.8, 1, 0.6])
    return np.clip(out, 0.05, 0.85)
def render(dx, dy):
    o = (np.arange(N * SS) + 0.5) / SS
    xs, ys = np.meshgrid(o + dx, o + dy)
    img = scene(xs, ys).reshape(N, SS, N, SS, 3).mean((1, 3))
    return img
alpha, beta = [2e-4] * 4, [2e-6] * 4
frames, gt = [], None
for f in range(8):
    dx, dy = (0.0, 0.0) if f == 0 else rng.uniform(-1.5, 1.5, 2)
    rgb = render(dx, dy)
    if f == 0: gt = rgb
    m = np.empty((N, N))
    m[0::2, 0::2] = rgb[0::2, 0::2, 0]; m[0::2, 1::2] = rgb[0::2, 1::2, 1]
    m[1::2, 0::2] = rgb[1::2, 0::2, 1]; m[1::2, 1::2] = rgb[1::2, 1::2, 2]
    m = m + rng.normal(0, np.sqrt(alpha[0] * m + beta[0]))
    frames.append(m)
ref, comp = frames[0], np.stack(frames[1:])
def score(out, name):
    m = 24; e = (out - gt)[m:-m, m:-m]
    quads = {'vgrating': e[:232, :232], 'diag': e[:232, 232:], 'zoneplate': e[232:, :232], 'edges': e[232:, 232:]}
    s = ' '.join(f'{k} {10*np.log10(1/np.mean(v**2)):5.1f}' for k, v in quads.items())
    print(f'{name:26s} all {10*np.log10(1/np.mean(e**2)):5.1f} dB | {s}', flush=True)
results = {'off': P.merge(ref, comp, alpha, beta, 30.0)}
score(results['off'], 'no S.3')
for rho in (0.1, 0.2, 0.3):
    for k in (4, 16):
        out = P.merge(ref, comp, alpha, beta, 30.0, s3=(rho, k))
        results[f'rho{rho}_k{k}'] = out
        score(out, f'S.3 rho<{rho} k={k} rej={np.mean(P.STATE["rejected"])*100:.1f}%')
np.savez(sys.argv[2], gt=gt, **results)
