# SPDX-License-Identifier: GPL-3.0-or-later
"""Host reference for alternating pose and plane updates from 2312.05190.

Takes calibrated, exposure-compatible feature maps and a coarse depth map.
The patch radius, iteration counts, robust scale and damping are explicit
reference choices. This module targets verification on small bursts.
"""
import torch
import torch.nn.functional as F


def _rays(height, width, intrinsics, dx=0, dy=0):
    y, x = torch.meshgrid(torch.arange(height, dtype=intrinsics.dtype, device=intrinsics.device),
                          torch.arange(width, dtype=intrinsics.dtype, device=intrinsics.device),
                          indexing="ij")
    return torch.stack((x + dx, y + dy, torch.ones_like(x)), dim=-1) @ intrinsics.inverse().T


def _project(points, intrinsics, height, width):
    eps = torch.finfo(points.dtype).eps
    xyz = points @ intrinsics.T
    xy = xyz[..., :2] / xyz[..., 2:].clamp_min(eps)
    extent = xy.new_tensor([width, height])
    valid = (points[..., 2] > eps) & ((xy >= -1e-4) & (xy <= extent - 1 + 1e-4)).all(-1)
    return 2 * (xy + 0.5) / extent - 1, valid


def _sample(images, xy):
    return F.grid_sample(images, xy, align_corners=False, padding_mode="border")


def _skew(v):
    a, b, c = v.unbind(-1)
    zero = torch.zeros_like(a)
    return torch.stack((zero, -c, b, c, zero, -a, -b, a, zero), -1).reshape(*v.shape[:-1], 3, 3)


def _patches(height, width, intrinsics, radius):
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            ray = _rays(height, width, intrinsics, dx, dy)
            x = torch.arange(width, device=ray.device) + dx
            y = torch.arange(height, device=ray.device) + dy
            valid = (y[:, None] >= 0) & (y[:, None] < height) & (x >= 0) & (x < width)
            yield ray, valid


def _points(ray, planes, poses):
    disparity = (ray * planes.movedim(0, -1)).sum(-1)
    xyz = ray / disparity.clamp_min(1e-5)[..., None]
    return (torch.einsum("kij,hwj->khwi", poses[:, :3, :3], xyz)
            + poses[:, None, None, :3, 3]), (disparity > 1e-5)


def _projection_jacobian(xyz, xy, intrinsics):
    return ((intrinsics[:2] - xy[..., None] * intrinsics[2])
            / xyz[..., 2:].clamp_min(1e-5)[..., None])


def _support(mask, source, reference):
    if mask is None:
        return True
    return ((_sample(mask[1:], source)[:, 0] >= 1 - 1e-6)
            & (_sample(mask[:1], reference[None])[0, 0] >= 1 - 1e-6))


def _guard_masked_pose(features, planes, poses, candidate, intrinsics, robust_scale, mask):
    """Accept a candidate only when it improves the same visible observations."""
    _, _, height, width = features.shape
    ray = _rays(height, width, intrinsics)
    ref, _ = _project(ray, intrinsics, height, width)
    observed = features[0, 0]

    def evaluate(current):
        xyz, positive = _points(ray, planes, current)
        source, valid = _project(xyz, intrinsics, height, width)
        usable = valid & positive & _support(mask, source, ref)
        residual = _sample(features[1:], source)[:, 0] - observed
        return torch.log1p((residual / robust_scale).square()), usable

    old_cost, old_support = evaluate(poses)
    new_cost, new_support = evaluate(candidate)
    common = old_support & new_support
    common_count = common.sum((1, 2))
    accept = ((common_count >= 0.95 * old_support.sum((1, 2)))
              & (common_count >= 6)
              & (torch.where(common, new_cost - old_cost, 0).sum((1, 2)) < 0))
    return torch.where(accept[:, None, None], candidate, poses)


@torch.no_grad()
def _pose_step(features, planes, poses, intrinsics, patch_radius, robust_scale, damping,
               mask=None):
    count, _, height, width = features.shape
    kernels = features.new_tensor([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]]) / 8
    sobel = torch.stack((kernels, kernels.T))[:, None]
    gradients = F.conv2d(F.pad(features[1:], (1, 1, 1, 1), mode="replicate"), sobel)
    pose_count = count - 1
    hessian = features.new_zeros(pose_count, 6, 6)
    rhs = features.new_zeros(pose_count, 6, 1)
    extent = features.new_tensor([width, height])
    for ray, patch_valid in _patches(height, width, intrinsics, patch_radius):
        xyz, positive = _points(ray, planes, poses)
        source, valid = _project(xyz, intrinsics, height, width)
        ref, _ = _project(ray, intrinsics, height, width)
        residual = _sample(features[1:], source)[:, 0] - _sample(features[:1], ref[None])[0, 0]
        gxy = _sample(gradients, source).movedim(1, -1)
        xy = (source + 1) * extent / 2 - 0.5
        dproj = _projection_jacobian(xyz, xy, intrinsics)
        gxyz = (gxy[..., None] * dproj).sum(-2)
        jac = torch.cat((gxyz, torch.linalg.cross(xyz, gxyz)), -1).reshape(pose_count, -1, 6)
        usable = valid & patch_valid & positive & _support(mask, source, ref)
        weight = usable / (1 + (residual / robust_scale).square())
        weighted = jac * weight.reshape(pose_count, -1, 1)
        hessian += jac.transpose(1, 2) @ weighted
        rhs += weighted.transpose(1, 2) @ residual.reshape(pose_count, -1, 1)
    delta = torch.linalg.solve(hessian + damping * torch.eye(6, device=features.device,
                                                             dtype=features.dtype), -rhs)[..., 0]
    twist = features.new_zeros(pose_count, 4, 4)
    twist[:, :3, :3] = _skew(delta[:, 3:])
    twist[:, :3, 3] = delta[:, :3]
    candidate = torch.linalg.matrix_exp(twist) @ poses
    if mask is not None:
        candidate = _guard_masked_pose(features, planes, poses, candidate, intrinsics,
                                       robust_scale, mask)
    return candidate


