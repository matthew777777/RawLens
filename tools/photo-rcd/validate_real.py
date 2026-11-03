#!/usr/bin/env python3
"""Frozen CC0 RAW crops through the scalar oracle at three compiler modes."""
from array import array
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from build_oracle import ROOT,build

def run(work):
    fixtures=json.loads((ROOT/'fixtures/manifest.json').read_text())
    executables=[build(work/'O0','-O0'),build(work/'O2'),build(work/'asan','-O1',True)]
    results=[]
    for fixture in fixtures['crops']:
        path=ROOT/'fixtures'/fixture['file'];data=path.read_bytes()
        assert hashlib.sha256(data).hexdigest()==fixture['sha256']
        w,h=fixture['size'];outputs=[];stages=[]
        for v,exe in enumerate(executables):
            output=work/f'output-{v}.f32';prefix=work/f'stage-{v}-'
            for f in work.glob(prefix.name+'*.bin'):f.unlink()
            subprocess.run([str(exe),str(w),str(h),str(w),'0','0',str(w),str(h),fixture['cfa'],str(path),str(output),'normalized-rgb',str(prefix)],check=True)
            outputs.append(output.read_bytes());stages.append({f.name[len(prefix.name):]:hashlib.sha256(f.read_bytes()).hexdigest() for f in work.glob(prefix.name+'*.bin')})
        assert outputs[0]==outputs[1]==outputs[2] and stages[0]==stages[1]==stages[2]
        results.append({'fixture':fixture,'rgb_sha256':hashlib.sha256(outputs[0]).hexdigest(),'stages':stages[0],'O0_O2_ASan_UBSan_exact':True})
    return {'scope':'Host oracle self-check on licensed real CFA crops','cases':results,'fixture_manifest_sha256':hashlib.sha256((ROOT/'fixtures/manifest.json').read_bytes()).hexdigest()}

if __name__=='__main__':
    work=Path(sys.argv[1]).resolve();report=Path(sys.argv[2]);r=run(work);report.write_text(json.dumps(r,indent=2)+'\n');print('PASS: 3 real CFA crops, exact output and stage hashes at O0/O2/ASan+UBSan')
