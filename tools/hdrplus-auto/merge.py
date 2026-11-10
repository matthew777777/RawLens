#!/usr/bin/env python3
"""Run the app's native merge on selector plans and fixed HQ controls."""
import argparse
import subprocess
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('fixtures',type=Path)
p.add_argument('--runner',type=Path,default=Path('build/hdrplus-burst/hdrplus_burst'))
p.add_argument('--assets',type=Path,default=Path('app/src/main/assets'))
a=p.parse_args()
for folder in sorted(a.fixtures.iterdir()):
    if not (folder/'decision.txt').exists(): continue
    plan=dict(line.split('=',1) for line in (folder/'decision.txt').read_text().splitlines())
    rows=[line.split('\t') for line in (folder/'frames.tsv').read_text().splitlines()]
    files=[rows[int(i)][0] for i in plan['kept'].split(',')]
    for mode in ['auto','auto-hq','hq3','hq6']:
        out=folder/f'{mode}.dng'
        cmd=[str(a.runner),str(a.assets),str(out),'--ref',plan['ref']]
        if mode!='auto' or plan['hq']=='true': cmd+=['--hq']
        if mode.startswith('auto'):
            cmd+=['--strength',plan['strength'],'--frame-strengths',plan['frameStrengths'],
                  '--strength-maps',str(folder/'strengths.bin')]
        else: cmd+=['--strength',mode[2:]]
        with (folder/f'{mode}.log').open('w') as log:
            subprocess.run(cmd+files,stdout=log,stderr=subprocess.STDOUT,check=True)
        print(folder.name,mode,flush=True)
