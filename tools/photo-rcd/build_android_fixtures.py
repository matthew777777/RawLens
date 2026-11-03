#!/usr/bin/env python3
"""Reproduce Android-test-only fixtures from the approved pinned scalar oracle."""
from pathlib import Path
import hashlib
import json
import subprocess
from build_oracle import ROOT,build
from experiment_validate import data_for

if __name__=='__main__':
    work=Path('/tmp/rawlens-photo-rcd-android-fixtures');oracle=build(work/'oracle')
    destination=ROOT.parent.parent/'app/src/androidTest/assets/photo_rcd_reference'
    destination.mkdir(parents=True,exist_ok=True)
    corpus=json.loads((ROOT.parent.parent/'docs/refactor/rcd-replacement/gpu-host-validation.json').read_text())
    records=[]
    for i,case in enumerate(corpus['cases']):
        if case['fixture'] not in ['noise','chroma','zero','ramp','near-black','ricoh-gr-iii-0.f32','ricoh-gr-iii-1.f32','ricoh-gr-iii-2.f32']:continue
        data=data_for(case);assert hashlib.sha256(data).hexdigest()==case['input_sha256']
        w,h=case['size'];source=destination/f'{i}-cfa.f32';output=destination/f'{i}-rgb.f32';source.write_bytes(data)
        subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),case['cfa'],str(source),str(output),'normalized-rgb','-'],check=True)
        records.append({'width':w,'height':h,'cfa':case['cfa'],'kind':case['fixture'],'input':source.name,'expected':output.name,'input_sha256':hashlib.sha256(data).hexdigest(),'expected_sha256':hashlib.sha256(output.read_bytes()).hexdigest()})
    (destination/'manifest.json').write_text(json.dumps({'source':'RawTherapee b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c scalar oracle; not Kotlin/disputed backend','synthetic_license':'GPL-3.0-or-later; real RAW crops CC0 per tools/photo-rcd/fixtures/manifest.json','cases':records},indent=2)+'\n')
    print(f'Generated {len(records)} Android scalar-oracle cases')
