#!/usr/bin/env python3
"""Compare a saved baseline bench/shader set with a candidate, on the same GPU.

Use a Python with NumPy. The frozen fixture is checked by run_parity.sh;
this gate additionally requires byte-identical baseline/candidate output for
multiple settings, partial tiles and a full 4208x3120 frame. GPU best-of-five
comes from denoise_bench; this is not Android capture-to-save timing.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-bin', type=Path, required=True)
    parser.add_argument('--baseline-shaders', type=Path, required=True)
    parser.add_argument('--candidate-bin', type=Path,
                        default=ROOT / 'tools/wavelet-parity/host/build/denoise_bench')
    parser.add_argument('--candidate-shaders', type=Path,
                        default=ROOT / 'app/src/main/assets/spirv/wavelet')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'tools/wavelet-parity/data/manifest.json').read_text())['fixtures'][0]
    frozen = ROOT / 'tools/wavelet-parity/data' / fixture['input']
    rng = np.random.default_rng(150029)
    cases = [
        ('fixture-s6', 512, 512, 1536, 6, 1, 1, .25, .75, frozen),
        ('fixture-s1', 512, 512, 1536, 1, 1, 1, .25, .75, frozen),
        ('fixture-s7-strong', 512, 512, 1536, 7, 8, 0, .4, 1, frozen),
        ('fixture-luma-off', 512, 512, 256, 7, 2, 1, 0, .75, frozen),
        ('partial-tiles', 1544, 1032, 1536, 7, 1, 1, .25, .75, None),
        ('small-tiles', 521, 519, 256, 6, 1, .5, .25, .75, None),
        ('redmi-extent', 4208, 3120, 1536, 7, 1, 1, .25, .75, None),
    ]
    report = {'cases': [], 'pass': True, 'scope': 'same-GPU bit parity; host GPU timing'}
    for name, w, h, tile, scales, strength, shadows, fy, fuv, inp in cases:
        if inp is None:
            inp = args.out / (name + '-input.f32')
            image = rng.uniform(0, 1.2, (h, w, 4)).astype(np.float32)
            image[:, :, 3] = 1
            image.tofile(inp)
            del image
        common = ['--input-f32', str(inp), '--width', str(w), '--height', str(h),
                  '--tile', str(tile), '--scales', str(scales), '--strength', str(strength),
                  '--shadows', str(shadows), '--force-y', str(fy), '--force-uv', str(fuv),
                  '--wb', *map(str, fixture['wb']), '--a', str(fixture['a_norm']),
                  '--b', str(fixture['b_norm'])]
        row = {'name': name, 'extent': [w, h], 'tile': tile, 'scales': scales}
        outputs = []
        for label, binary, shaders in [('baseline', args.baseline_bin, args.baseline_shaders),
                                       ('candidate', args.candidate_bin, args.candidate_shaders)]:
            output = args.out / (name + '-' + label + '.f32')
            proc = subprocess.run([str(binary.resolve()), '--spv-dir', str(shaders.resolve()),
                                   *common, '--dump-out', str(output)], capture_output=True, text=True)
            (args.out / (name + '-' + label + '.log')).write_text(proc.stdout + proc.stderr)
            if proc.returncode:
                raise RuntimeError(f'{name}/{label}: {proc.stdout}\n{proc.stderr}')
            match = re.search(r'best_gpu_ms=([0-9.]+)', proc.stdout)
            row[label + '_gpu_ms'] = float(match.group(1))
            outputs.append(output.read_bytes())
        row['byte_identical'] = outputs[0] == outputs[1]
        row['baseline_sha256'], row['candidate_sha256'] = [hashlib.sha256(b).hexdigest() for b in outputs]
        row['candidate_finite'] = bool(np.isfinite(np.frombuffer(outputs[1], dtype=np.float32)).all())
        row['speedup'] = row['baseline_gpu_ms'] / row['candidate_gpu_ms']
        report['pass'] &= row['byte_identical'] and row['candidate_finite']
        report['cases'].append(row)
        print(json.dumps(row), flush=True)
        (args.out / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if report['pass'] else 1)


if __name__ == '__main__':
    main()
