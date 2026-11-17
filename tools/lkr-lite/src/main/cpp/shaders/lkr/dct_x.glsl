// SPDX-License-Identifier: Apache-2.0
// Non-learned x-step: sliding 8x8 orthonormal DCT (stride 4), empirical-Wiener
// coefficient shrinkage in luma / colour-difference space, Hann overlap-add.
// Independent of the solver: planar RGB in, planar RGB out, per-pixel noise.
// One channel per shader (CH: 0 luma, 1 R-G, 2 B-G); x accumulates (Y, U, V) and
// dct_yuv.comp converts it to RGB.
//
// Mobile layout (2026-11-17): one 8x8 block per 8 invocations, one block ROW each
// (workgroup 8 x 8 = 8 blocks). Row transform in registers, column transform through
// shared memory, block sums (noise level, mean, AC energy) by a shared reduction. The
// former one-block-per-invocation layout held 128 floats per thread: Mali-G615/G720
// spilled 336-520 B to stack at 64 registers (50 % occupancy), Adreno A750 used 37
// registers (25 % fiber occupancy). Same maths; only the separable transform order
// (rows first) differs, i.e. rounding.
//
// The block grid is dispatched in four phases (grid index mod 2): blocks of one phase
// never overlap, so x accumulates with plain read-modify-write, in place. Dispatch:
// x = ceil(((nx + 1) / 2) * ((ny + 1) / 2) / 8), y = 1. Requires H and W % 4 == 0.
#ifdef LKR_APERTURE
#include "dct_x_old.glsl"
#else
layout(local_size_x = 8, local_size_y = 8) in;
layout(std430, binding = 0) readonly buffer Z { vec4 z[]; };
layout(std430, binding = 1) buffer X { vec4 x[]; };
layout(std430, binding = 2) readonly buffer N { vec4 ns[]; };
// 1 / (sum of window over overlapping blocks): rows, then columns at ixo.
layout(std430, binding = 3) readonly buffer W { vec4 iw[]; };
// Mean noise level per block: written by the luma pass, read by the two others.
layout(std430, binding = 4) buffer B { float nv[]; };
layout(push_constant) uniform P {
  int H, W, ny, nx, py, px, ixo, has_ns;
  float sigma2, lv_a, lv_b, k_y2, k_c2, wb_r, wb_g, wb_b;
  float dark_level, dark_cap, dark_luma;
  float luma_cap, chroma_all, luma_floor, grain2;
} pc;

const float C4 = 0.35355339, C1 = 0.98078528, C2 = 0.92387953, C3 = 0.83146961,
            C5 = 0.55557023, C6 = 0.38268343, C7 = 0.19509032;
const vec4 WIN_A = vec4(0.03806023, 0.30865828, 0.69134172, 0.96193977);
const vec4 WIN_B = WIN_A.wzyx;

shared float S[8][8][9];   // [block][row][col], padded
shared float R[8][8];      // per-block reduction slots (one per row thread)
shared float R2[8][8];

void fdct8(inout float v[8]) {
  float s07 = v[0] + v[7], d07 = v[0] - v[7], s16 = v[1] + v[6], d16 = v[1] - v[6];
  float s25 = v[2] + v[5], d25 = v[2] - v[5], s34 = v[3] + v[4], d34 = v[3] - v[4];
  float a = s07 + s34, b = s16 + s25, c = s16 - s25, d = s07 - s34;
  v[0] = C4 * (a + b);
  v[4] = C4 * (a - b);
  v[2] = 0.5 * (C2 * d + C6 * c);
  v[6] = 0.5 * (C6 * d - C2 * c);
  v[1] = 0.5 * (C1 * d07 + C3 * d16 + C5 * d25 + C7 * d34);
  v[3] = 0.5 * (C3 * d07 - C7 * d16 - C1 * d25 - C5 * d34);
  v[5] = 0.5 * (C5 * d07 - C1 * d16 + C7 * d25 + C3 * d34);
  v[7] = 0.5 * (C7 * d07 - C5 * d16 + C3 * d25 - C1 * d34);
}

void idct8(inout float v[8]) {
  float o0 = 0.5 * (C1 * v[1] + C3 * v[3] + C5 * v[5] + C7 * v[7]);
  float o1 = 0.5 * (C3 * v[1] - C7 * v[3] - C1 * v[5] - C5 * v[7]);
  float o2 = 0.5 * (C5 * v[1] - C1 * v[3] + C7 * v[5] + C3 * v[7]);
  float o3 = 0.5 * (C7 * v[1] - C5 * v[3] + C3 * v[5] - C1 * v[7]);
  float p = C4 * (v[0] + v[4]), q = C4 * (v[0] - v[4]);
  float r = 0.5 * (C2 * v[2] + C6 * v[6]), s = 0.5 * (C6 * v[2] - C2 * v[6]);
  float e0 = p + r, e3 = p - r, e1 = q + s, e2 = q - s;
  v[0] = e0 + o0; v[7] = e0 - o0;
  v[1] = e1 + o1; v[6] = e1 - o1;
  v[2] = e2 + o2; v[5] = e2 - o2;
  v[3] = e3 + o3; v[4] = e3 - o3;
}

float shrink(float c, float t) {
  float c2 = c * c;
  return c * c2 / max(c2 + t, 1e-20);
}

float blockSum(int b, int r, float v) {   // all 8 row threads of block b get the block total
  R[b][r] = v;
  barrier();
  float s = R[b][0] + R[b][1] + R[b][2] + R[b][3] + R[b][4] + R[b][5] + R[b][6] + R[b][7];
  barrier();
  return s;
}

