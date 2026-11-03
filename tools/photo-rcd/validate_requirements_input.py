#!/usr/bin/env python3
"""Validate GPU-only RAW packing → pinned RCD reconstruction, host Vulkan only."""
import argparse
from array import array
import hashlib
import json
from pathlib import Path
import struct
import subprocess
from build_oracle import build
from experiment_validate import environment
from gpu_validate import floats, metrics
from requirements_layout import pack
from requirements_input_shaders import ROOT,packing_source,reconstruction_source
from validate_raw_cell_pack import f32


def run(work):
    work.mkdir(parents=True,exist_ok=True)
    commands=[]
    for name,text in [('pack',packing_source()),('rcd',reconstruction_source())]:
        source=work/(name+'.comp');source.write_text(text)
        commands.extend([['glslangValidator','-V','--target-env','vulkan1.1',str(source),'-o',str(source.with_suffix('.spv'))],['spirv-val','--target-env','vulkan1.1',str(source.with_suffix('.spv'))]])
    runner=work/'input-host'
    commands.append(['clang++','-std=c++17','-O2','-I/opt/homebrew/include',str(ROOT/'requirements_input_host.cc'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(runner)])
    for command in commands:subprocess.run(command,check=True)
    oracle=build(work/'oracle');cases=[];job=0
    for w,h in [(19,21),(41,38),(194,195),(195,197)]:
        for left,top in [(0,0),(1,0),(0,1),(1,1)]:
            for pattern in ['RGGB','GRBG','GBRG','BGGR']:
                pixel=[2,3,4][job%3];job+=1;sw=w+left+2;sh=h+top+2;pitch=(sw-1)*pixel+9
                raw=bytearray([0xa5]*((sh-1)*pitch+(sw-1)*pixel+2))
                for y in range(sh):
                    for x in range(sw):struct.pack_into('<H',raw,y*pitch+x*pixel,(x*137+y*991+17)%5000)
                black=[64.,64.5,63.25,65.];white=4095.
                cases.append((w,h,left,top,pattern,pixel,sw,sh,pitch,raw,black,white,'synthetic'))
    manifest=json.loads((ROOT/'fixtures/manifest.json').read_text())
    for fixture in manifest['crops']:
        w,h=fixture['size'];values=floats(ROOT/'fixtures'/fixture['file']);pixel=2;pitch=w*2+6
        raw=bytearray((h-1)*pitch+w*2)
        for y in range(h):
            for x in range(w):struct.pack_into('<H',raw,y*pitch+x*2,round(64+values[y*w+x]*(16314-64)))
        cases.append((w,h,0,0,fixture['cfa'],2,w,h,pitch,raw,[64.]*4,16314.,fixture['file']))
    results=[]
    for w,h,left,top,pattern,pixel,sw,sh,pitch,raw,black,white,fixture in cases:
        params=struct.pack('<8i4ff3i',sw,sh,pitch,pixel,left,top,w,h,*black,white,0,0,0)
        (work/'params.bin').write_bytes(params);(work/'raw.bin').write_bytes(raw)
        local=''.join(pattern[((top+q//2)&1)*2+((left+q%2)&1)] for q in range(4))
        command=[str(runner),str(w),str(h),local,str(work/'params.bin'),str(work/'raw.bin'),str(work/'rcd.spv'),str(work/'pack.spv'),str(work/'gpu.rgba'),str(work/'gpu.cells')]
        device=subprocess.run(command,check=True,env=environment(),capture_output=True,text=True).stdout.strip()
        expected_pack=pack(raw,sw,sh,pitch,pixel,left,top,w,h)
        assert (work/'gpu.cells').read_bytes()==expected_pack
        normalized=array('f')
        for y in range(h):
            for x in range(w):
                code=struct.unpack_from('<H',raw,(top+y)*pitch+(left+x)*pixel)[0];b=black[((y+top)&1)*2+((x+left)&1)]
                normalized.append(max(0.,min(1.,f32(f32(code-b)/f32(white-b)))))
        (work/'normalized.f32').write_bytes(normalized.tobytes())
        subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),local,str(work/'normalized.f32'),str(work/'cpu.rgb'),'normalized-rgb',str(work/'oracle-stage-')],check=True,capture_output=True)
        cpu=floats(work/'cpu.rgb');rgba=floats(work/'gpu.rgba');rgb=array('f',(v for i,v in enumerate(rgba) if i%4!=3))
        error=metrics(cpu,rgb);assert error['max_abs']<=2e-5 and error['mean_abs']<=2e-6,error
        results.append({'size':[w,h],'sensor_cfa':pattern,'crop_cfa':local,'origin':[left,top],
            'pixel_stride':pixel,'row_stride':pitch,'fixture':fixture,'integer_packing_exact':True,
            'error':error,'input_sha256':hashlib.sha256(raw).hexdigest(),'output_sha256':hashlib.sha256((work/'gpu.rgba').read_bytes()).hexdigest()})
    return {'scope':'Host-only GPU RAW16 pack→public baseline RCD; staging upload precedes GPU chain, no intermediate CPU normalization/readback. No Android import/video/performance pass.',
        'oracle':'RawTherapee b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c','case_count':len(results),'all_passed':True,
        'device':device,'commands':commands,'cases':results,'max_abs':max(c['error']['max_abs'] for c in results),
        'largest_case_mean_abs':max(c['error']['mean_abs'] for c in results),
        'fixture_note':'Three licensed CC0 CFA crops were reencoded to RAW16 with explicit metadata for adapter testing; this is not original full-sensor RAW/Android validation.',
        'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'baseline.comp',ROOT/'raw_cell_pack.comp',ROOT/'requirements_input_shaders.py',ROOT/'requirements_input_host.cc',ROOT/'validate_requirements_input.py']}}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    report=run(a.work.resolve());a.report.write_text(json.dumps(report,indent=2)+'\n');print(f"PASS: {report['case_count']} GPU input→RCD cases; maximum oracle error {report['max_abs']}")
