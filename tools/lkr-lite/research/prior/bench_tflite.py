# SPDX-License-Identifier: Apache-2.0
"""Kodak benchmark of exported TFLite models (run tile by tile as on the phone: 256 px tiles, 16 px
overlap) against the cached Lite results of bench.py. Usage: bench_tflite.py BENCH_WORK_DIR TEMPLATE_UNUSED MODEL.tflite..."""
import glob, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench import scores
from gen_pairs import align_truth
from ai_edge_litert.interpreter import Interpreter


def run_tiled(it, x4, T=256, ov=16):
    di, do = it.get_input_details()[0], it.get_output_details()[0]
    si, zi = di['quantization']; so, zo = do['quantization']
    C, H, W = x4.shape; out = np.zeros((3, H, W), np.float32)
    step = T - 2 * ov
    for y0 in list(range(0, H - T, step)) + [H - T]:
        for x0 in list(range(0, W - T, step)) + [W - T]:
            t = x4[:, y0:y0 + T, x0:x0 + T][None]
            if si:
                ii = np.iinfo(di['dtype']); t = np.clip(np.round(t / si + zi), ii.min, ii.max)
            it.set_tensor(di['index'], t.astype(di['dtype'])); it.invoke()
            o = it.get_tensor(do['index']).astype(np.float32)
            if so: o = (o - zo) * so
            o = o[0]
            ya, xa = (0 if y0 == 0 else ov), (0 if x0 == 0 else ov)
            out[:, y0 + ya:y0 + T, x0 + xa:x0 + T] = o[:, ya:, xa:]
    return out


work = sys.argv[1]; models = sys.argv[3:]
its = {os.path.basename(m)[:-7]: Interpreter(model_path=m) for m in models}
for i in its.values(): i.allocate_tensors()
res = {}
for lab in sorted(glob.glob(os.path.join(work, '*_noisy', 'labels.npz'))) + sorted(glob.glob(os.path.join(work, '*_clean', 'labels.npz'))):
    b = os.path.dirname(lab); cond = b.rsplit('_', 1)[1]; n = 480
    z = np.fromfile(b + '_t_tile_0_0-hybrid.f32', np.float32).reshape(3, n, n)
    g = align_truth(z.transpose(1, 2, 0), np.load(lab)['gt'].astype(np.float32))
    na = 1.2e-4 if cond == 'noisy' else 1e-6
    x4 = np.concatenate([np.sqrt(np.clip(z, 0, None)), np.full((1, n, n), 100 * np.sqrt(na * 0.18), np.float32)])
    for nm, it in its.items():
        o = run_tiled(it, x4).clip(min=0) ** 2
        res.setdefault((cond, nm), []).append(scores(o.transpose(1, 2, 0).astype(np.float64), g.astype(np.float64)))
print(f"{'condition':8s} {'variant':18s} luma PSNR  chroma PSNR | band err luma  chroma")
for (cond, nm), v in sorted(res.items()):
    a = np.array(v).mean(0); print(f'{cond:8s} {nm:18s} {a[0]:8.2f}  {a[1]:10.2f}  | {a[2]:12.3f}  {a[3]:6.3f}')
