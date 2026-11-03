#!/usr/bin/env python3
"""Exact optimization checks against the saved same-device GPU baseline corpus."""
from array import array
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from build_oracle import ROOT
from build_oracle import build as build_oracle
from build_variants import build

def data_for(case):
    w,h=case['size'];pattern=case['cfa'];kind=case['fixture']
    if kind.endswith('.f32'): return (ROOT/'fixtures'/kind).read_bytes()
    state=0x12345678;data=array('f')
    for y in range(h):
        for x in range(w):
            state=(1664525*state+1013904223)&0xffffffff;c=pattern[(y&1)*2+(x&1)]
            data.append({'zero':0.,'color':{'R':.25,'G':.5,'B':.75}[c], 'ramp':(x+3*y)/(w+3*h),'edge':.8 if x>=w//2 else .1,'diagonal':.9 if x>=y else .05,'checker':float((x+y)&1),'chroma':.75 if c==('R' if (x//4+y//4)&1 else 'B') else .125,'impulse':float(x==w//2 and y==h//2),'noise':(state>>8)/16777216.,'near-black':float(state&31)/65536.,'saturated':1.}[kind])
    return data.tobytes()

def compile_runner(work):
    exe=work/'experiment-host'
    subprocess.run(['clang++','-std=c++17','-O2','-I/opt/homebrew/include',str(ROOT/'experiment_host.cc'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(exe)],check=True)
    return exe

def environment():
    env=os.environ.copy();env.setdefault('VK_ICD_FILENAMES','/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json');env['MVK_CONFIG_FAST_MATH_ENABLED']='0';return env

def run(work,quick=False,selected=None,stages=False):
    work.mkdir(parents=True,exist_ok=True);variants=build(work/'shaders');runner=compile_runner(work)
    baseline_path=ROOT.parent.parent/'docs/refactor/rcd-replacement/gpu-host-validation.json'
    baseline=json.loads(baseline_path.read_text())
    assert baseline['all_initial_diagnostic_budgets_pass']
    assert baseline['source_sha256']==hashlib.sha256((ROOT/'baseline.comp').read_bytes()).hexdigest()
    oracle=build_oracle(work/'oracle') if stages else None
    results=[]
    for index,case in enumerate(baseline['cases']):
        if quick and (case['fixture'] not in ['noise','ricoh-gr-iii-0.f32','ricoh-gr-iii-1.f32','ricoh-gr-iii-2.f32'] or case['size'] not in [[19,21],[194,195],[97,95]]):continue
        data=data_for(case);assert hashlib.sha256(data).hexdigest()==case['input_sha256']
        source=work/'input.f32';source.write_bytes(data);w,h=case['size']
        owned=[]
        if stages:
            for path in work.glob('oracle-stage-*.bin'):path.unlink()
            subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),case['cfa'],str(source),str(work/'oracle-rgb.f32'),'normalized-rgb',str(work/'oracle-stage-')],check=True,capture_output=True)
            tile=0
            for rs in range(0,h,176):
                for cs in range(0,w,176):
                    for y in range(rs+9,min(rs+194,h)-9):
                        for x in range(cs+9,min(cs+194,w)-9):owned.append((tile*194*194+(y-rs)*194+x-cs,case['cfa'][(y&1)*2+(x&1)]!='G'))
                    tile+=1
        for variant in selected or ['packed','tiled-capture','tiled-packed-capture','cached','cached-packed','tiled-cached-capture']:
            output=work/'output.f32';prefix=work/'stage-'
            cmd=[str(runner),str(w),str(h),case['cfa'],str(source),str(work/'shaders'/(variant+'.spv')),str(output),str(prefix),variant,'1']
            r=subprocess.run(cmd,check=True,capture_output=True,text=True,env=environment())
            actual=hashlib.sha256(output.read_bytes()).hexdigest()
            passed=actual==case['gpu_rgba_sha256']
            record={'size':[w,h],'cfa':case['cfa'],'fixture':case['fixture'],'variant':variant,'exact_rgba_match':passed,'rgba_sha256':actual,'device':r.stdout.strip()}
            if stages:
                checks={}
                for name,gpu_name,rb_only in [('vh','vh',False),('lpf','lpf',True),('pq','pq',True),('green','green',False),('opposite_r','opposite',True)]:
                    values=array('f');values.frombytes((work/f'stage-{gpu_name}.bin').read_bytes())
                    selected_values=array('f',(values[i*(2 if gpu_name=='opposite' else 1)] for i,rb in owned if not rb_only or rb))
                    checks[name]=selected_values.tobytes()==(work/f'oracle-stage-{name}.bin').read_bytes()
                record['stage_bytes_exact']=checks
                assert all(checks.values()),record
            results.append(record)
            if not passed:
                # Keep an inspectable failure, never silently update expected pixels.
                failure=work/f'failed-{index}-{variant}.f32';failure.write_bytes(output.read_bytes())
                print('MISMATCH',record,flush=True)
        if index%44==0:print(f'checked corpus index {index}',flush=True)
    return {'scope':'Exact layout/scheduling equivalence on host GPU; no Android fps claim','baseline_report_sha256':hashlib.sha256(baseline_path.read_bytes()).hexdigest(),'variants':variants,'case_count':len(results),'all_exact':all(r['exact_rgba_match'] for r in results),'cases':results}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--quick',action='store_true');p.add_argument('--variant',action='append');p.add_argument('--stages',action='store_true');a=p.parse_args()
    r=run(a.work.resolve(),a.quick,a.variant,a.stages);a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(r,indent=2)+'\n');print(f"Exact optimization comparisons: {r['case_count']}, pass={r['all_exact']}");raise SystemExit(0 if r['all_exact'] else 1)
