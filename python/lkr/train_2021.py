# SPDX-License-Identifier: GPL-3.0-or-later
"""Train a narrow prior through same-exposure Bayer LK-R on synthetic crops."""
import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image
import torch
import torch.nn as nn
import torch.nn.functional as F

from hdr_hqs import NarrowResUNet
from lkr_torch import Ops, unrolled


class LearnedReconstruction(nn.Module):
    def __init__(self, width, iterations, data_steps):
        super().__init__()
        self.prior = NarrowResUNet(4, 3, width)
        nn.init.zeros_(self.prior.tail.weight)
        nn.init.zeros_(self.prior.tail.bias)
        self.log_mu = nn.Parameter(torch.linspace(-4, 0, iterations))
        self.log_eta = nn.Parameter(torch.full((iterations,), math.log(.12)))
        self.log_gamma = nn.Parameter(torch.zeros(iterations))
        self.data_steps = data_steps

    def forward(self, raw, motion, seed, noise, use_prior=True, stages=None):
        def restore(image):
            return image[:, :3] + self.prior(image) if use_prior else image[:, :3]

        stop = len(self.log_mu) if stages is None else stages
        result, _, _ = unrolled(raw, motion, seed, restore, 1,
                                self.log_mu[:stop].exp(), self.log_eta[:stop].exp(),
                                gamma=self.log_gamma[:stop].exp() * noise,
                                z_steps=self.data_steps)
        return result


def image_paths(path):
    return sorted(p for p in path.iterdir() if p.suffix.lower() in ('.jpg', '.jpeg', '.png'))


def sample(paths, rng, size):
    path = paths[int(torch.randint(len(paths), (), generator=rng))]
    with Image.open(path) as image:
        w, h = image.size
        x = int(torch.randint(w - size + 1, (), generator=rng))
        y = int(torch.randint(h - size + 1, (), generator=rng))
        srgb = torch.from_numpy(np.array(image.crop((x, y, x + size, y + size)).convert('RGB')))
    srgb = srgb.permute(2, 0, 1).float() / 255
    truth = torch.where(srgb <= .04045, srgb / 12.92, ((srgb + .055) / 1.055).pow(2.4))
    gains = torch.tensor([1.8, 1., 1.5])[:, None, None]
    truth = truth / gains * (.25 + .7 * torch.rand((), generator=rng))

    angle = (.016 * torch.rand(7, generator=rng) - .008)
    shift = 2.5 * torch.rand(7, 2, generator=rng) - 1.25
    c, s = angle.cos(), angle.sin()
    mid = size / 2
    moving = torch.stack((c, -s, shift[:, 0] + mid * (1 - c + s),
                          s, c, shift[:, 1] + mid * (1 - c - s)), 1)
    motion = torch.cat((torch.tensor([[1., 0., 0., 0., 1., 0.]]), moving))
    ops = Ops(size, size, 1, truth.device).setup(motion)
    signal = ops.fwd(truth)
    shot = 10 ** (-4.3 + torch.rand((), generator=rng) * 1.65)
    read = 10 ** (-6.5 + torch.rand((), generator=rng) * 2)
    raw = (signal + torch.randn(signal.shape, generator=rng) * (shot * signal + read).sqrt()).clamp(0, 1)
    initial_motion = motion.clone()
    initial_motion[1:, (2, 5)] += .35 * torch.randn(7, 2, generator=rng)
    kernel = raw.new_tensor([[1., 2., 1.], [2., 4., 2.], [1., 2., 1.]]).expand(3, 1, 3, 3)
    seed = (F.conv2d(raw[None, :1] * ops.mask, kernel, padding=1, groups=3)
            / F.conv2d(ops.mask[None], kernel, padding=1, groups=3))[0]
    return (raw, initial_motion, seed, (shot * .18 + read).sqrt()), truth


@torch.no_grad()
def evaluate(model, paths, crops, size):
    rng = torch.Generator().manual_seed(2304)
    modes = [('solver', False, None), ('learned', True, None)]
    if len(model.log_mu) > 3:
        modes.extend((('solver_3', False, 3), ('learned_3', True, 3)))
    sums = {name: torch.zeros(3) for name, _, _ in modes}
    dark_count = 0
    for _ in range(crops):
        args, truth = sample(paths, rng, size)
        truth = truth[:, 4:-4, 4:-4]
        truth_dx = truth.diff(dim=-1)
        dark = (truth.mean(0) < .09) & ((truth - F.avg_pool2d(
            truth[None], 3, 1, 1, count_include_pad=False)[0]).abs().mean(0) < .008)
        for name, enabled, stages in modes:
            pred = model(*args, use_prior=enabled, stages=stages)[:, 4:-4, 4:-4]
            error = pred - truth
            gradient_error = pred.diff(dim=-1) - truth_dx
            sums[name] += torch.stack((error.square().mean(), gradient_error.abs().mean(),
                                       (error.square() * dark).sum()))
        dark_count += int(dark.sum()) * 3
    for name, (mse, gradient, dark_mse) in sums.items():
        print(f'{name}: PSNR {-10 * (mse / crops).log10():.3f} dB, '
              f'gradient MAE {gradient / crops:.6f}, dark-flat RMSE '
              f'{(dark_mse / max(dark_count, 1)).sqrt():.6f}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('train', type=Path)
    parser.add_argument('validation', type=Path)
    parser.add_argument('checkpoint', type=Path)
    parser.add_argument('--steps', type=int, default=600)
    parser.add_argument('--width', type=int, default=8)
    parser.add_argument('--iterations', type=int, default=4)
    parser.add_argument('--data-steps', type=int, default=3)
    parser.add_argument('--patch', type=int, default=32)
    parser.add_argument('--validation-crops', type=int, default=48)
    args = parser.parse_args()
    train, val = map(image_paths, (args.train, args.validation))
    if not train or not val or args.train.resolve() == args.validation.resolve():
        parser.error('provide distinct nonempty image folders')
    if min(args.steps, args.width, args.iterations, args.data_steps,
           args.validation_crops) < 1 or args.patch < 16:
        parser.error('use positive counts and a patch of at least 16 pixels')
    torch.manual_seed(2021)
    torch.set_num_threads(4)
    rng = torch.Generator().manual_seed(1927)
    model = LearnedReconstruction(args.width, args.iterations, args.data_steps)
    opt = torch.optim.Adam(model.parameters(), lr=3e-4)
    for step in range(args.steps):
        inputs, truth = sample(train, rng, args.patch)
        opt.zero_grad()
        prediction = model(*inputs)
        loss = F.l1_loss(prediction[:, 4:-4, 4:-4], truth[:, 4:-4, 4:-4])
        if not torch.isfinite(loss):
            raise RuntimeError(f'nonfinite training loss at step {step}')
        loss.backward()
        opt.step()
        if (step + 1) % 100 == 0:
            print(f'step {step + 1}: linear MAE {loss.item():.6f}', flush=True)
    torch.save({'state': model.state_dict(), 'width': args.width, 'iterations': args.iterations,
                'data_steps': args.data_steps, 'steps': args.steps}, args.checkpoint)
    evaluate(model.eval(), val, args.validation_crops, args.patch)


if __name__ == '__main__':
    main()
