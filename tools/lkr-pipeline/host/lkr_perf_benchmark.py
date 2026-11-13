#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Paired full-frame performance replay; input hashes and quality settings stay fixed.

Manifest: {"case-name": {"scale": 1, "width": 4080, "height": 3060,
 "frames": [{"path": "/absolute/frame.dng", "sha256": "..."}, ...]}}
An executable is the desktop lkr_full_run, built before starting this script.
Outputs retain the first float reconstruction per executable/case and hash all
repeats. Timed replays disable GPU timestamps; --profile adds separate profiles.
"""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import statistics
import subprocess
import time


def fingerprint(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--manifest', type=Path, required=True)
    ap.add_argument('--baseline', type=Path, required=True)
    ap.add_argument('--candidate', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--repeats', type=int, default=3)
    ap.add_argument('--profile', action='store_true')
    args = ap.parse_args()
    if args.repeats < 1:
        ap.error('--repeats must be positive')
    if args.output.exists():
        ap.error('--output must be a new directory to preserve previous evidence')
    args.output.mkdir(parents=True)
    manifest = json.loads(args.manifest.read_text())
    executables = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
    identity = {k: {'path': str(v), 'sha256': fingerprint(v)} for k, v in executables.items()}
    (args.output / 'executables.json').write_text(json.dumps(identity, indent=2))
    (args.output / 'host.json').write_text(json.dumps(
        dict(platform=platform.platform(), machine=platform.machine()), indent=2))
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    env = dict(os.environ)
    env.setdefault('DYLD_FALLBACK_LIBRARY_PATH', '/opt/homebrew/lib')
    for key in ('LKR_DEBUG_MAPS', 'LKR_TRACE_PREFIX', 'LKR_TRACE_TILE', 'LKR_DUMP_WT',
                'LKR_DUMP_ZX', 'LKR_DUMP_NS', 'LKR_DEBUG_NS', 'LKR_DEBUG_SUB', 'LKR_DEBUG_BS'):
        env.pop(key, None)
    rows = []
    for name, case in manifest.items():
        if Path(name).name != name:
            raise ValueError('case name must be a directory name')
        folder = args.output / 'inputs' / name
        folder.mkdir(parents=True)
        hashes = []
        for i, item in enumerate(case['frames']):
            source = Path(item['path']).resolve()
            digest = fingerprint(source)
            if digest != item['sha256']:
                raise ValueError(f'input changed: {source}')
            hashes.append(digest)
            (folder / f'frame{i:03}.dng').symlink_to(source)
        if len(set(hashes)) != len(hashes):
            raise ValueError(f'duplicate input frame in {name}')
        scale = case['scale']
        # Match the app's normal worker limits, without changing tile/quality settings.
        workers = 3 if scale == 1 else 2
        plan = [('profile', variant, True) for variant in executables] if args.profile else []
        for rep in range(args.repeats):
            order = ('baseline', 'candidate') if rep % 2 == 0 else ('candidate', 'baseline')
            plan += [(f'run{rep}', variant, False) for variant in order]
        for run, variant, profile in plan:
            dest = args.output / name / variant / run
            dest.mkdir(parents=True)
            output = dest / 'output.dng'
            command = [str(executables[variant]), str(folder), str(output), '--s', str(scale),
                       '--frames', str(len(hashes)), '--tile', '512', '--overlap', '32',
                       '--workers', str(workers), '--iterations', '8', '--prior', '0',
                       '--guide', '0', '--profile', str(int(profile))]
            started = time.monotonic()
            with (dest / 'run.log').open('w') as log:
                result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
            row = dict(case=name, variant=variant, run=run, profile=profile,
                       command=command, exit=result.returncode, wall_s=time.monotonic()-started)
            text = (dest / 'run.log').read_text()
            (dest / 'run.json').write_text(json.dumps(row, indent=2))
            if result.returncode:
                raise RuntimeError(f'{variant}/{name}/{run} failed: {text[-2000:]}')
            geometry = re.search(r'-> (\d+)x(\d+) s=', text)
            expected = (case['width']*scale, case['height']*scale)
            if not geometry or tuple(map(int, geometry.groups())) != expected:
                raise ValueError(f'unexpected output dimensions for {name}')
            floats = Path(str(output)+'.f32')
            if floats.stat().st_size != expected[0]*expected[1]*12:
                raise ValueError('unexpected float output length')
            row['f32_sha256'] = fingerprint(floats)
            row['merge_ms'] = float(re.search(r'TOTAL\(merge\) (\d+)', text)[1])
            row['gpu_host'] = next((s for s in text.splitlines() if s.startswith('GPU host totals:')), '')
            row['gpu_work'] = next((s for s in text.splitlines() if s.startswith('GPU work totals:')), '')
            row['shader_profile'] = next((s for s in text.splitlines() if s.startswith('gpu profile:')), '')
            # Keep one full reconstruction per variant, plus every log/hash.
            if run != 'run0':
                floats.unlink()
                output.unlink()
            (dest / 'run.json').write_text(json.dumps(row, indent=2))
            rows.append(row)
            (args.output / 'results.json').write_text(json.dumps(rows, indent=2))
            print(f'{name} {variant} {run}: merge={row["merge_ms"]:.0f}ms '
                  f'wall={row["wall_s"]:.2f}s sha256={row["f32_sha256"]}', flush=True)
    summary = {}
    for name in manifest:
        summary[name] = {}
        for variant in executables:
            values = [r['merge_ms'] for r in rows
                      if r['case']==name and r['variant']==variant and not r['profile']]
            summary[name][variant] = dict(runs_ms=values, median_ms=statistics.median(values))
        summary[name]['speedup'] = summary[name]['baseline']['median_ms'] / summary[name]['candidate']['median_ms']
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    main()
