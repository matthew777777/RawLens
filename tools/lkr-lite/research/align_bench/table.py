# SPDX-License-Identifier: Apache-2.0
"""Per-category table: table.py RESULT.json --scale 1 --metrics psnr_all epe_texture.mean ... --variants base ..."""
import argparse, json
from collections import defaultdict
import numpy as np
ap = argparse.ArgumentParser(); ap.add_argument('res'); ap.add_argument('--scale', default='1'); ap.add_argument('--variants', nargs='+', required=True)
ap.add_argument('--metrics', nargs='+', default=['epe_texture.mean', 'epe_texture.p95', 'psnr_all', 'detail_texture'])
ap.add_argument('--csv', action='store_true')
a = ap.parse_args(); R = json.load(open(a.res)); sep = ',' if a.csv else ' '
def get(r, m):
    for p in m.split('.'):
        r = r.get(p) if isinstance(r, dict) else None
        if r is None: return np.nan
    return r
for m in a.metrics:
    T = defaultdict(lambda: defaultdict(list))
    for k, r in R.items():
        sc, s, v = k.split('|')
        if s == a.scale: T[sc.rsplit('_', 1)[0]][v].append(get(r, m))
    print(f'## {m}  (x{a.scale})'); print(sep.join(['%-13s' % 'category'] + ['%9s' % v for v in a.variants]))
    allv = defaultdict(list)
    for c in sorted(T):
        row = [np.mean(T[c][v]) if T[c][v] else np.nan for v in a.variants]
        for v, x in zip(a.variants, row): allv[v].append(x)
        print(sep.join(['%-13s' % c] + ['%9.4f' % x for x in row]))
    print(sep.join(['%-13s' % 'MEAN'] + ['%9.4f' % np.nanmean(allv[v]) for v in a.variants]))
