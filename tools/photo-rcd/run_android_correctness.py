#!/usr/bin/env python3
"""Run only isolated, synthetic headless Android correctness instrumentation.

No activity, camera, recording, benchmark or package uninstall command.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]
ADB=Path.home()/'Library/Android/sdk/platform-tools/adb'

def run(serial,out,recording_candidate=False,images=False,cached=False,baked=False,packed=False,direct_raw=False,requirements_input=False,normalize_once=False,shared_final=False,tile_local=False,gpu_image=False):
    out.mkdir(parents=True,exist_ok=True)
    def adb(*args,timeout=180):
        r=subprocess.run([str(ADB),'-s',serial,*args],text=True,capture_output=True,timeout=timeout)
        if r.returncode:raise RuntimeError(r.stdout+r.stderr)
        return r.stdout
    main=ROOT/'app/build/outputs/apk/github/deviceTest'
    tests=ROOT/'app/build/outputs/apk/androidTest/github/deviceTest'
    metadata=json.loads((main/'output-metadata.json').read_text());testmeta=json.loads((tests/'output-metadata.json').read_text())
    package=metadata['applicationId'];testpackage=testmeta['applicationId']
    assert package=='com.matthew.rawlens.rcdcheck', 'Build with -PphotoRcdHeadless; never overwrite normal app'
    selected=next((e for e in metadata['elements'] if any(f.get('identifier',f.get('value'))=='arm64-v8a' for f in e.get('filters',[]))),None)
    if selected is None:selected=next(e for e in metadata['elements'] if not e.get('filters'))
    apk=main/selected['outputFile'];testapk=tests/testmeta['elements'][0]['outputFile']
    info={k:adb('shell','getprop',k).strip() for k in ['ro.product.model','ro.build.fingerprint','ro.build.version.sdk']}
    artifacts={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [apk,testapk]}
    for path in [apk,testapk]:print(adb('install','-r','-t',str(path)),flush=True)
    command=['shell','am','instrument','-w','-r','-e','class','com.matthew.rawlens.PhotoRcdCorrectnessDeviceTest',testpackage+'/androidx.test.runner.AndroidJUnitRunner']
    if gpu_image:command[-1:-1]=['-e','gpuImage','true']
    if tile_local:command[-1:-1]=['-e','tileLocal','true']
    if shared_final:command[-1:-1]=['-e','sharedFinal','true']
    if normalize_once:command[-1:-1]=['-e','normalizeOnce','true']
    if requirements_input:command[-1:-1]=['-e','requirementsInput','true']
    if direct_raw:command[-1:-1]=['-e','directRaw','true']
    if packed:command[-1:-1]=['-e','packed','true']
    if baked:command[-1:-1]=['-e','baked','true']
    if cached:command[-1:-1]=['-e','responseCache','true']
    if images:command[-1:-1]=['-e','images','true']
    if recording_candidate:command[-1:-1]=['-e','recordingCandidate','true']
    result=adb(*command,timeout=300)
    (out/'instrumentation.txt').write_text(result);print(result,flush=True)
    log=adb('logcat','-d','-v','threadtime','-s','PhotoRcdCorrectness:I','AndroidRuntime:E')
    (out/'logcat.txt').write_text(log)
    adb('pull',f'/sdcard/Android/data/{package}/files/photo-rcd-correctness/p010-cell-chroma.json',str(out/'p010-cell-chroma.json'))
    destination=f'/sdcard/Android/data/{package}/files/photo-rcd-correctness/canonical.json'
    if recording_candidate:
        adb('pull',f'/sdcard/Android/data/{package}/files/photo-rcd-correctness/recording-candidate.json',str(out/'recording-candidate.json'))
    if requirements_input:adb('pull',f'/sdcard/Android/data/{package}/files/photo-rcd-correctness/requirements-adapter.json',str(out/'requirements-adapter.json'))
    pulled=subprocess.run([str(ADB),'-s',serial,'pull',destination,str(out/'canonical.json')],text=True,capture_output=True)
    (out/'pull.txt').write_text(pulled.stdout+pulled.stderr)
    passed='OK (6 tests)' in result and 'FAILURES' not in result and 'INSTRUMENTATION_FAILED' not in result
    report={'scope':'Headless correctness only; no activity/camera/recording/performance run','serial':serial,'device':info,'artifacts':artifacts,'command':command,'passed':passed,'canonical_report_pulled':pulled.returncode==0}
    (out/'run.json').write_text(json.dumps(report,indent=2)+'\n')
    return passed

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--recording-candidate',action='store_true');p.add_argument('--images',action='store_true');p.add_argument('--baked',action='store_true');p.add_argument('--packed',action='store_true');p.add_argument('--direct-raw',action='store_true');p.add_argument('--requirements-input',action='store_true');p.add_argument('--normalize-once',action='store_true');p.add_argument('--shared-final',action='store_true');p.add_argument('--tile-local',action='store_true');p.add_argument('--gpu-image',action='store_true');p.add_argument('--cached',action='store_true');a=p.parse_args();raise SystemExit(0 if run(a.serial,a.out.resolve(),a.recording_candidate,a.images,a.cached,a.baked,a.packed,a.direct_raw,a.requirements_input,a.normalize_once,a.shared_final,a.tile_local,a.gpu_image) else 1)
