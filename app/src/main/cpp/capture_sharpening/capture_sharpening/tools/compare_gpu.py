"""Compare native/capture_sharpening (GPU, host MoltenVK) with the bit-exact NumPy RT port.

Build the host validator first:
    cmake -S native/capture_sharpening -B tmp/capture_sharpening/gpu_build -G Ninja -DCAPTURE_SHARPENING_BUILD_TESTS=ON
    cmake --build tmp/capture_sharpening/gpu_build
then:
    uv run --with numpy --with rawpy --with pillow python native/capture_sharpening/tools/compare_gpu.py --dng <file.dng> [--iters 10] [--repeats 3]
    uv run --with numpy python native/capture_sharpening/tools/compare_gpu.py --synthetic

Both sides get the same fp16-quantized pre-WB camera RGB (white = 1), as the app's
RGBA16F demosaic output. The NumPy side converts to RT units (x 65535 x WB) and
derives the CFA plane from native samples, exactly what the GPU passes read.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

import capsharp_reference as cs

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TMP = REPO / "tmp" / "capture_sharpening"  # all generated files (AGENTS.md: keep temp work in ./tmp)
VALIDATOR = TMP / "gpu_build" / "capture_sharpening_validate"
f32 = np.float32
CFA_RGGB = 0


def native_plane(r, g, b):
    """RGGB native samples."""
    raw = np.empty_like(g)
    raw[0::2, 0::2] = r[0::2, 0::2]
    raw[0::2, 1::2] = g[0::2, 1::2]
    raw[1::2, 0::2] = g[1::2, 0::2]
    raw[1::2, 1::2] = b[1::2, 1::2]
    return raw


def reference(rgb16, wb, p: cs.Params):
    wbn = np.asarray(wb, np.float64) / min(wb)
    to_rt = (65535.0 * wbn).astype(f32)
    r, g, b = (rgb16[..., c] * to_rt[c] for c in range(3))
    raw = native_plane(r, g, b)
    cfa_mul = np.array([[wbn[0], wbn[1]], [wbn[1], wbn[2]]])
    whites = (0.95 * 65535.0 * cfa_mul).astype(f32)
    t = time.time()
    res = cs.capture_sharpening(raw, r, g, b, whites, f32(to_rt[1]), (0, 1), p)
    dt = time.time() - t
    out = np.stack([res.r / to_rt[0], res.g / to_rt[1], res.b / to_rt[2]], -1).astype(f32)
    return out, res.radius, res.contrast, dt


def run_gpu(work: Path, rgb16, wb, p: cs.Params, repeats):
    H, W, _ = rgb16.shape
    work.mkdir(parents=True, exist_ok=True)
    rgb16.astype(f32).tofile(work / "rgb.f32")
    (work / "params.txt").write_text(
        f"{W} {H} {CFA_RGGB} {wb[0]!r} {wb[1]!r} {wb[2]!r} {int(p.auto_radius)} {p.radius} {p.radius_offset} "
        f"{int(p.auto_contrast)} {p.contrast} {p.iterations} {int(p.iter_check)} {repeats}\n")
    subprocess.run([str(VALIDATOR), str(work)], check=True)
    out = np.fromfile(work / "gpu_rgb.f32", f32).reshape(H, W, 3)
    info = {}
    for line in (work / "gpu_info.txt").read_text().splitlines():
        k, *v = line.split()
        info[k] = [float(x) for x in v]
    return out, info


def luma(x):
    return 0.2627 * x[..., 0] + 0.6780 * x[..., 1] + 0.0593 * x[..., 2]


def report(name, rgb16, ref, gpu, ref_r, ref_c, info, ref_s):
    y0, yr, yg = luma(rgb16.astype(np.float64)), luma(ref.astype(np.float64)), luma(gpu.astype(np.float64))
    dr, dg = yr - y0, yg - y0
    err = dg - dr
    rel = np.linalg.norm(err) / max(np.linalg.norm(dr), 1e-12)
    corr = float(np.corrcoef(dr.ravel(), dg.ravel())[0, 1]) if dr.std() > 0 else float("nan")
    ref16 = ref.astype(np.float16).astype(f32)  # GPU output is RGBA16F
    diff = np.abs(gpu - ref16)
    scale = np.maximum(np.abs(ref16), 1e-3)
    big = (diff / scale > 2.0 ** -9).mean()  # beyond ~2 fp16 ulps
    peak = max(float(rgb16.max()), 1.0)
    mse = float(((gpu.astype(np.float64) - ref16) ** 2).mean())
    psnr = 10 * np.log10(peak * peak / mse) if mse > 0 else float("inf")
    print(f"== {name}  {rgb16.shape[1]}x{rgb16.shape[0]}")
    print(f"   radius   ref {ref_r:.5f}  gpu {info['radius'][0]:.5f}")
    print(f"   contrast ref {ref_c:.2f}  gpu {info['contrast'][0]:.2f}  valid {int(info['valid'][0])}")
    print(f"   sharpening delta (Y): |gpu-ref|/|ref| = {rel:.4f}, corr = {corr:.5f}, "
          f"mean|ref delta| = {np.abs(dr).mean():.3e}")
    print(f"   RGB: PSNR {psnr:.1f} dB vs ref (peak {peak:.2f}), pixels beyond 2 fp16 ulps: {big * 100:.3f}%, "
          f"max abs {diff.max():.4g}")
    print(f"   time: GPU {', '.join(f'{m:.1f}' for m in info['gpu_ms'])} ms (host MoltenVK), NumPy {ref_s:.1f} s,"
          f" scratch {info['scratch_bytes'][0] / 2**20:.0f} MiB")
    return rel, info


def from_dng(path: Path):
    """Pre-WB camera RGB (white = 1) after gain maps and RT AMaZE; cached per DNG."""
    cache = TMP / "work" / f"{path.stem}_prewb.npy"
    wb_file = cache.with_suffix(".wb.txt")
    if cache.exists() and wb_file.exists() and cache.stat().st_mtime >= path.stat().st_mtime:
        return np.load(cache), [float(x) for x in wb_file.read_text().split()]
    rgb, wb = _decode_dng(path)
    cache.parent.mkdir(parents=True, exist_ok=True)
    np.save(cache, rgb)
    wb_file.write_text(" ".join(repr(x) for x in wb))
    return rgb, wb


def _decode_dng(path: Path):
    import run_dng as rd
    import rawpy

    with rawpy.imread(str(path)) as rp:
        raw = rp.raw_image_visible.astype(f32)
    black = rd.nums(path, "BlackLevel")
    white = rd.nums(path, "WhiteLevel")[0]
    neutral = rd.nums(path, "AsShotNeutral")
    blk = np.empty((2, 2), f32)
    blk.flat[:] = black[:4]
    H, W = raw.shape
    raw -= np.tile(blk, (H // 2, W // 2))
    raw /= f32(white - np.mean(black))
    for m in rd.parse_gain_maps(rd.exif(path, "OpcodeList2", binary=True)):
        rd.apply_gain_map(raw, m)
    pre_mul = np.array([1 / neutral[0], 1 / neutral[1], 1 / neutral[2]])
    pre_mul /= pre_mul.min()
    cfa_mul = np.array([[pre_mul[0], pre_mul[1]], [pre_mul[1], pre_mul[2]]], f32)
    scaled = raw * np.tile(cfa_mul * f32(65535.0), (H // 2, W // 2))
    r, g, b = rd.demosaic_amaze(scaled, float(pre_mul.max() / pre_mul.min()), TMP / "work" / "amaze_tmp")
    to_rt = (65535.0 * pre_mul).astype(f32)
    rgb = np.stack([r / to_rt[0], g / to_rt[1], b / to_rt[2]], -1).astype(f32)
    return rgb, [float(x) for x in pre_mul]


def synthetic():
    import parity as pt

    rgb = pt.scene(600, 800, seed=7, blur_sigma=0.9) / 65535.0
    raw = np.clip(pt.mosaic_rggb(rgb), 0, 1.0)
    dm = pt.bilinear_demosaic(raw)
    # keep native samples exact, as real demosaicers do
    dm[0][0::2, 0::2] = raw[0::2, 0::2]
    dm[1][0::2, 1::2] = raw[0::2, 1::2]
    dm[1][1::2, 0::2] = raw[1::2, 0::2]
    dm[2][1::2, 1::2] = raw[1::2, 1::2]
    return np.stack(dm, -1).astype(f32), [2.0, 1.0, 1.6]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", type=Path)
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--iters", type=int, nargs="*", default=[20])
    ap.add_argument("--repeats", type=int, default=1)
    ap.add_argument("--crop", type=int, nargs=4, metavar=("Y", "X", "H", "W"))
    ap.add_argument("--scale", type=int, default=1, help="2x2 box downscale before running (12.5 MP from 50 MP)")
    a = ap.parse_args()
    if not VALIDATOR.exists():
        sys.exit("build first: see the module docstring")
    if a.dng:
        rgb, wb = from_dng(a.dng)
        name = a.dng.stem
    else:
        rgb, wb = synthetic()
        name = "synthetic"
    if a.crop:
        y, x, h, w = a.crop
        rgb = rgb[y:y + h, x:x + w]
        name += f"_crop{y}_{x}"
    if a.scale == 2:
        H, W = rgb.shape[0] // 4 * 4, rgb.shape[1] // 4 * 4
        rgb = rgb[:H, :W].reshape(H // 2, 2, W // 2, 2, 3).mean(axis=(1, 3)).astype(f32)
        name += "_half"
    rgb16 = rgb.astype(np.float16).astype(f32)
    worst = 0.0
    for it in a.iters:
        p = cs.Params(iterations=it)
        ref, rr, rc, rs = reference(rgb16, wb, p)
        gpu, info = run_gpu(TMP / "work" / f"gpu_{name}_{it}", rgb16, wb, p, a.repeats)
        rel, _ = report(f"{name} iterations={it}", rgb16, ref, gpu, rr, rc, info, rs)
        worst = max(worst, rel)
    print(f"worst relative sharpening-delta error {worst:.4f}")


if __name__ == "__main__":
    main()
