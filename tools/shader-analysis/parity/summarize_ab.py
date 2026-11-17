#!/usr/bin/env python3
"""Summarize MhcHighlightDeviceTest A/B logcat lines (one per size x variant x round).

Usage: python3 tools/shader-analysis/parity/summarize_ab.py LOG [--bundled-label NEW] [--candidate-label HEAD]
The bundled asset is the working-tree shader; `candidate` is the -e abSpv file.
"""
import re, statistics, sys
from collections import defaultdict
labels = {'candidate': 'HEAD (abSpv)'}
args = sys.argv[1:]
log = args[0]
rows = defaultdict(lambda: defaultdict(list))
pat = re.compile(r'(\d+x\d+)@\d+ variant=(\S+) frames=\d+ mean=([\d.]+) p50=([\d.]+) p95=([\d.]+) p99=([\d.]+) max=([\d.]+) budget=([\d.]+) over=(\d+)')
for line in open(log):
    m = pat.search(line)
    if not m: continue
    size, var = m.group(1), m.group(2)
    g = re.search(r'gpuGreenP50=([\d.]+) gpuMainP50=([\d.]+) gpuTotalP95=([\d.]+)', line)
    rows[size][var].append(tuple(float(x) for x in m.groups()[2:]) + (tuple(float(x) for x in g.groups()) if g else (float('nan'),) * 3))
print('| size | variant | rounds | wall p50 | wall p95 | wall p99 | over budget | GPU green p50 | GPU main p50 | GPU total p95 |')
print('|---|---|---:|---|---|---|---:|---|---|---|')
for size in rows:
    for var, rs in rows[size].items():
        def col(i): v = [r[i] for r in rs]; return f'{statistics.median(v):.1f} ({min(v):.1f}-{max(v):.1f})'
        name = labels.get(var, var + ' (new)')
        print(f'| {size} | {name} | {len(rs)} | {col(1)} | {col(2)} | {col(3)} | {int(sum(r[6] for r in rs))} | {col(7)} | {col(8)} | {col(9)} |')
