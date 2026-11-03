#!/usr/bin/env python3
"""Phone shape diagnostics, fixed full crop. No recording/FPS acceptance."""
import argparse,hashlib,json,statistics,subprocess
from pathlib import Path
from tile_local import source
from requirements_input_shaders import ROOT,app_normalizing_source

def run(serial,work,out):
 work.mkdir(parents=True,exist_ok=True);out.parent.mkdir(parents=True,exist_ok=True)
 sdk=Path.home()/'Library/Android/sdk';adb=sdk/'platform-tools/adb';compiler=sdk/'ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++'
 cpp=ROOT.parents[1]/'app/src/main/cpp/photo_rcd';binary=work/'bench'
 subprocess.run([str(compiler),'-std=c++17','-O2','-fno-fast-math','-ffp-contract=off','-I'+str(cpp),str(ROOT/'tile_local_benchmark.cc'),str(cpp/'photo_rcd_engine.cpp'),'-lvulkan','-landroid','-static-libstdc++','-o',str(binary)],check=True)
 shaders=[]
 for name,text in [('normalize',app_normalizing_source())]+[(f'tile{x}x{y}',source((x,y))) for x,y in [(8,8),(16,8),(8,16),(16,16),(32,8)]]:
  p=work/(name+'.comp');p.write_text(text);spv=p.with_suffix('.spv')
  subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1','-Os',str(p),'-o',str(spv)],check=True)
  subprocess.run(['spirv-val','--target-env','vulkan1.1',str(spv)],check=True);shaders.append(spv)
 def command(*args,timeout=120):
  r=subprocess.run([str(adb),'-s',serial,*args],text=True,capture_output=True,timeout=timeout)
  if r.returncode:raise RuntimeError(r.stdout+r.stderr)
  return r.stdout
 remote='/data/local/tmp/rawlens-rcd-tile-bench-'+hashlib.sha256(binary.read_bytes()).hexdigest()[:12]
 command('shell','mkdir','-p',remote)
 for p in [binary]+shaders:command('push',str(p),remote+'/'+p.name)
 command('shell','chmod','700',remote+'/bench')
 report={'scope':'Isolated full-source native Vulkan shape diagnostic on synthetic RAW16. No camera/encoder/recording delivered FPS or quality acceptance. Workload uncontrolled.','serial':serial,'source':[4080,3060],'crop':[120,450,3840,2160],'pattern':'uint16((x*137+y*991+17)%5000), four sensor blacks, white4095','warmup_samples':2,'repeats':6,'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'shaders':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in shaders},'runs':[]}
 try:
  for x,y in [(8,8),(16,8),(8,16),(16,16),(32,8)]:
   try:
    text=command('shell',remote+'/bench',remote+f'/tile{x}x{y}.spv',remote+'/normalize.spv',str(x),str(y),'6')
    r=json.loads(text);warm=r['samples'][2:];r['warm_wall_median_ms']=statistics.median(s['wall_ms'] for s in warm);r['warm_reconstruct_gpu_median_ms']=statistics.median(s['gpu_stage_ms'][3] for s in warm)
   except Exception as error:
    r={'core':[x,y],'failed':True,'error':str(error)}
   report['runs'].append(r);out.write_text(json.dumps(report,indent=2)+'\n');print(r,flush=True)
 finally:out.write_text(json.dumps(report,indent=2)+'\n')
 return report
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args();run(a.serial,a.work.resolve(),a.report.resolve())
