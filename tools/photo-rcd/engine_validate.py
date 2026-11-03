#!/usr/bin/env python3
"""Compare the app's actual reusable native engine with saved GPU outputs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from build_oracle import ROOT
from experiment_validate import data_for,environment

def run(work,supplied_shader=None,specialize=False):
    work.mkdir(parents=True,exist_ok=True);cpp=ROOT.parent.parent/'app/src/main/cpp/photo_rcd'
    exe=work/'engine-host'
    command=['clang++','-std=c++17','-O2','-ffp-contract=off','-fno-fast-math','-I/opt/homebrew/include','-I'+str(cpp),str(ROOT/'engine_host.cc'),str(cpp/'photo_rcd_engine.cpp'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(exe)]
    subprocess.run(command,check=True)
    shader=supplied_shader
    if shader is None:
        shader=work/'baseline.spv';subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1',str(ROOT/'baseline.comp'),'-o',str(shader)],check=True)
    baseline=json.loads((ROOT.parent.parent/'docs/refactor/rcd-replacement/gpu-host-validation.json').read_text())
    jobs=[]
    for i,c in enumerate(baseline['cases']):
        data=data_for(c);assert hashlib.sha256(data).hexdigest()==c['input_sha256'];source=work/f'input-{i}.f32';source.write_bytes(data)
        phase=sum(('RGB'.index(ch)<<(q*2)) for q,ch in enumerate(c['cfa']))
        jobs.append(f"{c['size'][0]} {c['size'][1]} {phase} {source} {work/f'output-{i}.f32'}")
    jobfile=work/'jobs.txt';jobfile.write_text('\n'.join(jobs)+'\n')
    result=subprocess.run([str(exe),str(shader),str(jobfile)]+(['specialized'] if specialize else []),env=environment(),check=True,capture_output=True,text=True)
    cases=[]
    for i,c in enumerate(baseline['cases']):
        digest=hashlib.sha256((work/f'output-{i}.f32').read_bytes()).hexdigest();assert digest==c['gpu_rgba_sha256'],i
        cases.append({'index':i,'rgba_sha256':digest,'exact_match':True})
    return {'scope':'Actual app native engine exercised on host, no Android results','compile_command':command,'stdout':result.stdout,'case_count':len(cases),'all_exact':True,'cases':cases,'shader_sha256':hashlib.sha256(shader.read_bytes()).hexdigest(),'engine_source_sha256':hashlib.sha256((cpp/'photo_rcd_engine.cpp').read_bytes()).hexdigest()}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--shader',type=Path);p.add_argument('--specialize',action='store_true');a=p.parse_args();r=run(a.work.resolve(),a.shader.resolve() if a.shader else None,a.specialize);a.report.write_text(json.dumps(r,indent=2)+'\n');print(f"Shared engine: {r['case_count']} exact frames, persistent reuse/resize/phase changes and invalid-phase recovery passed")
