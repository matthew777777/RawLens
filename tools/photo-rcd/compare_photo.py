#!/usr/bin/env python3
"""Audit current Kotlin photo comparison independently of the primary oracle."""
from array import array
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from build_oracle import ROOT,build
from gpu_validate import floats,metrics

def jar(cache,module,version):
    return next(p for p in (cache/module/version).glob('*/*.jar') if p.name==f'{module}-{version}.jar')

def run(work):
    work.mkdir(parents=True,exist_ok=True)
    cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
    jars=[jar(cache/'org.jetbrains.kotlin',module,'2.2.21') for module in ['kotlin-compiler-embeddable','kotlin-stdlib','kotlin-script-runtime']]
    jars += [jar(cache/'org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar(cache/'org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.1')]
    jars += [jar(cache/'org.jetbrains','annotations','13.0')]
    reference=ROOT.parent.parent/'app/src/main/java/com/matthew/rawlens/RcdReference.kt'
    output=work/'photo.jar'
    command=['java','-cp',':'.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(jars[1]),str(reference),str(ROOT/'PhotoComparison.kt'),'-d',str(output)]
    subprocess.run(command,check=True)
    oracle=build(work/'oracle');results=[]
    w,h=41,38
    for pattern in ['RGGB','GRBG','GBRG','BGGR']:
        for kind in ['flat','noise','chroma']:
            state=0x12345678;codes=array('i');normalized=array('f')
            for y in range(h):
                for x in range(w):
                    state=(1664525*state+1013904223)&0xffffffff
                    c=pattern[(y&1)*2+(x&1)]
                    code=16384 if kind=='flat' else state>>16 if kind=='noise' else 49152 if c==('R' if (x//4+y//4)&1 else 'B') else 8192
                    codes.append(code);normalized.append(code/65536.)
            inp=work/'codes.i32';inp.write_bytes(codes.tobytes());src=work/'input.f32';src.write_bytes(normalized.tobytes())
            photo=work/'photo.f32';cpu=work/'cpu.f32'
            subprocess.run(['java','-cp',str(output)+':'+str(jars[1]),'PhotoComparisonKt',str(w),str(h),pattern,str(inp),str(photo)],check=True)
            subprocess.run([str(oracle),str(w),str(h),str(w),'0','0',str(w),str(h),pattern,str(src),str(cpu),'normalized-rgb','-'],check=True)
            expected=floats(cpu);actual=floats(photo)
            inner=[(y*w+x)*3+c for y in range(10,h-10) for x in range(10,w-10) for c in range(3)]
            results.append({'size':[w,h],'cfa':pattern,'fixture':kind,'whole_image':metrics(expected,actual),'interior_excluding_10':metrics([expected[i] for i in inner],[actual[i] for i in inner])})
    return {'scope':'Compatibility comparison, not a pass gate; current Kotlin photo equations/edges differ from pinned RawTherapee','reference_sha256':hashlib.sha256(reference.read_bytes()).hexdigest(),'compile_command':command,'compiler_dependencies':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},'cases':results}

if __name__=='__main__':
    r=run(Path(sys.argv[1]).resolve());Path(sys.argv[2]).write_text(json.dumps(r,indent=2)+'\n');print('Recorded 12 photo compatibility comparisons; differences are reported, not treated as oracle failures')
