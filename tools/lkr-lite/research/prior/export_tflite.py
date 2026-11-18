# SPDX-License-Identifier: Apache-2.0
"""Export the LK-R Lite post-fit prior to TFLite / LiteRT (ai-edge-torch): float32 and int8 (PT2E static
quantisation, calibrated on training crops). Fixed tile input 1 x 4 x T x T: Lite's result in sqrt space
(3 ch) + the noise plane (100 * sqrt(a * 0.18)); output 1 x 3 x T x T corrected (sqrt space). Run tile by tile
on the phone (MediaTek APU / Qualcomm Hexagon delegates; GPU / CPU fallback).

  export_tflite.py MODEL.pt PAIRS_DIR OUT_PREFIX [--tile 256]
"""
import argparse, glob, os, sys
import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import Prior


class Tile(nn.Module):
    """Single 4-channel input (NPU-friendly): channels 0-2 image (sqrt space, 0..1), channel 3 = 100 x noise
    sigma (~0.1..1): one shared int8 input range (a raw sigma channel set the whole input scale to 0..0.06)."""
    def __init__(self, m):
        super().__init__(); self.m = m

    def forward(self, t):
        return self.m(t[:, :3], t[:, 3:4] * 0.01)


def crops(pairs_dir, tile, n, seed=0):
    rng = np.random.default_rng(seed)
    fs = sorted(glob.glob(os.path.join(pairs_dir, '*.x.npy')))
    out = []
    for _ in range(n):
        f = fs[rng.integers(len(fs))]
        x = np.load(f, mmap_mode='r'); nz = float(np.load(f.replace('.x.npy', '.n.npy')))
        i, j = (int(rng.integers(32, x.shape[0] - 32 - tile)) // 4 * 4 for _ in range(2))
        a = np.sqrt(np.clip(np.asarray(x[i:i + tile, j:j + tile], np.float32), 0, None)).transpose(2, 0, 1)
        s = np.full((1, tile, tile), 100.0 * np.sqrt(nz * 0.18), np.float32)
        out.append(torch.from_numpy(np.concatenate([a, s])[None]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('model'); ap.add_argument('pairs'); ap.add_argument('out')
    ap.add_argument('--tile', type=int, default=256)
    a = ap.parse_args()
    import litert_torch as ai_edge_torch
    ck = torch.load(a.model, map_location='cpu')
    m = Prior(ck['ch'], ck['layers'], ck.get('cond', 1)); m.load_state_dict(ck['state']); m.eval()
    tm = Tile(m).eval()
    sample = crops(a.pairs, a.tile, 1, seed=99)[0]

    # float32
    em = ai_edge_torch.convert(tm, (sample,))
    em.export(a.out + '_fp32.tflite')
    with torch.no_grad(): ref = tm(sample).numpy()
    got = em(sample)
    got = got.numpy() if hasattr(got, 'numpy') else np.asarray(got)
    print('fp32 tflite max |diff| vs torch %.2e' % np.abs(got - ref).max())

    # int8 (PT2E static quantisation, per-channel weights, calibrated on 64 training crops)
    from litert_torch.quantize.pt2e_quantizer import get_symmetric_quantization_config, PT2EQuantizer
    from litert_torch.quantize.quant_config import QuantConfig
    from torchao.quantization.pt2e.quantize_pt2e import prepare_pt2e, convert_pt2e
    q = PT2EQuantizer().set_global(get_symmetric_quantization_config(is_per_channel=True, is_dynamic=False))
    gm = torch.export.export(tm, (sample,)).module()
    gm = prepare_pt2e(gm, q)
    with torch.no_grad():
        for c in crops(a.pairs, a.tile, 64, seed=1): gm(c)
    gm = convert_pt2e(gm, fold_quantize=False)
    qm = ai_edge_torch.convert(gm, (sample,), quant_config=QuantConfig(pt2e_quantizer=q))
    qm.export(a.out + '_int8.tflite')
    # fully int8 I/O (NPU friendly): quantise the input / dequantise the output with the model's own params
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=a.out + '_int8.tflite'); it.allocate_tensors()
    di, do = it.get_input_details()[0], it.get_output_details()[0]
    si, zi = di['quantization']; so, zo = do['quantization']
    print('int8 I/O:', di['dtype'].__name__, di['shape'], 'in scale %.3g zp %d' % (si, zi), '| out', do['dtype'].__name__, do['shape'], 'scale %.3g zp %d' % (so, zo))
    xin = sample.numpy()
    if list(di['shape']) != list(xin.shape): xin = xin.transpose(0, 2, 3, 1)   # NHWC
    it.set_tensor(di['index'], np.clip(np.round(xin / si + zi), -128, 127).astype(np.int8)); it.invoke()
    o8 = it.get_tensor(do['index']).astype(np.float32)
    got8 = (o8 - zo) * so if so else o8   # float output when scale is 0
    if got8.shape != ref.shape: got8 = got8.transpose(0, 3, 1, 2)
    # error of the CORRECTION (output - input) relative to the correction itself: what quantisation costs
    corr = ref - sample[:, :3].numpy()
    print('int8 tflite: output max |diff| %.2e, correction relative error %.1f %%' % (
        np.abs(got8 - ref).max(), 100 * np.sqrt(((got8 - ref) ** 2).mean() / max(float((corr ** 2).mean()), 1e-20))))
    for f in (a.out + '_fp32.tflite', a.out + '_int8.tflite'): print(f, os.path.getsize(f), 'bytes')


if __name__ == '__main__':
    main()
