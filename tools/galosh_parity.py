#!/usr/bin/env python3
"""GALOSH Vulkan parity harness: deterministic mosaic input, CPU reference, PSNR gate.

Flow (see docs/galosh-phase2-parity.md once written):
  1. gen   : write a seeded Bayer-mosaic float32 input (+ sidecar with dims/params).
  2. ref   : run the upstream CPU reference (standalone/galosh_raw_cpu) on it.
  3. push input + sidecar to /data/local/tmp, run GaloshParityTest on device,
     pull Download/RawLens/galosh_parity_out.bin.
  4. psnr  : compare device output vs CPU reference (gate: >= 69 dB).

Requires numpy (input gen + PSNR only; the reference is upstream C).
"""
import argparse
import subprocess
import sys

import numpy as np

SEED_DEFAULT = 20260926
GATE_DEFAULT_DB = 69.0


def gen_input(path, sidecar, w, h, seed, strength=1.0, luma=1.0, chroma=1.0):
    assert w % 2 == 0 and h % 2 == 0, "GALOSH needs even dims"
    rng = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    scene = 0.12 + 0.45 * (xx / w) + 0.18 * (yy / h)
    scene[h // 4: h // 2, w // 4: w // 2] += 0.30  # bright patch
    scene[3 * h // 4:, :] *= 0.55  # dark band
    scene[:, 5 * w // 8:] += 0.10 * ((yy[:, 5 * w // 8:] % 2) == 0)  # row stripes
    # RGGB mosaic gains + Poisson-Gaussian noise (normalized-unit ballpark).
    gains = np.array([[1.00, 0.85], [0.85, 0.70]], dtype=np.float64)
    mosaic = scene * gains[np.ix_(np.arange(h) % 2, np.arange(w) % 2)]
    alpha, sigma = 2.5e-4, 2.5e-6
    noisy = mosaic + np.sqrt(alpha * mosaic + sigma) * rng.standard_normal((h, w))
    np.clip(noisy, 0.0, 1.0).astype(np.float32).tofile(path)
    with open(sidecar, "w") as f:
        f.write(f"{w} {h} {strength} {luma} {chroma} 0 0\n")
    print(f"gen: {w}x{h} seed={seed} -> {path} (+ {sidecar})")


def run_ref(exe, inp, out, w, h, strength=1.0, luma=1.0, chroma=1.0):
    cmd = [exe, inp, out, str(w), str(h), "galosh",
           str(strength), str(luma), str(chroma), "0", "0"]
    print("ref:", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    sys.stdout.write(r.stdout[-2000:] if len(r.stdout) > 2000 else r.stdout)
    sys.stderr.write(r.stderr[-2000:] if len(r.stderr) > 2000 else r.stderr)
    if r.returncode != 0:
        raise SystemExit(f"CPU reference failed: {r.returncode}")
    print(f"ref: wrote {out}")


def psnr_gate(ref, got, gate_db):
    a = np.fromfile(ref, dtype=np.float32)
    b = np.fromfile(got, dtype=np.float32)
    assert a.shape == b.shape, f"shape mismatch {a.shape} vs {b.shape}"
    assert np.all(np.isfinite(b)), "device output has non-finite values"
    diff = a.astype(np.float64) - b.astype(np.float64)
    mse = float((diff ** 2).mean())
    peak = 1.0  # [0,1] normalized domain, same scale as upstream's dB numbers
    psnr = 10.0 * np.log10(peak * peak / mse) if mse > 0 else float("inf")
    print(f"psnr: {psnr:.2f} dB  mse={mse:.4e}  "
          f"maxabs={float(np.abs(diff).max()):.4e}  meanabs={float(np.abs(diff).mean()):.4e}")
    if psnr < gate_db:
        raise SystemExit(f"FAIL: {psnr:.2f} dB < gate {gate_db:.1f} dB")
    print(f"PASS: {psnr:.2f} dB >= gate {gate_db:.1f} dB")


F32_PHASES = ["p2_in_gat", "p4_C1_h", "p4_C2_h", "p4_C3_h", "p7_C1_loess_h",
                "p7_C1_q_up", "p7_C1_e_up", "p10_output"]
# f16-oracle refs: CPU --f16-storage dumps. p5_pilot is an f32 buffer holding
# f16-rounded values, so it compares against the staged oracle pilot.
F16_PHASES = ["p3_L_cs", "p5_L_cs_den", "p5_pilot", "p6_L_pixel", "p6_L_h_den",
              "p8_C1_h_den", "p8_C2_h_den", "p8_C3_h_den"]


def phases(cpu_dir, cpu_f16_dir, vk_dir):
    print(f"{'phase':16s} {'psnr_db':>8s} {'mse':>12s} {'maxabs':>12s}  ref")
    for name in F32_PHASES + F16_PHASES:
        cpu = f"{cpu_f16_dir}/{name}.bin" if name in F16_PHASES else f"{cpu_dir}/{name}.bin"
        got = f"{vk_dir}/galosh_dump_{name}.bin"
        a = np.fromfile(cpu, dtype=np.float32).astype(np.float64)
        b = np.fromfile(got, dtype=np.float32).astype(np.float64)
        assert a.shape == b.shape, f"{name}: {a.shape} vs {b.shape}"
        diff = a - b
        mse = float((diff ** 2).mean())
        psnr = 10.0 * np.log10(1.0 / mse) if mse > 0 else float("inf")
        tag = "f16-oracle" if name in F16_PHASES else "f32"
        print(f"{name:16s} {psnr:8.2f} {mse:12.4e} {float(np.abs(diff).max()):12.4e}  {tag}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("gen", help="write deterministic mosaic input + sidecar")
    g.add_argument("--out", required=True)
    g.add_argument("--sidecar", required=True)
    g.add_argument("--w", type=int, default=512)
    g.add_argument("--h", type=int, default=384)
    g.add_argument("--seed", type=int, default=SEED_DEFAULT)
    r = sub.add_parser("ref", help="run upstream CPU reference")
    r.add_argument("--exe", required=True)
    r.add_argument("--in", dest="inp", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--w", type=int, default=512)
    r.add_argument("--h", type=int, default=384)
    p = sub.add_parser("psnr", help="PSNR device output vs reference + gate")
    p.add_argument("--ref", required=True)
    p.add_argument("--got", required=True)
    p.add_argument("--gate", type=float, default=GATE_DEFAULT_DB)
    h = sub.add_parser("phases", help="per-phase PSNR of device dumps vs CPU dumps")
    h.add_argument("--cpu-dir", required=True)
    h.add_argument("--cpu-f16-dir", required=True)
    h.add_argument("--vk-dir", required=True)
    a = ap.parse_args()
    if a.cmd == "gen":
        gen_input(a.out, a.sidecar, a.w, a.h, a.seed)
    elif a.cmd == "ref":
        run_ref(a.exe, a.inp, a.out, a.w, a.h)
    elif a.cmd == "psnr":
        psnr_gate(a.ref, a.got, a.gate)
    elif a.cmd == "phases":
        phases(a.cpu_dir, a.cpu_f16_dir, a.vk_dir)


if __name__ == "__main__":
    main()
