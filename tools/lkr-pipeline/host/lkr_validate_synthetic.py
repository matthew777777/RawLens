#!/usr/bin/env python3
"""Small, labeled physical-sampling tests of rejection, with real static texture.

The source/template DNG is read only. Only generated synthetic frames are written.
Uses the existing generator and scorer; scores are not evidence for an unlabeled
real object. Keeps all per-case commands and console output.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import numpy as np
import rawpy
import tifffile


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--before',type=Path,required=True)
    ap.add_argument('--after',type=Path,required=True)
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    template=a.out/'template.dng'
    if not template.exists():
        with rawpy.imread(str(a.source)) as raw:codes=raw.raw_image_visible[:1024,:1024].copy()
        omitted={256,257,258,259,262,273,277,278,279,284,330,34665,34853,50830}
        tags=[]
        with tifffile.TiffFile(a.source) as t:
            for tag in t.pages[0].tags.values():
                if tag.code in omitted:continue
                val=tag.value
                if tag.code==274:val=1
                if tag.code==50719:val=(0,0)
                if tag.code==50720:val=(1024,1024)
                if tag.code==50829:val=(0,0,1024,1024)
                tags.append((tag.code,tag.dtype,tag.count,val,False))
        tifffile.imwrite(template,codes,photometric=32803,metadata=None,extratags=tags)
        with rawpy.imread(str(template)) as raw:
            if raw.raw_image_visible.shape!=(1024,1024):raise ValueError('synthetic template crop metadata')
    here=Path(__file__).resolve().parent
    env=dict(os.environ,DYLD_FALLBACK_LIBRARY_PATH='/opt/homebrew/lib')
    import sys
    cases=[('plain',1,[]),('chroma-blur',2,['--person-chroma','--person-blur','3']),
           ('nonrigid',3,['--person-wobble','.2','--person-blur','3'])]
    results=[]
    for name,seed,extra in cases:
        burst=a.out/name
        if not (burst/'labels.npz').exists():
            cmd=[sys.executable,str(here/'lkr_synth_burst.py'),str(template),str(a.source),str(burst),
                 '--frames','16','--size','512','--at','256','256','--seed',str(seed),'--blur','.3']+extra
            with (a.out/(name+'-generate.log')).open('w') as log:
                subprocess.run(cmd,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
        for scale in (1,2):
            for mode,binary,flags in [('before',a.before,[]),('guide2',a.after,['--guide','2']),
                                      ('tau02',a.before,['--tau','.2']),
                                      ('no-reject-bound',a.after,['--rk','1000','--tk','0'])]:
                label=f'{name}-x{scale}-{mode}'
                log=a.out/(label+'.log')
                cmd=[sys.executable,str(here/'lkr_synth_score.py'),str(binary),str(burst),
                     '--s',str(scale),'--blur','.3','--tag',label,'--save',str(a.out/(label+'.png')),'--']+flags
                if not log.exists():
                    r=subprocess.run(cmd,env=env,capture_output=True,text=True)
                    log.write_text(r.stdout+r.stderr)
                    if r.returncode:raise RuntimeError(log.read_text())
                print(log.read_text().strip(),flush=True)
                results.append(dict(case=name,scale=scale,variant=mode,command=cmd,output=log.read_text()))
                (a.out/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
