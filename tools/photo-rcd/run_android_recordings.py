#!/usr/bin/env python3
"""Authorized headless real-camera RCD diagnostic recordings, no UI or retiming."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
from run_android_correctness import ADB,ROOT

def run(serial,out,seconds,label,modes,install=True,response_cache=False,tiled=False,recording_candidate=False,images=False,baked=False,packed=False,direct_raw=False,requirements_input=False,reconstruction_scale=1,gpu_writer=False,normalize_once=False,shared_final=False,tile_local=False,gpu_image=False):
    out.mkdir(parents=True,exist_ok=True)
    def adb(*args,timeout=180):
        return subprocess.run([str(ADB),'-s',serial,*args],text=True,capture_output=True,timeout=timeout)
    main=ROOT/'app/build/outputs/apk/github/deviceTest';tests=ROOT/'app/build/outputs/apk/androidTest/github/deviceTest'
    metadata=json.loads((main/'output-metadata.json').read_text());testmeta=json.loads((tests/'output-metadata.json').read_text())
    assert metadata['applicationId']=='com.matthew.rawlens.rcdcheck'
    entry=next(e for e in metadata['elements'] if any(f.get('value')=='arm64-v8a' for f in e.get('filters',[])))
    paths=[main/entry['outputFile'],tests/testmeta['elements'][0]['outputFile']]
    artifacts={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    if install:
        for path in paths:
            r=adb('install','-r','-t',str(path));print(r.stdout,flush=True);assert r.returncode==0,r.stderr+r.stdout
    installed=adb('shell','pm','path',metadata['applicationId'])
    paths_on_device=[line.removeprefix('package:').strip() for line in installed.stdout.splitlines() if line.startswith('package:')]
    assert installed.returncode==0 and len(paths_on_device)==1,installed.stdout+installed.stderr
    assert re.fullmatch(r'/data/app/[A-Za-z0-9_./~+=-]+',paths_on_device[0]),paths_on_device
    digest=adb('shell','sha256sum',paths_on_device[0]);assert digest.returncode==0,digest.stderr
    installed_sha256=digest.stdout.split()[0]
    assert installed_sha256==artifacts[str(paths[0])], 'Installed APK differs from local build; rerun without --no-install to establish artifact identity'
    for permission in ['CAMERA','RECORD_AUDIO']:
        r=adb('shell','pm','grant',metadata['applicationId'],'android.permission.'+permission);assert r.returncode==0,r.stderr
    results=[]
    for mode in modes:
        w,h,fps=mode.split(':');name=f'{w}x{h}-{fps}-{label}';modeout=out/name;modeout.mkdir(exist_ok=True)
        command=['shell','am','instrument','-w','-r','-e','class','com.matthew.rawlens.PhotoRcdRecordingDeviceTest','-e','authorizeRcdRecording','true','-e','width',w,'-e','height',h,'-e','fps',fps,'-e','seconds',str(seconds),'-e','label',label,testmeta['applicationId']+'/androidx.test.runner.AndroidJUnitRunner']
        if response_cache:command[-1:-1]=['-e','responseCache','true']
        if gpu_writer:command[-1:-1]=['-e','gpuWriter','true']
        if gpu_image:command[-1:-1]=['-e','gpuImage','true']
        if tile_local:command[-1:-1]=['-e','tileLocal','true']
        if shared_final:command[-1:-1]=['-e','sharedFinal','true']
        if normalize_once:command[-1:-1]=['-e','normalizeOnce','true']
        if requirements_input:command[-1:-1]=['-e','requirementsInput','true','-e','reconstructionScale',str(reconstruction_scale)]
        if direct_raw:command[-1:-1]=['-e','directRaw','true']
        if packed:command[-1:-1]=['-e','packed','true']
        if baked:command[-1:-1]=['-e','baked','true']
        if images:command[-1:-1]=['-e','images','true']
        if recording_candidate:command[-1:-1]=['-e','recordingCandidate','true']
        if tiled:command[-1:-1]=['-e','tiled','true']
        print('Recording',name,seconds,'seconds',flush=True)
        result=adb(*command,timeout=seconds+240);(modeout/'instrumentation.txt').write_text(result.stdout+result.stderr);print(result.stdout,flush=True)
        for extension in ['json','mp4']:
            remote=f'/sdcard/Android/data/{metadata["applicationId"]}/files/photo-rcd-recordings/{name}.{extension}'
            r=adb('pull',remote,str(modeout/f'recording.{extension}'),timeout=180)
            (modeout/f'pull-{extension}.txt').write_text(r.stdout+r.stderr)
        logs=adb('logcat','-d','-v','threadtime','-s','EncodedVideo:I','RawLensVfVk:W','AndroidRuntime:E',timeout=30)
        (modeout/'logcat.txt').write_text(logs.stdout)
        results.append({'mode':[int(w),int(h),int(fps)],'out':str(modeout),'instrumentation_ok':'OK (1 test)' in result.stdout,'command':command})
    (out/'runs.json').write_text(json.dumps({'artifacts':artifacts,'installed_apk_sha256':installed_sha256,'serial':serial,'seconds_requested':seconds,'runs':results,'scope':'Real RCD + separate grade + Main10/audio recording; actual cadence evaluated from sensor identities, not PTS alone'},indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--seconds',type=int,default=30);p.add_argument('--label',default='initial');p.add_argument('--mode',action='append');p.add_argument('--no-install',action='store_true');p.add_argument('--response-cache',action='store_true');p.add_argument('--tiled',action='store_true');p.add_argument('--recording-candidate',action='store_true');p.add_argument('--images',action='store_true');p.add_argument('--baked',action='store_true');p.add_argument('--packed',action='store_true');p.add_argument('--direct-raw',action='store_true');p.add_argument('--requirements-input',action='store_true');p.add_argument('--normalize-once',action='store_true');p.add_argument('--shared-final',action='store_true');p.add_argument('--tile-local',action='store_true');p.add_argument('--gpu-image',action='store_true');p.add_argument('--gpu-writer',action='store_true');p.add_argument('--reconstruction-scale',type=int,choices=range(1,9),default=1);a=p.parse_args();run(a.serial,a.out.resolve(),a.seconds,a.label,a.mode or ['1920:1080:30','2560:1440:30','3840:2160:24'],not a.no_install,a.response_cache,a.tiled,a.recording_candidate,a.images,a.baked,a.packed,a.direct_raw,a.requirements_input,a.reconstruction_scale,a.gpu_writer,a.normalize_once,a.shared_final,a.tile_local,a.gpu_image)
