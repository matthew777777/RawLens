# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Weight transfer between MLX and PyTorch RawNIND-tiny (+ parity gate).

The two frameworks mirror the same architecture (model.py / model_mlx.py)
with identical attribute names, so keys map 1:1. The ONLY transformation:

  * conv weights: MLX OHWI (out, kh, kw, in) <-> torch OIHW (out, in, kh, kw)
  * biases: identical, copied verbatim

Anything else (shape mismatch, missing key, channel-count mixup between
with_sigma True/False) fails LOUDLY -- a silent mis-map would train fine
and detonate only on-device. After every transfer run --check (default on):
same fixed input through both models must agree to 1e-5.

Usage:
  # after train_mlx.py -> checkpoints/mlx_best.npz:
  python3 transfer_mlx_to_torch.py --mlx checkpoints/mlx_best.npz --torch checkpoints/best.pt
  python3 export.py --checkpoint checkpoints/best.pt --out model.onnx   # unchanged
  # reverse direction (testing only):
  python3 transfer_mlx_to_torch.py --torch checkpoints/best.pt --mlx roundtrip.npz --to-mlx
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import mlx.core as mx
    from model_mlx import RawNindTiny as MlxTiny
except ImportError as _e:  # pragma: no cover
    raise SystemExit("transfer needs mlx: pip install -r requirements-mlx.txt (%s)" % _e)

try:
    import torch
    from model import RawNindTiny as TorchTiny
except ImportError as _e:  # pragma: no cover
    raise SystemExit("transfer needs torch: pip install -r requirements.txt (%s)" % _e)

# (torch_key, mlx_key, transpose_axes or None). Order matches both modules'
# definition order; every conv weight transposes OHWI<->OIHW.
MAP = []
for block in ["stem", "enc1", "bottleneck"]:
    for conv in ["conv1", "conv2"]:
        MAP.append((f"{block}.{conv}.weight", f"{block}.{conv}.weight", (0, 3, 1, 2)))
        MAP.append((f"{block}.{conv}.bias", f"{block}.{conv}.bias", None))
MAP.append(("down1.conv.weight", "down1.conv.weight", (0, 3, 1, 2)))
MAP.append(("down1.conv.bias", "down1.conv.bias", None))
MAP.append(("down2.conv.weight", "down2.conv.weight", (0, 3, 1, 2)))
MAP.append(("down2.conv.bias", "down2.conv.bias", None))
for up in ["up1", "up2"]:
    for conv in ["conv1", "conv2"]:
        MAP.append((f"{up}.block.{conv}.weight", f"{up}.block.{conv}.weight", (0, 3, 1, 2)))
        MAP.append((f"{up}.block.{conv}.bias", f"{up}.block.{conv}.bias", None))
MAP.append(("head.weight", "head.weight", (0, 3, 1, 2)))
MAP.append(("head.bias", "head.bias", None))

PARITY_TOL = 1e-5


def mlx_to_torch(mlx_path, torch_path):
    """MLX flat npz -> torch .pt state_dict. Returns the state_dict."""
    flat = dict(mx.load(mlx_path))
    sd = {}
    for tkey, mkey, axes in MAP:
        if mkey not in flat:
            raise SystemExit("mlx checkpoint missing key: %s" % mkey)
        arr = np.asarray(flat[mkey])
        if axes is not None:
            arr = np.transpose(arr, axes)
        sd[tkey] = torch.from_numpy(arr.copy())
    # Refuse to silently mix 4ch/5ch stem variants.
    stem_in = sd["stem.conv1.weight"].shape[1]
    if stem_in not in (4, 5):
        raise SystemExit("bad stem in-channels: %d" % stem_in)
    torch.save(sd, torch_path)
    print("wrote %s (%d tensors, stem in=%d)" % (torch_path, len(sd), stem_in))
    return sd


def torch_to_mlx(torch_path, mlx_path):
    """Torch .pt state_dict -> MLX flat npz. Returns the flat dict."""
    sd = torch.load(torch_path, map_location="cpu")
    flat = {}
    for tkey, mkey, axes in MAP:
        if tkey not in sd:
            raise SystemExit("torch checkpoint missing key: %s" % tkey)
        arr = np.asarray(sd[tkey])
        if axes is not None:  # invert the transpose: OIHW -> OHWI
            arr = np.transpose(arr, (0, 2, 3, 1))
        flat[mkey] = mx.array(arr)
    mx.savez(mlx_path, **flat)
    print("wrote %s (%d tensors)" % (mlx_path, len(flat)))
    return flat


def parity_check(sd, with_sigma=True, base=32):
    """Same fixed input through both frameworks; must agree to PARITY_TOL."""
    rng = np.random.default_rng(0)
    c = 5 if with_sigma else 4
    x = rng.random((1, c, 32, 32)).astype(np.float32)
    tm = TorchTiny(base=base, with_sigma=with_sigma)
    tm.load_state_dict(sd, strict=True)
    tm.eval()
    with torch.no_grad():
        ref = tm(torch.from_numpy(x)).numpy()
    flat = {}
    for tkey, mkey, axes in MAP:
        arr = np.asarray(sd[tkey])
        if axes is not None:  # OIHW -> OHWI
            arr = np.transpose(arr, (0, 2, 3, 1))
        flat[mkey] = mx.array(arr)
    mm = MlxTiny(base=base, with_sigma=with_sigma)
    mm.load_weights(list(flat.items()))
    got = mm(mx.array(np.transpose(x, (0, 2, 3, 1))))
    mx.eval(got)
    got = np.transpose(np.asarray(got), (0, 3, 1, 2))
    err = float(np.abs(got - ref).max())
    print("parity: max_abs_err=%.3e %s" % (err, "OK" if err < PARITY_TOL else "FAIL"))
    if err >= PARITY_TOL:
        raise SystemExit("framework parity diverged (err=%.3e)" % err)
    return err


def main():
    ap = argparse.ArgumentParser(description="Transfer RawNIND-tiny weights MLX<->torch")
    ap.add_argument("--mlx", default="checkpoints/mlx_best.npz")
    ap.add_argument("--torch", default="checkpoints/best.pt")
    ap.add_argument("--to-mlx", action="store_true", help="reverse direction (testing)")
    ap.add_argument("--no-check", action="store_true", help="skip the parity gate (not recommended)")
    ap.add_argument("--base", type=int, default=32)
    args = ap.parse_args()

    if args.to_mlx:
        torch_to_mlx(args.torch, args.mlx)
        sd = torch.load(args.torch, map_location="cpu")
    else:
        sd = mlx_to_torch(args.mlx, args.torch)
    if not args.no_check:
        stem_in = sd["stem.conv1.weight"].shape[1]
        parity_check(sd, with_sigma=(stem_in == 5), base=args.base)


if __name__ == "__main__":
    main()
