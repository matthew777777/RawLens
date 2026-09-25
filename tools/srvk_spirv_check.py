#!/usr/bin/env python3
"""SPIR-V freshness for the cutover: the checked-in app assets must equal a
fresh transform of the app ESSL. Skips loudly without glslangValidator, or
when its version differs from the canonical pin (SPIR-V bytes are
version-sensitive; only the canonical toolchain may bless a regen).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CANONICAL_GLSLANG = "16.6.0"


def main():
    glslang = shutil.which("glslangValidator")
    if glslang is None:
        print("spirvCheck: SKIP (glslangValidator not installed)")
        return 0
    ver = subprocess.run([glslang, "--version"], capture_output=True, text=True).stdout
    first = ver.splitlines()[0] if ver.strip() else "?"
    if CANONICAL_GLSLANG not in first:
        print(f"spirvCheck: SKIP (need glslang {CANONICAL_GLSLANG}, have: {first})")
        return 0
    with tempfile.TemporaryDirectory(prefix="srvk-spirv-check") as tmp:
        r = subprocess.run(
            [sys.executable, "tools/srvk_shader_transform.py",
             "--shaders", "app/src/main/assets/shaders", "--out", tmp],
            cwd=ROOT, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"spirvCheck: transform failed:\n{r.stdout}\n{r.stderr}")
            return 1
        d = subprocess.run(
            ["diff", "-r", "-x", ".DS_Store", tmp, "app/src/main/assets/spirv"],
            cwd=ROOT, capture_output=True, text=True)
        if d.stdout.strip():
            print("spirvCheck: checked-in SPIR-V is stale; regenerate:\n"
                  "  python3 tools/srvk_shader_transform.py"
                  " --shaders app/src/main/assets/shaders --out app/src/main/assets/spirv\n"
                  + "\n".join(d.stdout.splitlines()[:20]))
            return 1
    print("spirvCheck: checked-in SPIR-V matches a fresh transform")
    return 0


if __name__ == "__main__":
    sys.exit(main())
