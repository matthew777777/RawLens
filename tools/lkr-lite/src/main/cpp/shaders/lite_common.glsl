// SPDX-License-Identifier: Apache-2.0
// LK-R Lite shared definitions. Forward model (same as LK-R): LR raw RGGB h x w,
// HR planar RGB H = s h, W = s w; pixel centres at +0.5; p_k = (a11 a12 tx a21 a22 ty)
// maps frame-k LR point u to the reference: A u + t (+ local block offset d_k(u)).
// Each LR sample averages s*s sub-samples, each bilinear in its own Bayer plane of z
// at q = s (A u + t + d). A sample is valid only if every tap lies inside the image.

layout(push_constant) uniform PC {
  int h, w, s, K;
  int H, W, LB, mode;
  float a, b, clip, robk;      // noise var = a y + b; clipped level; robust k
  float tau, rpow, scale, dscale;   // edge tolerance; rejection steepness; fixed-point scales (acc, coverage)
} pc;

int bayerChannel(int i, int j) {
  bool ei = (i & 1) == 0, ej = (j & 1) == 0;
  return (ei && ej) ? 0 : ((!ei && !ej) ? 2 : 1);
}

bool taps(vec2 q, out ivec2 x0, out vec2 f) {
  vec2 g = q - 0.5;
  vec2 fl = floor(g);
  x0 = ivec2(fl);
  f = g - fl;
  return x0.x >= 0 && x0.y >= 0 && x0.x + 1 < pc.W && x0.y + 1 < pc.H;
}

#ifdef LITE_WARP
layout(std430, binding = LITE_WARP) readonly buffer PD { float pm[]; };          // K x 6 affine
#ifndef LITE_WARP_RW
#define LITE_WARP_RW readonly
#endif
layout(std430, binding = LITE_WARP + 1) LITE_WARP_RW buffer LD { vec2 dloc[]; };     // K x nb local offsets
// Frame-k LR point u -> reference LR coordinates (affine + local block offset).
vec2 warpLR(int k, vec2 u) {
  int nbx = (pc.w + pc.LB - 1) / pc.LB, nby = (pc.h + pc.LB - 1) / pc.LB;
  int bx = clamp(int(u.x) / pc.LB, 0, nbx - 1), by = clamp(int(u.y) / pc.LB, 0, nby - 1);
  vec2 d = dloc[(k * nby + by) * nbx + bx];
  int o = k * 6;
  precise vec2 q = vec2(pm[o] * u.x + pm[o + 1] * u.y + pm[o + 2], pm[o + 3] * u.x + pm[o + 4] * u.y + pm[o + 5]) + d;
  return q;
}
vec2 warpQ(int k, vec2 u) { precise vec2 q = float(pc.s) * warpLR(k, u); return q; }
#endif

vec2 subPos(int i, int j, int ox, int oy) {
  float s = float(pc.s);
  return vec2(float(j) + (float(ox) + 0.5) / s, float(i) + (float(oy) + 0.5) / s);
}

// Frame-innermost workgroup order for 16x16 LR passes (dispatch gx = K * ceil(w/16)):
// the K frames of one block run back to back while their HR taps are in cache.
void lrIndex(out int k, out int i, out int j) {
  int wx = int(gl_WorkGroupID.x);
  k = wx % pc.K;
  j = (wx / pc.K) * 16 + int(gl_LocalInvocationID.x);
  i = int(gl_GlobalInvocationID.y);
}
