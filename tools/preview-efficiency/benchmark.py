#!/usr/bin/env python3
"""Host CPU comparison against a git revision; not an Android power measurement.

Run the GithubDebug unit tests first to compile current classes. Uses only the
Kotlin compiler already cached by Gradle. Outputs live in a temporary directory.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', default='cb1f496b41cca3cf1ecb3474a9884510dbab1911')
args = parser.parse_args()
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'


def jar(group, artifact, version=None):
    base = cache / group / artifact
    candidates = list((base / version if version else base).rglob(f'{artifact}-*.jar'))
    candidates = [p for p in candidates if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if not candidates:
        raise SystemExit(f'Missing cached dependency: {base}')
    return str(sorted(candidates)[-1])


stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21')
compiler = [jar('org.jetbrains.kotlin', name, '2.4.21') for name in
            ('kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-script-runtime',
             'kotlin-daemon-embeddable')]
compiler += [stdlib, jar('org.jetbrains.kotlin', 'kotlin-reflect'),
             jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'),
             jar('org.jetbrains', 'annotations')]
classes = ROOT / 'app/build/intermediates/built_in_kotlinc/githubDebug/compileGithubDebugKotlin/classes'
if not classes.exists():
    raise SystemExit('Compile GithubDebug first.')

with tempfile.TemporaryDirectory(prefix='rawlens-preview-bench-') as temp:
    work = Path(temp)
    sources = []
    for name in ('VfLevels', 'BguSample', 'BguSlice'):
        path = f'app/src/main/java/com/matthew/rawlens/{name}.kt'
        text = subprocess.check_output(['git', 'show', f'{args.baseline}:{path}'], cwd=ROOT, text=True)
        dest = work / f'{name}Baseline.kt'
        dest.write_text(text.replace(name, name + 'Baseline'))
        sources.append(str(dest))
    cp = os.pathsep.join([str(classes), stdlib, str(work)])
    subprocess.run(['java', '-cp', os.pathsep.join(compiler),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', cp, '-d', str(work), *sources], check=True)
    bench = ROOT / 'tools/preview-efficiency/PreviewBench.java'
    subprocess.run(['javac', '-cp', cp, '-d', str(work), str(bench)], check=True)
    print('Baseline:', args.baseline, flush=True)
    subprocess.run(['java', '-cp', cp, 'com.matthew.rawlens.PreviewBench'], check=True)
