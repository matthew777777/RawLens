#!/usr/bin/env python3
"""Build RT's scalar Capture Sharpening oracle without changing its algorithm sources."""
import argparse
from pathlib import Path
import shlex
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--reference',type=Path,required=True)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
here=Path(__file__).resolve().parents[2]/'app/src/main/cpp/capture_sharpening/capture_sharpening/tools/rt_harness'
script=(here/'build.sh').read_text()
script=script.replace('HERE=$(cd "$(dirname "$0")" && pwd)','HERE='+shlex.quote(str(here)))
script=script.replace('RT=${RT:-$REPO/tmp/ref/RawTherapee}','RT='+shlex.quote(str(a.reference.resolve())))
script=script.replace('OUT=$REPO/tmp/capture_sharpening/rt_harness','OUT='+shlex.quote(str(out)))
path=out/'build_reference.sh';path.write_text(script)
subprocess.run(['sh',str(path)],check=True)
