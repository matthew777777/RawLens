#!/usr/bin/env python3
"""Run the locally installed Linux AOC in an amd64 container on macOS."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
COMPILER = ROOT / '.local/adreno-offline-compiler/7.0.26/aoc'


def main():
    if not COMPILER.is_file():
        print(f'Missing licensed compiler: {COMPILER}', file=sys.stderr)
        return 2
    # Preserve absolute host paths, including spaces. Sources and SDK stay read-only.
    # AOC stdout carries reports; transient compiler files live in container /tmp.
    command = ['docker', '--context',
               os.environ.get('RAWLENS_AOC_DOCKER_CONTEXT', 'colima-rawlens-shaders'),
               'run', '--rm', '--platform=linux/amd64',
               '--network=none', '--read-only', '--tmpfs', '/tmp:exec,size=512m',
               '--mount', f'type=bind,source={ROOT},target={ROOT},readonly',
               '--env', 'HOME=/tmp', '--workdir', '/tmp',
               '--entrypoint', str(COMPILER),
               os.environ.get('RAWLENS_AOC_IMAGE', 'rawlens-aoc:7.0.26'), *sys.argv[1:]]
    try:
        return subprocess.run(command).returncode
    except OSError as e:
        print(str(e), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
