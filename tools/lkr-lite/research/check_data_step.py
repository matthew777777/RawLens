#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check a Lite --audit-prefix first data step against a float64 adjoint.

The recorded confidence is held fixed so this checks the image formation
operator and its transpose. Both FP32 input and the actual FP16-staged
samples are evaluated to isolate the contribution of RAW storage precision.
"""
import argparse
import json
from pathlib import Path
import struct

import numpy as np


def warp_taps(affine, blocks, scale, height, width, stride):
    """Bayer row indices, four bilinear neighbours and averaging weights."""
    y, x = np.indices((height, width), dtype=np.float64)
    channels = (y.astype(np.intp) & 1) + (x.astype(np.intp) & 1)
    height_hr, width_hr = scale * height, scale * width
    valid = np.ones((height, width), dtype=bool)
    taps = []
    for sy in range(scale):
        for sx in range(scale):
            u = x + (sx + 0.5) / scale
            v = y + (sy + 0.5) / scale
            gx, gy = u / stride - 0.5, v / stride - 0.5
            bx, by = np.floor(gx).astype(np.intp), np.floor(gy).astype(np.intp)
            fx, fy = (gx - bx)[..., None], (gy - by)[..., None]
            rows, cols, _ = blocks.shape
            x0, x1 = np.clip(bx, 0, cols - 1), np.clip(bx + 1, 0, cols - 1)
            y0, y1 = np.clip(by, 0, rows - 1), np.clip(by + 1, 0, rows - 1)
            top = (1 - fx) * blocks[y0, x0] + fx * blocks[y0, x1]
            bottom = (1 - fx) * blocks[y1, x0] + fx * blocks[y1, x1]
            offset = (1 - fy) * top + fy * bottom
            hx = scale * (affine[0] * u + affine[1] * v + affine[2] + offset[..., 0]) - 0.5
            hy = scale * (affine[3] * u + affine[4] * v + affine[5] + offset[..., 1]) - 0.5
            ix, iy = np.floor(hx).astype(np.intp), np.floor(hy).astype(np.intp)
            dx, dy = hx - ix, hy - iy
            valid &= (ix >= 0) & (ix + 1 < width_hr) & (iy >= 0) & (iy + 1 < height_hr)
            for j, wy in enumerate((1 - dy, dy)):
                for i, wx in enumerate((1 - dx, dx)):
                    index = channels * height_hr * width_hr + (iy + j) * width_hr + ix + i
                    taps.append((index, wy * wx / (scale * scale)))
    return [(index[valid], weight[valid]) for index, weight in taps], valid


def check(prefix):
    def path(suffix):
        return Path(f"{prefix}-{suffix}")

    def data(suffix, dtype="<f4"):
        return np.fromfile(path(suffix), dtype=dtype)

    meta = json.loads(path("meta.json").read_text())
    h, w, s, k = (meta[key] for key in ("h", "w", "s", "K"))
    raw = data("raw.f32").reshape(k, h, w)
    weights = data("weights.f32").reshape(k, h, w)
    affine = data("affine.f32").reshape(k, 6)
    seed = data("pre-J1.f32").astype(np.float64)
    push = struct.unpack("<8i8f", path("adjoint-push.bin").read_bytes())
    stride = push[6]
    grad_scale, cov_scale = push[-2:]
    blocks = data("local.f32").reshape(k, (h + stride - 1) // stride, (w + stride - 1) // stride, 2)
    observed = data("gradient-J1.i32", "<i4") / grad_scale
    coverage = data("coverage-J1.i32", "<i4") / cov_scale
    grad_fp32 = np.zeros_like(seed)
    grad_staged = np.zeros_like(seed)
    coverage_ref = np.zeros_like(seed)
    for frame in range(k):
        taps, valid = warp_taps(affine[frame], blocks[frame], s, h, w, stride)
        weight = weights[frame][valid]
        samples = raw[frame][valid]
        staged = samples.astype(np.float16).astype(np.float64)
        prediction = sum(seed[index] * coeff for index, coeff in taps)
        for index, coeff in taps:
            np.add.at(grad_fp32, index, coeff * weight * (prediction - samples))
            np.add.at(grad_staged, index, coeff * weight * (prediction - staged))
            np.add.at(coverage_ref, index, coeff * weight)

    def errors(a, b):
        delta = np.abs(a - b)
        return {"rms": float(np.sqrt(np.mean(delta**2))), "max": float(np.max(delta))}

    return {
        "shape": [k, h, w], "scale": s,
        "gradient_vs_fp16_staged": errors(observed, grad_staged),
        "gradient_vs_fp32_input": errors(observed, grad_fp32),
        "fp16_staging_only": errors(grad_fp32, grad_staged),
        "coverage": errors(coverage, coverage_ref),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix", help="Lite solver tile trace prefix, without the -meta.json suffix")
    args = parser.parse_args()
    print(json.dumps(check(args.prefix), indent=2))
