# SPDX-License-Identifier: GPL-3.0-or-later
"""Known-correspondence Bayer burst experiments for the host geometry estimator."""
import argparse
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from time import perf_counter

from PIL import Image
import torch
import torch.nn.functional as F

from dense_alignment import _projection_jacobian, _skew, align
from dense_warp import forward_grid, reverse_grid
from lkr_torch import Ops


def burst(category, seed):
    """Render fronto-parallel layers independently in each camera."""
    generator = torch.Generator().manual_seed(seed)
    h, w = 64, 80
    k = torch.tensor([[75., 0, (w - 1) / 2], [0, 75., (h - 1) / 2], [0, 0, 1.]])
    y, x = torch.meshgrid(torch.arange(h), torch.arange(w), indexing="ij")
    pixels = torch.stack((x, y, torch.ones_like(x)), -1).float()
    rays = pixels @ k.inverse().T
    translations = torch.tensor([[0., 0., 0.], [.014, -.006, .003], [-.018, .008, -.006]])
    angles = torch.tensor([0., .003, -.004])
    if category == "shake":
        translations *= 5
        angles *= 3
    rotations = torch.eye(3).repeat(3, 1, 1)
    rotations[:, 0, 0] = rotations[:, 1, 1] = angles.cos()
    rotations[:, 1, 0], rotations[:, 0, 1] = angles.sin(), -angles.sin()
    object_shift = torch.zeros_like(translations)
    if category in ("motion", "branches", "hdr"):
        object_shift[1:, 0] = torch.tensor([.075, -.09])
    near, far = 1., 3.
    if category == "planar":
        near = far
    textures = .12 + .68 * F.interpolate(
        torch.rand(2, 3, h // 3, w // 3, generator=generator),
        (h, w), mode="bicubic", align_corners=False).clamp(0, 1)
    textures = .75 * textures.mean(1, keepdim=True) + .25 * textures
    if category == "weak_texture":
        textures[1] = .12 + .065 * textures[1]
    elif category == "branches":
        pattern = (torch.sin((x - 0.8 * y) * .75) + 1) / 2
        textures[0] = .12 + .65 * (.75 * pattern + .25 * textures[0])
    extent = torch.tensor([w, h])

    def render_layer(depth, rotation, translation, texture):
        direction = rays @ rotation
        origin = -translation @ rotation
        point = origin + direction * ((depth - origin[2]) / direction[..., 2:3])
        coords = (point @ k.T)[..., :2] / depth
        grid = 2 * (coords + .5) / extent - 1
        rgb = F.grid_sample(texture[None], grid[None], align_corners=False, padding_mode="border")[0]
        return rgb, rectangle(coords)

    def rectangle(coords):
        u, v = coords.unbind(-1)
        region = (u > 24) & (u < 58) & (v > 14) & (v < 49) & (category != "planar")
        if category == "branches":
            # Thin diagonal foreground strips on the same layer.
            region &= (u - .8 * v).remainder(6) < 1.8
        return region

    frames = []
    direct = []
    visible = []
    backward = []
    reverse_visible = []
    moving = rectangle(pixels[..., :2])
    depth = torch.where(moving, near, far)
    points = rays * depth[..., None]
    for r, t, shift in zip(rotations, translations, object_shift):
        foreground, covered = render_layer(near, r, t + shift, textures[0])
        background, _ = render_layer(far, r, t, textures[1])
        frames.append(torch.where(covered, foreground, background))
        direction = rays @ r
        point = torch.where(covered[..., None], -(t + shift) @ r, -t @ r)
        z = torch.where(covered, near, far)[..., None]
        point = point + direction * ((z - point[..., 2:]) / direction[..., 2:])
        back_xy = (point @ k.T)[..., :2] / z
        backward.append(back_xy)
        reverse_visible.append((covered | ~rectangle(back_xy))
                               & ((back_xy >= 2) & (back_xy < extent - 2)).all(-1))
        projected = (points @ r.T + t + moving[..., None] * shift) @ k.T
        target = projected[..., :2] / projected[..., 2:]
        target_ray = torch.cat((target, torch.ones_like(target[..., :1])), -1) @ k.inverse().T @ r
        origin = -(t + shift) @ r
        intersection = origin + target_ray * ((near - origin[2]) / target_ray[..., 2:])
        hit = rectangle((intersection @ k.T)[..., :2] / near)
        direct.append(target)
        visible.append((moving | ~hit) & ((target >= 2) & (target < extent - 2)).all(-1))
    frames = torch.stack(frames)
    ops = Ops(h, w, 1, "cpu")
    exposure = torch.tensor([1., .25, 2.5]) if category.startswith("hdr") else torch.ones(3)
    signal = (frames * ops.mask).sum(1) * exposure[:, None, None]
    shot, read = (0.001, .003) if category.startswith("hdr") else (0.0001, .001)
    raw = (torch.poisson(signal / shot, generator=generator) * shot
           + read * torch.randn(signal.shape, generator=generator)).clamp(0, 1)
    raw = (raw * 16383).round() / 16383
    normalized = raw / exposure[:, None, None]
    # A cell-centred alignment guide. Reconstruction uses the original CFA phases.
    guide = F.avg_pool2d(normalized[:, None], 2)
    guide = F.interpolate(guide, (h, w), mode="bilinear", align_corners=False)
    return (guide, raw, exposure, frames[0], k, depth, moving, torch.stack(direct),
            torch.stack(visible), torch.stack(backward), torch.stack(reverse_visible))


def native_motion(runner, raw, scale=1):
    """Replay the native single-tile solver and collect coarse and refined motion."""
    import numpy as np

    count, h, w = raw.shape
    bh, bw = (h + 15) // 16, (w + 15) // 16
    identity = np.tile([1, 0, 0, 0, 1, 0], (count, 1)).astype("f4")
    local = np.zeros((count, bh, bw, 2), dtype="f4")
    with TemporaryDirectory(prefix="lkrl-alignment-") as directory:
        directory = Path(directory)
        prefix, output, audit = directory / "input", directory / "result", directory / "audit"
        Path(f"{prefix}_meta.txt").write_text(
            f"K={count} h={h} w={w} H={h * scale} a=0.0001 b=0.000001 clip=0.999 "
            "robust_k=2 robust_tau=0.1\n")
        raw.numpy().tofile(f"{prefix}_frames.f32")
        identity.tofile(f"{prefix}_motion_initial.f32")
        local.tofile(f"{prefix}_local_initial.f32")
        start = perf_counter()
        subprocess.run([str(runner), "--trace", str(prefix), str(output),
                        "--audit-prefix", str(audit), "--estimate-affine"],
                       check=True, capture_output=True)
        elapsed = perf_counter() - start
        motion = torch.from_numpy(np.fromfile(f"{output}.motion", dtype="f4"))
        coarse = torch.from_numpy(np.fromfile(f"{audit}-affine-initial.f32", dtype="f4"))
        reconstructed = torch.from_numpy(np.fromfile(output, dtype="f4")).reshape(
            3, h * scale, w * scale)
    return (coarse.reshape(count, 2, 3), motion[:count * 6].reshape(count, 2, 3),
            motion[count * 6:].reshape(count, bh, bw, 2), elapsed, reconstructed)


def native_grids(affine, offsets, h, w):
    """Convert native edge-centred reverse motion to integer-centred grids."""
    y, x = torch.meshgrid(torch.arange(h), torch.arange(w), indexing="ij")
    points = torch.stack((x, y), -1).to(affine) + .5
    extent = affine.new_tensor([w, h])
    block_extent = affine.new_tensor([offsets.shape[2], offsets.shape[1]]) * 16
    matrix, shift = affine[..., :2], affine[..., 2]
    inverse = matrix.inverse()

    def displacement(coords):
        return F.grid_sample(offsets.movedim(-1, 1), 2 * coords / block_extent - 1,
                             align_corners=False, padding_mode="border").movedim(1, -1)

    def apply(matrices, coords):
        return torch.einsum("kij,khwj->khwi", matrices, coords)

    original = points.expand(len(affine), -1, -1, -1)
    reverse = apply(matrix, original) + shift[:, None, None] + displacement(original)
    direct = apply(inverse, original - shift[:, None, None])
    for _ in range(12):
        direct = apply(inverse, original - shift[:, None, None] - displacement(direct))
    closure = apply(matrix, direct) + shift[:, None, None] + displacement(direct) - original
    valid = ((direct >= .5) & (direct < extent - .5)).all(-1) & (closure.norm(dim=-1) <= .05)
    mask = ((reverse >= .5) & (reverse < extent - .5)).all(-1)
    return direct - .5, valid, 2 * reverse / extent - 1, mask.to(affine)


def stats(errors, mask):
    v = errors[mask]
    if not v.numel():
        return None
    median, p95, p99 = torch.quantile(v, v.new_tensor([.5, .95, .99])).tolist()
    return dict(mean=float(v.mean()), median=median, p95=p95, p99=p99,
                over_half_pixel=float((v > .5).float().mean()), samples=v.numel())


def reconstruct(raw, exposure, grid, valid, confidence, reference_anchor=False):
    """Shared fixed-operator reconstruction with clip-aware sensor weights."""
    _, h, w = raw.shape
    ops = Ops(h, w, 1, "cpu")
    ops.grid, ops.valid = grid, valid
    values = raw / exposure[:, None, None]
    weights = confidence * (raw < .999)
    # Diagonal majorizer of U^T diag(weights) U. Each row of U sums to one.
    coverage = ops.adj(weights)
    start = ops.adj(values * weights) / coverage.clamp_min(1e-6)
    fallback = F.avg_pool2d(values[:1] * ops.mask, 2)[None]
    fallback = fallback / F.avg_pool2d(ops.mask[None], 2)
    fallback = F.interpolate(fallback, (h, w), mode="bilinear", align_corners=False)[0]
    z = torch.where(coverage > 1e-6, start, fallback)
    # On motion disagreement, anchor the affected reference footprint even
    # when another frame is its only measurement of a given color.
    anchor = 0
    if reference_anchor:
        rejected = (1 - confidence).clone()
        rejected[0] = 0
        anchor = ops.adj(rejected).sum(0)
        anchor = 2 * F.max_pool2d(anchor[None], 5, 1, 2)[0]
    for _ in range(12):
        residual = (ops.fwd(z) - values) * weights
        gradient = ops.adj(residual) + anchor * (z - fallback)
        z = (z - gradient / (coverage + anchor).clamp_min(1e-6)).clamp_min(0)
    return z


def photometric_confidence(guide, raw, exposure, reverse, category):
    """Compare normalized low-pass guides with an exposure-scaled noise floor."""
    pred = F.grid_sample(guide[:1].expand_as(guide), reverse, align_corners=False,
                         padding_mode="border")[:, 0]
    observed = guide[:, 0]
    expo = exposure[:, None, None]
    shot, read = (0.001, .003) if category.startswith("hdr") else (0.0001, .001)
    variance = shot * (pred.clamp_min(0) / expo + pred.clamp_min(0)) + read**2 * (1 / expo**2 + 1)
    residual = F.avg_pool2d((observed - pred).square()[:, None], 3, 1, 1, count_include_pad=False)[:, 0]
    confidence = 1 / (1 + (residual / (9 * variance + .02**2)).square())
    # A clipped guide neighborhood provides weak evidence of motion.
    clipped = F.max_pool2d((raw >= .999).float()[:, None], 5, 1, 2)
    ref_clipped = F.grid_sample(clipped[:1].expand_as(clipped), reverse, align_corners=False)[:, 0]
    confidence = torch.where((clipped[:, 0] > 0) | (ref_clipped > 0), 1., confidence)
    confidence[0] = 1
    return confidence


def ssim(rgb, truth):
    def local_mean(image):
        return F.avg_pool2d(image, 7, stride=1)

    a, b = local_mean(rgb), local_mean(truth)
    va, vb = local_mean(rgb.square()) - a.square(), local_mean(truth.square()) - b.square()
    cov = local_mean(rgb * truth) - a * b
    return float((((2 * a * b + .01**2) * (2 * cov + .03**2))
                  / ((a.square() + b.square() + .01**2) * (va + vb + .03**2))).mean())


def verify():
    """Check the projective model against a matrix homography and the CFA transpose."""
    for scale in (1, 2):
        h, w = 12 * scale, 16 * scale
        k = torch.tensor([[31., 0, w / 2 - .5], [0, 34., h / 2 - .5], [0, 0, 1.]], dtype=torch.float64)
        plane = torch.tensor([.1, -.04, .7], dtype=k.dtype)
        planes = plane[:, None, None].expand(3, h, w)
        rotation = torch.eye(3, dtype=k.dtype)[None]
        translation = torch.tensor([[.023, -.01, .015]], dtype=k.dtype)
        xy, valid = forward_grid(planes, rotation, translation, k)
        homography = k @ (rotation + translation[:, :, None] * plane) @ k.inverse()
        inverse, mask = reverse_grid(planes, rotation, translation, k, scale=scale)
        y, x = torch.meshgrid(torch.arange(h, dtype=k.dtype), torch.arange(w, dtype=k.dtype), indexing="ij")
        points = torch.stack((x, y, torch.ones_like(x)), -1)
        expected = points[None] @ homography[:, None].transpose(-1, -2)
        torch.testing.assert_close(xy, expected[..., :2] / expected[..., 2:])
        assert valid.any() and mask.any()
        expected = points[None] @ homography.inverse()[:, None].transpose(-1, -2)
        back = (inverse + 1) * xy.new_tensor([w / 2, h / 2]) - .5
        fine = mask.bool().repeat_interleave(scale, -2).repeat_interleave(scale, -1)
        torch.testing.assert_close(back[fine], (expected[..., :2] / expected[..., 2:])[fine],
                                   atol=1e-8, rtol=1e-8)
        ops = Ops(h // scale, w // scale, scale, "cpu")
        ops.grid, ops.valid = inverse, mask
        image = torch.randn(3, h, w, dtype=k.dtype)
        sensor = torch.randn_like(mask)
        torch.testing.assert_close((ops.fwd(image) * mask * sensor).sum(),
                                   (image * ops.adj(sensor)).sum(), atol=1e-10, rtol=1e-10)

    xyz = k.new_tensor([.3, -.2, 1.7])
    pixel_direction = k.new_tensor([.21, -.47])
    perturbation = k.new_tensor([.05, .03, -.07, .05, .08, .11])

    def pixels(point):
        projected = k @ point
        return projected[:2] / projected[2]

    def sample(step):
        twist = k.new_zeros(4, 4)
        twist[:3, :3] = _skew(perturbation[3:])
        twist[:3, 3] = perturbation[:3]
        transform = torch.linalg.matrix_exp(step * twist)
        return pixels(transform[:3, :3] @ xyz + transform[:3, 3]) @ pixel_direction

    derivative = pixel_direction @ _projection_jacobian(xyz, pixels(xyz), k)
    analytic = torch.cat((derivative, torch.linalg.cross(xyz, derivative))) @ perturbation
    torch.testing.assert_close(analytic, (sample(1e-5) - sample(-1e-5)) / 2e-5)


def run(category, seed, directory, runner):
    guide, raw, exposure, truth, k, depth, moving, target, visible, back, back_visible = burst(
        category, seed)
    _, _, h, w = guide.shape
    boundary = F.max_pool2d(moving[None, None].float(), 5, 1, 2)
    interior = -F.max_pool2d(-moving[None, None].float(), 5, 1, 2)
    boundary = (boundary != interior)[0, 0]
    visible[0] = False
    back_visible[0] = False
    extent = torch.tensor([w, h])
    result = {}
    methods = ["reference_only", "flat_seed", "coarse_truth_seed", "flat_seed_confidence",
               "flat_seed_anchor"]
    if category.startswith("hdr"):
        methods.append("flat_seed_clip_mask")
    elif runner:
        coarse, affine, local, native_elapsed, native_rgb = native_motion(runner, raw)
        methods.extend(("native_affine", "native_blocks", "native_blocks_anchor",
                        "native_solver_rgb",
                        "native_seed_geometry"))
    for method in methods:
        start = perf_counter()
        is_native = method in ("native_affine", "native_blocks", "native_blocks_anchor", "native_solver_rgb")
        anchored = method.endswith("_anchor")
        if is_native:
            xy, valid, reverse, mask = native_grids(
                coarse if method == "native_affine" else affine,
                torch.zeros_like(local) if method == "native_affine" else local, h, w)
        elif method == "reference_only":
            r = torch.eye(3).repeat(len(raw), 1, 1)
            t = torch.zeros(len(raw), 3)
            p = torch.zeros(3, h, w)
            p[2] = 1 / 3
        elif method in ("flat_seed_confidence", "flat_seed_anchor"):
            r, t, p = flat_pose
        else:
            seed_depth = torch.full((8, 10), 3.)
            if method == "coarse_truth_seed":
                seed_depth = F.interpolate(depth[None, None], (8, 10), mode="area")[0, 0]
            guide_mask = None
            initial_poses = None
            if method == "flat_seed_clip_mask":
                clipped = F.max_pool2d((raw >= .999).float()[:, None], 2)
                clipped = F.interpolate(clipped, (h, w), mode="bilinear", align_corners=False)
                guide_mask = clipped == 0
            if method == "native_seed_geometry":
                # Lift the inverse affine displacement at the principal point
                # to a lateral translation using the flat depth seed.
                center = k[:2, 2] + .5
                coeff = coarse[1:]
                source = torch.linalg.solve(coeff[..., :2], center - coeff[..., 2])
                initial_poses = torch.eye(4).repeat(len(coeff), 1, 1)
                initial_poses[:, :2, 3] = (source - center) * 3 / k.diag()[:2]
            r, t, p = align(guide, k, seed_depth, alternations=4, mask=guide_mask,
                            initial_poses=initial_poses)
            if method == "flat_seed":
                flat_pose = r, t, p
        if not is_native:
            xy, valid = forward_grid(p, r, t, k)
            reverse, mask = reverse_grid(p, r, t, k)
        confidence = (photometric_confidence(guide, raw, exposure, reverse, category)
                      if anchored or method == "flat_seed_confidence" else torch.ones_like(mask))
        elapsed = perf_counter() - start
        reverse_xy = (reverse + 1) * extent / 2 - .5
        back_errors = (reverse_xy - back).norm(dim=-1)
        back_accepted = back_visible & mask.bool()
        query = 2 * (xy + .5) / extent - 1
        sampled = F.grid_sample(reverse_xy.movedim(-1, 1), query, align_corners=False).movedim(1, -1)
        support = F.grid_sample(mask[:, None], query, align_corners=False)[:, 0] > .999
        y, x = torch.meshgrid(torch.arange(h), torch.arange(w), indexing="ij")
        cycle = (sampled - torch.stack((x, y), -1)).norm(dim=-1)
        errors = (xy - target).norm(dim=-1)
        mask = mask.clone()
        if method == "reference_only":
            mask[1:] = 0
        reconstruction_start = perf_counter()
        rgb = (native_rgb if method == "native_solver_rgb"
               else reconstruct(raw, exposure, reverse, mask, confidence, anchored))
        reconstruction_seconds = perf_counter() - reconstruction_start
        scored, clean = rgb[:, 4:-4, 4:-4], truth[:, 4:-4, 4:-4]
        mse = (scored - clean).square().mean()
        result[method] = dict(all=stats(errors, visible), background=stats(errors, visible & ~moving),
                              foreground=stats(errors, visible & moving),
                              depth_edge=stats(errors, visible & boundary),
                              cycle=stats(cycle, visible & valid & support),
                              cycle_coverage=float((valid & support)[visible].float().mean()),
                              reverse=stats(back_errors, back_accepted),
                              reverse_coverage=float(back_accepted.sum() / back_visible.sum()),
                              psnr=float(-10 * mse.log10()), ssim=ssim(scored, clean), seconds=elapsed,
                              reconstruction_seconds=reconstruction_seconds,
                              mean_nonreference_weight=float((confidence * mask)[1:].mean()))
        if method in ("native_blocks", "native_solver_rgb"):
            result[method]["native_trace_seconds"] = native_elapsed
        if directory:
            stem = directory / f"{category}-{seed}-{method}"
            Image.fromarray((rgb.clamp(0, 1).movedim(0, -1).numpy() * 255).astype("uint8")).save(
                stem.with_suffix(".png"))
            heatmap = torch.where(visible[1], errors[1].clamp(0, 2) / 2, 0)
            Image.fromarray((255 * heatmap.numpy()).astype("uint8")).save(f"{stem}-error.png")
            Image.fromarray((255 * (confidence * mask)[1].numpy()).astype("uint8")).save(
                f"{stem}-confidence.png")
            torch.save({"camera_rgb": rgb, "raw": raw, "exposure": exposure,
                        "confidence": confidence, "valid": mask, "endpoint_error": errors,
                        "forward_xy": xy, "reverse_xy": reverse_xy, "ground_truth_xy": target,
                        "visible": visible}, f"{stem}.pt")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Write image previews, tensors and metrics here.")
    parser.add_argument("--seeds", nargs="+", type=int, default=[42, 137, 271])
    parser.add_argument("--native-runner", type=Path, help="Built lkrl_run executable for matched traces.")
    args = parser.parse_args()
    torch.set_num_threads(1)
    torch.manual_seed(0)
    verify()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    runner = args.native_runner.resolve() if args.native_runner else None
    results = {category: {seed: run(category, seed, args.output, runner) for seed in args.seeds}
               for category in ("planar", "parallax", "motion", "branches", "weak_texture",
                                "shake", "hdr_static", "hdr")}
    report = json.dumps(results, indent=2, allow_nan=False)
    if args.output:
        (args.output / "metrics.json").write_text(report + "\n")
    else:
        print(report)
