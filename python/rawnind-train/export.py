# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Export RawNIND-tiny to ONNX and prepare the NCNN conversion (Mac).

Steps:
  1. Load checkpoints/best.pt (or --checkpoint) into RawNindTiny.
  2. torch.onnx.export with DYNAMIC height/width (batch fixed at 1 -- the
     Android tiled inference always runs single tiles).
  3. Sanity: reload with onnxruntime (if installed), compare vs torch on a
     random 128x128 packed patch; fail loudly on mismatch/NaN.
  4. Print the pnnx -> NCNN conversion + Android integration checklist
     (exact RF radius, input spec, tiling border, asset paths).

Usage:
  python3 export.py --checkpoint checkpoints/best.pt --out ../assets
  # then, with pnnx installed (https://github.com/pnnx/pnnx):
  pnnx model.onnx inputshape=[1,5,528,528]   # 528 = 1024/2+2*16 probe
  # copy model_ncnn.param/.bin -> app/src/main/assets/models/rawnind_tiny.*

Android contract (must match!):
  * Input: packed [R,G1,G2,B] float32 + sigma plane, i.e. (1,5,H/2,W/2),
    values in normalized [0,1]-ish domain AFTER black/white normalization
    and lens-shading correction, BEFORE AMaZE demosaic.
  * Output: (1,4,H/2,W/2) denoised packed Bayer, same layout.
  * Tiling: TILE core 1024 Bayer (512 packed) + BORDER >= RF radius packed
    (printed below; KernelNet default 32 covers it -- reuse that path in
    app/src/main/cpp/ncnnMl.cpp as RawNindCtx).
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import TrainConfig

try:
    import torch
    from model import RawNindTiny, count_parameters, receptive_field_radius_packed
except ImportError as e:  # pragma: no cover
    raise SystemExit("export.py needs torch: pip install -r requirements.txt (%s)" % e)


def export_onnx(checkpoint, out_path, opset, with_sigma=True, base=32):
    """Load checkpoint, export dynamic-H/W ONNX. Returns (model, rf_radius)."""
    model = RawNindTiny(base=base, with_sigma=with_sigma)
    sd = torch.load(checkpoint, map_location="cpu")
    # Tolerate checkpoints saved as raw state_dict (train.py format).
    model.load_state_dict(sd, strict=True)
    model.eval()
    c = 5 if with_sigma else 4
    dummy = torch.rand(1, c, 128, 128)
    dynamic = {"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}}
    torch.onnx.export(
        model, dummy, out_path, input_names=["input"], output_names=["output"],
        dynamic_axes=dynamic, opset_version=opset, do_constant_folding=True)
    return model, receptive_field_radius_packed()


def verify_onnx(torch_model, onnx_path, with_sigma=True):
    """Compare torch vs onnxruntime on a fixed-seed patch. Returns max abs err."""
    try:
        import onnxruntime as ort
    except ImportError:
        print("onnxruntime not installed; skipping numeric verify "
              "(pip install onnxruntime to enable)")
        return None
    rng = np.random.default_rng(0)
    c = 5 if with_sigma else 4
    x = rng.random((1, c, 128, 128)).astype(np.float32)
    with torch.no_grad():
        ref = torch_model(torch.from_numpy(x)).numpy()
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    got = sess.run(None, {"input": x})[0]
    assert got.shape == ref.shape, (got.shape, ref.shape)
    assert np.isfinite(got).all(), "onnx output has NaN/Inf"
    err = float(np.abs(got - ref).max())
    print("onnx verify: max_abs_err=%.3e %s" % (err, "OK" if err < 1e-4 else "FAIL"))
    if err >= 1e-4:
        raise SystemExit("ONNX export diverged from torch (err=%.3e)" % err)
    return err


def main():
    ap = argparse.ArgumentParser(description="Export RawNIND-tiny to ONNX/NCNN")
    ap.add_argument("--checkpoint", default="checkpoints/best.pt")
    ap.add_argument("--out", default="model.onnx")
    ap.add_argument("--opset", type=int, default=TrainConfig.onnx_opset)
    ap.add_argument("--base", type=int, default=TrainConfig.base_channels)
    ap.add_argument("--no-sigma", action="store_true",
                    help="export 4ch model without sigma conditioning")
    args = ap.parse_args()

    with_sigma = not args.no_sigma
    model, rf = export_onnx(args.checkpoint, args.out, args.opset,
                            with_sigma=with_sigma, base=args.base)
    print("exported %s params=%d rf_radius_packed=%d (bayer px: %d)" % (
        args.out, count_parameters(model), rf, rf * 2))
    verify_onnx(model, args.out, with_sigma=with_sigma)
    c = 5 if with_sigma else 4
    print("\n--- NCNN conversion ---")
    print("pnnx %s inputshape=[1,%d,528,528]" % (args.out, c))
    print("cp %s_ncnn.param app/src/main/assets/models/rawnind_tiny.ncnn.param"
          % os.path.splitext(args.out)[0])
    print("cp %s_ncnn.bin   app/src/main/assets/models/rawnind_tiny.ncnn.bin"
          % os.path.splitext(args.out)[0])
    print("\n--- Android checklist ---")
    print("1. Add RawNindCtx in app/src/main/cpp/ncnnMl.cpp (copy KernelNetCtx")
    print("   tiling; TILE core 1024 Bayer / border >= %d packed px)." % (rf + 2))
    print("2. Feed packed [R,G1,G2,B]+sigma AFTER lens-shading/defect, BEFORE")
    print("   amaze.process(); non-RGGB sensors must packed_to_rggb() first.")
    print("3. Extend DenoiseSettings + RawDevelopmentCoordinator + Settings UI;")
    print("   keep wavelet fallback; update estimateMemory().")


if __name__ == "__main__":
    main()
