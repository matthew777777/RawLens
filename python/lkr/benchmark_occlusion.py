# SPDX-License-Identifier: GPL-3.0-or-later
"""Measure dense bidirectional flow and occlusion rejection on labelled RAW bursts."""
import argparse
import json
from pathlib import Path
from time import perf_counter

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from benchmark_geometry import burst, native_grids, native_motion, photometric_confidence, reconstruct
from dense_alignment import align
from dense_warp import forward_grid, reverse_grid
from lkr_torch import Ops


def sample(field, locations):
    """Bilinearly sample a field in pixel-centred coordinates."""
    h, w = field.shape[-2:]
    norm = 2 * (locations + 0.5) / locations.new_tensor([w, h]) - 1
    return F.grid_sample(field, norm, align_corners=False)


def dense_flow(guide):
    """Estimate each direction independently. Keep the reference fixed."""
    _, _, h, w = guide.shape
    y, x = torch.meshgrid(torch.arange(h), torch.arange(w), indexing="ij")
    coords = torch.stack((x, y), dim=-1).float()
    # All frames share the same transform after RAW exposure normalization.
    scale = max(0.01, float(torch.quantile(guide[0], 0.99)))
    gray = (guide[:, 0].clamp(0, scale) * (255 / scale)).round().byte().numpy()
    estimator = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_FAST)
    estimator.setUseSpatialPropagation(True)
    forward = [coords]
    reverse = [coords]
    for current in gray[1:]:
        forward.append(coords + torch.from_numpy(estimator.calc(gray[0], current, None)))
        reverse.append(coords + torch.from_numpy(estimator.calc(current, gray[0], None)))
    return torch.stack(forward), torch.stack(reverse), coords


def layered_flow(guide, coords, estimates, reverse=False):
    """Fit two affine motion hypotheses and choose by local guide error."""
    points = coords.numpy()
    fields = [coords]
    for frame in range(1, len(guide)):
        source, target = (guide[frame], guide[0]) if reverse else (guide[0], guide[frame])
        dense = estimates[frame]
        # A checker grid avoids counting every interpolated guide pixel as
        # an independent observation. Refit the second model on outliers
        # of the dominant one, so a foreground can keep a separate warp.
        src = points[::2, ::2].reshape(-1, 2)
        dst = dense[::2, ::2].numpy().reshape(-1, 2)
        candidates = [dense]
        for _ in range(2):
            if len(src) < 16:
                break
            fit, consensus = cv2.estimateAffine2D(src, dst, ransacReprojThreshold=0.6, maxIters=100)
            if fit is None or int(consensus.sum()) < 16:
                break
            affine = torch.from_numpy(fit).float()
            candidates.append(coords @ affine[:, :2].T + affine[:, 2])
            remaining = ~consensus[:, 0].astype(bool)
            src, dst = src[remaining], dst[remaining]
        options = torch.stack(candidates)
        visible = ((options >= 0) & (options < coords.new_tensor([guide.shape[-1], guide.shape[-2]]) - 1)).all(-1)
        predicted = sample(target[None].expand(len(options), -1, -1, -1), options)[:, 0]
        residual = (predicted - source[0]).abs().clamp_max(0.1)
        cost = 0.7 * residual + 0.3 * F.avg_pool2d(
            residual[:, None], 3, 1, 1, count_include_pad=False
        )[:, 0]
        # Prefer the dense match unless a separate affine is clearly better.
        cost[1:] += 0.005
        best = cost.masked_fill(~visible, torch.inf).argmin(0)
        fields.append(options[best, coords[..., 1].long(), coords[..., 0].long()])
    return torch.stack(fields)


