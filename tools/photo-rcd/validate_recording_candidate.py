#!/usr/bin/env python3
"""Diagnose the photo recording candidate against the pinned public oracle.

Differences are reported, never used to relax the existing numerical gate.
"""
import argparse
from array import array
import hashlib
import json
from pathlib import Path
import subprocess
from build_oracle import ROOT, build
from experiment_validate import data_for, environment
from prepare_recording_candidate import canonical_engine_source as engine_source, canonical_image_source, cached_engine_source, baked_stage_source, packed_engine_source, direct_raw_engine_source
from gpu_validate import floats, metrics


def run(work,images=False,cached=False,baked=False,packed=False,direct_raw=False):
    packed=packed or direct_raw
    cached=cached or baked
    work.mkdir(parents=True, exist_ok=True)
    cpp=ROOT.parent.parent/'app/src/main/cpp/photo_rcd'
    shader=work/'candidate.comp'; shader.write_text(direct_raw_engine_source() if direct_raw else packed_engine_source() if packed else cached_engine_source() if cached else canonical_image_source() if images else engine_source())
    binary=work/'candidate.spv';exe=work/'engine-host'
    commands=[['glslangValidator','-V','--target-env','vulkan1.1',str(shader),'-o',str(binary)],
        ['spirv-val','--target-env','vulkan1.1',str(binary)],
        ['clang++','-std=c++17','-O2','-ffp-contract=off','-fno-fast-math','-I/opt/homebrew/include','-I'+str(cpp),str(ROOT/'engine_host.cc'),str(cpp/'photo_rcd_engine.cpp'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(exe)]]
    if baked:
        for stage in (0,1,2,3,4,6,7):
            source=work/f'stage-{stage}.comp';source.write_text(baked_stage_source(stage))
            commands.extend([['glslangValidator','-V','-Os','--target-env','vulkan1.1',str(source),'-o',str(source.with_suffix('.spv'))],['spirv-val','--target-env','vulkan1.1',str(source.with_suffix('.spv'))]])
    for cmd in commands:subprocess.run(cmd,check=True)
    cases=json.loads((ROOT.parent.parent/'docs/refactor/rcd-replacement/gpu-host-validation.json').read_text())['cases']
    jobs=[]
    for i,c in enumerate(cases):
        data=data_for(c);assert hashlib.sha256(data).hexdigest()==c['input_sha256']
        source=work/f'input-{i}.f32';source.write_bytes(data)
        phase=sum('RGB'.index(ch)<<(q*2) for q,ch in enumerate(c['cfa']))
        jobs.append(f"{c['size'][0]} {c['size'][1]} {phase} {source} {work/f'output-{i}.f32'}")
    jobfile=work/'jobs.txt';jobfile.write_text('\n'.join(jobs)+'\n')
    result=subprocess.run([str(exe),str(binary),str(jobfile),'candidate-packed' if packed else 'candidate-baked' if baked else 'candidate-cached' if cached else 'candidate-images' if images else 'candidate'],check=True,env=environment(),capture_output=True,text=True)
    oracle=build(work/'oracle');records=[]
    for i,c in enumerate(cases):
        w,h=c['size'];cpu=work/'cpu.f32'
        subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),c['cfa'],str(work/f'input-{i}.f32'),str(cpu),'normalized-rgb',str(work/'stage-')],check=True,capture_output=True)
        expected=floats(cpu);rgba=floats(work/f'output-{i}.f32');actual=array('f',(v for k,v in enumerate(rgba) if k%4!=3))
        record={k:c[k] for k in ('size','cfa','fixture','input_sha256')}
        for name,interior in [('interior',True),('border',False)]:
            indices=[(y*w+x)*3+ch for y in range(h) for x in range(w) if (9<=x<w-9 and 9<=y<h-9)==interior for ch in range(3)]
            record[name]=metrics([expected[k] for k in indices],[actual[k] for k in indices]) if indices else None
        record['budget_pass']=all(record[n] is None or (record[n]['max_abs']<=2e-5 and record[n]['mean_abs']<=2e-6) for n in ('interior','border'))
        record['output_sha256']=hashlib.sha256((work/f'output-{i}.f32').read_bytes()).hexdigest()
        records.append(record)
    return {'scope':'FP32 race-free shared-CFA photo candidate with pinned tile/border adapters on host Vulkan; not recording acceptance',
        'commands':commands,'runner':result.stdout,'case_count':len(records),'cases':records,
        'shader_sha256':hashlib.sha256(shader.read_bytes()).hexdigest(),
        'stage_programs':{str(stage):{'source_sha256':hashlib.sha256((work/f'stage-{stage}.comp').read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256((work/f'stage-{stage}.spv').read_bytes()).hexdigest()} for stage in (0,1,2,3,4,6,7)} if baked else {},
        'max_interior_abs':max(r['interior']['max_abs'] for r in records if r['interior']),
        'max_border_abs':max(r['border']['max_abs'] for r in records if r['border']),
        'all_existing_budgets_pass':all(r['budget_pass'] for r in records),
        'promotion':'host diagnostic only; Android/product/cadence gates separate'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--images',action='store_true');p.add_argument('--cached',action='store_true');p.add_argument('--baked',action='store_true');p.add_argument('--packed',action='store_true');p.add_argument('--direct-raw',action='store_true');a=p.parse_args()
    report=run(a.work.resolve(),a.images,a.cached,a.baked,a.packed,a.direct_raw);a.report.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ('cases','commands')},indent=2))

    if not report["all_existing_budgets_pass"]:
        raise SystemExit(1)
