// SPDX-License-Identifier: GPL-3.0-or-later
// Non-learned x-step: sliding 8x8 orthonormal DCT (stride 4), empirical-Wiener
// coefficient shrinkage in luma / colour-difference space, Hann overlap-add.
// Independent of the solver: planar RGB in, planar RGB out, per-pixel noise.
// One channel per shader (CH: 0 luma, 1 R-G, 2 B-G) to stay within the Mali
// register file; x accumulates (Y, U, V) and dct_yuv.comp converts it to RGB.
//
// The block grid is dispatched in four phases (grid index mod 2): blocks of one
// phase never overlap, so x accumulates with plain read-modify-write, in place
// and without atomics or a separate weight buffer. Requires H and W % 4 == 0,
// so every access is an aligned vec4 (Mali cost is per load instruction).
layout(local_size_x = 4, local_size_y = 4) in;
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
} pc;

const float C4 = 0.35355339, C1 = 0.98078528, C2 = 0.92387953, C3 = 0.83146961,
            C5 = 0.55557023, C6 = 0.38268343, C7 = 0.19509032;
const vec4 WIN_A = vec4(0.03806023, 0.30865828, 0.69134172, 0.96193977);
const vec4 WIN_B = WIN_A.wzyx;

void fdct(inout vec4 v0, inout vec4 v1, inout vec4 v2, inout vec4 v3,
          inout vec4 v4, inout vec4 v5, inout vec4 v6, inout vec4 v7) {
  vec4 s07 = v0 + v7, d07 = v0 - v7, s16 = v1 + v6, d16 = v1 - v6;
  vec4 s25 = v2 + v5, d25 = v2 - v5, s34 = v3 + v4, d34 = v3 - v4;
  vec4 a = s07 + s34, b = s16 + s25, c = s16 - s25, d = s07 - s34;
  v0 = C4 * (a + b);
  v4 = C4 * (a - b);
  v2 = 0.5 * (C2 * d + C6 * c);
  v6 = 0.5 * (C6 * d - C2 * c);
  v1 = 0.5 * (C1 * d07 + C3 * d16 + C5 * d25 + C7 * d34);
  v3 = 0.5 * (C3 * d07 - C7 * d16 - C1 * d25 - C5 * d34);
  v5 = 0.5 * (C5 * d07 - C1 * d16 + C7 * d25 + C3 * d34);
  v7 = 0.5 * (C7 * d07 - C5 * d16 + C3 * d25 - C1 * d34);
}

void idct(inout vec4 v0, inout vec4 v1, inout vec4 v2, inout vec4 v3,
          inout vec4 v4, inout vec4 v5, inout vec4 v6, inout vec4 v7) {
  vec4 o0 = 0.5 * (C1 * v1 + C3 * v3 + C5 * v5 + C7 * v7);
  vec4 o1 = 0.5 * (C3 * v1 - C7 * v3 - C1 * v5 - C5 * v7);
  vec4 o2 = 0.5 * (C5 * v1 - C1 * v3 + C7 * v5 + C3 * v7);
  vec4 o3 = 0.5 * (C7 * v1 - C5 * v3 + C3 * v5 - C1 * v7);
  vec4 p = C4 * (v0 + v4), q = C4 * (v0 - v4);
  vec4 r = 0.5 * (C2 * v2 + C6 * v6), s = 0.5 * (C6 * v2 - C2 * v6);
  vec4 e0 = p + r, e3 = p - r, e1 = q + s, e2 = q - s;
  v0 = e0 + o0; v7 = e0 - o0;
  v1 = e1 + o1; v6 = e1 - o1;
  v2 = e2 + o2; v5 = e2 - o2;
  v3 = e3 + o3; v4 = e3 - o3;
}

vec4 shrink(vec4 c, float t) {
  vec4 c2 = c * c;
  return c * c2 / max(c2 + vec4(t), vec4(1e-20));
}

