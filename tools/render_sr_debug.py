#!/usr/bin/env python3
"""Jamy-L-style debug PNG renders for linear-sr-desktop --dump-fields output.

Reads the raw-LE-float32 + dims-sidecar dumps (*.f32 + *.txt) and renders:
  rgb_pre.png        linear merge rgb before CFL (gamma 1/2.2, clip 0..1)
  den_R/G/B.png      per-channel denominator heatmaps (log1p, shared scale)
  den_RG/BG.png      R/G and B/G denominator ratios (diverging, latch imbalance)
  quot_RG/BG.png     pre-CFL (num/den) chroma ratios R/G, B/G (diverging)
  zipmap_pre.png     chroma high-pass energy on rgb_pre (zipper locator)
  r_*.png            per-frame robustness [0,1]
  flow_*.png         per-frame flow as HSV (hue=angle, value=magnitude)
  cov_*.png          kernel ellipses (hue=orientation, val=size, sat=anisotropy)
  support/rc/fallback/evidence/oob.png  merge diagnostics

Usage: python3 tools/render_sr_debug.py <dump-dir> [<png-dir, default <dump-dir>/png>]
Deps: numpy, PIL only.
"""
import os
import sys
import numpy as np
from PIL import Image


def load_f32(path):
    base, _ = os.path.splitext(path)
    with open(base + ".txt") as f:
        w, h, c = (int(v) for v in f.read().split())
    a = np.fromfile(path, dtype="<f4")
    assert a.size == w * h * c, f"{path}: {a.size} != {w*h*c}"
    return a.reshape(h, w, c) if c > 1 else a.reshape(h, w)


def heatmap(v, vmax=None):
    """Inferno-ish heatmap for v>=0. Shared-scale when vmax given."""
    v = np.nan_to_num(v, nan=0.0, posinf=0.0, neginf=0.0)
    v = np.maximum(v, 0)
    if vmax is None:
        vmax = np.quantile(v, 0.999)
    t = np.clip(v / max(vmax, 1e-12), 0, 1)
    # black -> purple -> red -> orange -> yellow polynomial ramps
    r = np.clip(1.35 * t - 0.15 * t * t - 0.05, 0, 1)
    g = np.clip(1.9 * t * t - 0.25 * t, 0, 1)
    b = np.clip(0.9 * t - 1.35 * t * t + 0.12 * (1 - t) * (t > 0.02), 0, 1)
    return (np.stack([r, g, b], -1) * 255).astype(np.uint8)


def diverging(v, vabs):
    """Blue-white-red for signed v with range [-vabs, +vabs]."""
    v = np.nan_to_num(v, nan=0.0, posinf=vabs, neginf=-vabs)
    t = np.clip(v / vabs, -1, 1)
    pos = np.clip(t, 0, 1)[..., None]
    neg = np.clip(-t, 0, 1)[..., None]
    rgb = np.ones(t.shape + (3,)) * (1 - np.abs(t))[..., None]
    rgb += pos * np.array([0.85, 0.15, 0.10]) + neg * np.array([0.10, 0.25, 0.85])
    return (np.clip(rgb, 0, 1) * 255).astype(np.uint8)


