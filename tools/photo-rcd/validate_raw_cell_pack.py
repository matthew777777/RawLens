#!/usr/bin/env python3
"""Host Vulkan gate for requirements-derived integer packing and FP32 normalization."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
from requirements_layout import pack
from experiment_validate import environment

ROOT=Path(__file__).resolve().parent

def f32(value): return struct.unpack('<f',struct.pack('<f',value))[0]

def run(work):
    work.mkdir(parents=True,exist_ok=True);shader=work/'pack.spv';runner=work/'pack-host'
    commands=[['glslangValidator','-V','-Os','--target-env','vulkan1.1',str(ROOT/'raw_cell_pack.comp'),'-o',str(shader)],
        ['spirv-val','--target-env','vulkan1.1',str(shader)],
        ['clang++','-std=c++17','-O2','-I/opt/homebrew/include',str(ROOT/'raw_cell_pack_host.cc'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(runner)]]
    for command in commands:subprocess.run(command,check=True)
    rows=[];maximum=0.0
    for w,h in [(1,1),(1,7),(7,1),(3,5),(19,21),(38,41),(193,195)]:
        for left,top in [(0,0),(1,0),(0,1),(1,1)]:
            for pixel in [2,3,4]:
                sw,sh=w+left+2,h+top+2;pitch=(sw-1)*pixel+2+7
                # Last row is deliberately truncated to the last actual sample.
                raw=bytearray([0xa5]*((sh-1)*pitch+(sw-1)*pixel+2))
                for y in range(sh):
                    for x in range(sw):struct.pack_into('<H',raw,y*pitch+x*pixel,(x*137+y*991+17)&65535)
                black=[64.0,64.5,63.25,65.0];white=4095.0
                params=struct.pack('<8i4ff3i',sw,sh,pitch,pixel,left,top,w,h,*black,white,0,0,0)
                (work/'params.bin').write_bytes(params);(work/'raw.bin').write_bytes(raw)
                command=[str(runner),str(work/'params.bin'),str(work/'raw.bin'),str(shader),str(work/'packed.bin'),str(work/'normalized.bin'),'1']
                device=subprocess.run(command,env=environment(),check=True,capture_output=True,text=True).stdout.strip()
                packed=(work/'packed.bin').read_bytes();assert packed==pack(raw,sw,sh,pitch,pixel,left,top,w,h)
                actual=struct.unpack('<'+'f'*(len(packed)//2),(work/'normalized.bin').read_bytes());expected=[]
                for cy in range((h+1)//2):
                    for cx in range((w+1)//2):
                        for lane in range(4):
                            x=cx*2+lane%2;y=cy*2+lane//2
                            if x>=w or y>=h:expected.append(0.0);continue
                            code=struct.unpack_from('<H',raw,(top+y)*pitch+(left+x)*pixel)[0]
                            b=black[((top+y)&1)*2+((left+x)&1)]
                            expected.append(max(0.0,min(1.0,f32(f32(code-b)/f32(white-b)))))
                error=max(abs(a-b) for a,b in zip(actual,expected));maximum=max(maximum,error)
                assert error<=2e-7, (w,h,left,top,pixel,error)
                # Production specialization must leave the diagnostic FP32 output untouched.
                command[-1]='0';subprocess.run(command,env=environment(),check=True,capture_output=True)
                assert (work/'packed.bin').read_bytes()==packed
                assert (work/'normalized.bin').read_bytes()==bytes([255])*len(actual)*4
                rows.append({'size':[w,h],'crop_origin':[left,top],'pixel_stride':pixel,'row_stride':pitch,
                    'packed_exact':True,'normalization_max_abs':error,'production_probe_untouched':True,
                    'input_sha256':hashlib.sha256(raw).hexdigest(),'packed_sha256':hashlib.sha256(packed).hexdigest()})
    invalid=[]
    params=list(struct.unpack('<8i4ff3i',(work/'params.bin').read_bytes()))
    for label,index,value in [('negative-origin',4,-1),('short-row',2,1),('bad-pixel-stride',3,1),('zero-width',6,0),('nan-white',12,float('nan')),('black-equals-white',8,4095.0)]:
        bad=params.copy();bad[index]=value;(work/'bad-params.bin').write_bytes(struct.pack('<8i4ff3i',*bad))
        command=[str(runner),str(work/'bad-params.bin'),str(work/'raw.bin'),str(shader),str(work/'packed.bin'),str(work/'normalized.bin'),'1']
        result=subprocess.run(command,env=environment(),capture_output=True,text=True)
        assert result.returncode==2,(label,result.stdout,result.stderr)
        invalid.append({'case':label,'rejected':True,'reason':result.stderr.strip()})
    return {'scope':'Host Vulkan pack/normalize stage only; no Android import, RCD interpolation, recording or mobile-performance result',
        'device':device,'commands':commands,'case_count':len(rows),'dispatches':2*len(rows),
        'integer_packing_exact':True,'normalization_budget_abs':2e-7,'normalization_max_abs':maximum,
        'all_passed':True,'cases':rows,'invalid_inputs':invalid,
        'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'raw_cell_pack.comp',ROOT/'raw_cell_pack_host.cc',ROOT/'requirements_layout.py',ROOT/'validate_raw_cell_pack.py']},
        'spirv_sha256':hashlib.sha256(shader.read_bytes()).hexdigest()}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    report=run(a.work.resolve());a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(f"PASS: {report['case_count']} host GPU cases, production/probe specializations, max normalization error {report['normalization_max_abs']}")
