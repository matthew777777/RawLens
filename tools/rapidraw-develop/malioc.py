#!/usr/bin/env python3
"""Compile every RapidRAW develop variant (standalone passes and fused JPEG output) with
glslangValidator and, when available, Arm Mali Offline Compiler.

Usage: python3 tools/rapidraw-develop/malioc.py [--malioc PATH] [--core Mali-G615] [--output out.json]
"""
import argparse
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--malioc', default='/Applications/Arm Performance Studio 2026.6/mali_offline_compiler/malioc')
parser.add_argument('--core', default='Mali-G615')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/assets/shaders/display/rapidraw_develop.glsl').read_text()
output = (root / 'app/src/main/assets/shaders/display/agx_srgb8.glsl').read_text()
sigmoid = root / 'app/src/main/java/com/matthew/rawlens/SigmoidDisplayTransform.kt'
if sigmoid.exists():
    m = re.search(r'val glsl: String = """(.*?)"""', sigmoid.read_text(), re.S)
    if m:
        output = output.replace('// SIGMOID_FUNCTIONS', re.sub(r'\$\{[^}]*\}', '0.1', m.group(1)))
names = {0: 'nr_half', 1: 'nr_quarter', 2: 'nr_relwiden', 3: 'nr_filter', 4: 'nr_upsample',
         5: 'nr_core', 7: 'md_sample', 8: 'out_band', 9: 'md_hist', 10: 'md_pick'}
variants = [(n, f'#define MODE {m}\n' + source) for m, n in names.items()]
for basic in (0, 1):
    for gain in (0, 1):
        variants.append((f'fused-{"basic" if basic else "agx"}-{"hdr" if gain else "sdr"}',
            f'#define MODE 8\n#define RAPIDRAW_OUTPUT_FUSED 1\n#define RAPIDRAW_BASIC {basic}\n'
            f'#define RAPIDRAW_GAINMAP {gain}\nlayout(local_size_x=8,local_size_y=8) in;\n{source}\n{output}'))
use_malioc = Path(args.malioc).exists()
results, failed = [], False
with tempfile.TemporaryDirectory(prefix='rapidraw-develop-') as tmp:
    for name, body in variants:
        shader = Path(tmp) / f'{name}.comp'
        shader.write_text('#version 310 es\n' + body)
        if shutil.which('glslangValidator'):
            g = subprocess.run(['glslangValidator', str(shader)], capture_output=True, text=True)
            if g.returncode:
                failed = True
                print(f'FAIL glslang {name}\n{g.stdout}{g.stderr}')
                continue
        if use_malioc:
            r = subprocess.run([args.malioc, '--compute', '--opengles', '--core', args.core, '--format', 'json', str(shader)],
                               capture_output=True, text=True)
            if r.returncode:
                failed = True
                print(f'FAIL malioc {name}\n{r.stdout}{r.stderr}')
                continue
            s = json.loads(r.stdout)['shaders'][0]
            s['filename'] = name
            results.append(s)
            v = s['variants'][0]
            print(name, json.dumps(v.get('properties', []))[:200])
        else:
            print('ok', name)
if args.output and results:
    args.output.write_text(json.dumps(results, indent=1))
raise SystemExit(1 if failed else 0)
