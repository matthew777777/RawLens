#!/usr/bin/env python3
"""Build the owned metadata wrapper, never a modified vendor-library payload."""
import argparse
import pathlib
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--llvm-bin', type=pathlib.Path, required=True)
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
symbols = {'entry_count': 0xaf920, 'log_print': 0xaf420, 'original_body': 0x87244,
           'entry_for': 0xaf490, 'push_int': 0xaf3e0, 'push_long': 0xaf3f0,
           'update': 0xaf410, 'destroy': 0xaf450}
with tempfile.TemporaryDirectory() as work:
    elf = pathlib.Path(work) / 'hook.elf'
    subprocess.run([str(a.llvm_bin / 'aarch64-linux-android35-clang'), '-nostdlib',
                    '-no-pie', '-Wl,-Ttext=0x509a0', '-Wl,-e,hook',
                    *[f'-Wl,--defsym={k}={v:#x}' for k, v in symbols.items()],
                    str(root / 'tools/lapis-profile/metadata-hook.S'), '-o', str(elf)], check=True)
    subprocess.run([str(a.llvm_bin / 'llvm-objcopy'), '-O', 'binary', '--only-section=.text',
                    str(elf), str(root / 'app/src/main/assets/device-profiles/lapis-50mp-hook.bin')], check=True)
