# SPDX-License-Identifier: Apache-2.0
"""Export the post-fit prior as a pure CONV_2D (+fused ReLU) / ADD graph for NNAPI (MediaTek MDLA, Qualcomm):
the input centring (x - 0.5) * 4 and the sigma scale are folded into conv0, the 0.1 output scale into the
last conv, and the skip (x + net) is a fixed 1x1 identity conv + ADD (no SLICE: NPU-friendly ops only).

Input NHWC 1 x T x T x 4 float: channels 0-2 = Lite's result in sqrt space, channel 3 = 100 * sigma.
Writes OUT_DIR/prior.bin (layers: u32 cin, cout, k, relu; f32 filter[cout][k][k][cin]; f32 bias[cout]),
test_in.f32 / test_out.f32 (one tile, NHWC) and checks the folded graph against PyTorch.

  export_nnapi.py MODEL.pt PAIRS_DIR OUT_DIR [--tile 256]
"""
import argparse, os, struct, sys
import numpy as np
import torch
import torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import Prior
from export_tflite import Tile, crops


def folded_layers(m):
    convs = [l for l in m.net if isinstance(l, torch.nn.Conv2d)]
    L = []
    for i, c in enumerate(convs):
        W = c.weight.detach().double().clone(); b = c.bias.detach().double().clone()
        if i == 0:   # input: x' = (x - 0.5) * 4 (ch 0-2), s' = s * 100 = t3 (ch 3, given as 100 sigma)
            b = b - 2.0 * W[:, :3].sum((1, 2, 3)); W[:, :3] *= 4.0
        if i == len(convs) - 1:
            W *= 0.1; b *= 0.1
        L.append((W, b, i < len(convs) - 1))
    return L


def run_folded(L, t):   # t: 1 x 4 x T x T (100 sigma in ch 3), zero padding like NNAPI SAME
    h = t.double()
    for W, b, relu in L:
        h = F.conv2d(h, W, b, padding=W.shape[-1] // 2)
        if relu: h = torch.relu(h)
    return h + t.double()[:, :3]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('model'); ap.add_argument('pairs'); ap.add_argument('out')
    ap.add_argument('--tile', type=int, default=256); a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ck = torch.load(a.model, map_location='cpu'); m = Prior(ck['ch'], ck['layers'], ck.get('cond', 1)); m.load_state_dict(ck['state']); m.eval()
    L = folded_layers(m)
    t = crops(a.pairs, a.tile, 1, seed=99)[0]
    with torch.no_grad(): ref = Tile(m)(t)
    got = run_folded(L, t).float()
    c = slice(8, -8)   # interior: border padding differs ((x-0.5)*4 pads with -2 in the original, 0 here)
    print('folded vs torch, interior max |diff| %.2e' % (got - ref)[..., c, c].abs().max().item())
    with open(os.path.join(a.out, 'prior.bin'), 'wb') as f:
        f.write(struct.pack('<I', len(L)))
        for W, b, relu in L:
            co, ci, k, _ = W.shape
            f.write(struct.pack('<4I', ci, co, k, int(relu)))
            f.write(W.permute(0, 2, 3, 1).contiguous().float().numpy().tobytes())   # OHWI
            f.write(b.float().numpy().tobytes())
    t.permute(0, 2, 3, 1).contiguous().numpy().astype(np.float32).tofile(os.path.join(a.out, 'test_in.f32'))
    run_folded(L, t).float().permute(0, 2, 3, 1).contiguous().numpy().tofile(os.path.join(a.out, 'test_out.f32'))
    print('wrote', a.out, [(W.shape[1], W.shape[0], W.shape[-1], r) for W, b, r in L])


if __name__ == '__main__':
    main()
