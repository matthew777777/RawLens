#!/usr/bin/env python3
"""Standalone headless Vulkan correctness, no camera/UI/recording or app install."""
from array import array
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
from build_oracle import build
from gpu_validate import floats,metrics
from requirements_layout import pack
from requirements_input_shaders import ROOT,packing_source,reconstruction_source
from validate_raw_cell_pack import f32


def run(serial,work,out):
    work.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk';adb=sdk/'platform-tools/adb'
    compiler=sdk/'ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++'
    executable=work/'rcd-requirements-input'
    compile_command=[str(compiler),'-std=c++17','-O2',str(ROOT/'requirements_input_host.cc'),'-lvulkan','-static-libstdc++','-o',str(executable)]
    subprocess.run(compile_command,check=True)
    for name,source in [('pack',packing_source()),('rcd',reconstruction_source())]:
        path=work/(name+'.comp');path.write_text(source)
        subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1',str(path),'-o',str(path.with_suffix('.spv'))],check=True)
        subprocess.run(['spirv-val','--target-env','vulkan1.1',str(path.with_suffix('.spv'))],check=True)
    oracle=build(work/'oracle')
    def command(*args,timeout=90):
        result=subprocess.run([str(adb),'-s',serial,*args],capture_output=True,text=True,timeout=timeout)
        if result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result.stdout.strip()
    fingerprint=command('shell','getprop','ro.build.fingerprint')
    remote='/data/local/tmp/rawlens-rcd-requirements-'+hashlib.sha256(executable.read_bytes()).hexdigest()[:12]
    command('shell','mkdir','-p',remote)
    for path in [executable,work/'pack.spv',work/'rcd.spv']:command('push',str(path),remote+'/'+path.name)
    command('shell','chmod','700',remote+'/'+executable.name)
    manifest=json.loads((ROOT.parent.parent/'docs/refactor/rcd-replacement/requirements-gpu-input-validation.json').read_text())
    selected=[(i,c) for i,c in enumerate(manifest['cases']) if i<16 or 32<=i<48 or c['fixture']!='synthetic']
    rows=[];report={'scope':'Standalone Android Vulkan pack→public RCD correctness only; no camera/UI/app installation/recording/performance test',
        'serial':serial,'fingerprint':fingerprint,'compile_command':compile_command,'remote_directory':remote,
        'artifacts':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [executable,work/'pack.spv',work/'rcd.spv']},'cases':rows,'passed':False}
    try:
        for index,c in selected:
            w,h=c['size'];left,top=c['origin'];pixel=c['pixel_stride'];pitch=c['row_stride']
            sw,sh=(w+left+2,h+top+2) if c['fixture']=='synthetic' else (w,h)
            raw=bytearray([0xa5]*((sh-1)*pitch+(sw-1)*pixel+2))
            if c['fixture']=='synthetic':
                black=[64.,64.5,63.25,65.];white=4095.
                for y in range(sh):
                    for x in range(sw):struct.pack_into('<H',raw,y*pitch+x*pixel,(x*137+y*991+17)%5000)
            else:
                black=[64.]*4;white=16314.;values=floats(ROOT/'fixtures'/c['fixture'])
                # Match the host fixture's deterministic zero-filled row padding.
                raw=bytearray(len(raw))
                for y in range(h):
                    for x in range(w):struct.pack_into('<H',raw,y*pitch+x*2,round(64+values[y*w+x]*(16314-64)))
            assert hashlib.sha256(raw).hexdigest()==c['input_sha256']
            (work/'raw.bin').write_bytes(raw)
            (work/'params.bin').write_bytes(struct.pack('<8i4ff3i',sw,sh,pitch,pixel,left,top,w,h,*black,white,0,0,0))
            for p in [work/'raw.bin',work/'params.bin']:command('push',str(p),remote+'/'+p.name)
            device=command('shell',remote+'/'+executable.name,str(w),str(h),c['crop_cfa'],remote+'/params.bin',remote+'/raw.bin',remote+'/rcd.spv',remote+'/pack.spv',remote+'/rgba.bin',remote+'/cells.bin')
            for name in ['rgba.bin','cells.bin']:command('pull',remote+'/'+name,str(work/name))
            assert (work/'cells.bin').read_bytes()==pack(raw,sw,sh,pitch,pixel,left,top,w,h)
            normalized=array('f')
            for y in range(h):
                for x in range(w):
                    code=struct.unpack_from('<H',raw,(y+top)*pitch+(x+left)*pixel)[0]
                    b=black[((y+top)&1)*2+((x+left)&1)]
                    normalized.append(max(0.,min(1.,f32(f32(code-b)/f32(white-b)))))
            (work/'normalized.f32').write_bytes(normalized.tobytes())
            subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),c['crop_cfa'],str(work/'normalized.f32'),str(work/'oracle.rgb'),'normalized-rgb',str(work/'oracle-stage-')],check=True,capture_output=True)
            expected=floats(work/'oracle.rgb');rgba=floats(work/'rgba.bin');actual=array('f',(v for i,v in enumerate(rgba) if i%4!=3));error=metrics(expected,actual)
            assert error['max_abs']<=2e-5 and error['mean_abs']<=2e-6,error
            rows.append({'host_case_index':index,'fixture':c,'device':device,'integer_packing_exact':True,'error':error,'rgba_sha256':hashlib.sha256((work/'rgba.bin').read_bytes()).hexdigest()})
            print(f'PASS Android case {index}: {w}x{h} {c["crop_cfa"]}, max {error["max_abs"]}',flush=True)
        report['passed']=True;report['case_count']=len(rows);report['max_abs']=max(r['error']['max_abs'] for r in rows)
    finally:(out/'run.json').write_text(json.dumps(report,indent=2)+'\n')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.serial,a.work.resolve(),a.out.resolve())
