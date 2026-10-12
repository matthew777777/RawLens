#!/usr/bin/env python3
"""GPU parity: run the shipped DenoisePipeline (denoise_bench, MoltenVK) on the
frozen fixtures and compare against the float32 oracle goldens.

The production pipeline stores its image and wavelet tiles as RGBA16F and
reduces thresholds on the GPU, so it cannot match the float32 oracle to the
last ulp. The gate instead bounds the error well below the size of the
denoising itself:
  PASS if max_abs <= 5e-3 AND p99_abs <= 5e-4 AND mean_abs <= 1e-4
       AND mean_abs <= 0.25 * mean |golden - input|.
Measured envelope (M-series MoltenVK): max 2.1e-3, p99 2.8e-4, mean 5.8e-5,
error/effect <= 0.13.

Each fixture also runs with 256 px tiles (four tiles at 512 px) to cover the
multi-tile path. Thresholds are per tile by design, so this run is not
compared to the golden; instead the error in a band around the tile seams
must not exceed SEAM_RATIO x the error elsewhere (no visible seams).

Usage: uv run --no-project --with numpy \\
         native/raw_denoise/tools/verify_denoise_parity.py [fixture-filter...]
Output: tmp/denoise_visuals/parity/ + exit code.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.normpath(os.path.join(HERE, ".."))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
BIN = os.path.join(MOD, "build-macos", "denoise_bench")
SHADERS = os.path.join(MOD, "build-shaders")
DATA = os.path.join(MOD, "tests", "data")
OUT = os.path.join(REPO, "tmp", "denoise_visuals", "parity")

PASS_MAX = 5e-3
PASS_P99 = 5e-4
PASS_MEAN = 1e-4
PASS_EFFECT_RATIO = 0.25
SEAM_TILE = 256
SEAM_BAND = 6
SEAM_RATIO = 1.5


def build_args(fx: dict, out_path: str, tile: int = 1536) -> list:
    w, h = fx["crop"]
    return [BIN, "--spv-dir", SHADERS, "--tile", str(tile),
            "--input-f32", os.path.join(DATA, fx["input"]),
            "--width", str(w), "--height", str(h),
            "--scales", str(fx["scales"]),
            "--wb", *[repr(float(v)) for v in fx["wb"]],
            "--a", repr(float(fx["a_norm"])), "--b", repr(float(fx["b_norm"])),
            "--strength", repr(float(fx["strength"])), "--shadows", repr(float(fx["shadows"])),
            "--dump-out", out_path]


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    manifest = json.load(open(os.path.join(DATA, "manifest.json")))
    filt = sys.argv[1:]
    report = {"fixtures": [], "pass": True}
    for fx in manifest["fixtures"]:
        fid = fx["stem"] + "/" + fx["tuning"]
        if filt and not any(x in fx["stem"] + fx["tuning"] for x in filt):
            continue
        w, h = fx["crop"]
        out_path = os.path.join(OUT, fx["stem"] + "_" + fx["tuning"] + "_gpu.f32")
        r = subprocess.run(build_args(fx, out_path), capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout.strip())
            print(r.stderr[-2000:])
            report["fixtures"].append({"id": fid, "ok": False, "error": "runner failed"})
            report["pass"] = False
            continue
        gpu = np.fromfile(out_path, dtype=np.float32).reshape(h, w, 4)[:, :, :3]
        ref = np.fromfile(os.path.join(DATA, fx["output"]), dtype=np.float32).reshape(h, w, 3)
        inp = np.fromfile(os.path.join(DATA, fx["input"]), dtype=np.float32).reshape(h, w, 4)[:, :, :3]
        d = np.abs(gpu - ref)
        effect = float(np.abs(ref - inp).mean())
        stats = {"id": fid, "max": float(d.max()), "p99": float(np.percentile(d, 99)),
                 "mean": float(d.mean()), "effect": effect}
        stats["ratio"] = stats["mean"] / effect if effect > 0 else float("inf")
        ok = (stats["max"] <= PASS_MAX and stats["p99"] <= PASS_P99 and stats["mean"] <= PASS_MEAN
              and stats["ratio"] <= PASS_EFFECT_RATIO)
        tiled_path = out_path.replace("_gpu.f32", "_gpu_tiled.f32")
        rt = subprocess.run(build_args(fx, tiled_path, SEAM_TILE), capture_output=True, text=True)
        if rt.returncode == 0:
            dt = np.abs(np.fromfile(tiled_path, dtype=np.float32).reshape(h, w, 4)[:, :, :3] - ref).max(-1)
            band = np.zeros((h, w), bool)
            for edge in range(SEAM_TILE, max(w, h), SEAM_TILE):
                band[max(edge - SEAM_BAND, 0):edge + SEAM_BAND, :] = True
                band[:, max(edge - SEAM_BAND, 0):edge + SEAM_BAND] = True
            stats["seam_ratio"] = float(dt[band].mean() / max(dt[~band].mean(), 1e-12))
        else:
            stats["seam_ratio"] = float("inf")
        ok = ok and stats["seam_ratio"] <= SEAM_RATIO
        stats["ok"] = ok
        print(f"{fid}: max={stats['max']:.2e} p99={stats['p99']:.2e} mean={stats['mean']:.2e} "
              f"effect={effect:.2e} ratio={stats['ratio']:.3f} seam={stats['seam_ratio']:.2f} "
              f"{'PASS' if ok else 'FAIL'}")
        report["fixtures"].append(stats)
        report["pass"] = report["pass"] and ok
    json.dump(report, open(os.path.join(OUT, "parity.json"), "w"), indent=2)
    print("PARITY_" + ("PASS" if report["pass"] else "FAIL"))
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