def metrics(
    raw, exposure, truth, grid, mask, confidence,
    direct, reverse, forward_truth, reverse_truth, visible, reverse_visible, boundary
):
    estimate = reconstruct(raw, exposure, grid, mask, confidence)
    rgb_error = (estimate - truth).square().mean(0)
    shifted = (direct - forward_truth).norm(dim=-1) if direct is not None else None
    back_error = (reverse - reverse_truth).norm(dim=-1)
    keep = (mask > 0) & (confidence >= 0.5)
    interior = torch.zeros_like(reverse_visible)
    interior[1:, 5:-5, 5:-5] = True
    hidden = interior & ~reverse_visible
    observable = interior & reverse_visible
    accepted = observable & keep
    return {
        "image_psnr": float(-10 * rgb_error[4:-4, 4:-4].mean().log10()),
        "boundary_rmse": float(rgb_error[boundary].mean().sqrt()) if boundary.any() else None,
        "boundary_flow_epe": float(shifted[visible & boundary].mean()) if shifted is not None and boundary.any() else None,
        "visible_reverse_epe": float(back_error[observable].mean()),
        "visible_keep": float(keep[observable].float().mean()),
        "occluded_keep": float(keep[hidden].float().mean()) if hidden.any() else None,
        "accepted_visible_epe": float(back_error[accepted].mean()) if accepted.any() else None,
    }


def geometry_hybrid(guide, intrinsics, coarse, native_grid, native_valid):
    """Prefer native motion unless valid geometry improves the same guide patch."""
    h, w = guide.shape[-2:]
    initial_depth = guide.new_full((8, 10), 3.0)
    center = intrinsics[:2, 2] + 0.5
    transform = coarse[1:]
    source = torch.linalg.solve(transform[..., :2], center - transform[..., 2])
    poses = torch.eye(4).repeat(len(transform), 1, 1)
    poses[:, :2, 3] = (source - center) * 3 / intrinsics.diag()[:2]
    rotation, translation, planes = align(
        guide, intrinsics, initial_depth, alternations=1, plane_steps=1,
        initial_poses=poses
    )
    geometric_grid, geometric_valid = reverse_grid(planes, rotation, translation, intrinsics)
    photo = guide[:1].expand_as(guide)

    def cost(grid):
        error = (F.grid_sample(photo, grid, align_corners=False) - guide).abs()
        return F.avg_pool2d(error.clamp_max(0.1), 5, 1, 2, count_include_pad=False)[:, 0]

    old_cost, new_cost = cost(native_grid), cost(geometric_grid)
    # Compare full patches with common support. A missing or invalid reverse
    # projection cannot vote for geometry, including near tile borders.
    both = (native_valid > 0) & (geometric_valid > 0)
    supported = F.avg_pool2d(both[:, None].float(), 5, 1, 2)[:, 0] == 1
    use_geometry = supported & (new_cost + 0.005 < 0.8 * old_cost)
    use_geometry[0] = False
    grid = torch.where(use_geometry[..., None], geometric_grid, native_grid)
    direct, _ = forward_grid(planes, rotation, translation, intrinsics)
    return grid, geometric_grid, geometric_valid, direct, use_geometry, old_cost


