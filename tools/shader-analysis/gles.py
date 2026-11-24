#!/usr/bin/env python3
"""Assemble RawLens runtime GLES compute shaders and analyze them offline.

Mirrors Gles31AmazeProcessor.ProgramCache: `#import amaze|quad|agx_output|cleanup`
expansion, the `// SIGMOID_FUNCTIONS` substitution, and the `#version 310 es`
prefix. Writes assembled sources to <output>/src and runs malioc (--opengles)
and/or AOC (-api=OpenGLES) on them. Pass runtime shader dumps as .frag paths.
Usage from the repository root:

  python3 tools/shader-analysis/gles.py --backend both --core Mali-G615 \
      --core Mali-G720 --adreno-core a750 --output build/shader-analysis/gles-before
"""
import argparse, concurrent.futures, hashlib, json, os, re, subprocess, sys, textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/shaders'
KOTLIN = ROOT / 'app/src/main/java/com/matthew/rawlens'
DEFAULT = ['amaze/' + n for n in (
    'pad', 'gradcd', 'hvwt', 'nyqref', 'area', 'nyq2', 'green', 'gcorr', 'bound',
    'pmrbint', 'rbpm', 'chroma', 'final', 'final_display', 'to_acescg')] + [
    'rcd/rcd_final', 'quad/green', 'quad/color', 'raw/preprocess']


def kotlin_glsl(name):
    source = (KOTLIN / (name + '.kt')).read_text()
    match = re.search(r'val glsl(?:\s*:\s*String)?\s*=[^"]*?(?:ToneLook\.glsl \+ "\\n" \+ )?"""(.*?)"""', source, re.S)
    return textwrap.dedent(match.group(1)).strip()


def display_functions():
    return '\n'.join(kotlin_glsl(name) for name in (
        'ToneLook', 'OpenDrt', 'CameraToneBalance', 'SigmoidDisplayTransform',
        'Aces2DisplayTransform'))


def read(rel, baseline_ref=None):
    if baseline_ref and rel in ('display/agx_srgb8.glsl', 'amaze/final_display.glsl'):
        path = ASSETS.relative_to(ROOT) / rel
        body = subprocess.check_output(['git', 'show', f'{baseline_ref}:{path}'], cwd=ROOT, text=True)
    else:
        body = (ASSETS / rel).read_text()
    if '// SIGMOID_FUNCTIONS' in body:
        body = body.replace('// SIGMOID_FUNCTIONS', display_functions())
    if '// PHOTO_CUBE' in body:
        body = body.replace('// PHOTO_CUBE', kotlin_glsl('PhotoLutGl'))
    return body


RAPIDRAW_MODES = {0: 'nr_half', 1: 'nr_quarter', 2: 'nr_relwiden', 3: 'nr_filter', 4: 'nr_upsample',
                  5: 'nr_core', 7: 'md_sample', 8: 'out_band', 9: 'md_hist', 10: 'md_pick'}
EXTRA = ['display/agx_srgb8', 'display/agx_srgb8@jxl', 'merged/develop'] + [
    'rapidraw/' + n for n in RAPIDRAW_MODES.values()] + [
    f'rapidraw/fused-{b}-{g}' for b in ('agx', 'basic') for g in ('sdr', 'hdr')]