def main(dump_dir, png_dir):
    os.makedirs(png_dir, exist_ok=True)
    names = sorted(f[:-4] for f in os.listdir(dump_dir) if f.endswith(".f32"))
    print(f"dumps: {len(names)} fields")

    rgb = load_f32(os.path.join(dump_dir, "cpu_merge_rgb_pre.f32"))
    Image.fromarray((np.clip(rgb, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)).save(
        os.path.join(png_dir, "rgb_pre.png"))

    num = load_f32(os.path.join(dump_dir, "cpu_merge_num.f32"))
    den = load_f32(os.path.join(dump_dir, "cpu_merge_den.f32"))
    eps = 1e-8
    dmax = max(np.quantile(np.maximum(den[..., c], 0), 0.999) for c in range(3))
    for c, ch in enumerate("RGB"):
        Image.fromarray(heatmap(np.log1p(np.maximum(den[..., c], 0)),
                                np.log1p(dmax))).save(
            os.path.join(png_dir, f"den_{ch}.png"))
        frac = np.mean(den[..., c] <= eps)
        print(f"den_{ch}: max={den[...,c].max():.3f} mean={den[...,c].mean():.3f} "
              f"zeroFrac={frac:.4f}")
    g = np.maximum(den[..., 1], eps)
    for c, ch in ((0, "RG"), (2, "BG")):
        Image.fromarray(diverging(np.log2(np.maximum(den[..., c], eps) / g), 3.0)).save(
            os.path.join(png_dir, f"den_{ch}.png"))
    # pre-CFL chroma ratios from quotients
    q = np.zeros_like(num)
    for c in range(3):
        m = den[..., c] > eps
        q[..., c] = np.where(m, num[..., c] / np.maximum(den[..., c], eps), 0)
    gg = np.maximum(q[..., 1], 1e-4)
    for c, ch in ((0, "RG"), (2, "BG")):
        Image.fromarray(diverging(np.log2(np.maximum(q[..., c], 1e-4) / gg), 1.5)).save(
            os.path.join(png_dir, f"quot_{ch}.png"))
    # zipper locator: chroma HP energy
    rbm = rgb[..., 0] - rgb[..., 2]
    gbm = rgb[..., 1] - 0.5 * (rgb[..., 0] + rgb[..., 2])
    hp = np.abs(rbm - _blur3(rbm)) + np.abs(gbm - _blur3(gbm))
    Image.fromarray(heatmap(hp)).save(os.path.join(png_dir, "zipmap_pre.png"))
    print(f"zipmap: p99={np.quantile(hp,0.99):.4f} p999={np.quantile(hp,0.999):.4f} "
          f"max={hp.max():.4f}")

    for n in names:
        a = load_f32(os.path.join(dump_dir, n + ".f32"))
        if n.startswith("cpu_r_mov"):
            Image.fromarray(heatmap(np.clip(a, 0, 1), 1.0)).save(
                os.path.join(png_dir, n + ".png"))
        elif n.startswith("cpu_flow_mov"):
            Image.fromarray(_flow_hsv(a)).save(os.path.join(png_dir, n + ".png"))
        elif n.startswith("cpu_cov_"):
            Image.fromarray(_cov_viz(a)).save(os.path.join(png_dir, n + ".png"))
        elif n in ("cpu_merge_support", "cpu_merge_rc"):
            Image.fromarray(heatmap(np.clip(a, 0, None))).save(
                os.path.join(png_dir, n + ".png"))
        elif n in ("cpu_merge_fallback", "cpu_merge_evidence", "cpu_merge_oob"):
            Image.fromarray(heatmap(a)).save(os.path.join(png_dir, n + ".png"))
    print(f"wrote {png_dir}")


def _blur3(v):
    p = np.pad(v, 1, mode="edge")
    return (p[:-2, :-2] + p[:-2, 1:-1] + p[:-2, 2:] + p[1:-1, :-2] + p[1:-1, 1:-1] +
            p[1:-1, 2:] + p[2:, :-2] + p[2:, 1:-1] + p[2:, 2:]) / 9.0


def _flow_hsv(f):
    dx, dy = f[..., 0], f[..., 1]
    mag = np.hypot(dx, dy)
    ang = (np.arctan2(dy, dx) / (2 * np.pi) + 1.0) % 1.0
    sat = np.ones_like(mag)
    val = np.clip(mag / max(np.quantile(mag, 0.99), 1e-6), 0, 1)
    hsv = (np.stack([ang, sat, val], -1) * 255).astype(np.uint8)
    return np.asarray(Image.fromarray(hsv, "HSV").convert("RGB"))


def _cov_viz(c):
    """Kernel ellipses: hue = major-axis angle, value = major radius, sat = 1-1/A."""
    cxx, cxy = c[..., 0], c[..., 1]
    cyy = c[..., 3]
    tr = cxx + cyy
    det = cxx * cyy - cxy * cxy
    disc = np.sqrt(np.maximum(tr * tr / 4 - det, 0))
    l1 = np.maximum(tr / 2 + disc, 1e-12)
    l2 = np.maximum(tr / 2 - disc, 1e-12)
    ang = 0.5 * np.arctan2(2 * cxy, cxx - cyy) / np.pi % 1.0
    aniso = 1 - np.sqrt(l2 / l1)
    size = np.clip(np.sqrt(np.sqrt(l1)) / 1.5, 0, 1)
    hsv = (np.stack([ang, np.clip(aniso, 0, 1), size], -1) * 255).astype(np.uint8)
    return np.asarray(Image.fromarray(hsv, "HSV").convert("RGB"))


if __name__ == "__main__":
    dump = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(dump, "png")
    main(dump, out)
