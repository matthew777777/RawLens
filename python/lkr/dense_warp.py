# SPDX-License-Identifier: GPL-3.0-or-later
"""Reverse sampling grid from a 2023 LK-R plane map and calibrated poses."""
import torch
import torch.nn.functional as F


def forward_grid(planes, rotations, translations, intrinsics):
    """Project each reference pixel into each burst frame, in pixel units.

    Also return an in-bounds, positive-depth mask. Visibility through scene
    surfaces requires a separate check.
    """
    _, height, width = planes.shape
    y, x = torch.meshgrid(torch.arange(height, device=planes.device, dtype=planes.dtype),
                          torch.arange(width, device=planes.device, dtype=planes.dtype),
                          indexing="ij")
    pixels = torch.stack((x, y, torch.ones_like(x)), dim=-1)
    rays = pixels @ torch.linalg.inv(intrinsics).T
    disparity = (rays * planes.movedim(0, -1)).sum(-1)
    points = (torch.einsum("kij,hwj->khwi", rotations, rays)
              + disparity[..., None] * translations[:, None, None])
    projected = points @ intrinsics.T
    eps = torch.finfo(planes.dtype).eps
    xy = projected[..., :2] / projected[..., 2:].clamp_min(eps)
    extent = xy.new_tensor([width, height])
    valid = ((disparity > eps) & (points[..., 2] > eps)
             & ((xy >= -1e-4) & (xy <= extent - 1 + 1e-4)).all(-1))
    return xy, valid


def reverse_grid(planes, rotations, translations, intrinsics, scale=1,
                 iterations=12, tolerance=0.05):
    """Compute Appendix B fixed-point warps into the reference camera.

    planes is [3,H,W], defining n⋅X=1 at each reference pixel. Rotations
    [K,3,3] and translations [K,3] map the reference into each camera, and
    intrinsics [3,3] maps normalized rays to integer-centered pixels.
    Returns the grid and LR validity mask expected by lkr_torch.Ops.
    """
    _, height, width = planes.shape
    if scale < 1 or height % scale or width % scale or iterations < 1:
        raise ValueError("scale must divide both plane dimensions and iterations must be positive")
    y, x = torch.meshgrid(torch.arange(height, device=planes.device, dtype=planes.dtype),
                          torch.arange(width, device=planes.device, dtype=planes.dtype),
                          indexing="ij")
    pixels = torch.stack((x, y, torch.ones_like(x)), dim=-1)
    rays = pixels @ torch.linalg.inv(intrinsics).T
    reference = (rays * planes.permute(1, 2, 0)).sum(-1)
    rotated = torch.einsum("kji,hwj->khwi", rotations, rays)
    inverse_t = -torch.einsum("kji,kj->ki", rotations, translations)
    disparity = reference.expand(rotations.shape[0], -1, -1)
    eps = torch.finfo(planes.dtype).eps

    def update(disparity):
        direction = rotated + disparity[..., None] * inverse_t[:, None, None]
        depth_factor = direction[..., 2]
        normal = direction / depth_factor.clamp_min(eps)[..., None]
        source = (normal @ intrinsics.T)[..., :2]
        norm = source.new_tensor([width, height])
        grid = 2 * (source + 0.5) / norm - 1
        sampled = F.grid_sample(reference[None, None].expand(len(rotations), -1, -1, -1),
                                grid, padding_mode="border", align_corners=False)[:, 0]
        projected = (torch.einsum("kij,khwj->khwi", rotations, normal)
                     + sampled[..., None] * translations[:, None, None])
        target_depth = projected[..., 2]
        target = (projected @ intrinsics.T)[..., :2] / target_depth.clamp_min(eps)[..., None]
        error = torch.linalg.vector_norm(target - pixels[..., :2], dim=-1)
        inside = ((source >= -tolerance) & (source <= norm - 1 + tolerance)).all(-1)
        valid = (inside & (sampled > 0) & (depth_factor > eps)
                 & (target_depth > eps) & (error <= tolerance))
        return sampled / target_depth.clamp_min(eps), grid, valid

    for _ in range(iterations):
        disparity, _, _ = update(disparity)
    _, grid, valid = update(disparity)
    valid = valid & torch.isfinite(grid).all(-1)
    grid = torch.where(valid[..., None], grid, 0.0)
    return grid, -F.max_pool2d(-valid[:, None].to(planes.dtype), scale)[:, 0]
