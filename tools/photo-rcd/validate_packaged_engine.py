#!/usr/bin/env python3
"""Run the real native engine with shader bytes extracted from a verified APK."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from build_oracle import ROOT
from engine_validate import run

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    root=ROOT.parent.parent
    inspected=json.loads((root/'docs/refactor/rcd-replacement/package-validation.json').read_text())
    package=inspected['packages'][0];apk=Path(package['path'])
    assert hashlib.sha256(apk.read_bytes()).hexdigest()==package['sha256']
    work=a.work.resolve();work.mkdir(parents=True,exist_ok=True);reports={}
    with zipfile.ZipFile(apk) as z:
        for name in ['baseline','optimized']:
            shader=work/(name+'.spv');shader.write_bytes(z.read(f'assets/shaders/photo_rcd/{name}.spv'))
            reports[name]=run(work/name,shader,name=='optimized')
            print(f'Packaged {name}: {reports[name]["case_count"]} exact shared-engine frames',flush=True)
    a.report.write_text(json.dumps({'scope':'Actual APK shader bytes on host native engine, not Android execution','apk_sha256':package['sha256'],'results':reports},indent=2)+'\n')
