# SPDX-License-Identifier: GPL-3.0-or-later
"""Small synthetic training check for HDR HQS with known registration."""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image
import torch
import torch.nn.functional as F

from hdr_hqs import BurstHQS
from lkr_torch import Ops


def sample(scene, rng, size=40):
    h, w = scene.shape[-2:]
    top = int(torch.randint(h - size + 1, (), generator=rng))
    left = int(torch.randint(w - size + 1, (), generator=rng))
    truth = scene[:, top:top + size, left:left + size] * (0.5 + torch.rand((), generator=rng))

    motion = torch.tensor([1., 0., 0., 0., 1., 0.]).repeat(8, 1)
    motion[1:, (2, 5)] = 1.5 * torch.rand(7, 2, generator=rng) - 0.75
    ops = Ops(size, size, 1, truth.device).setup(motion)
    exposure = torch.tensor([1., 0.25, 0.5, 0.75, 1.25, 1.5, 2., 4.])
    expected = exposure[:, None, None] * ops.fwd(truth)
    noise = torch.randn(expected.shape, generator=rng) * (0.0002 * expected + 0.000001).sqrt()
    raw = (expected + noise).clamp(0, 1)
    mask = ops.mask[None]
    kernel = torch.tensor([[1., 2., 1.], [2., 4., 2.], [1., 2., 1.]]).expand(3, 1, 3, 3)
    demosaicked = F.conv2d(raw[:, None] * mask, kernel, padding=1, groups=3)
    demosaicked /= F.conv2d(mask, kernel, padding=1, groups=3)
    initial = demosaicked[0]
    reference = ops._sample(initial)
    pairs = torch.cat((demosaicked[1:], reference[1:]), dim=1)
    return (raw, exposure, initial, [(ops.grid, ops.valid)] * 3, [pairs] * 3), truth


def load_image(path):
    with Image.open(path) as image:
        srgb = torch.from_numpy(np.array(image.convert("RGB"))).float().permute(2, 0, 1) / 255
    return torch.where(srgb > 0.04045, ((srgb + 0.055) / 1.055).pow(2.4), srgb / 12.92)


@torch.no_grad()
def evaluate(model, scene):
    rng = torch.Generator().manual_seed(8190)
    errors = []
    for _ in range(8):
        args, truth = sample(scene, rng)
        errors.append(F.l1_loss(model(*args), truth))
    return torch.stack(errors).mean().item()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("training_image", type=Path)
    parser.add_argument("validation_image", type=Path)
    parser.add_argument("--steps", type=int, default=128)
    args = parser.parse_args()
    if args.steps < 1 or args.training_image.resolve() == args.validation_image.resolve():
        parser.error("use positive steps and two distinct images")
    torch.set_num_threads(2)
    torch.manual_seed(41)
    rng = torch.Generator().manual_seed(42)
    training, validation = map(load_image, (args.training_image, args.validation_image))
    model = BurstHQS()
    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
    before = evaluate(model, validation)

    for step in range(args.steps):
        inputs, truth = sample(training, rng)
        optimizer.zero_grad()
        loss = F.l1_loss(model(*inputs), truth)
        loss.backward()
        if step == 0:
            for name, param in (
                ("prior", model.prior.tail.weight),
                ("confidence", model.confidence.tail.weight),
                ("step", model.log_step),
                ("penalty", model.log_penalty),
                ("gamma", model.gamma),
            ):
                grad = param.grad
                if grad is None or not torch.isfinite(grad).all() or grad.abs().max() == 0:
                    raise RuntimeError(f"{name} has no finite nonzero gradient")
        optimizer.step()
    after = evaluate(model, validation)
    if not np.isfinite(after):
        raise RuntimeError("validation loss is nonfinite")
    print(f"held-out synthetic crop linear MAE {before:.6f} -> {after:.6f} ({args.steps} steps)")


if __name__ == "__main__":
    main()
