#!/usr/bin/env python3
"""Compile every production cleanup variant with Arm Mali Offline Compiler."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--malioc',default='/Applications/Arm Performance Studio 2026.6/mali_offline_compiler/malioc')
parser.add_argument('--core',default='Mali-G615')
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
source=(root/'app/src/main/assets/shaders/display/rapidraw_cleanup.glsl').read_text()
names=['half','quarter','gradient','quarter-filter','upsample','core','unused','noise-samples','full-output','core-samples','half-filter']
with tempfile.TemporaryDirectory(prefix='rapidraw-malioc-') as tmp:
    def compile_mode(mode):
        name=names[mode] if mode<11 else f'fused-{"basic" if mode<13 else "agx"}-{"hdr" if mode%2==0 else "sdr"}'
        shader=Path(tmp)/f'{name}.comp'
        if mode>=11:
            output=(root/'app/src/main/assets/shaders/display/agx_srgb8.glsl').read_text()
            body=f'#define RAPIDRAW_BASIC {1 if mode<13 else 0}\n#define RAPIDRAW_GAINMAP {1 if mode%2==0 else 0}\n#define MODE 8\n#define RAPIDRAW_OUTPUT_FUSED 1\nlayout(local_size_x=8,local_size_y=8) in;\n'+source+'\n'+output
        else: body=f'#define MODE {mode}\n'+source
        shader.write_text('#version 310 es\n'+body)
        run=subprocess.run([args.malioc,'--compute','--opengles','--core',args.core,'--format','json',str(shader)],capture_output=True,text=True)
        assert run.returncode==0,(name,run.stdout,run.stderr)
        result=json.loads(run.stdout); s=result['shaders'][0]
        s['filename']=f'rapidraw_cleanup.glsl MODE={mode} ({name})'
        assert not s.get('errors'),s
        p={p['name']:p['value'] for p in s['variants'][0]['properties']}
        assert not p['has_stack_spilling'],(name,'register spills')
        return result
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(compile_mode,[0,1,2,3,4,5,7,8,9,10,11,12,13,14]))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'producer':results[0]['producer'],'core':args.core,'shaders':[r['shaders'][0] for r in results]},indent=2)+'\n')
    for r in results:
        name=r['shaders'][0]['filename'].split('(')[1].split(')')[0]
        s=r['shaders'][0];p={p['name']:p['value'] for p in s['variants'][0]['properties']}
        perf=s['variants'][0]['performance'];pipes=perf['pipelines'];cycles=perf['total_cycles']['cycle_count']
        print(f'{name:17} registers={p["work_registers_used"]:2} occupancy={p["thread_occupancy"]:3}% spills={p["stack_spill_bytes"]} '+
              ' '.join(f'{pipe}={v:.2f}' for pipe,v in zip(pipes,cycles) if pipe in ['arith_total','load_store','texture']))
