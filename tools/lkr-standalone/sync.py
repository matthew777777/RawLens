#!/usr/bin/env python3
"""Export the working-tree pipeline without rewriting a single source byte."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(__file__).resolve().parent / 'template'
MARKER = '.rawlens-standalone.json'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--check', action='store_true', help='Verify only; never write')
    args = parser.parse_args()
    dst = args.destination.resolve()
    if dst == ROOT or ROOT in dst.parents or dst in ROOT.parents:
        parser.error('Use a separate directory outside RawLens')
    tracked = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    prefixes = ('tools/lkr-pipeline/', 'tools/sr-pipeline/src/main/cpp/',
                'app/src/main/cpp/deps/tinydng/')
    paths = {p for p in tracked if p.startswith(prefixes) and (ROOT / p).is_file()}
    paths.update(['tools/sr-pipeline/host/npy.h', 'tools/sr-pipeline/LICENSE',
                  'tools/sr-pipeline/NOTICE', 'tools/lkr-pipeline/LICENSE',
                  'tools/lkr-pipeline/NOTICE'])
    sources = {p: ROOT / p for p in paths}
    for p in TEMPLATE.rglob('*'):
        if p.is_file():
            sources[p.relative_to(TEMPLATE).as_posix()] = p
    hashes = {p: digest(src) for p, src in sorted(sources.items())}
    marker = dst / MARKER
    old = json.loads(marker.read_text()) if marker.exists() else {'files': {}}
    for p in set(old['files']) | set(sources):
        rel = Path(p)
        if rel.is_absolute() or '..' in rel.parts or not rel.parts:
            raise SystemExit('Unsafe manifest path')
        target = dst / rel
        if target.is_symlink() or any(parent.is_symlink() for parent in target.parents if parent != dst and dst in parent.parents):
            raise SystemExit('Refusing managed symlink: ' + p)
    if args.check:
        bad = [p for p, sha in hashes.items() if not (dst / p).is_file() or digest(dst / p) != sha]
        bad += [p for p in old['files'] if p not in hashes and (dst / p).exists()]
        if old.get('files') != hashes or bad:
            raise SystemExit('Out of sync: ' + ', '.join(bad or ['manifest']))
        print(f'PASS: {len(hashes)} files are byte-for-byte identical (SHA-256).')
        return
    if dst.exists() and any(dst.iterdir()) and not marker.exists():
        parser.error('Destination must be empty or previously created by this exporter')
    # Refuse to overwrite edits made in the standalone checkout.
    conflicts = [p for p, sha in old['files'].items()
                 if not (dst / p).is_file() or digest(dst / p) not in {sha, hashes.get(p)}]
    if conflicts:
        raise SystemExit('Local changes: copy them back to RawLens first: ' + ', '.join(conflicts))
    for p in old['files']:
        if p not in sources:
            (dst / p).unlink()
    for p, src in sources.items():
        target = dst / p
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, target)
    provenance = {
        'source': 'https://github.com/matthew777777/RawLens',
        'commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip(),
        'snapshot': 'working tree; hashes include uncommitted changes',
        'files': hashes,
    }
    marker.write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n')
    print(f'Synced {len(hashes)} files to {dst}')


if __name__ == '__main__':
    main()
