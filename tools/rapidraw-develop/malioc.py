#!/usr/bin/env python3
"""Compile every RapidRAW develop variant (standalone passes and fused JPEG output) with
glslangValidator and, when available, Arm Mali Offline Compiler.

Usage: python3 tools/rapidraw-develop/malioc.py [--malioc PATH] [--core Mali-G615] [--output out.json]
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--malioc', default='/Applications/Arm Performance Studio 2026.6/mali_offline_compiler/malioc')
parser.add_argument('--core', default='Mali-G615')
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'tools/shader-analysis'))
from gles import RAPIDRAW_MODES, assemble

names = list(RAPIDRAW_MODES.values()) + [
    f'fused-{mapper}-{gainmap}'
    for mapper in ('agx', 'basic') for gainmap in ('sdr', 'hdr')
]
use_malioc = Path(args.malioc).exists()
results, failed = [], False
with tempfile.TemporaryDirectory(prefix='rapidraw-develop-') as tmp:
    for name in names:
        shader = Path(tmp) / f'{name}.comp'
        shader.write_text(assemble(f'rapidraw/{name}'))
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
