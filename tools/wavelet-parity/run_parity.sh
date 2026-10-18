#!/bin/sh
# Host parity gate for the vendored raw_denoise core (macOS + MoltenVK).
# Builds denoise_bench against the vendored sources + our compiled SPIR-V,
# runs it on the frozen fixture, and checks the upstream tolerances.
# Usage: tools/wavelet-parity/run_parity.sh [fixture-filter...]
set -e
cd "$(dirname "$0")"
ROOT="$PWD"
UPSTREAM=../../app/src/main/cpp/wavelet/upstream
SPV=../../app/src/main/assets/spirv/wavelet
OUT=/tmp/wavelet-parity

# Minimal path adaptation of the upstream verify script (upstream copy kept intact
# next to it for diffing): point BIN/SHADERS/DATA/OUT at this layout.
BIN="$ROOT/host/build/denoise_bench"
SHADERS="$ROOT/$SPV"
DATA="$ROOT/data"

mkdir -p host/build "$OUT"
c++ -std=c++20 -O2 -o "$BIN" \
  host/denoise_bench.cpp "$UPSTREAM/src/DenoisePipeline.cpp" \
  -Ihost -I"$UPSTREAM/include" \
  -I/opt/homebrew/include -L/opt/homebrew/lib -lMoltenVK \
  -framework Metal -framework Foundation -framework QuartzCore -framework IOSurface

# Compare with the upstream gate (PASS_MAX/P99/MEAN/RATIO + seam check).
BIN="$BIN" SHADERS="$SHADERS" DATA="$DATA" OUT="$OUT" python3 - "$@" <<'EOF'
import json, os, subprocess, sys
import numpy as np
BIN, SHADERS, DATA, OUT = (os.environ[k] for k in ("BIN", "SHADERS", "DATA", "OUT"))
PASS_MAX, PASS_P99, PASS_MEAN, PASS_RATIO = 5e-3, 5e-4, 1e-4, 0.25
SEAM_TILE, SEAM_BAND, SEAM_RATIO = 256, 6, 1.5
manifest = json.load(open(os.path.join(DATA, "manifest.json")))
filt = sys.argv[1:]
report = {"fixtures": [], "pass": True}
def build_args(fx, out_path, tile=1536):
    w, h = fx["crop"]
    return [BIN, "--spv-dir", SHADERS, "--tile", str(tile),
            "--input-f32", os.path.join(DATA, fx["input"]),
            "--width", str(w), "--height", str(h),
            "--scales", str(fx["scales"]),
            "--wb", *[repr(float(v)) for v in fx["wb"]],
            "--a", repr(float(fx["a_norm"])), "--b", repr(float(fx["b_norm"])),
            "--strength", repr(float(fx["strength"])), "--shadows", repr(float(fx["shadows"])),
            "--dump-out", out_path]
for fx in manifest["fixtures"]:
    fid = fx["stem"] + "/" + fx["tuning"]
    if filt and not any(x in fx["stem"] + fx["tuning"] for x in filt):
        continue
    w, h = fx["crop"]
    out_path = os.path.join(OUT, fx["stem"] + "_" + fx["tuning"] + "_gpu.f32")
    r = subprocess.run(build_args(fx, out_path), capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout.strip()); print(r.stderr[-2000:])
        report["fixtures"].append({"id": fid, "ok": False, "error": "runner failed"})
        report["pass"] = False
        continue
    print([l for l in r.stdout.splitlines() if "BENCH_RESULT" in l])
    gpu = np.fromfile(out_path, dtype=np.float32).reshape(h, w, 4)[:, :, :3]
    ref = np.fromfile(os.path.join(DATA, fx["output"]), dtype=np.float32).reshape(h, w, 3)
    inp = np.fromfile(os.path.join(DATA, fx["input"]), dtype=np.float32).reshape(h, w, 4)[:, :, :3]
    d = np.abs(gpu - ref)
    effect = float(np.abs(ref - inp).mean())
    stats = {"id": fid, "max": float(d.max()), "p99": float(np.percentile(d, 99)),
             "mean": float(d.mean()), "effect": effect}
    stats["ratio"] = stats["mean"] / effect if effect > 0 else float("inf")
    ok = (stats["max"] <= PASS_MAX and stats["p99"] <= PASS_P99 and
          stats["mean"] <= PASS_MEAN and stats["ratio"] <= PASS_RATIO)
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
sys.exit(0 if report["pass"] else 1)
EOF
