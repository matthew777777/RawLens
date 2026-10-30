#!/usr/bin/env python3
"""Install only the isolated preview probe and run camera-free GPU checks."""
import argparse
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--build', type=Path, default=Path('/tmp/rawlens-preview-efficiency-build/_app'))
parser.add_argument('--output', type=Path, default=Path('/tmp/rawlens-preview-device-results'))
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
test_package = None
for relative in ('outputs/apk/github/deviceTest', 'outputs/apk/androidTest/github/deviceTest'):
    directory = args.build / relative
    meta = json.loads((directory / 'output-metadata.json').read_text())
    assert meta['applicationId'].startswith('com.matthew.rawlens.previewefficiencyprobe'), 'Refusing to replace daily app'
    if 'androidTest' in relative:
        test_package = meta['applicationId']
    apk = next((x for x in meta['elements'] if any(f.get('value') == 'arm64-v8a'
        for f in x.get('filters', []))), meta['elements'][0])
    subprocess.run([args.adb, 'install', '-r', str(directory / apk['outputFile'])], check=True)
classes = ','.join('com.matthew.rawlens.' + x for x in (
    'CompactPreviewUploadDeviceTest', 'PreviewTonemapEfficiencyDeviceTest',
    'VfLensPrecisionInstrumentedTest', 'BguGlSliceInstrumentedTest', 'PreviewSurfaceSizeDeviceTest'))
result = subprocess.run([args.adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    classes, test_package + '/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
report = result.stdout + result.stderr
(args.output / 'device-tests.txt').write_text(report)
print(report, flush=True)
logs = subprocess.run([args.adb, 'logcat', '-d', '-s', 'PreviewEfficiency:I', '*:S'],
    capture_output=True, text=True, check=True)
(args.output / 'gpu-benchmark.txt').write_text(logs.stdout)
print(logs.stdout, flush=True)
assert result.returncode == 0 and 'OK (5 tests)' in result.stdout, 'GPU checks failed; see report'
