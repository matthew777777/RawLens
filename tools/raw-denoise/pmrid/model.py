# SPDX-License-Identifier: Apache-2.0
"""PMRID-style raw denoiser (Wang et al. 2020, "Practical Deep Raw Image Denoising on Mobile Devices"):
U-Net on packed RGGB, separable 5x5 blocks, stride-2 down, 2x2 transposed-conv up, additive skips.
NNAPI-friendly ops only: CONV_2D, DEPTHWISE_CONV_2D, TRANSPOSE_CONV_2D, ADD, fused RELU.
Input: 4 GAT-normalised planes + 1 noise-level plane (sigma of the input planes). Output: 4 planes (residual)."""
import torch, torch.nn as nn

def sep(ci, co, s=1, k=5, relu=True):
    l = [nn.Conv2d(ci, ci, k, s, k // 2, groups=ci), nn.Conv2d(ci, co, 1)]
    return nn.Sequential(*l, nn.ReLU()) if relu else nn.Sequential(*l)

class Enc(nn.Module):
    def __init__(s, ci, co, stride):
        super().__init__(); s.a = sep(ci, co // 4, stride); s.b = sep(co // 4, co, 1, relu=False)
        s.p = nn.Sequential(nn.Conv2d(ci, ci, 3, stride, 1, groups=ci), nn.Conv2d(ci, co, 1)) if (stride > 1 or ci != co) else nn.Identity()
        s.r = nn.ReLU()
    def forward(s, x): return s.r(s.b(s.a(x)) + s.p(x))

class Dec(nn.Module):
    def __init__(s, ci, cs, co):
        super().__init__(); s.up = nn.ConvTranspose2d(ci, co, 2, 2); s.sk = sep(cs, co, k=3, relu=False); s.r = nn.ReLU()
        s.f = Enc(co, co, 1)
    def forward(s, x, skip): return s.f(s.r(s.up(x) + s.sk(skip)))

class PMRID(nn.Module):
    def __init__(s, ch=(16, 64, 128, 256, 512)):
        super().__init__()
        c0, c1, c2, c3, c4 = ch
        s.inc = nn.Sequential(nn.Conv2d(5, c0, 3, 1, 1), nn.ReLU())
        s.e1 = nn.Sequential(Enc(c0, c1, 2), Enc(c1, c1, 1))
        s.e2 = nn.Sequential(Enc(c1, c2, 2), Enc(c2, c2, 1))
        s.e3 = nn.Sequential(Enc(c2, c3, 2), Enc(c3, c3, 1))
        s.e4 = nn.Sequential(Enc(c3, c4, 2), Enc(c4, c4, 1), Enc(c4, c4, 1))
        s.d3 = Dec(c4, c3, c3); s.d2 = Dec(c3, c2, c2); s.d1 = Dec(c2, c1, c1); s.d0 = Dec(c1, c0, c0)
        s.out = nn.Conv2d(c0, 4, 3, 1, 1)
        s.gain = 4.0
        nn.init.zeros_(s.out.weight); nn.init.zeros_(s.out.bias)
    def forward(s, u, sig):
        x0 = s.inc(torch.cat([u, sig], 1))
        x1 = s.e1(x0); x2 = s.e2(x1); x3 = s.e3(x2); x4 = s.e4(x3)
        y = s.d3(x4, x3); y = s.d2(y, x2); y = s.d1(y, x1); y = s.d0(y, x0)
        # v3: residual in noise-sigma units: the correction is bounded by the noise at every ISO, and one fixed
        # output range serves all ISOs (INT8). The NPU graph is out(y); scale + add stay in float.
        return u + s.gain * sig * s.out(y)
