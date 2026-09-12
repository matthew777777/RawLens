#!/usr/bin/env python3
"""Build the Android-vendored C writer on the host and check its serialized output."""
from pathlib import Path
import subprocess
import hashlib
import json
import shutil
import tempfile
root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/cpp/deps/tinydng'
names = ['tinydng_api', 'tinydng_io', 'tinydng_tiff', 'tinydng_dng', 'tinydng_codec',
         'tinydng_write', 'tinydng_psd', 'tinydng_psd_write', 'tiny_dng_ljpeg92_v2']
with tempfile.TemporaryDirectory(prefix='rawlens-tinydng-') as tmp:
    manifest = json.loads((src/'UPSTREAM.json').read_text())
    reference = root/'references/tinydng'
    if reference.exists():
        reproduced = Path(tmp)/'reproduced'
        reproduced.mkdir()
        for name, digest in manifest['sha256'].items():
            data = (reference/name).read_bytes()
            assert hashlib.sha256(data).hexdigest() == digest, name
            (reproduced/name).write_bytes(data)
        subprocess.run(['patch', '-p1', '-i', str(src/'rawlens.patch')], cwd=reproduced, check=True)
        for name in manifest['sha256']:
            assert (reproduced/name).read_bytes() == (src/name).read_bytes(), name
        print('PASS: vendored sources match pinned upstream plus recorded patch', flush=True)
    exe, dng = Path(tmp)/'roundtrip', Path(tmp)/'roundtrip.dng'
    subprocess.run(['cc', '-std=c11', '-O1', '-g', '-fsanitize=address,undefined',
        '-DTINYDNG_NO_ZIP', '-DTINYDNG_NO_BASELINE_JPEG', '-DTINYDNG_NO_PSD',
        '-DTINYDNG_DISABLE_THREADS', '-I'+str(src),
        *[str(src/(n+'.c')) for n in names], str(root/'tools/tinydng_roundtrip.c'),
        '-lm', '-o', str(exe)], check=True)
    subprocess.run([str(exe), str(dng)], check=True)
    subprocess.run(['python3', str(root/'tools/verify_dng_gainmap.py'), str(dng)], check=True)
