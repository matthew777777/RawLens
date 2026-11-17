#!/usr/bin/env python3
"""Run offline GPU analysis against SPIR-V, retaining vendor reports and hashes."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def run(command):
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=180)
        return {"command": command, "returncode": p.returncode,
                "stdout": p.stdout, "stderr": p.stderr}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"command": command, "returncode": -1, "error": str(e)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("inputs", nargs="*", type=Path,
                   help="SPIR-V files or directories; default: packaged app assets")
    p.add_argument("--backend", choices=("mali", "adreno", "both"), default="mali")
    p.add_argument("--core", action="append", help="Mali core; repeat for multiple GPUs")
    p.add_argument("--malioc", default=os.environ.get("MALIOC"))
    p.add_argument("--adreno-command", type=Path,
                   help="JSON argv template from installed AOC documentation; {input}/{output}/{name}")
    p.add_argument("--adreno-core", action="append", help="Adreno GPU, repeatable; default a750")
    p.add_argument("--output", type=Path, default=ROOT / "build/shader-analysis")
    p.add_argument("--jobs", type=int, default=4)
    a = p.parse_args()
    if a.jobs < 1:
        p.error("--jobs must be positive")
    if any(Path(c).name != c or c in (".", "..")
           for c in (a.core or []) + (a.adreno_core or [])):
        p.error("GPU core must be a name, not a path")
    malioc = a.malioc or shutil.which("malioc")
    if not malioc:
        candidates = sorted(Path("/Applications").glob(
            "Arm Performance Studio */mali_offline_compiler/malioc"), reverse=True)
        malioc = str(candidates[0]) if candidates else None
    if a.backend in ("mali", "both") and not malioc:
        p.error("Mali compiler missing: install Arm Performance Studio or set MALIOC")
    template = None
    if a.backend in ("adreno", "both"):
        if not a.adreno_command:
            if not (ROOT / '.local/adreno-offline-compiler/7.0.26/aoc').is_file():
                p.error("Adreno requires --adreno-command: install licensed AOC first; see README.md")
            a.adreno_command = Path(__file__).with_name('aoc-command.json')
        template = json.loads(a.adreno_command.read_text())
        if (not isinstance(template, list) or not template
                or not all(isinstance(t, str) for t in template)
                or not any("{input}" in t for t in template)):
            p.error("Adreno command must be a nonempty JSON array containing {input}")
    files = set()
    for path in a.inputs or [ROOT / "app/src/main/assets"]:
        if path.is_dir():
            files.update(f.resolve() for f in path.rglob("*.spv"))
        elif path.is_file() and path.suffix == ".spv":
            files.add(path.resolve())
        else:
            p.error(f"Missing input or not SPIR-V: {path}")
    if not files:
        p.error("No SPIR-V inputs found")
    a.output = a.output.resolve()
    a.output.mkdir(parents=True, exist_ok=True)
    tasks = []
    for path in sorted(files):
        name = path.stem + "-" + hashlib.sha256(str(path).encode()).hexdigest()[:12]
        if a.backend in ("mali", "both"):
            for core in a.core or ["Mali-G615"]:
                tasks.append((path, name, "mali", core))
        if template:
            for core in a.adreno_core or ["a750"]:
                tasks.append((path, name, "adreno", core))

    def analyze(task):
        path, name, backend, core = task
        directory = a.output / backend / core / name
        directory.mkdir(parents=True, exist_ok=True)
        if backend == "mali":
            command = [malioc, "--vulkan", "--spirv", "--core", core,
                       "--format", "json", str(path)]
        else:
            values = {"{input}": str(path), "{output}": str(directory / "vendor"),
                      "{name}": name, "{root}": str(ROOT), "{core}": core}
            if any('{stage}' in token for token in template):
                # OpEntryPoint execution model, independent of the file suffix.
                data = path.read_bytes()
                words = struct.unpack('<' + 'I' * (len(data) // 4), data)
                stages = {0: '-vs', 1: '-tcs', 2: '-tes', 3: '-gs', 4: '-fs', 5: '-cs'}
                index, models = 5, set()
                while index < len(words):
                    length, opcode = words[index] >> 16, words[index] & 65535
                    if not length:
                        raise ValueError(f'Malformed SPIR-V: {path}')
                    if opcode == 15:
                        models.add(words[index + 1])
                    index += length
                if len(models) != 1 or next(iter(models)) not in stages:
                    raise ValueError(f'AOC requires one supported entry-point stage: {path}')
                values['{stage}'] = stages[next(iter(models))]
            command = []
            for token in template:
                for key, value in values.items():
                    token = token.replace(key, value)
                command.append(token)
        result = run(command)
        result.update(input=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                      backend=backend, core=core)
        if result["returncode"] == 0 and (backend == "mali" or '-output_format=json' in command):
            try:
                report = json.loads(result["stdout"])
                (directory / "vendor.json").write_text(json.dumps(report, indent=2) + "\n")
                result['producer'] = report.get('producer', {})
                if backend == 'adreno' and report.get('compilation_status') != 'Compilation succeeded':
                    result['returncode'] = -1
                    result['error'] = report.get('compilation_status', 'Missing AOC compilation status')
            except ValueError:
                result["returncode"] = -1
                result["error"] = f"{backend} returned invalid JSON"
        (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as pool:
        results = list(pool.map(analyze, tasks))
    summary = {"results": results, "passed": sum(r["returncode"] == 0 for r in results),
               "failed": sum(r["returncode"] != 0 for r in results)}
    if a.backend in ("mali", "both"):
        summary["malioc_version"] = run([malioc, "--version"])
    (a.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"{summary['passed']} passed, {summary['failed']} failed; {a.output / 'summary.json'}")
    for r in results:
        if r["returncode"]:
            print(f"FAIL {r['backend']} {r['core']} {r['input']}", file=sys.stderr)
    return bool(summary["failed"])


if __name__ == "__main__":
    sys.exit(main())
