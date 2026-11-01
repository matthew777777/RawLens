#!/usr/bin/env python3
"""Verify exact RAWR source identity, then independently build and compare GPU output.

Usage: python3 tools/wavelet-parity/prove_rawr_parity.py --reference /path/to/rawr --out /tmp/rawr-proof
Requires macOS, MoltenVK, glslangValidator, NumPy. The reference checkout is read-only.
Only host Vulkan initialization/include paths are adapted; processing sources are not.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def run(command):
    subprocess.run(list(map(str, command)), check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    ref = args.reference.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    upstream = ref / 'native/raw_denoise'
    local = ROOT / 'app/src/main/cpp/wavelet/upstream'
    shaders = ROOT / 'app/src/main/assets/spirv/wavelet'
    revision = subprocess.check_output(['git', '-C', str(ref), 'rev-parse', 'HEAD'], text=True).strip()
    rows = []
    files = [(upstream / p, local / p) for p in (
        'include/raw_denoise/DenoisePipeline.hpp', 'src/DenoisePipeline.cpp', 'NOTICE.md')]
    files += [(p, shaders / p.name) for p in sorted((upstream / 'shaders').glob('*.comp'))]
    for original, candidate in files:
        a, b = original.read_bytes(), candidate.read_bytes()
        rows.append({'reference': str(original.relative_to(ref)),
                     'local': str(candidate.relative_to(ROOT)),
                     'sha256': hashlib.sha256(b).hexdigest(), 'identical': a == b})
    report = {'revision': revision, 'files': rows, 'pass': all(r['identical'] for r in rows)}
    (out / 'source-identity.json').write_text(json.dumps(report, indent=2) + '\n')
    if not report['pass']:
        raise SystemExit('RAWR source mismatch')
    refshaders = out / 'reference-shaders'
    refshaders.mkdir(exist_ok=True)
    for original in sorted((upstream / 'shaders').glob('*.comp')):
        run(['glslangValidator', '-V', '--target-env', 'vulkan1.1', original,
             '-o', refshaders / (original.stem + '.spv')])
        assert (refshaders / (original.stem + '.spv')).read_bytes() == (shaders / (original.stem + '.spv')).read_bytes()
    # The harness has only path/instance-extension adaptations from RAWR.
    # Use it for both builds to hold upload, rounding, timing and readback constant.
    harness = ROOT / 'tools/wavelet-parity/host'
    for label, source in [('reference', upstream), ('candidate', local)]:
        run(['c++', '-std=c++20', '-O2', '-o', out / (label + '-bench'),
             harness / 'denoise_bench.cpp', source / 'src/DenoisePipeline.cpp',
             '-I' + str(harness), '-I' + str(source / 'include'),
             '-I/opt/homebrew/include', '-L/opt/homebrew/lib', '-lMoltenVK',
             '-framework', 'Metal', '-framework', 'Foundation', '-framework', 'QuartzCore',
             '-framework', 'IOSurface'])
    run([sys.executable, ROOT / 'tools/wavelet-parity/compare_optimization.py',
         '--baseline-bin', out / 'reference-bench', '--baseline-shaders', refshaders,
         '--candidate-bin', out / 'candidate-bench', '--candidate-shaders', shaders,
         '--out', out / 'gpu'])
    print('RAWR_SOURCE_AND_GPU_BIT_PARITY_PASS ' + revision)


if __name__ == '__main__':
    main()
