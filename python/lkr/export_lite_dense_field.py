# SPDX-License-Identifier: GPL-3.0-or-later
"""Export single-tile dense motion for the LKRL_DENSE_FIELD Vulkan experiment."""
import argparse
import json
from pathlib import Path
from time import perf_counter

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from benchmark_occlusion import dense_flow, sample
from benchmark_geometry import native_grids


def export(prefix, output, mode, visibility=False):
    meta = json.loads(Path(f"{prefix}-meta.json").read_text())
    k, h, w = (meta[name] for name in ("K", "h", "w"))
    frames = torch.from_numpy(np.fromfile(f"{prefix}-raw.f32", dtype="<f4").reshape(k, h, w))
    if h % 2 or w % 2:
        raise ValueError("expected an even, RGGB-normalized tile")
    guide = F.interpolate(F.avg_pool2d(frames[:, None], 2), (h, w), mode="bilinear", align_corners=False)
    start = perf_counter()
    forward, reverse, coords = dense_flow(guide)
    elapsed = (perf_counter() - start) * 1000
    if mode != "dense":
        affine = torch.from_numpy(np.fromfile(f"{prefix}-affine.f32", dtype="<f4").reshape(k, 2, 3))
        field = torch.from_numpy(np.fromfile(f"{prefix}-local.f32", dtype="<f4").reshape(
            k, (h + 15) // 16, (w + 15) // 16, 2
        ))
        _, _, native_grid, native_valid = native_grids(affine, field, h, w)
        extent = coords.new_tensor([w, h])
        native = (native_grid + 1) * extent / 2 - 0.5
        if mode == "native":
            reverse = native
        else:
            ref = guide[:1].expand_as(guide)

            def cost(warp):
                residual = (sample(ref, warp) - guide).abs().clamp_max(0.1)
                return F.avg_pool2d(residual, 7, 1, 3, count_include_pad=False)[:, 0]

            # Both candidates must match on the same patch footprint.
            inside = ((reverse >= 0) & (reverse < extent - 1)).all(-1)
            common = ((native_valid > 0) & inside)[:, None].float()
            support = F.avg_pool2d(common, 7, 1, 3, count_include_pad=False)[:, 0] == 1
            selected = support & (cost(reverse) + 0.003 < 0.75 * cost(native))
            selected[0] = False
            reverse = torch.where(selected[..., None], reverse, native)
            print(f"dense selected for {100 * selected[1:].float().mean():.2f}% of moving-frame samples")
    with output.open("wb") as f:
        np.array([k, h, w], dtype="<i4").tofile(f)
        (reverse - coords).numpy().astype("<f4").tofile(f)
        if visibility:
            closure = (sample(forward.movedim(-1, 1), reverse).movedim(1, -1) - coords).norm(dim=-1)
            flow = reverse - coords
            strain = torch.zeros_like(closure)
            dx = (flow[:, :, 1:] - flow[:, :, :-1]).norm(dim=-1)
            dy = (flow[:, 1:] - flow[:, :-1]).norm(dim=-1)
            strain[:, :, 1:] = dx
            strain[:, 1:] = torch.maximum(strain[:, 1:], dy)
            suspect = (closure > 0.75) | (strain > 0.38)
            # Reject whole Bayer cells so inconsistent support cannot
            # alternate by CFA phase immediately along the silhouette.
            suspect = F.max_pool2d(suspect[:, None].float(), 3, 1, 1)
            reject = F.max_pool2d(suspect, 2).repeat_interleave(2, -2).repeat_interleave(2, -1)[:, 0] > 0
            reject[0] = False
            (~reject).numpy().astype("<f4").tofile(f)
            print(f"visibility rejected {100 * reject[1:].float().mean():.2f}% of moving-frame samples")
    print(f"{k} frames, {w}×{h}, bidirectional CPU flow {elapsed:.1f} ms")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace_prefix", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--mode", choices=("dense", "native", "hybrid"), default="dense")
    parser.add_argument("--visibility", action="store_true", help="Reject inconsistent dense correspondences by Bayer cell")
    args = parser.parse_args()
    if args.visibility and args.mode != "dense":
        parser.error("--visibility requires --mode dense")
    cv2.setNumThreads(1)
    torch.set_num_threads(1)
    export(args.trace_prefix, args.output, args.mode, args.visibility)
