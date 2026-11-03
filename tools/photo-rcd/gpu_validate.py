#!/usr/bin/env python3
"""Host Vulkan numerical validation only. Never connects to Android."""
from array import array
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess
from build_oracle import ROOT, build

def floats(path):
    a=array('f');a.frombytes(path.read_bytes());return a

def metrics(a,b):
    assert len(a)==len(b) and a
    assert all(math.isfinite(x) for x in a) and all(math.isfinite(x) for x in b)
    errors=[abs(x-y) for x,y in zip(a,b)]
    rel=[abs(x-y)/abs(x) for x,y in zip(a,b) if abs(x)>=1e-5]
    def ordered(x):
        v=struct.unpack('<i',struct.pack('<f',x))[0]
        return 0x80000000-v if v<0 else v
    ulps=[abs(ordered(x)-ordered(y)) for x,y in zip(a,b) if abs(x)>=1e-5 and abs(y)>=1e-5 and (x<0)==(y<0)]
    return {'max_abs':max(errors),'mean_abs':sum(errors)/len(errors),'max_relative_above_1e-5':max(rel,default=0),'max_ulp_same_sign_above_1e-5':max(ulps,default=0),'samples':len(a)}

def run(work,only=None):
    work.mkdir(parents=True,exist_ok=True)
    oracle=build(work/'oracle')
    shader=work/'baseline.spv'; runner=work/'vulkan-host'
    commands=[['glslangValidator','-V','--target-env','vulkan1.1',str(ROOT/'baseline.comp'),'-o',str(shader)],
              ['spirv-val','--target-env','vulkan1.1',str(shader)],
              ['clang++','-std=c++17','-O2','-I/opt/homebrew/include',str(ROOT/'vulkan_host.cc'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(runner)]]
    for command in commands: subprocess.run(command,check=True)
    env=os.environ.copy();env.setdefault('VK_ICD_FILENAMES','/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json')
    env['MVK_CONFIG_FAST_MATH_ENABLED']='0'
    results=[]
    cases=[]
    for w,h in [(19,21),(41,38),(177,179),(194,195),(195,197),(353,37)]:
        for pattern in ['RGGB','GRBG','GBRG','BGGR']:
            for kind in ['zero','color','ramp','edge','diagonal','checker','chroma','impulse','noise','near-black','saturated']:
                state=0x12345678;data=array('f')
                for y in range(h):
                    for x in range(w):
                        state=(1664525*state+1013904223)&0xffffffff
                        c=pattern[(y&1)*2+(x&1)]
                        data.append({'zero':0.,'color':{'R':.25,'G':.5,'B':.75}[c], 'ramp':(x+3*y)/(w+3*h),'edge':.8 if x>=w//2 else .1,'diagonal':.9 if x>=y else .05,'checker':float((x+y)&1),'chroma':.75 if c==('R' if (x//4+y//4)&1 else 'B') else .125,'impulse':float(x==w//2 and y==h//2),'noise':(state>>8)/16777216.,'near-black':float(state&31)/65536.,'saturated':1.}[kind])
                cases.append((w,h,pattern,kind,data))
    real_manifest=ROOT/'fixtures/manifest.json'
    if real_manifest.exists():
        real=json.loads(real_manifest.read_text())
        for fixture in real['crops']:
            cases.append((*fixture['size'],fixture['cfa'],fixture['file'],floats(ROOT/'fixtures'/fixture['file'])))
    for w,h,pattern,kind,data in cases:
        if only and f'{w}x{h}:{pattern}:{kind}' not in only: continue
        source=work/'input.f32';source.write_bytes(data.tobytes())
        for f in work.glob('cpu-*.bin'): f.unlink()
        cpu=work/'cpu.f32'; gpu=work/'gpu.f32'
        subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),pattern,str(source),str(cpu),'normalized-rgb',str(work/'cpu-')],check=True,capture_output=True)
        command=[str(runner),str(w),str(h),pattern,str(source),str(shader),str(gpu),str(work/'gpu-')]
        r=subprocess.run(command,check=True,capture_output=True,text=True,env=env)
        gpu_bytes=gpu.read_bytes()
        if kind=='noise':
            subprocess.run(command,check=True,capture_output=True,text=True,env=env)
            assert gpu.read_bytes()==gpu_bytes,'same-device baseline nondeterminism'
        c=floats(cpu);g=floats(gpu)
        rgb=array('f',(v for i,v in enumerate(g) if i%4!=3))
        final=metrics(c,rgb)
        channels={ch:metrics(c[i::3],rgb[i::3]) for i,ch in enumerate('RGB')}
        inner=[];border=[]
        for y in range(h):
            for x in range(w):
                (inner if 9<=x<w-9 and 9<=y<h-9 else border).extend(range((y*w+x)*3,(y*w+x+1)*3))
        stage_indices=[]
        # Same owned-interior traversal as upstream tile snapshots.
        tile=0
        for rs in range(0,h,176):
            for cs in range(0,w,176):
                for y in range(rs+9,min(rs+194,h)-9):
                    for x in range(cs+9,min(cs+194,w)-9):stage_indices.append((tile*194*194+(y-rs)*194+x-cs,pattern[(y&1)*2+(x&1)]!='G'))
                tile+=1
        stages={}
        for name,gpu_name,rb_only in [('vh','vh',False),('lpf','lpf',True),('pq','pq',True),('green','green',False),('opposite_r','opposite',True)]:
            expected=floats(work/f'cpu-{name}.bin');actual=floats(work/f'gpu-{gpu_name}.bin')
            selected=array('f',(actual[i*(2 if gpu_name=='opposite' else 1)] for i,rb in stage_indices if not rb_only or rb))
            stages[name]=metrics(expected,selected)
        sample=max(abs(data[i]-rgb[i*3+'RGB'.index(pattern[((i//w)&1)*2+(i%w&1)])]) for i in range(w*h))
        results.append({'size':[w,h],'cfa':pattern,'fixture':kind,'input_sha256':hashlib.sha256(data.tobytes()).hexdigest(),'gpu_rgba_sha256':hashlib.sha256(gpu_bytes).hexdigest(),'exact_repeat_checked':kind=='noise','device':r.stdout.strip(),'final':final,'channels':channels,'interior':metrics([c[i] for i in inner],[rgb[i] for i in inner]),'border':metrics([c[i] for i in border],[rgb[i] for i in border]),'stages':stages,'native_sample_max_abs':sample,'initial_diagnostic_budget_pass':sample==0 and final['max_abs']<=2e-5 and final['mean_abs']<=2e-6 and all(stages[s]['max_abs']<=2e-5 for s in ['vh','pq'])})
    return {'scope':'Host Apple/MoltenVK only; not Android validation or delivered fps','commands':commands,'driver_environment':{k:env[k] for k in ['VK_ICD_FILENAMES','MVK_CONFIG_FAST_MATH_ENABLED']},'tool_versions':{tool:subprocess.check_output([tool,'--version'],text=True) for tool in ['clang++','glslangValidator','spirv-val']},'case_count':len(results),'all_initial_diagnostic_budgets_pass':all(r['initial_diagnostic_budget_pass'] for r in results),'shader_sha256':hashlib.sha256(shader.read_bytes()).hexdigest(),'source_sha256':hashlib.sha256((ROOT/'baseline.comp').read_bytes()).hexdigest(),'cases':results}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--only',action='append');a=p.parse_args()
    report=run(a.work.resolve(),a.only);a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(f"{report['case_count']} host GPU cases; initial budgets pass: {report['all_initial_diagnostic_budgets_pass']}")
    raise SystemExit(0 if report['all_initial_diagnostic_budgets_pass'] else 1)
