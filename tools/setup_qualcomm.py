#!/usr/bin/env python3
"""Fetch pinned Qualcomm dependencies on macOS, Windows or Linux (Python 3)."""
import argparse
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

VERSION = '2.50.0.260828'
AAR = 'litert-npu-runtime-qualcomm-2.3.2.aar'
ROOT = Path(__file__).resolve().parents[1] / '.local' / 'qualcomm'
SDK_URL = f'https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/{VERSION}/v{VERSION}.zip'
AAR_URL = f'https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-qualcomm/2.3.2/{AAR}'
LIBS = [f'lib/aarch64-android/{name}.so' for name in ('libQnnSystem', 'libQnnHtp', 'libQnnHtpPrepare')]
for arch in (69, 73, 75, 79, 81):
    LIBS += [f'lib/aarch64-android/libQnnHtpV{arch}Stub.so',
             f'lib/hexagon-v{arch}/unsigned/libQnnHtpV{arch}Skel.so']


def download(url, destination):
    print(f'Downloading {url}', flush=True)
    with urllib.request.urlopen(url, timeout=120) as response, destination.open('wb') as output:
        shutil.copyfileobj(response, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-zip', type=Path, help='Use an already downloaded QAIRT ZIP')
    parser.add_argument('--runtime-aar', type=Path, help='Use an already downloaded LiteRT AAR')
    args = parser.parse_args()
    ROOT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='qualcomm-', dir=ROOT) as temporary:
        staging = Path(temporary)
        if not all((ROOT / VERSION / lib).is_file() for lib in LIBS):
            archive = args.sdk_zip or staging / 'sdk.zip'
            if not args.sdk_zip:
                download(SDK_URL, archive)
            with zipfile.ZipFile(archive) as sdk:
                for lib in LIBS:
                    target = ROOT / VERSION / lib
                    data = sdk.read(f'qairt/{VERSION}/{lib}')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
        if args.runtime_aar or not (ROOT / AAR).is_file():
            archive = args.runtime_aar or staging / AAR
            if not args.runtime_aar:
                download(AAR_URL, archive)
            with zipfile.ZipFile(archive) as aar:
                for name in ('libLiteRtCompilerPlugin_Qualcomm.so', 'libLiteRtDispatch_Qualcomm.so'):
                    assert aar.read(f'jni/arm64-v8a/{name}')[:4] == b'\x7fELF', name
            shutil.copyfile(archive, ROOT / AAR)
    print(f'Qualcomm dependencies ready: {ROOT}')


if __name__ == '__main__':
    main()
