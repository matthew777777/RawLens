#!/usr/bin/env python3
"""Reproducible M1 host contract checks; requires Python stdlib and clang++."""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
from build_oracle import build, ROOT

def sha(data):
    return hashlib.sha256(data).hexdigest()

def run(work):
    if sys.byteorder != 'little':
        raise RuntimeError('This binary adapter currently requires a little-endian host')
    executables = [build(work/'O0','-O0'), build(work/'O2'), build(work/'asan','-O1',True)]
    cases=[]
    patterns=['RGGB','GRBG','GBRG','BGGR']
    # Sizes cross upstream 176-pixel tile boundaries, including very short tail tiles.
    sizes=[(19,19),(20,21),(41,38),(177,179),(194,195),(353,37)]
    kinds=['zero','flat','color','ramp','edge','diagonal','checker','chroma','impulse','noise','near-black','saturated']
    work.mkdir(parents=True,exist_ok=True)
    for index, (w,h) in enumerate(sizes):
        for pattern in patterns:
            for left,top in [(0,0),(1,0),(0,1),(1,1)]:
                sw,sh=w+left+2,h+top+2
                stride=sw+3
                for kind in kinds:
                    values=array('f')
                    state=0x12345678
                    for y in range(sh):
                        for x in range(stride):
                            c=pattern[(y&1)*2+(x&1)]
                            state=(1664525*state+1013904223)&0xffffffff
                            v={'zero':0.,'flat':0.25,'color':{'R':0.25,'G':0.5,'B':0.75}[c],
                               'ramp':(x+3*y)/(stride+3*sh), 'edge':0.8 if x>=sw//2 else 0.1,
                               'diagonal':0.9 if x>=y else 0.05,'checker':float((x+y)&1),
                               'chroma':0.75 if c==('R' if (x//4+y//4)&1 else 'B') else 0.125,
                               'impulse':float(x==left+w//2 and y==top+h//2),
                               'noise':(state>>8)/16777216.,'near-black':float(state&31)/65536.,
                               'saturated':1.}[kind]
                            values.append(v)
                    data=values.tobytes()
                    input_file=work/'input.f32'; input_file.write_bytes(data)
                    outputs=[]; stages=[]
                    for variant,exe in enumerate(executables):
                        prefix=work/f'stage-{variant}-'
                        for f in work.glob(prefix.name+'*.bin'): f.unlink()
                        output=work/f'output-{variant}.f32'
                        command=[str(exe),str(sw),str(sh),str(stride),str(left),str(top),str(w),str(h),pattern,str(input_file),str(output),'normalized-rgb',str(prefix)]
                        subprocess.run(command,check=True,capture_output=True)
                        outputs.append(output.read_bytes())
                        stages.append({f.name[len(prefix.name):]:sha(f.read_bytes()) for f in sorted(work.glob(prefix.name+'*.bin'))})
                    assert outputs[0]==outputs[1]==outputs[2],(w,h,pattern,left,top,kind,'optimization drift')
                    assert stages[0]==stages[1]==stages[2],(w,h,pattern,left,top,kind,'stage drift')
                    result=array('f');result.frombytes(outputs[0])
                    assert len(result)==w*h*3 and all(math.isfinite(v) and v>=0 for v in result)
                    sample_error=0.
                    interior_error=0.
                    for y in range(h):
                        for x in range(w):
                            c=pattern[((y+top)&1)*2+((x+left)&1)]
                            channel='RGB'.index(c)
                            sample_error=max(sample_error,abs(result[(y*w+x)*3+channel]-values[(y+top)*stride+x+left]))
                            if kind in ('zero','flat','color','saturated') and 9<=x<w-9 and 9<=y<h-9:
                                expected=[{'R':.25,'G':.5,'B':.75}[c] for c in 'RGB'] if kind=='color' else [values[(y+top)*stride+x+left]]*3
                                interior_error=max(interior_error,*(abs(result[(y*w+x)*3+c]-expected[c]) for c in range(3)))
                    assert sample_error==0.,sample_error
                    # eps in the upstream ratio gives a small flat-field bias: 1e-5 envelope fixed before this run.
                    assert interior_error<=1e-5,interior_error
                    cases.append({'size':[w,h],'sensor_size':[sw,sh],'stride':stride,'origin':[left,top],'cfa':pattern,'fixture':kind,'input_sha256':sha(data),'rgb_sha256':sha(outputs[0]),'stages':stages[0],'sample_max_abs':sample_error,'flat_interior_max_abs':interior_error})
    # Required invalid inputs must fail, without producing an accepted result.
    base=[str(executables[2]),'24','24','27','0','0','24','24','RGGB',str(work/'invalid.f32'),str(work/'invalid-output.f32'),'normalized-rgb','-']
    good=array('f',[0.5]*(27*24)).tobytes()
    invalid=[]
    for name,arg,value,content in [('tiny',6,'1',good),('bad-stride',3,'23',good),('crop-overflow',4,'1',good),('bad-cfa',8,'RGBG',good),('truncated',None,None,good[:-4]),('extra-bytes',None,None,good+b'0000'),('nan',None,None,array('f',[float('nan')]*(27*24)).tobytes()),('above-white',None,None,array('f',[1.1]*(27*24)).tobytes()),('negative',None,None,array('f',[-0.1]*(27*24)).tobytes())]:
        args=base.copy()
        if arg is not None: args[arg]=value
        (work/'invalid.f32').write_bytes(content)
        r=subprocess.run(args,capture_output=True,text=True)
        assert r.returncode==2,(name,r.returncode,r.stderr)
        assert 'Sanitizer' not in r.stderr,r.stderr
        invalid.append({'name':name,'exit_code':r.returncode,'diagnostic':r.stderr.strip()})
    return {'scope':'M1 canonical upstream host oracle, not CPU/GPU equivalence or mobile performance','case_count':len(cases),'exact_O0_O2_ASan_output_and_stage_equality':True,'asan_ubsan':'passed','invalid':invalid,'cases':cases,'fixture_license':'New mathematical fixtures generated by RawLens/Codex, 2026-11-01; GPL-3.0-or-later. No disputed expected pixels.','real_raw_fixtures':'separate validate_real.py / real-host-validation.json report','builds':[json.loads((p.parent/'build.json').read_text()) for p in executables]}

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    report=run(args.work.resolve())
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(f"PASS: {report['case_count']} fixtures across O0/O2/ASan+UBSan; {len(report['invalid'])} invalid inputs rejected")
