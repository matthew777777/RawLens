#!/usr/bin/env python3
"""Diagnostic RAW16 pack-only timestamps; never a recording/FPS benchmark."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import struct
import subprocess
import time
from requirements_input_shaders import ROOT


def run(serial,work,out):
    work.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk';adb=sdk/'platform-tools/adb';compiler=sdk/'ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++'
    executable=work/'raw-pack-diagnostic';shader=work/'pack.spv'
    compile_command=[str(compiler),'-std=c++17','-O2',str(ROOT/'raw_cell_pack_host.cc'),'-lvulkan','-static-libstdc++','-o',str(executable)]
    subprocess.run(compile_command,check=True)
    subprocess.run(['glslangValidator','-V','-Os','--target-env','vulkan1.1',str(ROOT/'raw_cell_pack.comp'),'-o',str(shader)],check=True)
    subprocess.run(['spirv-val','--target-env','vulkan1.1',str(shader)],check=True)
    def command(*args):
        result=subprocess.run([str(adb),'-s',serial,*args],capture_output=True,text=True,timeout=180)
        if result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result.stdout.strip()
    remote='/data/local/tmp/rawlens-pack-diagnostic-'+hashlib.sha256(executable.read_bytes()).hexdigest()[:12]
    command('shell','mkdir','-p',remote)
    for p in [executable,shader]:command('push',str(p),remote+'/'+p.name)
    command('shell','chmod','700',remote+'/'+executable.name)
    sw,sh=4080,3060;pitch=sw*2
    sensor_row=b''.join(struct.pack('<H',(x*13)&65535) for x in range(sw))
    (work/'raw.bin').write_bytes(sensor_row*sh);command('push',str(work/'raw.bin'),remote+'/raw.bin')
    rows=[]
    for w,h,left,top in [(3840,2160,120,450),(3839,2159,121,451)]:
        (work/'params.bin').write_bytes(struct.pack('<8i4ff3i',sw,sh,pitch,2,left,top,w,h,0.,0.,0.,0.,65535.,0,0,0))
        command('push',str(work/'params.bin'),remote+'/params.bin')
        start=time.monotonic()
        text=command('shell',remote+'/'+executable.name,remote+'/params.bin',remote+'/raw.bin',remote+'/pack.spv',remote+'/packed.bin',remote+'/probe.bin','0','12')
        wall=(time.monotonic()-start)*1000
        samples=json.loads(next(line.split('=',1)[1] for line in text.splitlines() if line.startswith('pack_gpu_ms=')))
        assert not samples or len(samples)==12
        regular=[];last=[]
        for cx in range((w+1)//2):
            a=((left+cx*2)*13)&65535;b=((left+cx*2+1)*13)&65535 if cx*2+1<w else 0
            regular.append(struct.pack('<4H',a,b,a,b));last.append(struct.pack('<4H',a,b,0,0))
        expected=b''.join(regular)*(h//2)+(b''.join(last) if h&1 else b'')
        digest=command('shell','sha256sum',remote+'/packed.bin').split()[0]
        assert digest==hashlib.sha256(expected).hexdigest()
        warm=samples[2:]
        rows.append({'source':[sw,sh],'crop':[left,top,w,h],'packed_bytes':len(expected),'packed_sha256':digest,
            'packing_exact':True,'gpu_samples_ms_all':samples,'warmup_dispatches_excluded':2,'warm_sample_count':len(warm),
            'gpu_median_warm_ms':statistics.median(warm) if warm else None,'gpu_p95_warm_ms':sorted(warm)[math.ceil(.95*len(warm))-1] if warm else None,
            'process_wall_ms_including_setup_file_io_and_adb':wall,'stdout':text})
        print(json.dumps(rows[-1]),flush=True)
    report={'scope':'Pack-only diagnostic on uncontrolled phone workload; excludes camera import, CPU staging upload, RCD, grade/P010, encoding and recording cadence',
        'serial':serial,'remote_directory':remote,'compile_command':compile_command,'rows':rows,
        'artifacts':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [executable,shader]},
        'input_pattern':'row-repeated uint16 ramp: code(x,y)=(13*x)&65535',
        'input_sha256':hashlib.sha256((work/'raw.bin').read_bytes()).hexdigest(),
        'recording_targets_passed':False}
    (out/'run.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.serial,a.work.resolve(),a.out.resolve())
