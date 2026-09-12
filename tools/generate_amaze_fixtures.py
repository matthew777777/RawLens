#!/usr/bin/env python3
"""Generate host-upstream oracle fixtures; never derive expected values on Android."""
from pathlib import Path
import math
import struct
import subprocess
import tempfile
from build_amaze_reference import generate

def main():
    root=Path(__file__).resolve().parents[1]
    cpp=root/'app/src/main/cpp'
    patterns=((0,1,1,2),(1,0,2,1),(1,2,0,1),(2,1,1,0))
    out=root/'app/src/androidTest/assets/amaze_reference.bin'
    out.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='rt-amaze-oracle-') as temp:
        generated=Path(temp)/'upstream.cpp'
        executable=Path(temp)/'oracle'
        generate(root/'references/RawTherapee/amaze_demosaic_RT.cc',generated)
        subprocess.run(['c++','-std=c++14','-O2','-ffp-contract=off','-fno-fast-math',
            '-I',str(cpp),str(generated),str(root/'tools/amaze_reference_host.cpp'),
            '-o',str(executable)],check=True)
        with out.open('wb') as stream:
            stream.write(struct.pack('<II',0x414d415a,24))
            for phase,fc in enumerate(patterns):
                for scene in range(6):
                    w,h=((64,66),(130,134),(258,262))[scene%3]
                    gain=(1.,0.5,2.)[scene%3]
                    raw=[]
                    for y in range(h):
                        for x in range(w):
                            c=fc[(y%2)*2+x%2]
                            if scene==0: v=(0.18,0.25,0.12)[c]
                            elif scene==1: v=(0.03 if x*3<y*2 else 1.4)*(0.6,1.,0.8)[c]
                            elif scene==2: v=(0.05 if (x//2+y//2)%2 else 0.65)
                            elif scene==3: v=0.0002+((x*37+y*19+x*y*11)%127)*0.00007
                            elif scene==4: v=0.3+0.2*math.sin(x*x*0.07+y*y*0.03)*(0.9,1.,0.7)[c]
                            else: v=((x*73856093 ^ y*19349663)&65535)/65535*1.3-0.02
                            raw.append(v*65535.)
                    packed=struct.pack('<%df'%len(raw),*raw)
                    header=struct.pack('<II4If',w,h,*fc,gain)
                    result=subprocess.run([str(executable)],input=header+packed,
                        stdout=subprocess.PIPE,check=True).stdout
                    assert len(result)==w*h*12
                    stream.write(struct.pack('<IIIIf',w,h,phase,scene,gain))
                    stream.write(packed)
                    stream.write(result)
    print(f'Generated 24 pinned upstream fixtures: {out} ({out.stat().st_size} bytes)')

if __name__=='__main__': main()
