#!/usr/bin/env python3
"""Extract the pinned public dependency closure; never rewrite its equations."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent

def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

def build(out, optimization='-O2', sanitize=False):
    manifest = json.loads((ROOT/'upstream/manifest.json').read_text())
    for name, expected in manifest['sha256'].items():
        assert hashlib.sha256((ROOT/'upstream'/name).read_bytes()).hexdigest() == expected, name
    out.mkdir(parents=True, exist_ok=True)
    source = (ROOT/'upstream/RawTherapee/rcd_demosaic.cc').read_text()
    generated = '\n'.join(line for line in source.splitlines() if not line.startswith('#include'))+'\n'
    generated = '#include "oracle_shim.h"\n'+generated
    hooks = [
        ('// Step 2: Low pass', 'vh', 'VH_Dir', False),
        ('// Step 3: Populate', 'lpf', 'lpf', True),
        ('/**\n            * STEP 4:', 'green', 'rgb[1]', False),
        ('// Step 4.2: Populate', 'pq', 'PQ_Dir', True),
        ('// Step 4.3: Populate', 'opposite_r', 'rgb[0]', False),
        ('// For the outermost tiles', 'final_r', 'rgb[0]', False),
    ]
    for marker, name, field, half in hooks:
        assert generated.count(marker) == 1
        hook = f'capture("{name}", {field}, tileSize, {str(half).lower()}, rowStart, rowEnd, colStart, colEnd);\n            '
        generated = generated.replace(marker,hook+marker)
    (out/'rcd_oracle.cc').write_text(generated)
    (out/'adaptation.patch').write_text(''.join(difflib.unified_diff(source.splitlines(True),generated.splitlines(True),fromfile='upstream/RawTherapee/rcd_demosaic.cc',tofile='generated/rcd_oracle.cc')))
    math = (ROOT/'upstream/RawTherapee/rt_math.h').read_text()
    helpers = '\n\n'.join(function(math, s) for s in [
        'template<typename T>\nconstexpr T SQR',
        'template<typename T>\nconstexpr T LIM01',
        'template<typename T>\nconstexpr T intp'])
    (out/'math_helpers.inc').write_text('using std::min; using std::max;\n'+helpers+'\n')
    border = (ROOT/'upstream/RawTherapee/demosaic_algos.cc').read_text()
    notice = border[:border.index('#include')]
    (out/'border.cc').write_text(notice+'#include "oracle_shim.h"\nnamespace rtengine {\n'+function(border,'void RawImageSource::border_interpolate')+'\n}\n')
    flags = ['-std=c++17',optimization,'-ffp-contract=off','-fno-fast-math','-Wall','-Wextra','-Wno-unused-parameter']
    if sanitize:
        flags += ['-fsanitize=address,undefined','-fno-omit-frame-pointer']
    command = ['clang++',*flags,'-I'+str(ROOT),'-I'+str(out),str(out/'rcd_oracle.cc'),str(out/'border.cc'),str(ROOT/'oracle_main.cc'),'-o',str(out/'oracle')]
    subprocess.run(command, check=True)
    (out/'build.json').write_text(json.dumps({'command':command,'compiler':subprocess.check_output(['clang++','--version'],text=True),'generated_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir() if p.suffix in ('.cc','.inc','.patch')}},indent=2)+'\n')
    return out/'oracle'

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--sanitize',action='store_true')
    args = parser.parse_args()
    build(args.output.resolve(),sanitize=args.sanitize)