void main() {
  const int r = int(gl_LocalInvocationID.x), b = int(gl_LocalInvocationID.y);
  const int bnx = (pc.nx - pc.px + 1) / 2, bny = (pc.ny - pc.py + 1) / 2;
  const int blk = int(gl_WorkGroupID.x) * 8 + b;
  const int bx = blk % max(bnx, 1), by = blk / max(bnx, 1);
  const bool live = bnx > 0 && by < bny;   // inactive invocations still take part in the barriers
  const int ix = bx * 2 + pc.px, iy = by * 2 + pc.py;
  const int row = pc.W / 4, plane = pc.H * row;
  const int o = (iy * 4 + r) * row + ix;   // this thread's block row: vec4s o (cols 0-3), o + 1 (cols 4-7)

  float v[8];
  vec4 va = vec4(0.0), vb = vec4(0.0);
  float rowNoise = 0.0, rowTotalNoise = 0.0;
  if (live) {
#if CH == 0
    va = 0.25 * (z[o] * pc.wb_r + z[o + 2 * plane] * pc.wb_b) + 0.5 * z[o + plane] * pc.wb_g;
    vb = 0.25 * (z[o + 1] * pc.wb_r + z[o + 2 * plane + 1] * pc.wb_b) + 0.5 * z[o + plane + 1] * pc.wb_g;
    vec4 la = max(pc.lv_a * max(va, vec4(0.0)) + vec4(pc.lv_b), vec4(0.0));
    vec4 lb = max(pc.lv_a * max(vb, vec4(0.0)) + vec4(pc.lv_b), vec4(0.0));
    if (pc.has_ns != 0) {
      vec4 na = ns[o], nb = ns[o + 1];
      rowTotalNoise = dot(la * (na * na + 1.0), vec4(1.0)) + dot(lb * (nb * nb + 1.0), vec4(1.0));
      la *= na * na; lb *= nb * nb;
    }
    rowNoise = dot(la, vec4(1.0)) + dot(lb, vec4(1.0));
    if (pc.has_ns == 0) rowTotalNoise = rowNoise;
#else
    va = (CH == 1 ? z[o] * pc.wb_r : z[o + 2 * plane] * pc.wb_b) - z[o + plane] * pc.wb_g;
    vb = (CH == 1 ? z[o + 1] * pc.wb_r : z[o + 2 * plane + 1] * pc.wb_b) - z[o + plane + 1] * pc.wb_g;
#endif
  }
#if CH == 0
  float level = blockSum(b, r, rowNoise) / 64.0;
  float totalLevel = blockSum(b, r, rowTotalNoise) / 64.0;
  float ymean = blockSum(b, r, dot(va + vb, vec4(1.0))) / 64.0;
#endif

  // Rows (horizontal frequency) in registers, then columns through shared memory.
  v[0] = va.x; v[1] = va.y; v[2] = va.z; v[3] = va.w; v[4] = vb.x; v[5] = vb.y; v[6] = vb.z; v[7] = vb.w;
  fdct8(v);
  for (int k = 0; k < 8; ++k) S[b][r][k] = v[k];
  barrier();
  for (int j = 0; j < 8; ++j) v[j] = S[b][j][r];   // this thread now owns horizontal frequency r
  fdct8(v);                                         // v[j]: vertical frequency j

  float t;
  float dc = v[0];
#if CH == 0
  float gate = pc.dark_cap > 1.0 ? 1.0 - smoothstep(0.25 * pc.dark_level, pc.dark_level, ymean) : 0.0;
  float sm2 = pc.sigma2 * level;
  float boost = 1.0;
  float e = 0.0;
  for (int j = 0; j < 8; ++j) e += v[j] * v[j];
  float etot = blockSum(b, r, e);   // uniform per block: every invocation reaches it
  R2[b][r] = (r == 0) ? dc : 0.0;
  barrier();
  float bdc = R2[b][0];
  barrier();
  if (gate > 0.0 && sm2 > 0.0) {
    float ac = (etot - bdc * bdc) / 63.0;
    boost = mix(1.0, clamp(ac / sm2, 1.0, pc.dark_cap), gate);
  }
  float ty = pc.k_y2 * sm2;
  if (pc.grain2 > 0.0) {
    float relativeMean = ymean / 0.18;
    float tot = pc.sigma2 * totalLevel;
    ty = pc.k_y2 * min(max(tot - pc.grain2 * pc.sigma2 * relativeMean * relativeMean,
                           pc.luma_floor * tot), pc.luma_cap * tot);
  }
  t = ty * mix(1.0, boost, pc.dark_luma);
  if (live && r == 0)
    nv[iy * pc.nx + ix] = (pc.grain2 > 0.0 || pc.chroma_all > 0.0 ? totalLevel : level) * boost;
#else
  t = live ? pc.k_c2 * pc.sigma2 * nv[iy * pc.nx + ix] : 0.0;
#endif
  for (int j = 0; j < 8; ++j) v[j] = shrink(v[j], t);
  if (r == 0) v[0] = dc;

  idct8(v);
  for (int j = 0; j < 8; ++j) S[b][j][r] = v[j];
  barrier();
  for (int k = 0; k < 8; ++k) v[k] = S[b][r][k];
  idct8(v);

  if (!live) return;
  vec4 wxa = iw[pc.ixo + ix] * WIN_A, wxb = iw[pc.ixo + ix + 1] * WIN_B;
  float wy = r < 4 ? (iw[iy] * WIN_A)[r] : (iw[iy + 1] * WIN_B)[r - 4];
  const int oo = o + CH * plane;
  x[oo] += wxa * wy * vec4(v[0], v[1], v[2], v[3]);
  x[oo + 1] += wxb * wy * vec4(v[4], v[5], v[6], v[7]);
}

#endif // LKR_APERTURE
