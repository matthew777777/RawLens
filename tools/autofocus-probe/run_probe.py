#!/usr/bin/env python3
"""Run the opt-in Camera2 probe in its own package; leave RawLens installed unchanged."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-apk', type=Path, required=True, help='Non-minified deviceTest APK (provides Kotlin runtime)')
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--warm', action='store_true')
    parser.add_argument('--raw', action='store_true', help='Add the largest RAW stream to repeating requests')
    parser.add_argument('--ae', choices=['0', '1'])
    parser.add_argument('--strategies')
    parser.add_argument('--camera-id', default='0')
    parser.add_argument('--serial')
    args = parser.parse_args()
    # A Gradle build creates the standard Android debug key if absent.
    keystore = Path.home() / '.android/debug.keystore'
    if not keystore.exists():
        parser.error('Build a debug/deviceTest APK first to create the Android debug key')
    version = lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name))
    build_tools = max(args.sdk.joinpath('build-tools').iterdir(), key=version)
    platform = max(args.sdk.joinpath('platforms').iterdir(), key=version)
    adb = [str(args.sdk / 'platform-tools/adb')]
    if args.serial:
        adb += ['-s', args.serial]

    def run(command, **kwargs):
        return subprocess.run([str(v) for v in command], check=True, **kwargs)

    with tempfile.TemporaryDirectory(prefix='rawlens-af-') as directory:
        work = Path(directory)
        manifest = work / 'AndroidManifest.xml'
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="com.matthew.afprobe" android:versionCode="1" android:versionName="1">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="37"/>
<uses-permission android:name="android.permission.CAMERA"/>
<application android:label="RawLens AF Probe" android:debuggable="true">
<uses-library android:name="android.test.runner" android:required="false"/>
</application><instrumentation android:name="androidx.test.runner.AndroidJUnitRunner"
android:targetPackage="com.matthew.afprobe"/></manifest>''')
        compiled = work / 'manifest.apk'
        apk = work / 'probe.apk'
        run([build_tools / 'aapt2', 'link', '-o', compiled, '--manifest', manifest, '-I', platform / 'android.jar'])
        with zipfile.ZipFile(args.test_apk) as tests, zipfile.ZipFile(args.app_apk) as app, zipfile.ZipFile(compiled) as meta, zipfile.ZipFile(apk, 'w', zipfile.ZIP_DEFLATED) as out:
            count = 0
            for item in tests.infolist():
                if item.filename == 'AndroidManifest.xml' or item.filename.startswith('META-INF/'):
                    continue
                out.writestr(item, tests.read(item))
                if re.fullmatch(r'classes\d*\.dex', item.filename):
                    count += 1
            # AGP excludes the target app's runtime from the instrumentation APK.
            for name in app.namelist():
                if re.fullmatch(r'classes\d*\.dex', name):
                    count += 1
                    out.writestr(f'classes{count}.dex', app.read(name))
            out.writestr('AndroidManifest.xml', meta.read('AndroidManifest.xml'))
        run([build_tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'androiddebugkey',
             '--ks-pass', 'pass:android', '--key-pass', 'pass:android', apk])
        run(adb + ['install', '-r', str(apk)])
        run(adb + ['shell', 'pm', 'grant', 'com.matthew.afprobe', 'android.permission.CAMERA'])
        command = adb + ['shell', 'am', 'instrument', '-w', '-e', 'rawlensAfProbe', 'true',
                         '-e', 'class', 'com.matthew.rawlens.ContinuousPictureAfDeviceTest',
                         '-e', 'cameraId', args.camera_id]
        if args.warm:
            command += ['-e', 'warm', 'true']
        if args.raw:
            command += ['-e', 'raw', 'true']
        if args.ae is not None:
            command += ['-e', 'ae', args.ae]
        if args.strategies:
            command += ['-e', 'strategies', args.strategies]
        command += ['com.matthew.afprobe/androidx.test.runner.AndroidJUnitRunner']
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # `am instrument` can exit zero on test failure; check its text too.
        result = run(command, capture_output=True, text=True)
        print(result.stdout)
        args.output.with_suffix('.instrumentation.txt').write_text(result.stdout + result.stderr)
        if 'OK (1 test)' not in result.stdout:
            raise SystemExit('Probe failed; see instrumentation output')
        run(adb + ['pull', '/sdcard/Android/data/com.matthew.afprobe/files/continuous-af-probe.txt', str(args.output)])


if __name__ == '__main__':
    main()
