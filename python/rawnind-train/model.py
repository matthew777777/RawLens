# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""RawNIND-tiny: mobile single-frame Bayer denoiser (PyTorch, Mac training).

Design (RawNIND / Brummer et al. arXiv:2501.08924 Bayer-direct idea, shrunk
for NCNN-on-Android):
  * Operates PACKED: (B,4,H/2,W/2) [R,G1,G2,B] + optional (B,1,H/2,W/2)
    sigma plane -> 5ch input. Staying packed quarters the compute vs
    full-res Bayer and needs no PixelShuffle tail.
  * Fully convolutional U-Net, 2 downsamples (4x total), global residual
    (out = in[:, :4] + head(features)); no clipping inside (Android clamps).
  * NCNN-SAFE OPS ONLY: 3x3/1x1 conv, LeakyReLU(0.2), nearest upsample,
    concat, add. NO attention/transformer, NO PixelShuffle, NO LayerNorm /
    InstanceNorm / BatchNorm, NO transposed conv (checkerboard + slower on
    Adreno/Mali). Nearest-upsample + 3x3 conv is the portable decoder.
  * Stride-2 convs for downsampling keep phase alignment with the tiled
    Android inference (even tile origins, cf. KernelNet tiling in
    app/src/main/cpp/ncnnMl.cpp).