def assemble(asset, baseline_ref=None):
    # Gles31JpegOutputProcessor / Gles31JxlOutputProcessor / Gles31MergedDevelopProcessor / RapidRawDevelop.
    if asset.startswith('display/agx_srgb8'):
        body = read('display/agx_srgb8.glsl', baseline_ref)
        if asset.endswith('@jxl'):
            body = body.replace('binding = 1, rgba8', 'binding = 1, rgba16f')
        elif not baseline_ref:
            body = '#define RAWLENS_STILL_SDR 1\n' + body
        return '#version 310 es\n' + body
    if asset == 'merged/develop':
        return read('merged/develop.glsl').replace('#import cleanup', read('amaze/cleanup.glsl'))
    if asset.startswith('rapidraw/'):
        name = asset.split('/', 1)[1]
        src = read('display/rapidraw_develop.glsl')
        if name.startswith('fused-'):
            _, b, g = name.split('-')
            return ('#version 310 es\n#define MODE 8\n#define RAPIDRAW_OUTPUT_FUSED 1\n'
                    f'#define RAPIDRAW_BASIC {int(b == "basic")}\n#define RAPIDRAW_GAINMAP {int(g == "hdr")}\n'
                    'layout(local_size_x=8,local_size_y=8) in;\n' + src + '\n' + read('display/agx_srgb8.glsl', baseline_ref))
        mode = {v: k for k, v in RAPIDRAW_MODES.items()}[name]
        return f'#version 310 es\n#define MODE {mode}\n' + src
    agx = read('display/agx_srgb8.glsl', baseline_ref)
    agx = agx[agx.index('uniform highp int u_basic;'):agx.index('void main()')]
    body = (read(asset + '.glsl', baseline_ref)
            .replace('#import amaze', read('utils/import_amaze.glsl'))
            .replace('#import quad', read('quad/common.glsl') if asset.startswith('quad/') else '')
            .replace('#import agx_output', agx)
            .replace('#import cleanup', read('amaze/cleanup.glsl')))
    return body if body.startswith('#version') else '#version 310 es\n' + body


def find_malioc():
    if os.environ.get('MALIOC'):
        return os.environ['MALIOC']
    for p in sorted(Path('/Applications').glob('Arm Performance Studio */mali_offline_compiler/malioc')):
        return str(p)
    return 'malioc'


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    return dict(command=cmd, returncode=r.returncode, stdout=r.stdout, stderr=r.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('assets', nargs='*', help='asset names like amaze/green or paths to runtime .frag dumps; default: all compute assets')
    ap.add_argument('--backend', choices=['mali', 'adreno', 'both'], default='mali')
    ap.add_argument('--core', action='append')
    ap.add_argument('--adreno-core', action='append')
    ap.add_argument('--output', default=str(ROOT / 'build/shader-analysis/gles'))
    ap.add_argument('--jobs', type=int, default=4)
    ap.add_argument('--baseline-ref', help='Read the two photo output GLSL assets from this Git revision')
    a = ap.parse_args()
    out = Path(a.output).resolve()
    (out / 'src').mkdir(parents=True, exist_ok=True)
    tasks = []
    for asset in a.assets or DEFAULT + EXTRA:
        if asset.endswith('.frag'):
            src = Path(asset).resolve()
            stage, aoc_stage = '--fragment', '-fs'
        else:
            src = out / 'src' / (asset.replace('/', '__').replace('@', '_') + '.comp')
            src.write_text(assemble(asset, a.baseline_ref))
            stage, aoc_stage = '--compute', '-cs'
        if a.backend in ('mali', 'both'):
            for core in a.core or ['Mali-G615']:
                tasks.append([find_malioc(), '--opengles', stage, '--core', core,
                              '--format', 'json', str(src)])
        if a.backend in ('adreno', 'both'):
            for core in a.adreno_core or ['a750']:
                tasks.append(['python3', str(ROOT / 'tools/shader-analysis/aoc.py'), '-api=OpenGLES',
                              f'-arch={core}', '-dump=stats_perf', '-output_format=json', aoc_stage, str(src)])
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as ex:
        results = list(ex.map(run, tasks))
    for r in results:
        r['sha256'] = hashlib.sha256(Path(r['command'][-1]).read_bytes()).hexdigest()
    failed = [r for r in results if r['returncode'] != 0]
    (out / 'summary.json').write_text(json.dumps(
        dict(results=results, passed=len(results) - len(failed), failed=len(failed)), indent=1))
    for r in failed:
        print('FAIL', ' '.join(r['command'][-3:]), (r['stdout'] + r['stderr'])[-400:], file=sys.stderr)
    print(f'{len(results) - len(failed)} passed, {len(failed)} failed; {out / "summary.json"}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
