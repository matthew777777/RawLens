#!/usr/bin/env python3
"""Build/run CompiledModel NPU probe and optional paired native NNAPI timings."""
import argparse,glob,json,os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--sdk',required=True,help='Extracted litert_cc_sdk directory');p.add_argument('--libraries',required=True,help='Directory with arm64 LiteRT + MediaTek compiler/dispatch .so files');p.add_argument('--models',required=True);p.add_argument('--prepared',required=True);p.add_argument('--output',required=True);p.add_argument('--tiles',nargs='+',type=int,default=[256,512,1024]);p.add_argument('--runs',type=int,default=7);p.add_argument('--compare-mdla',action='store_true');a=p.parse_args()
sdk=pathlib.Path(a.sdk);libs=pathlib.Path(a.libraries);models=pathlib.Path(a.models);prepared=pathlib.Path(a.prepared);out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True)
android=pathlib.Path(os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')));adb=str(android/'platform-tools/adb');ndk=sorted((android/'ndk').iterdir())[-1];clang=next((ndk/'toolchains/llvm/prebuilt').glob('*/bin/aarch64-linux-android30-clang++'));remote='/data/local/tmp/rawlens-ffdnet-litert'
# Generate only CMake feature-toggle header; other headers remain SDK originals.
buildconfig=sdk/'litert/build_common/build_config.h'
if not buildconfig.exists():buildconfig.write_text('#pragma once\n#define LITERT_BUILD_CONFIG_DISABLE_GPU 0\n#define LITERT_BUILD_CONFIG_DISABLE_NPU 0\n')
subprocess.run([str(clang),str(root/'tools/ffdnet-probe/litert_probe.cpp'),'-I'+str(sdk),'-L'+str(libs),'-lLiteRt','-ldl','-llog','-landroid','-static-libstdc++','-O2','-o',str(out/'probe')],check=True)
if a.compare_mdla:
 ncnn=root/'app/src/main/cpp/ncnn/arm64-v8a'
 subprocess.run([str(clang),str(root/'tools/ffdnet-probe/probe.cpp'),'-I'+str(ncnn/'include/ncnn'),'-Wl,--start-group',*sorted(glob.glob(str(ncnn/'lib/*.a'))),'-Wl,--end-group','-O2','-o',str(out/'nnapi-probe'),'-lneuralnetworks','-landroid','-llog','-ldl','-fopenmp','-static-openmp','-static-libstdc++'],check=True)
subprocess.run([adb,'shell',f'mkdir -p {remote} /data/local/tmp/ffdnet-probe'],check=True)
def push(local,device):subprocess.run([adb,'push',str(local),device],check=True,stdout=subprocess.DEVNULL)
push(out/'probe',remote+'/probe')
for lib in sorted(libs.glob('*.so')):push(lib,remote+'/'+lib.name)
if a.compare_mdla:
 push(out/'nnapi-probe',remote+'/nnapi-probe');push(prepared/'weights.bin',remote+'/weights.bin')
for tile in a.tiles:
 for local,device in [(models/f'ffdnet-color-{tile}.tflite',f'ffdnet-color-{tile}.tflite'),(prepared/f'input-{tile}.bin',f'input-{tile}.bin'),(prepared/f'expected-{tile}.bin',f'expected-{tile}.bin')]:push(local,remote+'/'+device)
 if a.compare_mdla:
  push(prepared/f'input-{tile}.bin',f'/data/local/tmp/ffdnet-probe/trained-input-{tile}.bin')
  push(prepared/f'expected-{tile}.bin',f'/data/local/tmp/ffdnet-probe/trained-ref-{tile}.bin')
subprocess.run([adb,'shell',f'chmod 755 {remote}/probe'+(f' {remote}/nnapi-probe' if a.compare_mdla else '')],check=True)
identity=subprocess.run([adb,'shell','getprop ro.product.model; getprop ro.soc.model; getprop ro.build.version.release'],capture_output=True,text=True,check=True).stdout;(out/'device.txt').write_text(identity);print(identity,flush=True)
results=[]
with (out/'raw.log').open('w') as log:
 for i,tile in enumerate(a.tiles):
  backends=['litert-npu','nnapi-mdla'] if a.compare_mdla else ['litert-npu']
  if i%2:backends.reverse()
  for backend in backends:
   thermal=subprocess.run([adb,'shell','dumpsys thermalservice'],capture_output=True,text=True,check=True).stdout;(out/f'thermal-{tile}-{backend}.txt').write_text(thermal)
   if backend=='litert-npu':command=f'LD_LIBRARY_PATH={remote} {remote}/probe {remote} {tile} {a.runs} npu';device_output=f'{remote}/output-{tile}-litert-npu.bin'
   else:command=f'{remote}/nnapi-probe {tile} {a.runs} mtk-mdla_shim {remote}/weights.bin';device_output=f'/data/local/tmp/ffdnet-probe/trained-output-{tile}-mtk-mdla_shim.bin'
   r=subprocess.run([adb,'shell',command],capture_output=True,text=True,timeout=180);log.write(f'RUN {backend} tile={tile}\n'+r.stdout+r.stderr);log.flush()
   if r.returncode:raise RuntimeError(r.stdout+r.stderr)
   if backend=='litert-npu' and 'non_cpu_fully_accelerated=1' not in r.stdout:raise RuntimeError('Unverified NPU delegation')
   for line in r.stdout.splitlines():
    if line.startswith('RESULT '):
     row=dict(item.split('=',1) for item in line.split()[1:]);row['path']=backend;results.append(row);print(line,flush=True)
   subprocess.run([adb,'pull',device_output,str(out/f'output-{tile}-{backend}.bin')],check=True,stdout=subprocess.DEVNULL)
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(f'Artifacts: {out}',flush=True)
