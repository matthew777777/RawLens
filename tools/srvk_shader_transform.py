#!/usr/bin/env python3
"""SR shader ESSL -> Vulkan GLSL transform (tools/srvk_shader_transform.py).

The phone compiles these shaders as ESSL 310 (`#version 310 es` prepended by
Gles31AmazeProcessor.ProgramCache). Vulkan cannot consume ESSL program
objects: opaque types need explicit descriptor bindings and free uniforms
must live in a block. This script applies that mapping MECHANICALLY at build
time; the .glsl sources stay byte-identical (parity-enforced) and this
mapping is the contract the unified Vulkan host implements:

  1. `#version 450` header (mirrors the phone's `#version 310 es` prepend).
  2. `#import ...` lines resolved exactly like ProgramCache (same files,
     same order, same quad/ condition).
  3. Opaque declarations (sampler/image) get `layout(binding=N)` in file
     order (multi-declarator lines split); existing layout params (formats)
     and access/precision qualifiers are preserved verbatim.
  4. Free non-opaque uniforms move verbatim into one std140 uniform block
     (next binding); references need no change (block members stay visible).
  5. A manifest records every binding/offset for the host; the script also
     compiles each result to SPIR-V with glslangValidator (proof of validity).

Sliced-dispatch preprocessing (rawsr/raw only, mirroring ProgramCache with
slicedDispatch=true): replicates RawSrGpuScheduling.shaderSource EXACTLY
(same requires, same declaration insert, same gl_GlobalInvocationID rewrite)
on the version-prepended source, so u_dispatch_offset is hoisted into the
uniform block like every other free uniform. tools/sr-vulkan ShaderStagingTest
pins staged ESSL == shaderSource(original) byte-for-byte (modulo the
sanctioned 310 es -> 450 version line), so this replication cannot drift.

Usage:
    python3 tools/srvk_shader_transform.py --shaders <shaders-dir> --out <dir>
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

UNIFORM_RE = re.compile(
    r"^(\s*)"
    r"(layout\s*\(([^;{}]*)\)\s*)?"
    r"((?:writeonly|readonly|coherent|volatile|restrict)\s+)*"
    r"uniform\s+(?:(highp|mediump|lowp)\s+)?"
    r"(\w+)\s+([^;{}]+);(.*)$"
)
OPAQUE = {
    "sampler2D", "usampler2D", "isampler2D", "sampler3D", "sampler2DArray",
    "image2D", "uimage2D", "iimage2D",
}
SCALARS = {"int": 4, "uint": 4, "float": 4, "bool": 4}
VEC_SIZES = {"": 1, "2": 2, "3": 3, "4": 4}


def std140_size(type_name):
    """(base_align, size) for the scalar/vector/matrix types the shaders use.

    Matrices are column-major: each column strides 16 bytes (std140 array
    rule), so mat3 occupies 48 bytes for 36 floats of data. The host packer
    must pad accordingly (the manifest records the type); the phone uploads
    the same 36 floats tightly via glUniformMatrix3fv.
    """
    m = re.fullmatch(r"mat([234])", type_name)
    if m:
        return 16, 16 * int(m.group(1))
    m = re.fullmatch(r"(int|uint|float|bool|vec|ivec|uvec)([234]?)", type_name)
    if not m:
        raise ValueError(f"unsupported uniform type for std140: {type_name}")
    kind, width = m.group(1), VEC_SIZES[m.group(2)]
    unit = 4
    if width == 1:
        return unit, unit
    if width == 2:
        return unit * 2, unit * 2
    return unit * 4, unit * width


def split_declarators(text):
    """'a,b[17],c' -> [('a', None), ('b', 17), ('c', None)]."""
    out = []
    for part in text.split(","):
        part = part.strip()
        m = re.fullmatch(r"(\w+)(?:\[(\d+)\])?", part)
        if not m:
            raise ValueError(f"cannot split uniform declarator: {part!r}")
        out.append((m.group(1), int(m.group(2)) if m.group(2) else None))
    return out


FORMAT_TOKENS = {"rgba32f", "rgba16f", "rgba8", "r32f", "r32ui", "r32i", "r16ui"}

GID = "gl_GlobalInvocationID"


def apply_sliced_preprocessing(versioned, name):
    """Exact replica of RawSrGpuScheduling.shaderSource (pinned by test)."""
    if not versioned.startswith("#version"):
        raise ValueError(f"{name}: staged source must start with #version")
    if GID not in versioned:
        raise ValueError(f"{name}: staged source lacks {GID}")
    if "gl_WorkGroupID" in versioned or "gl_NumWorkGroups" in versioned:
        raise ValueError(f"{name}: workgroup communication forbids slicing")
    end = versioned.index("\n")
    return (versioned[:end + 1] + "uniform highp uvec3 u_dispatch_offset;\n" +
            versioned[end + 1:].replace(GID, f"({GID} + u_dispatch_offset)"))


def transform(name, body, shaders_dir, sliced):
    # Step 0: replicate ProgramCache preprocessing exactly.
    def read(path):
        return (shaders_dir / path).read_text()
    body = body.replace("#import amaze", read("utils/import_amaze.glsl"))
    body = body.replace("#import quad", read("quad/common.glsl") if "quad/" in name else "")
    agx = shaders_dir / "display/agx_srgb8.glsl"
    agx_common = ""
    if agx.is_file():
        agx_source = agx.read_text()
        agx_common = agx_source[
            agx_source.index("uniform highp int u_display_p3;"):agx_source.index("void main()")]
    body = body.replace("#import agx_output", agx_common)

    # Staged ESSL: the exact input the phone compiler sees (modulo the
    # sanctioned version line). Pinned by ShaderStagingTest against
    # RawSrGpuScheduling.shaderSource.
    staged = "#version 450\n" + body
    if sliced:
        staged = apply_sliced_preprocessing(staged, name)

    lines = staged.splitlines()
    out = []
    opaque = {}
    block_members = []  # (type_text, declarators_text, comment, src_line)
    binding = 0
    for lineno, line in enumerate(lines, start=1):
        m = UNIFORM_RE.match(line)
        if not m or "{" in line:
            out.append(line)
            continue
        indent, _layout, layout_params, _access, precision, type_name, declarators, comment = (
            m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6),
            m.group(7), m.group(8),
        )
        access = (_access or "").strip()
        access = (access + " ") if access else ""
        precision = (precision + " ") if precision else ""
        if type_name in OPAQUE:
            kept = []
            image_format = None
            essl_binding = None
            if layout_params:
                for p in layout_params.split(","):
                    p = p.strip()
                    mb = re.match(r"(?i)^binding\s*=\s*(\d+)$", p)
                    if mb:
                        essl_binding = int(mb.group(1))
                        continue
                    if not p:
                        continue
                    kept.append(p)
                    if p.lower() in FORMAT_TOKENS:
                        image_format = p.lower()
            kind = "sampled" if "sampler" in type_name else "storage"
            for var_name, count in split_declarators(declarators):
                if count is not None:
                    raise ValueError(f"{name}:{lineno}: opaque arrays unsupported: {var_name}")
                if var_name in opaque:
                    raise ValueError(f"{name}:{lineno}: duplicate sampler {var_name}")
                opaque[var_name] = {"binding": binding, "kind": kind, "otype": type_name,
                                    "format": image_format, "esslBinding": essl_binding}
                params = ", ".join([f"binding = {binding}"] + kept)
                out.append(f"{indent}layout({params}) {access}uniform {precision}{type_name} {var_name};{comment}")
                comment = ""
                binding += 1
        else:
            block_members.append((f"{precision}{type_name}", declarators.strip(), comment, lineno))
            out.append(None)  # placeholder; block goes at the first one

    # Step 4: uniform block + std140 manifest.
    uniforms = {}
    block_binding = binding
    block_bytes = 0
    if block_members:
        offset = 0
        member_lines = []
        for type_text, declarators, comment, src_line in block_members:
            base_type = type_text.split()[-1]
            for var_name, count in split_declarators(declarators):
                if var_name in uniforms:
                    raise ValueError(f"{name}:{src_line}: duplicate uniform {var_name}")
                align, size = std140_size(base_type)
                type_tag = base_type
                if count is not None:
                    align, size = 16, 16 * count
                    type_tag = f"{base_type}[{count}]"
                offset = (offset + align - 1) // align * align
                uniforms[var_name] = {"offset": offset, "bytes": size, "type": type_tag}
                offset += size
            member_lines.append(f"    {type_text} {declarators};{comment} // src:{src_line}")
        block_bytes = (offset + 15) // 16 * 16
        block = [f"layout(binding = {block_binding}, std140) uniform SrUniforms {{"] + \
            member_lines + ["};"]
        first = next(i for i, l in enumerate(out) if l is None)
        out[first:first + 1] = block
        out = [l for l in out if l is not None]

    version_line, rest = "\n".join(out).split("\n", 1)
    assert version_line == "#version 450", f"{name}: version line moved during hoist"
    vk_source = (version_line + "\n" +
                 f"// generated by tools/srvk_shader_transform.py from {name} (sources stay ESSL)\n" +
                 rest + "\n")
    return vk_source, staged, {
        "blockBinding": block_binding, "blockBytes": block_bytes,
        "uniforms": uniforms, "opaque": opaque,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shaders", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--assets", nargs="*",
                        default=["rawsr", "raw", "quad"],
                        help="shader subdirs (relative) to transform")
    parser.add_argument("--sliced-subdirs", nargs="*",
                        default=["rawsr", "raw"],
                        help="subdirs whose dispatch is sliced (u_dispatch_offset injected)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"version": 2, "shaders": {}}
    failures = 0
    for sub in args.assets:
        for path in sorted((args.shaders / sub).glob("*.glsl")):
            if path.name == "common.glsl":
                continue  # include file, transformed with its importers
            name = f"{sub}/{path.name}"
            try:
                source, staged, entry = transform(
                    name, path.read_text(), args.shaders, sliced=sub in args.sliced_subdirs)
            except (ValueError, AssertionError) as e:
                print(f"TRANSFORM-FAIL {name}: {e}")
                failures += 1
                continue
            (args.out / (path.stem + ".staged.glsl")).write_text(staged)
            vk_path = args.out / (path.stem + ".vk.glsl")
            vk_path.write_text(source)
            spv_path = args.out / (path.stem + ".spv")
            proc = subprocess.run(
                ["glslangValidator", "-S", "comp", "--target-env", "vulkan1.2",
                 "-V", "-o", str(spv_path), str(vk_path)],
                capture_output=True, text=True)
            if proc.returncode != 0 or not spv_path.is_file():
                print(f"COMPILE-FAIL {name}:\n{proc.stdout}\n{proc.stderr}")
                failures += 1
                continue
            manifest["shaders"][name] = entry
            print(f"ok {name}: {len(entry['opaque'])} opaque, "
                  f"{len(entry['uniforms'])} uniforms, block={entry['blockBytes']}B")
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"transformed {len(manifest['shaders'])} shaders, {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
