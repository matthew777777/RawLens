# SPDX-License-Identifier: GPL-3.0-or-later
"""Differentiable HDR reconstruction on supplied burst warps.

Implements the 2022 image and prior updates (eqs. 7, 9, 11, 12) using the
existing RGGB operator. The caller supplies registration and the initial RGB
estimate. The CNN layout mirrors the standalone NNAPI workload.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F

from drunet import ResBlock
from lkr_torch import Ops


class NarrowResUNet(nn.Module):
    def __init__(self, inputs, outputs, width):
        super().__init__()
        self.head = nn.Conv2d(inputs, width, 3, padding=1)
        self.enc = nn.ModuleList(
            nn.Sequential(ResBlock(width), ResBlock(width)) for _ in range(3)
        )
        self.down = nn.ModuleList(nn.Conv2d(width, width, 2, stride=2) for _ in range(3))
        self.body = nn.Sequential(ResBlock(width), ResBlock(width))
        self.up = nn.ModuleList(
            nn.Sequential(nn.Conv2d(width, 4 * width, 1), nn.PixelShuffle(2)) for _ in range(3)
        )
        self.dec = nn.ModuleList(
            nn.Sequential(ResBlock(width), ResBlock(width)) for _ in range(3)
        )
        self.tail = nn.Conv2d(width, outputs, 3, padding=1)

    def forward(self, image):
        h, w = image.shape[-2:]
        x = self.head(F.pad(image, (0, (-w) % 8, 0, (-h) % 8), mode="replicate"))
        skips = []
        for encode, downsample in zip(self.enc, self.down):
            x = encode(x)
            skips.append(x)
            x = downsample(x)
        x = self.body(x)
        for upsample, decode, skip in zip(self.up, self.dec, reversed(skips)):
            x = decode(upsample(x) + skip)
        return self.tail(x)[..., :h, :w]


class BurstHQS(nn.Module):
    def __init__(self, width=4, data_steps=1):
        super().__init__()
        if width < 1 or data_steps < 1:
            raise ValueError("width and data_steps must be positive")
        self.prior = NarrowResUNet(4, 3, width)
        self.confidence = NarrowResUNet(6, 1, width)
        self.log_step = nn.Parameter(torch.full((3,), -4.0))
        self.log_penalty = nn.Parameter(torch.tensor([-2.0, -1.0, 0.0]))
        self.gamma = nn.Parameter(torch.tensor([0.1, 0.05, 0.025]))
        self.data_steps = data_steps

    def forward(self, raw, exposure, initial, warps, rgb_pairs, saturation=1.0):
        """Reconstruct one burst with a fixed warp per frame at each HQS stage.

        raw is [K,h,w], exposure is [K] in relative exposure units, initial is
        [3,sh,sw], and each of the three entries in warps is (grid, validity)
        in grid_sample's align_corners=False convention. rgb_pairs is a sequence
        of three [K-1,6,h,w] tensors of demosaicked non-reference / warped
        reference pairs. Frame zero is the reference. Outputs remain linear.
        """
        _, h, w = raw.shape
        scale = initial.shape[-2] // h
        if scale < 1 or initial.shape[-2:] != (scale * h, scale * w):
            raise ValueError("initial must have an integer output scale")
        if len(warps) != 3 or len(rgb_pairs) != 3:
            raise ValueError("supply warps and confidence inputs for all three stages")
        ops = Ops(h, w, scale, raw.device)
        gain = exposure[:, None, None]
        available = gain * (raw < saturation)
        weight_sum = available.sum(0)
        safe_sum = torch.where(weight_sum > 0, weight_sum, 1.0)
        base = torch.where(weight_sum > 0, available / safe_sum, 1.0 / len(raw))

        z = x = initial
        for stage, ((grid, valid), pairs) in enumerate(zip(warps, rgb_pairs)):
            # Ops uses the same Bayer, box integration and exact transpose for
            # a supplied dense inverse warp as for the existing affine case.
            ops.grid, ops.valid = grid, valid
            confidence = self.confidence(pairs)[:, 0].sigmoid() if len(raw) > 1 else base[:0]
            weight = base * torch.cat((torch.ones_like(base[:1]), confidence))
            step = self.log_step[stage].exp()
            penalty = self.log_penalty[stage].exp()
            for _ in range(self.data_steps):
                residual = gain * ops.fwd(z) - raw
                gradient = ops.adj(gain * weight.square() * residual)
                z = z - step * (gradient + penalty * (z - x))
            gamma = self.gamma[stage].expand_as(z[:1])
            x = self.prior(torch.cat((z, gamma))[None])[0]
        return x