def run(category, seed, runner, test_geometry=False):
    guide, raw, exposure, truth, intrinsics, _, foreground, forward_truth, visible, reverse_truth, reverse_visible = burst(category, seed)
    _, h, w = raw.shape
    boundary = (
        F.max_pool2d(foreground[None, None].float(), 5, 1, 2) +
        F.max_pool2d(-foreground[None, None].float(), 5, 1, 2)
    )[0, 0] > 0
    visible[0] = False
    extent = raw.new_tensor([w, h])
    started = perf_counter()
    direct, reverse, coords = dense_flow(guide)
    flow_ms = 1000 * (perf_counter() - started)
    grid = 2 * (reverse + 0.5) / extent - 1
    inside = ((reverse >= 0) & (reverse < extent - 1)).all(-1)
    round_trip = sample(direct.movedim(-1, 1), reverse).movedim(1, -1)
    cycle_error = (round_trip - coords).norm(dim=-1)
    confidence = photometric_confidence(guide, raw, exposure, grid, category)
    # Keep soft photometric weights in regions where independent motion agrees.
    # A hard forward/reverse check turns apparent correspondence into rejection.
    output = {"dense_flow_ms": flow_ms}
    for name, valid in (("dense", inside), ("dense_cycle", inside & (cycle_error <= 0.75))):
        output[name] = metrics(
            raw, exposure, truth, grid, valid.float(), confidence,
            direct, reverse, forward_truth, reverse_truth, visible, reverse_visible, boundary
        )
    started = perf_counter()
    forward_layered = layered_flow(guide, coords, direct)
    reverse_layered = layered_flow(guide, coords, reverse, reverse=True)
    output["layer_selection_ms"] = 1000 * (perf_counter() - started)
    layered_grid = 2 * (reverse_layered + 0.5) / extent - 1
    layered_inside = ((reverse_layered >= 0) & (reverse_layered < extent - 1)).all(-1)
    layered_round_trip = sample(forward_layered.movedim(-1, 1), reverse_layered).movedim(1, -1)
    layered_consistent = (layered_round_trip - coords).norm(dim=-1) <= 0.75
    layered_confidence = photometric_confidence(guide, raw, exposure, layered_grid, category)
    output["layered_cycle"] = metrics(
        raw, exposure, truth, layered_grid, (layered_inside & layered_consistent).float(),
        layered_confidence, forward_layered, reverse_layered, forward_truth, reverse_truth,
        visible, reverse_visible, boundary
    )
    if runner and not category.startswith("hdr"):
        coarse, affine, field, elapsed, rgb = native_motion(runner, raw)
        xy, _, inv_grid, valid = native_grids(affine, field, h, w)
        reverse_native = (inv_grid + 1) * extent / 2 - 0.5
        conf = photometric_confidence(guide, raw, exposure, inv_grid, category)
        for name, validity in (("native", valid), ("native_cycle", valid * (cycle_error <= 0.75))):
            output[name] = metrics(
                raw, exposure, truth, inv_grid, validity, conf,
                xy, reverse_native, forward_truth, reverse_truth, visible, reverse_visible, boundary
            )
        output["native"]["solver_psnr"] = float(
            -10 * (rgb[:, 4:-4, 4:-4] - truth[:, 4:-4, 4:-4]).square().mean().log10()
        )
        output["native_replay_ms"] = elapsed * 1000
        if test_geometry:
            started = perf_counter()
            hybrid, geometry, geometry_valid, direct_geo, selected, native_cost = geometry_hybrid(
                guide, intrinsics, coarse, inv_grid, valid
            )
            output["geometry_ms"] = 1000 * (perf_counter() - started)
            output["geometry_selected"] = float(selected[1:].float().mean())
            # Invalid geometry includes boundary exits and incomplete inverse
            # convergence. Use it as a veto only with native photo disagreement.
            veto = (geometry_valid <= 0) & (native_cost > 0.03)
            veto[0] = False
            for name, warp, mask, forward in (
                ("geometry", geometry, geometry_valid, direct_geo),
                ("geometry_hybrid", hybrid, valid, None),
                ("geometry_veto", inv_grid, valid * ~veto, xy),
            ):
                conf = photometric_confidence(guide, raw, exposure, warp, category)
                backward = (warp + 1) * extent / 2 - 0.5
                output[name] = metrics(
                    raw, exposure, truth, warp, mask, conf, forward, backward,
                    forward_truth, reverse_truth, visible, reverse_visible, boundary
                )
    return output


