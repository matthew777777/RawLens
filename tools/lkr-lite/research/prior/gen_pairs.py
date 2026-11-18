# SPDX-License-Identifier: Apache-2.0
"""Training pairs for the LK-R Lite post-fit prior: synthetic raw bursts (lkr_synth_burst.py) from
RGB photos (Zurich RAW-to-RGB Canon images, unprocessed), merged by Lite (lkrl_full_run, 2x), saved as
(Lite's hybrid result before finishing, ground truth), both linear camera RGB, 1024x1024, float16.

  gen_pairs.py SOURCES_DIR TEMPLATE.dng OUT_DIR [--count N] [--seed S]
"""
import argparse, glob, os, shutil, subprocess, sys, tempfile
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SYN = os.path.join(HERE, '..', 'lkr_synth_burst.py')
LITE = os.path.join(HERE, '..', '..', 'build', 'host', 'lkrl_full_run')


def hardness(x, y, cell=32):
    """Gharbi et al. 2016-style hard-patch mining signal. x - y is Lite's noise-free reconstruction error
    (y = truth + Lite's own grain), i.e. its zipper / dots / colour errors. Per cell: fine-scale error
    energy in luma and chroma (their separate luminance / colour-moire tests). Returns (2, H/cell, W/cell)."""
    from scipy.ndimage import gaussian_filter as gf
    e = x.astype(np.float32) - y.astype(np.float32)
    hp = e - gf(e, (2, 2, 0))
    lum = hp.mean(-1); chroma = np.sqrt((hp[..., 0] - hp[..., 1]) ** 2 + (hp[..., 2] - hp[..., 1]) ** 2)
    lev = max(float(y.mean()), 1e-6)
    H, W = lum.shape
    f = lambda a: np.sqrt((a[:H // cell * cell, :W // cell * cell] ** 2).reshape(H // cell, cell, W // cell, cell).mean((1, 3))) / lev
    return np.stack([f(lum), f(chroma)]).astype(np.float32)


def align_truth(x, g):
    """The generator's truth and Lite's output differ by a fixed coordinate convention: the truth sits
    (+1.5, -0.5) HR px off (measured 2026-11-17). Shift it by that, then refine +-0.25 px in 1/8 steps on
    green (cubic resampling)."""
    from scipy.ndimage import shift
    c = slice(64, -64)
    best = None
    for dy in np.arange(1.25, 1.751, 0.125):
        for dx in np.arange(-0.75, -0.249, 0.125):
            ga = shift(g[..., 1], (dy, dx), order=3)
            k = (x[c, c, 1] * ga[c, c]).sum() / max((x[c, c, 1] ** 2).sum(), 1e-12)
            e = ((x[c, c, 1] * k - ga[c, c]) ** 2).mean()
            if best is None or e < best[0]: best = (e, dy, dx)
    return shift(g, (best[1], best[2], 0), order=3).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('sources'); ap.add_argument('template'); ap.add_argument('out')
    ap.add_argument('--count', type=int, default=400); ap.add_argument('--poles', action='store_true', help='every scene: thin bright tilted lines (pole highlights)'); ap.add_argument('--seed', type=int, default=1)
    a = ap.parse_args()
    a.sources, a.template, a.out = (os.path.abspath(os.path.expanduser(v)) for v in (a.sources, a.template, a.out))
    os.makedirs(a.out, exist_ok=True)
    srcs = sorted(glob.glob(os.path.join(a.sources, '**', '*.jpg'), recursive=True) +
                  glob.glob(os.path.join(a.sources, '**', '*.png'), recursive=True))
    srcs = [s for s in srcs if os.path.getsize(s) > 1_000_000]   # full-resolution photos only
    rng = np.random.default_rng(a.seed)
    n = 0
    while n < a.count:
        i = n + a.seed * 100000
        dst = os.path.join(a.out, f'pair_{i:06d}.npz')
        if os.path.exists(dst): n += 1; continue
        src = srcs[rng.integers(len(srcs))]
        from PIL import Image
        W, H = Image.open(src).size
        if W < 1100 or H < 1100: continue
        sx, sy = int(rng.integers(0, W - 1024)), int(rng.integers(0, H - 1024))
        nl = float(np.exp(rng.uniform(np.log(5e-6), np.log(3e-4))))   # noise a (signal-dependent)
        args = [sys.executable, '-I', SYN, a.template, src, None, '--frames', '8', '--size', '512',
                '--src-at', str(sx), str(sy), '--no-person', '--no-clip', '--person-speed', '0', '--seed', str(i),
                '--tremor', f'{rng.uniform(1, 4):.2f}', '--blur', f'{rng.uniform(0.2, 0.5):.2f}',
                '--noise-a', f'{nl:.3g}', '--noise-b', f'{nl * 0.05:.3g}']
        if a.poles:   # pole highlights: thin bright slightly tilted lines over natural (sometimes defocused) backgrounds
            args += ['--rail-tilt', f'{rng.choice([-1, 1]) * rng.uniform(0.5, 4):.2f}', '--rail-width', f'{rng.uniform(1.0, 2.5):.2f}',
                     '--rail-period', f'{rng.uniform(25, 70):.1f}', '--rail-level', f'{rng.uniform(0.4, 0.8):.2f}']
            if rng.random() < 0.5: args[args.index('--blur') + 1] = f'{rng.uniform(0.6, 1.2):.2f}'   # defocused hill behind
        elif rng.random() < 0.4:   # thin lines / bars (poles, rails) in 40 % of scenes
            args += ['--rail-tilt', f'{rng.uniform(-6, 6):.2f}', '--rail-width', f'{rng.choice([1.0, 1.5, 2.0, 4.0, 8.0])}',
                     '--rail-period', f'{rng.uniform(14, 60):.1f}', '--rail-level', f'{rng.choice([0.05, 0.1, 0.5, 0.75])}']
        else:
            args += ['--no-rail']
        with tempfile.TemporaryDirectory() as td:
            # Grain-preserving target (2026-11-17): the same burst twice (same seed = same motion), with and
            # without noise; Lite told the SAME noise model both times (same rejection), so
            # Lite(noisy) - Lite(clean) is Lite's own grain. Target = truth + that grain: the prior gets no
            # credit for removing grain, only for the zipper / dots / colour errors.
            outs = {}
            for tag, na, nb in (('noisy', nl, nl * 0.05), ('clean', 0.0, 0.0)):
                burst = os.path.join(td, 'burst_' + tag)
                ar = list(args); ar[5] = burst
                ar[ar.index('--noise-a') + 1] = f'{na:.3g}'; ar[ar.index('--noise-b') + 1] = f'{nb:.3g}'
                r = subprocess.run(ar, capture_output=True, text=True, cwd=td)
                if r.returncode != 0: print('synth failed', r.stderr[-300:]); break
                env = dict(os.environ, LKRL_TRACE_PREFIX=os.path.join(td, 't_' + tag))
                r = subprocess.run([LITE, burst, os.path.join(td, tag + '.dng'), '--s', '2', '--crop', '512', '512', '--at', '1536', '1024',
                                    '--noise', f'{nl:.6g}', f'{nl * 0.05:.6g}'], capture_output=True, text=True, env=env, cwd=td)
                hyb = os.path.join(td, f't_{tag}_tile_0_0-hybrid.f32')
                if r.returncode != 0 or not os.path.exists(hyb): print('lite failed'); break
                outs[tag] = np.transpose(np.fromfile(hyb, np.float32).reshape(3, 1024, 1024), (1, 2, 0))
                if tag == 'clean': g = np.load(os.path.join(burst, 'labels.npz'))['gt'].astype(np.float32)
            if len(outs) < 2: n += 1; continue
            x, xc = outs['noisy'], outs['clean']
            g = align_truth(xc, g)
            from scipy.ndimage import gaussian_filter as gf
            c = slice(64, -64)
            k = (xc[c, c] * g[c, c]).sum() / max(float((xc[c, c] ** 2).sum()), 1e-12); e = xc * k - g
            fine = float(np.sqrt(((e - gf(e, (2, 2, 0)))[c, c] ** 2).mean()) / max(float(g[c, c].mean()), 1e-6))
            if (g[c, c].max(-1) > 0.95).mean() > 0.002 or abs(k - 1) > 0.1 or fine > 0.25:
                print(f'rejected pair (gain {k:.2f}, fine error {fine:.3f})'); continue   # clipped / misaligned
            y = g + (x - xc)   # truth + Lite's own grain
            np.savez(dst, x=x.astype(np.float16), y=y.astype(np.float16), noise=np.float32(nl), hard=hardness(x, y))
        n += 1
        if n % 10 == 0: print(n, flush=True)


if __name__ == '__main__':
    main()
