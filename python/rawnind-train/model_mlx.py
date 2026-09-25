# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""RawNIND-tiny in MLX (Apple Silicon training).

Architectural mirror of model.py (same depths, widths, activations,
residual): 2x ConvBlock stem, stride-2 Down x2, bottleneck, nearest-upsample
Up x2, zero-init 1x1 head + global residual. Two deliberate differences:

  * NHWC layout throughout (MLX convention): (B,H,W,5) -> (B,H,W,4).
  * Conv weights are OHWI (out, kh, kw, in); PyTorch uses OIHW. The
    transfer script (transfer_mlx_to_torch.py) transposes explicitly and the
    parity gate proves the mapping -- never hand-transpose elsewhere.

Needs: pip install -r requirements-mlx.txt (Apple Silicon only).
"""

try:
    import mlx.core as mx
    import mlx.nn as nn
except ImportError as _e:  # pragma: no cover
    raise ImportError("model_mlx.py needs mlx: pip install -r requirements-mlx.txt (%s)" % _e)

NEG_SLOPE = 0.2  # must match model.py


class ConvBlock(nn.Module):
    """Two 3x3 conv + LeakyReLU. No norm (denoisers learn the DC level)."""

    def __init__(self, c_in, c_out):
        super().__init__()
        self.conv1 = nn.Conv2d(c_in, c_out, 3, padding=1, bias=True)
        self.act1 = nn.LeakyReLU(NEG_SLOPE)
        self.conv2 = nn.Conv2d(c_out, c_out, 3, padding=1, bias=True)
        self.act2 = nn.LeakyReLU(NEG_SLOPE)

    def __call__(self, x):
        return self.act2(self.conv2(self.act1(self.conv1(x))))


class Down(nn.Module):
    """Stride-2 3x3 conv downsample (learned, phase-stable for tiling)."""

    def __init__(self, c_in, c_out):
        super().__init__()
        self.conv = nn.Conv2d(c_in, c_out, 3, stride=2, padding=1, bias=True)
        self.act = nn.LeakyReLU(NEG_SLOPE)

    def __call__(self, x):
        return self.act(self.conv(x))


class Up(nn.Module):
    """Nearest x2 upsample + concat skip + ConvBlock (NCNN-friendly)."""

    def __init__(self, c_in, c_skip, c_out):
        super().__init__()
        self.block = ConvBlock(c_in + c_skip, c_out)

    def __call__(self, x, skip):
        x = mx.repeat(mx.repeat(x, 2, axis=1), 2, axis=2)
        if x.shape[1:3] != skip.shape[1:3]:
            dh, dw = skip.shape[1] - x.shape[1], skip.shape[2] - x.shape[2]
            x = mx.pad(x, [(0, 0), (0, max(dh, 0)), (0, max(dw, 0)), (0, 0)])[
                :, :skip.shape[1], :skip.shape[2], :]
        return self.block(mx.concatenate([x, skip], axis=-1))


class RawNindTiny(nn.Module):
    """Packed-Bayer residual denoiser. in: (B,H,W,4|5) -> out: (B,H,W,4)."""

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
        self.head.weight = mx.zeros_like(self.head.weight)
        self.head.bias = mx.zeros_like(self.head.bias)

    def __call__(self, x):
        bayer = x[..., :4]
        s1 = self.stem(x)
        d1 = self.enc1(self.down1(s1))
        d2 = self.bottleneck(self.down2(d1))
        u1 = self.up1(d2, d1)
        u2 = self.up2(u1, s1)
        return bayer + self.head(u2)


def count_parameters(model):
    """Total trainable params (AIM 2025 budget reference: <= 15M)."""
    n = 0
    for leaf in flatten_params(model.trainable_parameters()).values():
        r = 1
        for s in leaf.shape:
            r *= s
        n += r
    return n


def flatten_params(tree, prefix=""):
    """Nested MLX param dict -> {dotted.path: array} (matches torch key order
    when attributes are named identically, e.g. stem.conv1.weight)."""
    out = {}
    for k, v in tree.items():
        key = f"{prefix}.{k}" if prefix else k
        if isinstance(v, dict):
            out.update(flatten_params(v, key))
        else:
            out[key] = v
    return out


def _self_test():  # pragma: no cover
    mx.random.seed(0)
    for ws in (True, False):
        m = RawNindTiny(base=32, with_sigma=ws)
        mx.eval(m.parameters())
        c = 5 if ws else 4
        y = m(mx.random.uniform(shape=(1, 128, 128, c)))
        mx.eval(y)
        assert tuple(y.shape) == (1, 128, 128, 4), y.shape
        assert bool(mx.all(mx.isfinite(y)))
    n = count_parameters(RawNindTiny())
    assert n < 15_000_000, n
    print("params=%d" % n)
    # Identity-at-init: zero head -> out == bayer input.
    m = RawNindTiny()
    mx.eval(m.parameters())
    x = mx.random.uniform(shape=(1, 32, 32, 5))
    mx.eval(x)
    y = m(x)
    mx.eval(y)
    assert float(mx.max(mx.abs(y - x[..., :4]))) < 1e-6
    print("model_mlx.py self-test ok")


if __name__ == "__main__":  # pragma: no cover
    _self_test()
