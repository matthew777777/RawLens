#!/usr/bin/env python3
"""Warm host GPU layout experiment. These are not delivered recording fps."""
from array import array
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
from experiment_validate import build,compile_runner,environment

def percentile(values,p):
    return sorted(values)[min(len(values)-1,int((len(values)-1)*p+.5))]

def run(work,selected=None):
    work.mkdir(parents=True,exist_ok=True);variants=build(work/'shaders');exe=compile_runner(work)
    records=[];env=environment();env['PHOTO_RCD_NO_STAGE_DUMP']='1'
    for w,h in [(1920,1080),(2560,1440),(3840,2160)]:
        data=array('f',(((x*17+y*23)%65536)/65536. for y in range(h) for x in range(w)))
        source=work/'input.f32';source.write_bytes(data.tobytes());expected=None
        for variant in selected or ['scalar','packed','tiled','tiled-packed','cached','cached-packed','tiled-cached']:
            output=work/'output.f32';prefix=work/'stage-'
            command=[str(exe),str(w),str(h),'RGGB',str(source),str(work/'shaders'/(variant+'.spv')),str(output),str(prefix),variant,'15']
            subprocess.run(command,check=True,env=env)
            digest=hashlib.sha256(output.read_bytes()).hexdigest()
            if expected is None:expected=digest
            assert digest==expected,(w,h,variant,'optimization mismatch at product dimensions')
            timing=json.loads((work/'stage-timing.json').read_text());warm=timing['runs'][5:]
            total=[v['total_gpu_ms'] for v in warm]
            packing=[v['stage_ms'][0] for v in warm] if 'packed' in variant else []
            record={'size':[w,h],'variant':variant,'exact_rgba_match':True,'rgba_sha256':digest,'warm_runs':len(warm),'median_total_gpu_ms':statistics.median(total),'p95_total_gpu_ms':percentile(total,.95),'median_pack_gpu_ms':statistics.median(packing) if packing else 0,'raw_timing':timing}
            records.append(record);print(f'{w}x{h} {variant}: total median {record["median_total_gpu_ms"]:.3f} ms, pack {record["median_pack_gpu_ms"]:.3f} ms',flush=True)
    return {'scope':'Apple M1 host GPU timestamps, including packing; not mobile/camera/encoder/delivered fps','warmup_runs':5,'measured_runs':10,'variants':variants,'results':records,'driver_environment':{k:env[k] for k in ['VK_ICD_FILENAMES','MVK_CONFIG_FAST_MATH_ENABLED']}}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--variant',action='append');a=p.parse_args();r=run(a.work.resolve(),a.variant);a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(r,indent=2)+'\n')
