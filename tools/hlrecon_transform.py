#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate hlrecon Vulkan artifacts from the ESSL source of truth.

Replicates the transform rules proven by the checked-in hdr/ artifacts
(staged vs .vk.glsl diff + manifest.json entries):
  * `#version 450` + header comment.
  * Scalars/samplers lose their `uniform` keyword; scalar uniforms move
    into `layout(binding = B, std140) uniform SrUniforms` headed by
    `highp uvec3 u_dispatch_offset`, in source declaration order.
  * Opaque declarations (samplers, then storage images, source order) get
    sequential Vulkan bindings; storage keeps its ESSL binding as
    esslBinding in the manifest entry.
  * The `ivec2 o = ivec2(gl_GlobalInvocationID.xy);` line gains the
    dispatch-offset term for watchdog slicing.
  * UBO packing is true std140 (matches hdr_accumulate: uvec3@0,
    ivec2@16, ivec2@24, float@32..44, 48 bytes total).

Usage: tools/hlrecon_transform.py   (paths relative to repo root)
Writes: app/src/main/assets/spirv/hlrecon.vk.glsl
Prints: the manifest.json entry for "hl/hlrecon.glsl" on stdout.
Compile afterwards, e.g.:
  glslangValidator -V --target-env vulkan1.1 \\
      app/src/main/assets/spirv/hlrecon.vk.glsl -o app/src/main/assets/spirv/hlrecon.spv
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/assets/shaders/hl/hlrecon.glsl"
VK = ROOT / "app/src/main/assets/spirv/hlrecon.vk.glsl"
ASSET = "hl/hlrecon.glsl"

IMAGE_RE = re.compile(
    r"layout\(binding\s*=\s*(\d+)(?:\s*,\s*([a-z0-9]+))?\)\s*"
    r"(readonly|writeonly)?\s*uniform\s+highp\s+image2D\s+(\w+)\s*;"
)
SAMPLER_RE = re.compile(r"uniform\s+sampler2D\s+(\w+)\s*;\s*(?://.*)?$")
UNIFORM_RE = re.compile(
    r"uniform\s+(int|float|ivec2|ivec4|vec2|vec3|vec4)\s+(\w+)\s*;\s*(?://.*)?$")
COORD_RE = re.compile(r"ivec2\s+o\s*=\s*ivec2\(gl_GlobalInvocationID\.xy\);")


def main() -> int:
    lines = SRC.read_text().splitlines(keepends=True)
    uniforms = []  # (type, name, src_line)
    samplers = []  # (name, src_line)
    images = []  # (essl, format, qual, name, src_line)
    for n, line in enumerate(lines, start=1):
        s = line.strip()
        m = IMAGE_RE.fullmatch(s)
        if m:
            images.append((int(m.group(1)), m.group(2), m.group(3), m.group(4), n))
            continue
        m = SAMPLER_RE.fullmatch(s)
        if m:
            samplers.append((m.group(1), n))
            continue
        m = UNIFORM_RE.fullmatch(s)
        if m:
            uniforms.append((m.group(1), m.group(2), n))

    out = []
    out.append("#version 450\n")
    out.append(
        f"// hand-maintained replica of the tools/srvk_shader_transform.py output\n"
        f"// (script absent from checkout); ESSL source of truth is shaders/{ASSET}.\n"
        f"// Regenerate with tools/hlrecon_transform.py after editing the source.\n"
    )
    # UBO block: true std140 packing (scalar uniforms in declaration
    # order): each member starts at the next multiple of its base
    # alignment (int/float 4, ivec2/vec2 8, ivec3/vec3/ivec4/vec4 16);
    # the block size rounds up to 16. Verified against glslang -q
    # reflection of the generated file.
    block_lines = ["layout(binding = BIND_BLOCK, std140) uniform SrUniforms {\n"]
    block_lines.append("    highp uvec3 u_dispatch_offset;\n")
    offset = 12
    ubo_uniforms = {"u_dispatch_offset": (0, 12, "uvec3")}
    for ty, name, _ in uniforms:
        size = {"int": 4, "float": 4, "ivec2": 8, "ivec4": 16,
                "vec2": 8, "vec3": 12, "vec4": 16}[ty]
        align = {"int": 4, "float": 4, "ivec2": 8, "ivec4": 16,
                 "vec2": 8, "vec3": 16, "vec4": 16}[ty]
        offset = (offset + align - 1) // align * align
        ubo_uniforms[name] = (offset, size, ty)
        block_lines.append(f"    {ty} {name};\n")
        offset += size
    block_lines.append("};\n")
    block_bytes = (offset + 15) // 16 * 16

    # Opaque bindings in source declaration order: samplers first only if
    # declared first (hlrecon declares u_gamma before any image).
    opaque = []  # (name, vulkan_binding, kind, otype, format, essl)
    vk_binding = 0
    for name, _ in samplers:
        opaque.append((name, vk_binding, "sampled", "sampler2D", None, None))
        vk_binding += 1
    for essl, fmt, _qual, name, _ in images:
        opaque.append((name, vk_binding, "storage", "image2D", fmt, essl))
        vk_binding += 1
    block_binding = vk_binding

    for line in block_lines:
        out.append(line.replace("BIND_BLOCK", str(block_binding)))
    for line in lines:
        s = line.strip()
        if UNIFORM_RE.fullmatch(s):
            continue
        m = SAMPLER_RE.fullmatch(s)
        if m:
            vb = next(b for n_, b, *_ in opaque if n_ == m.group(1))
            out.append(f"layout(binding = {vb}) uniform sampler2D {m.group(1)};\n")
            continue
        m = IMAGE_RE.fullmatch(s)
        if m:
            essl = int(m.group(1))
            name = m.group(4)
            vb = next(b for n_, b, *_ in opaque if n_ == name)
            out.append(line.replace(f"binding = {essl}", f"binding = {vb}"))
            continue
        if COORD_RE.search(line):
            out.append(COORD_RE.sub(
                "ivec2 o = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);", line))
            continue
        out.append(line)
    VK.write_text("".join(out))

    entry = {
        "blockBinding": block_binding,
        "blockBytes": block_bytes,
        "uniforms": {
            name: {"offset": off, "bytes": size, "type": ty}
            for name, (off, size, ty) in ubo_uniforms.items()
        },
        "opaque": {
            name: {"binding": vb, "kind": kind, "otype": otype,
                   "format": fmt, "esslBinding": essl}
            for name, vb, kind, otype, fmt, essl in opaque
        },
    }
    print(json.dumps({ASSET: entry}, indent=2))
    print(f"blockBytes={block_bytes} opaque={len(opaque)}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