Receptive field: longest path (stem 2, down, enc 2, down, bottleneck 2,
up-block 2, up-block 2) gives RF diameter 47 packed px (radius 23, i.e. 46
Bayer px); Android TILE border must exceed it (KernelNet default border 32
is ample; export.py prints the exact value).
"""

import math

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except ImportError as _e:  # pragma: no cover
    raise ImportError("model.py needs torch: pip install -r requirements.txt (%s)" % _e)

NEG_SLOPE = 0.2  # RawNIND replaces ReLU with LeakyReLU(0.2)


class ConvBlock(nn.Module):
    """Two 3x3 conv + LeakyReLU. No norm (denoisers learn the DC level)."""

    def __init__(self, c_in, c_out):
        super().__init__()
        self.conv1 = nn.Conv2d(c_in, c_out, 3, padding=1, bias=True)
        self.act1 = nn.LeakyReLU(NEG_SLOPE, inplace=True)
        self.conv2 = nn.Conv2d(c_out, c_out, 3, padding=1, bias=True)
        self.act2 = nn.LeakyReLU(NEG_SLOPE, inplace=True)

    def forward(self, x):
        return self.act2(self.conv2(self.act1(self.conv1(x))))


class Down(nn.Module):
    """Stride-2 3x3 conv downsample (learned, phase-stable for tiling)."""

    def __init__(self, c_in, c_out):
        super().__init__()
        self.conv = nn.Conv2d(c_in, c_out, 3, stride=2, padding=1, bias=True)
        self.act = nn.LeakyReLU(NEG_SLOPE, inplace=True)

    def forward(self, x):
        return self.act(self.conv(x))


class Up(nn.Module):
    """Nearest x2 upsample + concat skip + ConvBlock (NCNN-friendly)."""

    def __init__(self, c_in, c_skip, c_out):
        super().__init__()
        self.block = ConvBlock(c_in + c_skip, c_out)

    def forward(self, x, skip):
        x = F.interpolate(x, scale_factor=2, mode="nearest")
        # Odd-input guard: center-crop/pad to skip size (tiling uses even
        # origins so this is a no-op in production; keeps ONNX dynamic).
        if x.shape[-2:] != skip.shape[-2:]:
            dh, dw = skip.shape[2] - x.shape[2], skip.shape[3] - x.shape[3]
            x = F.pad(x, (0, max(dw, 0), 0, max(dh, 0)))[:, :, :skip.shape[2], :skip.shape[3]]
        return self.block(torch.cat([x, skip], dim=1))


class RawNindTiny(nn.Module):
    """Packed-Bayer residual denoiser. in: (B,4|5,H,W) -> out: (B,4,H,W)."""

    def __init__(self, base=32, with_sigma=True):
        super().__init__()
        self.with_sigma = with_sigma
        c0 = 5 if with_sigma else 4
        self.stem = ConvBlock(c0, base)
        self.down1 = Down(base, base * 2)
        self.enc1 = ConvBlock(base * 2, base * 2)
        self.down2 = Down(base * 2, base * 4)
        self.bottleneck = ConvBlock(base * 4, base * 4)
        self.up1 = Up(base * 4, base * 2, base * 2)
        self.up2 = Up(base * 2, base, base)
        self.head = nn.Conv2d(base, 4, 1, bias=True)
        # Zero-init head: model starts as identity (stable early training).
        nn.init.zeros_(self.head.weight)
        nn.init.zeros_(self.head.bias)

    def forward(self, x):
        bayer = x[:, :4]
        s1 = self.stem(x)
        d1 = self.enc1(self.down1(s1))
        d2 = self.bottleneck(self.down2(d1))
        u1 = self.up1(d2, d1)
        u2 = self.up2(u1, s1)
        return bayer + self.head(u2)


def count_parameters(model):
    """Total trainable params (AIM 2025 budget reference: <= 15M)."""
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


def receptive_field_radius_packed():
    """Radius in PACKED px (double for Bayer px). Longest-path stack:

    stem 3x3 x2 (stride 1), down (stride 2), enc 3x3 x2, down (stride 2),
    bottleneck 3x3 x2, nearest-upsample (stride halves back), up-block 3x3
    x2, upsample, up-block 3x3 x2; 1x1 head adds nothing. Result: r=23,
    diameter 47. Android tiling border must exceed r (packed px).
    """
    r, stride = 0, 1
    seq = [("c", 1), ("c", 1),           # stem
           ("d", 2),                     # down1
           ("c", 1), ("c", 1),           # enc1
           ("d", 2),                     # down2
           ("c", 1), ("c", 1),           # bottleneck
           ("u", 2),                     # up1 (nearest x2)
           ("c", 1), ("c", 1),           # up1 block
           ("u", 2),                     # up2 (nearest x2)
           ("c", 1), ("c", 1)]           # up2 block
    for op, _ in seq:
        if op == "d":
            r += stride
            stride *= 2
        elif op == "u":
            stride //= 2
        else:
            r += stride
    return r  # diameter = 2r+1; Android border must exceed r


def make_input(packed, sigma=None):
    """packed (H2,W2,4) float32 [+ sigma scalar/array] -> (1,C,H2,W2) tensor."""
    import numpy as np
    x = np.asarray(packed, dtype=np.float32)
    assert x.ndim == 3 and x.shape[2] == 4, x.shape
    t = torch.from_numpy(x).permute(2, 0, 1).unsqueeze(0)
    if sigma is None:
        return t
    s = np.asarray(sigma, dtype=np.float32)
    s = np.full(x.shape[:2], float(sigma), np.float32) if s.ndim == 0 else s
    assert s.shape == x.shape[:2], (s.shape, x.shape)
    return torch.cat([t, torch.from_numpy(s).unsqueeze(0).unsqueeze(0)], dim=1)


def _self_test():  # pragma: no cover
    torch.manual_seed(0)
    for ws in (True, False):
        m = RawNindTiny(base=32, with_sigma=ws)
        m.eval()
        c = 5 if ws else 4
        with torch.no_grad():
            y = m(torch.rand(1, c, 128, 128))
        assert y.shape == (1, 4, 128, 128), y.shape
        assert torch.isfinite(y).all()
    n = count_parameters(RawNindTiny())
    assert n < 15_000_000, n
    print("params=%d rf_radius_packed=%d" % (n, receptive_field_radius_packed()))
    # Identity-at-init: zero head -> out == bayer input.
    m = RawNindTiny()
    m.eval()
    with torch.no_grad():
        x = torch.rand(1, 5, 32, 32)
        assert torch.abs(m(x) - x[:, :4]).max() < 1e-6
    print("model.py self-test ok")


if __name__ == "__main__":  # pragma: no cover
    _self_test()
