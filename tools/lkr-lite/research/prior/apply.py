# SPDX-License-Identifier: Apache-2.0
"""Apply a trained post-fit prior to Lite trace output (research).

  apply.py MODEL.pt TRACE_PREFIX OUT.f32
TRACE_PREFIX = the LKRL_TRACE_PREFIX path + '_tile_0_0-' (reads hybrid.f32 and adjoint-push.bin for the
noise). Writes the corrected image as interleaved H x W x 3 float32 (same layout as Lite's .dng.f32).
"""
import sys
import numpy as np
import torch
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from train import Prior


def main():
    mp, pre, out = sys.argv[1:4]
    ck = torch.load(mp, map_location='cpu')
    m = Prior(ck['ch'], ck['layers']); m.load_state_dict(ck['state']); m.eval()
    push = np.fromfile(pre + 'adjoint-push.bin', np.int32)
    H, W = int(push[4]), int(push[5])
    a_noise = float(np.fromfile(pre + 'adjoint-push.bin', np.float32)[8])
    z = np.fromfile(pre + 'hybrid.f32', np.float32).reshape(3, H, W)
    x = torch.from_numpy(np.sqrt(np.clip(z, 0, None)))[None]
    s = torch.full((1, 1, H, W), float(np.sqrt(a_noise * 0.18)))
    with torch.no_grad():
        o = m(x, s)[0].clamp(min=0).numpy() ** 2
    np.ascontiguousarray(o.transpose(1, 2, 0)).astype(np.float32).tofile(out)
    print('wrote', out, H, W)


if __name__ == '__main__':
    main()