// a[r] = rows 0..7 of columns 0..3, b[r] = columns 4..7. Filtered in place.
void filt(inout mat4 a0, inout mat4 a1, inout mat4 b0, inout mat4 b1, float t) {
  fdct(a0[0], a0[1], a0[2], a0[3], a1[0], a1[1], a1[2], a1[3]);
  fdct(b0[0], b0[1], b0[2], b0[3], b1[0], b1[1], b1[2], b1[3]);
  mat4 p0 = transpose(a0), q0 = transpose(a1), p1 = transpose(b0), q1 = transpose(b1);
  fdct(p0[0], p0[1], p0[2], p0[3], p1[0], p1[1], p1[2], p1[3]);
  fdct(q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3]);
  float dc = p0[0].x;
  p0[0] = shrink(p0[0], t); p0[1] = shrink(p0[1], t); p0[2] = shrink(p0[2], t); p0[3] = shrink(p0[3], t);
  p1[0] = shrink(p1[0], t); p1[1] = shrink(p1[1], t); p1[2] = shrink(p1[2], t); p1[3] = shrink(p1[3], t);
  q0[0] = shrink(q0[0], t); q0[1] = shrink(q0[1], t); q0[2] = shrink(q0[2], t); q0[3] = shrink(q0[3], t);
  q1[0] = shrink(q1[0], t); q1[1] = shrink(q1[1], t); q1[2] = shrink(q1[2], t); q1[3] = shrink(q1[3], t);
  p0[0].x = dc;
  idct(p0[0], p0[1], p0[2], p0[3], p1[0], p1[1], p1[2], p1[3]);
  idct(q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3]);
  a0 = transpose(p0); a1 = transpose(q0); b0 = transpose(p1); b1 = transpose(q1);
  idct(a0[0], a0[1], a0[2], a0[3], a1[0], a1[1], a1[2], a1[3]);
  idct(b0[0], b0[1], b0[2], b0[3], b1[0], b1[1], b1[2], b1[3]);
}

void main() {
  int iy = int(gl_GlobalInvocationID.y) * 2 + pc.py, ix = int(gl_GlobalInvocationID.x) * 2 + pc.px;
  if (iy >= pc.ny || ix >= pc.nx) return;
  const int row = pc.W / 4, plane = pc.H * row;
  int o0 = iy * 4 * row + ix;
  mat4 a0, a1, b0, b1;
#if CH == 0
  float acc = 0.0;
#define LOAD(M, C, I)                                                                   \
  {                                                                                     \
    int o = o0 + (I) * row;                                                             \
    vec4 ya = 0.25 * (z[o] * pc.wb_r + z[o + 2 * plane] * pc.wb_b) + 0.5 * z[o + plane] * pc.wb_g;           \
    vec4 yb = 0.25 * (z[o + 1] * pc.wb_r + z[o + 2 * plane + 1] * pc.wb_b) + 0.5 * z[o + plane + 1] * pc.wb_g; \
    a##M[C] = ya; b##M[C] = yb;                                                         \
    vec4 la = max(pc.lv_a * max(ya, vec4(0.0)) + vec4(pc.lv_b), vec4(0.0));             \
    vec4 lb = max(pc.lv_a * max(yb, vec4(0.0)) + vec4(pc.lv_b), vec4(0.0));             \
    if (pc.has_ns != 0) {                                                               \
      vec4 na = ns[o], nb = ns[o + 1];                                                  \
      la *= na * na; lb *= nb * nb;                                                     \
    }                                                                                   \
    acc += dot(la, vec4(1.0)) + dot(lb, vec4(1.0));                                     \
  }
#else
#define LOAD(M, C, I)                                                                   \
  {                                                                                     \
    int o = o0 + (I) * row;                                                             \
    a##M[C] = (CH == 1 ? z[o] * pc.wb_r : z[o + 2 * plane] * pc.wb_b) - z[o + plane] * pc.wb_g;             \
    b##M[C] = (CH == 1 ? z[o + 1] * pc.wb_r : z[o + 2 * plane + 1] * pc.wb_b) - z[o + plane + 1] * pc.wb_g; \
  }
#endif
  LOAD(0, 0, 0) LOAD(0, 1, 1) LOAD(0, 2, 2) LOAD(0, 3, 3)
  LOAD(1, 0, 4) LOAD(1, 1, 5) LOAD(1, 2, 6) LOAD(1, 3, 7)
#if CH == 0
  float level = acc / 64.0;
  nv[iy * pc.nx + ix] = level;
  filt(a0, a1, b0, b1, pc.k_y2 * pc.sigma2 * level);
#else
  filt(a0, a1, b0, b1, pc.k_c2 * pc.sigma2 * nv[iy * pc.nx + ix]);
#endif
  vec4 wxa = iw[pc.ixo + ix] * WIN_A, wxb = iw[pc.ixo + ix + 1] * WIN_B;
  vec4 wya = iw[iy] * WIN_A, wyb = iw[iy + 1] * WIN_B;
#define SAVE(M, C, I, WY)                                                               \
  {                                                                                     \
    int o = o0 + (I) * row + CH * plane;                                                \
    x[o] += wxa * (WY) * a##M[C];                                                       \
    x[o + 1] += wxb * (WY) * b##M[C];                                                   \
  }
  SAVE(0, 0, 0, wya.x) SAVE(0, 1, 1, wya.y) SAVE(0, 2, 2, wya.z) SAVE(0, 3, 3, wya.w)
  SAVE(1, 0, 4, wyb.x) SAVE(1, 1, 5, wyb.y) SAVE(1, 2, 6, wyb.z) SAVE(1, 3, 7, wyb.w)
}
