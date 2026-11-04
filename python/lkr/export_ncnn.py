# SPDX-License-Identifier: GPL-3.0-or-later
"""Export the FFDNet convolution core to ncnn (.param/.bin) for tools/lkr-pipeline.

  python -I export_ncnn.py ffdnet_color.pth OUT_DIR

Graph: input (W/2, H/2, 13) = pixel-unshuffled RGB (12 ch, torch order
c*4 + dy*2 + dx) + noise map -> 12 x [conv3x3 (+ReLU except last)] ->
12 ch, pixel-shuffled back by the caller. Pixel (un)shuffle, GAT and the
noise map live in lkr_prior.cpp (same split as tools/ffdnet-probe), so the
graph has no layout ops. Weights: exact fp32 copies of the KAIR checkpoint.
Also writes a small torch reference (ref_in.npy / ref_out.npy) for parity.
"""
import os
import struct
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drunet import load_ffdnet  # noqa: E402


def main(pth, out):
    os.makedirs(out, exist_ok=True)
    net = load_ffdnet(pth)
    convs = [m for m in net.model if isinstance(m, torch.nn.Conv2d)]
    assert len(convs) == 12
    lines, blobs = [], []
    prev = 'in0'
    lines.append('Input in0 0 1 in0')
    for i, c in enumerate(convs):
        w = c.weight.detach().numpy().astype('<f4')          # OIHW
        b = c.bias.detach().numpy().astype('<f4')
        top = 'out0' if i == len(convs) - 1 else f'c{i}'
        act = '' if i == len(convs) - 1 else ' 9=1'
        lines.append(f'Convolution conv{i} 1 1 {prev} {top} 0={w.shape[0]} 1=3 3=1 4=1 5=1 6={w.size}{act}')
        blobs.append(struct.pack('<I', 0) + w.tobytes() + b.tobytes())   # flag 0 = raw fp32
        prev = top
    with open(os.path.join(out, 'ffdnet.param'), 'w') as f:
        f.write('7767517\n')
        f.write(f'{len(lines)} {len(lines)}\n')
        f.write('\n'.join(lines) + '\n')
    with open(os.path.join(out, 'ffdnet.bin'), 'wb') as f:
        for blob in blobs:
            f.write(blob)
    # torch reference on a deterministic packed input
    rng = np.random.default_rng(0)
    x = rng.random((1, 13, 64, 80)).astype(np.float32)
    x[:, 12] = 0.05
    with torch.no_grad():
        y = net.model(torch.from_numpy(x)).numpy()
    np.save(os.path.join(out, 'ref_in.npy'), x[0])
    np.save(os.path.join(out, 'ref_out.npy'), y[0])
    print('wrote', out, 'params', sum(c.weight.numel() + c.bias.numel() for c in convs))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
