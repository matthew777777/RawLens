#!/usr/bin/env python3
"""Build and run the synthetic grayscale FFDNet convolution-core benchmark."""
import argparse, glob, json, os, pathlib, subprocess, time
root=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--prepared', help='Directory exported by prepare_pretrained.py');p.add_argument('--runs',type=int,default=7);p.add_argument('--tiles',type=int,nargs='+',default=[128,256,512]);p.add_argument('--output',default='/tmp/rawlens-ffdnet-benchmark');a=p.parse_args()
out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')))
adb=str(sdk/'platform-tools/adb');ndk=sorted((sdk/'ndk').iterdir())[-1];clang=next((ndk/'toolchains/llvm/prebuilt').glob('*/bin/aarch64-linux-android30-clang++'))
lib=root/'app/src/main/cpp/ncnn/arm64-v8a';binary=out/'ffdnet-probe'
subprocess.run([str(clang),str(root/'tools/ffdnet-probe/probe.cpp'),'-I'+str(lib/'include/ncnn'),'-Wl,--start-group',*sorted(glob.glob(str(lib/'lib/*.a'))),'-Wl,--end-group','-O2','-o',str(binary),'-lneuralnetworks','-landroid','-llog','-ldl','-fopenmp','-static-openmp','-static-libstdc++'],check=True)
subprocess.run([adb,'push',str(binary),'/data/local/tmp/ffdnet-probe-bin'],check=True)
subprocess.run([adb,'shell','mkdir -p /data/local/tmp/ffdnet-probe; chmod 755 /data/local/tmp/ffdnet-probe-bin'],check=True)
if a.prepared:
 prepared=pathlib.Path(a.prepared)
 subprocess.run([adb,'push',str(prepared/'weights.bin'),'/data/local/tmp/ffdnet-probe/weights.bin'],check=True)
 for tile in a.tiles:
  subprocess.run([adb,'push',str(prepared/f'input-{tile}.bin'),f'/data/local/tmp/ffdnet-probe/trained-input-{tile}.bin'],check=True)
results=[]
with (out/'raw.log').open('w') as log:
 for tile in a.tiles:
  for backend in ['cpu','cpu16','gpu','mtk-mdla_shim','mtk-neuron_shim']:
   thermal=subprocess.run([adb,'shell','dumpsys thermalservice'],capture_output=True,text=True).stdout
   (out/f'thermal-{tile}-{backend}.txt').write_text(thermal)
   command=f'/data/local/tmp/ffdnet-probe-bin {tile} {a.runs} {backend}'+(' /data/local/tmp/ffdnet-probe/weights.bin' if a.prepared else '')
   r=subprocess.run([adb,'shell',command],capture_output=True,text=True,timeout=180)
   log.write(r.stdout+r.stderr);log.flush()
   for line in r.stdout.splitlines():
    if line.startswith('RESULT '):
     row=dict(item.split('=',1) for item in line.split()[1:]);results.append(row);print(line,flush=True)
   if r.returncode: raise RuntimeError(f'{backend} failed: {r.stdout}\n{r.stderr}')
   if a.prepared:
    name=f'output-{tile}-{backend}.bin'
    subprocess.run([adb,'pull',f'/data/local/tmp/ffdnet-probe/trained-{name}',str(out/name)],check=True)
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
print(f'Artifacts: {out}',flush=True)