def _plane_loss(features, planes, poses, intrinsics, radius, robust_scale, mask=None):
    _, _, h, w = features.shape
    loss = planes.new_zeros(())
    for ray, patch_valid in _patches(h, w, intrinsics, radius):
        xyz, positive = _points(ray, planes, poses)
        source, valid = _project(xyz, intrinsics, h, w)
        ref, _ = _project(ray, intrinsics, h, w)
        residual = _sample(features[1:], source)[:, 0] - _sample(features[:1], ref[None])[0, 0]
        usable = valid & patch_valid & positive & _support(mask, source, ref)
        loss = loss + (torch.log1p((residual / robust_scale).square()) * usable).mean()
    return loss / (2 * radius + 1) ** 2


def align(features, intrinsics, coarse_depth, alternations=2, plane_steps=2, patch_radius=1,
          robust_scale=0.05, damping=0.01, plane_lr=0.01, *, mask=None, initial_poses=None):
    """Return relative rotation, translation and full-resolution tangent planes.

    Features are [K,1,H,W], with the reference first. Intrinsics use integer
    pixel centers. Each pose update uses the full resolution feature grid.
    Plane updates use the current pyramid level and a fixed set of poses.
    An optional boolean feature mask excludes any bilinear sample that
    touches an invalid input pixel, such as a saturated Bayer cell. Masked
    pose updates also require a cost decrease on nearly the same support.
    Initial poses, when supplied, are reference-to-frame SE(3) matrices
    for the non-reference frames.
    """
    if (features.ndim != 4 or features.shape[0] < 2 or features.shape[1] != 1
            or coarse_depth.ndim != 2 or torch.any(coarse_depth <= 0)
            or min(alternations, plane_steps) < 1 or patch_radius < 0
            or min(robust_scale, damping, plane_lr) <= 0):
        raise ValueError("invalid features, positive coarse depth, or optimizer settings")
    features = features.detach()
    if mask is not None:
        if mask.shape != features.shape or mask.dtype != torch.bool:
            raise ValueError("mask must be boolean and match the feature maps")
        mask = mask.to(features)
    _, _, height, width = features.shape
    h, w = coarse_depth.shape
    if h > height or w > width:
        raise ValueError("depth seed must fit within the feature grid")
    planes = torch.stack((torch.zeros_like(coarse_depth), torch.zeros_like(coarse_depth),
                          coarse_depth.reciprocal()))
    poses = torch.eye(4, device=features.device, dtype=features.dtype).repeat(len(features) - 1, 1, 1)
    if initial_poses is not None:
        if initial_poses.shape != poses.shape or not torch.isfinite(initial_poses).all():
            raise ValueError("initial poses must be finite [K-1,4,4] transforms")
        poses = initial_poses.detach().to(poses)

    while True:
        level = F.interpolate(features, size=(h, w), mode="area")
        level_mask = None if mask is None else -F.adaptive_max_pool2d(-mask, (h, w))
        transform = intrinsics.new_tensor([[w / width, 0, 0.5 * (w / width - 1)],
                                           [0, h / height, 0.5 * (h / height - 1)],
                                           [0, 0, 1]])
        local_k = transform @ intrinsics
        planes = planes.detach().requires_grad_(True)
        optimizer = torch.optim.Adam([planes], lr=plane_lr)
        for _ in range(alternations):
            full_planes = F.interpolate(planes[None], (height, width), mode="bilinear",
                                        align_corners=False)[0].detach()
            poses = _pose_step(features, full_planes, poses, intrinsics, patch_radius,
                               robust_scale, damping, mask)
            for _ in range(plane_steps):
                optimizer.zero_grad()
                _plane_loss(level, planes, poses, local_k, patch_radius, robust_scale,
                            level_mask).backward()
                optimizer.step()
        if (h, w) == (height, width):
            break
        h, w = min(h * 2, height), min(w * 2, width)
        planes = F.interpolate(planes.detach()[None], (h, w), mode="bilinear", align_corners=False)[0]

    poses = torch.cat((torch.eye(4, device=poses.device, dtype=poses.dtype)[None], poses))
    return poses[:, :3, :3], poses[:, :3, 3], planes.detach()
