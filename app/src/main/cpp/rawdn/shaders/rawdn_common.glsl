// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// RawLens raw-domain denoiser (CFA in, CFA out). Planes are stored as quads:
// one uvec2 (four snorm16, value = range x snorm) holds a 2x2 pixel cell (x = (0,0), y = (0,1), z = (1,0), w = (1,1)),
// so an 8x8 block is 4x2 aligned uvec4 loads of two quads (Mali cost is per load
// instruction, and half the bytes of fp32). A plane of a level with qw x qh quads sits at chan * qw * qh.
vec4 unq(uvec2 u) { return vec4(unpackSnorm2x16(u.x), unpackSnorm2x16(u.y)); }
uvec2 pkq(vec4 v) { return uvec2(packSnorm2x16(v.xy), packSnorm2x16(v.zw)); }
int quadIndex(int qy, int qx, int qw, int qh, int base) {
  return base + clamp(qy, 0, qh - 1) * qw + clamp(qx, 0, qw - 1);
}
