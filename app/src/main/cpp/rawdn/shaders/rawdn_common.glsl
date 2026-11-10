// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// RawLens raw-domain denoiser (CFA in, CFA out). Planes are stored as quads:
// one vec4 holds a 2x2 pixel cell (x = (0,0), y = (0,1), z = (1,0), w = (1,1)),
// so every stride-2 8x8 block is 4x4 aligned vec4 loads (Mali cost is per load
// instruction). A plane of a level with qw x qh quads sits at chan * qw * qh.
int quadIndex(int qy, int qx, int qw, int qh, int base) {
  return base + clamp(qy, 0, qh - 1) * qw + clamp(qx, 0, qw - 1);
}