def run_real(prefix, runner):
    """Score each fit against the same excluded exposure and correspondence."""
    meta = dict(pair.split("=") for pair in Path(f"{prefix}_meta.txt").read_text().split())
    k, h, w = (int(meta[key]) for key in ("K", "h", "w"))
    raw = torch.from_numpy(np.fromfile(f"{prefix}_frames.f32", dtype="<f4").reshape(k, h, w))
    guide = F.interpolate(F.avg_pool2d(raw[:, None], 2), (h, w), mode="bilinear", align_corners=False)
    start = perf_counter()
    direct, reverse, coords = dense_flow(guide)
    flow_ms = 1000 * (perf_counter() - start)
    extent = raw.new_tensor([w, h])
    grid = 2 * (reverse + 0.5) / extent - 1
    inside = ((reverse >= 0) & (reverse < extent - 1)).all(-1)
    closure = (sample(direct.movedim(-1, 1), reverse).movedim(1, -1) - coords).norm(dim=-1)
    consistent = closure <= 0.75
    exposure = torch.ones(len(raw))
    conf = photometric_confidence(guide, raw, exposure, grid, "real")
    start = perf_counter()
    forward_layered = layered_flow(guide[:-1], coords, direct[:-1])
    reverse_layered = layered_flow(guide[:-1], coords, reverse[:-1], reverse=True)
    layered_ms = 1000 * (perf_counter() - start)
    layered_grid = 2 * (reverse_layered + 0.5) / extent - 1
    layered_inside = ((reverse_layered >= 0) & (reverse_layered < extent - 1)).all(-1)
    layered_closure = sample(forward_layered.movedim(-1, 1), reverse_layered).movedim(1, -1)
    layered_valid = layered_inside & ((layered_closure - coords).norm(dim=-1) <= 0.75)
    layered_conf = photometric_confidence(guide[:-1], raw[:-1], exposure[:-1], layered_grid, "real")
    _, affine, field, elapsed, native_rgb = native_motion(runner, raw[:-1])
    _, _, native_grid, valid = native_grids(affine, field, h, w)
    native_conf = photometric_confidence(guide[:-1], raw[:-1], exposure[:-1], native_grid, "real")
    ops = Ops(h, w, 1, "cpu")
    ops.grid = grid[-1:]
    ops.valid = inside[-1:].float()
    trusted = inside[-1] & consistent[-1] & (conf[-1] >= 0.5) & (raw[-1] < 0.98)
    trusted[:16] = trusted[-16:] = False
    trusted[:, :16] = trusted[:, -16:] = False
    sigma = (float(meta["a"]) * raw[-1].clamp_min(0) + float(meta["b"])).clamp_min(1e-12).sqrt()
    output = {"dense_flow_ms": flow_ms, "layer_selection_ms": layered_ms, "native_replay_ms": 1000 * elapsed,
              "heldout_pixels": int(trusted.sum())}
    candidates = (
        ("native", native_grid, valid, native_conf),
        ("native_cycle", native_grid, valid * consistent[:-1], native_conf),
        ("dense", grid[:-1], inside[:-1].float(), conf[:-1]),
        ("dense_cycle", grid[:-1], (inside & consistent)[:-1].float(), conf[:-1]),
        ("layered_cycle", layered_grid, layered_valid.float(), layered_conf),
    )
    images = {"native_solver": native_rgb}
    for name, warp, mask, confidence in candidates:
        images[name] = reconstruct(raw[:-1], exposure[:-1], warp, mask, confidence)
    for name, rgb in images.items():
        error = ((ops.fwd(rgb)[0] - raw[-1]).abs() / sigma)[trusted]
        output[name] = {"heldout_median": float(error.median()),
                        "heldout_p90": float(torch.quantile(error, 0.9))}
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-runner", type=Path)
    parser.add_argument("--seeds", nargs="+", type=int, default=[907, 1201, 2027])
    parser.add_argument("--categories", nargs="+", default=["planar", "parallax", "motion", "branches", "weak_texture", "hdr"])
    parser.add_argument("--real-trace", type=Path, nargs="+", help="Equal-exposure PC tile traces")
    parser.add_argument("--geometry-hybrid", action="store_true",
                        help="Test a geometry fallback on calibrated synthetic SDR scenes")
    args = parser.parse_args()
    torch.set_num_threads(1)
    cv2.setNumThreads(1)
    cv2.setRNGSeed(0)
    runner = args.native_runner.resolve() if args.native_runner else None
    if args.geometry_hybrid and (not runner or args.real_trace):
        parser.error("--geometry-hybrid needs a native runner and calibrated synthetic scenes")
    if args.real_trace:
        if not runner:
            parser.error("--real-trace requires --native-runner")
        result = {prefix.name: run_real(prefix, runner) for prefix in args.real_trace}
    else:
        result = {kind: {seed: run(kind, seed, runner, args.geometry_hybrid) for seed in args.seeds}
                  for kind in args.categories}
    print(json.dumps(result, indent=2, allow_nan=False))
