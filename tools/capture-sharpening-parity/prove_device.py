#!/usr/bin/env python3
"""Compare shipping JNI output with independently built pinned RAWR on the same Android GPU.
Run prove_parity.py first and build the app's GithubDeviceTest APKs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
def run(cmd,**kw):
    return subprocess.run(list(map(str,cmd)),check=True,**kw)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reference',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--ndk',type=Path,required=True)
    p.add_argument('--adb',type=Path,required=True)
    p.add_argument('--apk',type=Path,required=True)
    p.add_argument('--test-apk',type=Path,required=True)
    a=p.parse_args();out=a.out.resolve();ref=a.reference.resolve()/'native';exe=out/'rawr-android-validate'
    package=json.loads((a.apk.parent/'output-metadata.json').read_text())['applicationId']
    assert package == 'com.matthew.rawlens.capsharpprobe', 'Build using isolated.init.gradle; preserve the daily app'
    test_package=json.loads((a.test_apk.parent/'output-metadata.json').read_text())['applicationId']
    compiler=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++'
    run([compiler,'-std=c++17','-O2','-static-libstdc++',ref/'capture_sharpening/tests/capture_sharpening_validate.cpp',
         ref/'capture_sharpening/src/CaptureSharpening.cpp',ref/'vk_common/src/OwnedImage.cpp',
         '-I'+str(ref/'capture_sharpening/include'),'-I'+str(ref/'vk_common/include'),
         '-I'+str(ref/'vk_common/testing'),'-I'+str(out/'reference/generated'),'-lvulkan','-o',exe])
    def adb(*cmd,**kw):return run([a.adb,*cmd],**kw)
    adb('install','-r','-t',a.apk);adb('install','-r','-t',a.test_apk)
    result=adb('shell','am','instrument','-w','-r','-e','class','com.matthew.rawlens.CaptureSharpeningDeviceTest',
               test_package+'/androidx.test.runner.AndroidJUnitRunner',capture_output=True,text=True)
    (out/'device-instrumentation.txt').write_text(result.stdout+result.stderr)
    print(result.stdout,flush=True)
    assert 'OK (2 tests)' in result.stdout,'Instrumentation failed'
    remote='/data/local/tmp/rawlens-capsharp-proof'
    adb('shell','mkdir','-p',remote);adb('push',exe,remote+'/validate');adb('shell','chmod','755',remote+'/validate')
    rows=[]
    for it,cfa in [(2,0),(3,1),(4,2),(6,3)]:
        name=f'preset-{it}-cfa-{cfa}';work=out/'cases'/name
        adb('push',work/'params.txt',remote+'/params.txt');adb('push',work/'rgb.f32',remote+'/rgb.f32')
        result=adb('shell',remote+'/validate',remote,capture_output=True,text=True)
        (work/'android-reference.log').write_text(result.stdout+result.stderr)
        adb('pull',remote+'/gpu_rgb.f32',work/'android-reference.f32')
        adb('pull',f'/sdcard/Android/data/{package}/files/capsharp-{name}.rgba16f',work/'android-jni.rgba16f')
        expected=np.fromfile(work/'android-reference.f32',np.float32)
        actual=np.fromfile(work/'android-jni.rgba16f',np.float16).reshape(-1,4)[:,:3].astype(np.float32).ravel()
        row=dict(case=name,identical=expected.tobytes()==actual.tobytes(),
                 reference_sha256=hashlib.sha256(expected.tobytes()).hexdigest(),
                 jni_sha256=hashlib.sha256(actual.tobytes()).hexdigest(),max_error=float(np.max(np.abs(expected-actual))))
        rows.append(row);assert row['identical'],row
    model=adb('shell','getprop','ro.product.model',capture_output=True,text=True).stdout.strip()
    (out/'android-gpu-parity.json').write_text(json.dumps(dict(passed=True,model=model,cases=rows),indent=2)+'\n')
    print('ANDROID_RAWR_JNI_BIT_PARITY_PASS',model)
if __name__=='__main__':main()
