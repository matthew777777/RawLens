# SPDX-License-Identifier: Apache-2.0
"""INT8 PMRID as the NNAPI graph runs it: every tensor between ops is QUANT8_ASYMM (one scale + zero point),
conv / depthwise / transposed-conv weights are per-output-channel symmetric int8, ADD and RELU fused.

The graph input is only the 4 GAT planes u; the noise plane is constant over a tile, so its share of the first conv
becomes a per-channel offset sb = bias + sig * sum(w[:, 4]) added after that conv (broadcast ADD, its own scale).
Graph output = out(y) in noise-sigma units; u + 4 * sig * out stays in float on the CPU.

QNet(model) wraps a trained PMRID without copying it: mode 'float' (reference), 'calib' (records each tensor's range),
'q' (fake quantization, straight-through gradients: QAT trains the same weights)."""
import torch, torch.nn as nn, torch.nn.functional as F

class Range(nn.Module):
    """Range of one graph tensor: running min/max in calibration (EMA in QAT), then uint8 fake quantization."""
    def __init__(s):
        super().__init__(); s.register_buffer('lo', torch.tensor(float('inf'))); s.register_buffer('hi', torch.tensor(float('-inf')))
    def observe(s, x, ema):
        lo, hi = x.detach().amin(), x.detach().amax()
        # percentile-free but outlier-robust enough: per-batch extremes, averaged over batches (EMA) after the first
        if not torch.isfinite(s.lo): s.lo.copy_(lo); s.hi.copy_(hi)
        else: s.lo.mul_(1 - ema).add_(ema * lo); s.hi.mul_(1 - ema).add_(ema * hi)
    def qparams(s):
        lo = min(float(s.lo), 0.0); hi = max(float(s.hi), lo + 1e-6)   # zero must be exact (padding, RELU)
        scale = (hi - lo) / 255.0; zp = int(round(-lo / scale)); return scale, max(0, min(255, zp))
    def forward(s, x, mode, ema):
        if mode == 'calib' or (mode == 'q' and s.training): s.observe(x, ema)
        if mode != 'q': return x
        scale, zp = s.qparams()
        return torch.fake_quantize_per_tensor_affine(x, scale, zp, 0, 255)

def wq(w, ch_axis=0):
    """Per-channel symmetric int8 weights (NNAPI TENSOR_QUANT8_SYMM_PER_CHANNEL), straight-through."""
    dims = [d for d in range(w.dim()) if d != ch_axis]
    m = w.detach().abs().amax(dim=dims).clamp(min=1e-8); scale = m / 127.0
    return torch.fake_quantize_per_channel_affine(w, scale, torch.zeros_like(scale, dtype=torch.int32), ch_axis, -127, 127)

class QNet(nn.Module):
    def __init__(s, model):
        super().__init__(); s.m = model; s.mode = 'float'; s.ema = 0.01
        s.R = nn.ModuleDict()
        s.order = []     # tensor names in graph order (export)
        s.src = {}       # op name -> name of its input tensor (bias scale = input scale * weight scale)
        dev = next(model.parameters()).device
        with torch.no_grad(): s.body(torch.zeros(1, 4, 32, 32, device=dev), torch.full((1, 1, 32, 32), 0.01, device=dev))   # registers every Range
    def r(s, name, x):
        k = name.replace('.', '__')   # module names cannot contain '.'
        if k not in s.R: s.R[k] = Range().to(x.device); s.order.append(name)
        y = s.R[k](x, s.mode, s.ema); y.qname = name
        return y
    def range(s, name): return s.R[name.replace('.', '__')]
    def conv(s, c, x, name, relu=False, add=None, addname=None):
        s.src[name] = getattr(x, 'qname', None)
        w = wq(c.weight) if s.mode == 'q' else c.weight
        y = F.conv2d(x, w, c.bias, c.stride, c.padding, c.dilation, c.groups)
        if add is not None:   # NNAPI: conv output is its own tensor, then ADD (+fused RELU) makes another
            y = s.r(name, y); y = y + add; name = addname
        return s.r(name, F.relu(y) if relu else y)
    def sep(s, seq, x, name, relu_last):
        dw, pw = seq[0], seq[1]
        x = s.conv(dw, x, name + '.dw')
        return s.conv(pw, x, name + '.pw', relu=relu_last)
    def enc(s, e, x, name):
        a = s.sep(e.a, x, name + '.a', True)
        b = s.sep(e.b, a, name + '.b', False)
        p = s.sep(e.p, x, name + '.p', False) if isinstance(e.p, nn.Sequential) else x
        return s.r(name + '.add', F.relu(b + p))
    def dec(s, d, x, skip, name):
        w = wq(d.up.weight, 1) if s.mode == 'q' else d.up.weight   # ConvTranspose2d weight: (in, out, kh, kw)
        s.src[name + '.up'] = getattr(x, 'qname', None)
        up = s.r(name + '.up', F.conv_transpose2d(x, w, d.up.bias, 2))
        sk = s.sep(d.sk, skip, name + '.sk', False)
        y = s.r(name + '.add', F.relu(up + sk))
        return s.enc(d.f, y, name + '.f')
    def sigbias(s, sig):
        """Per-channel offset of the first conv for a tile with noise sigma sig: [B, c0, 1, 1]."""
        c = s.m.inc[0]
        return (c.bias.view(1, -1) + sig.view(-1, 1) * c.weight[:, 4].sum((1, 2)).view(1, -1)).view(sig.shape[0], -1, 1, 1)
    def body(s, u, sig):
        m = s.m
        u = s.r('in', u)
        c = m.inc[0]
        w = wq(c.weight[:, :4]) if s.mode == 'q' else c.weight[:, :4]
        s.src['inc.conv'] = 'in'
        x = s.r('inc.conv', F.conv2d(u, w, None, 1, 1))
        sb = s.r('inc.sb', s.sigbias(sig[:, :1, :1, :1]))
        x0 = s.r('inc', F.relu(x + sb))
        x1 = s.enc(m.e1[1], s.enc(m.e1[0], x0, 'e1.0'), 'e1.1')
        x2 = s.enc(m.e2[1], s.enc(m.e2[0], x1, 'e2.0'), 'e2.1')
        x3 = s.enc(m.e3[1], s.enc(m.e3[0], x2, 'e3.0'), 'e3.1')
        x4 = s.enc(m.e4[2], s.enc(m.e4[1], s.enc(m.e4[0], x3, 'e4.0'), 'e4.1'), 'e4.2')
        y = s.dec(m.d3, x4, x3, 'd3'); y = s.dec(m.d2, y, x2, 'd2'); y = s.dec(m.d1, y, x1, 'd1'); y = s.dec(m.d0, y, x0, 'd0')
        return s.conv(m.out, y, 'out')
    def forward(s, u, sig):
        """Same contract as PMRID.forward. In 'float' mode it equals the model except at tile borders (sig padding)."""
        return u + s.m.gain * sig * s.body(u, sig)

def load(path, dev='cpu'):
    """QNet saved by quantize.py / QAT (.q.pt): model weights + tensor ranges, mode 'q'."""
    import os, sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from model import PMRID
    S = torch.load(path, map_location=dev)
    q = QNet(PMRID().to(dev)); q.load_state_dict(S); q.mode = 'q'; q.eval(); return q
