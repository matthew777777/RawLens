// SPDX-License-Identifier: GPL-3.0-or-later
// Shared definitions for the LK-Reloaded solver (Lecouat et al. ICCV 2021).
//
// Conventions (identical to python/lkr/lkr.py and lkr_torch.py):
//   * LR raw is RGGB-normalised, h x w; HR image is 3 planes, H = s*h, W = s*w.
//   * Pixel centres at integer + 0.5. p_k = (a11 a12 tx a21 a22 ty) maps a point
//     u of frame k (LR coords) to the reference LR frame: A u + t.
//   * U_k = D B W_{p_k}: each LR pixel averages s*s sub-samples at
//     u = (j + (ox+.5)/s, i + (oy+.5)/s), each bilinearly sampled from its own
//     Bayer channel of the HR image at q = s * (A u + t).
//   * An LR pixel is valid only if every sub-sample has all 4 bilinear taps
//     inside the HR image; invalid pixels contribute nothing (r = 0).

layout(push_constant) uniform PC {
  int h, w, s, K;
  int H, W, kOff, mode;
  float eta, mu, scale, invScale;
  float f0, f1, f2, f3;
  float rob_a, rob_b, rob_k, rob_on;   // robust data term (see robustWeight)
} pc;

int bayerChannel(int i, int j) {
  bool ei = (i & 1) == 0, ej = (j & 1) == 0;
  return (ei && ej) ? 0 : ((!ei && !ej) ? 2 : 1);
}

// Bilinear tap setup for HR pixel-centre coordinate q. Returns false if any
// tap falls outside (the python 'valid' rule: x0>=0, y0>=0, x0+1<W, y0+1<H).
bool taps(vec2 q, out ivec2 x0, out vec2 a) {
  vec2 f = q - 0.5;
  vec2 fl = floor(f);
  x0 = ivec2(fl);
  a = f - fl;
  return x0.x >= 0 && x0.y >= 0 && x0.x + 1 < pc.W && x0.y + 1 < pc.H;
}

// Robust data term (not in the paper; its Fig. A8 failure case is moving
// objects): per-sample Cauchy weight against OUR noise model sigma^2 = a y + b
// (reference frame k = 0 always 1). The solver uses the neighbourhood version
// in weight.comp; this per-sample form is kept for experiments.
float robustWeight(float r, float yv, int k) {
  if (pc.rob_on < 0.5 || k == 0) return 1.0;
  float sig = sqrt(max(pc.rob_a * max(yv, 0.0) + pc.rob_b, 1e-12));
  float t = r / (pc.rob_k * sig);
  return 1.0 / (1.0 + t * t);
}

// Local motion (beyond the paper's affine-per-crop model): per-frame
// translation per BxB LR block on top of the tile affine, B = pc.kOff
// (0 = off). Shaders that warp define LKR_LOCAL_BINDING before the include.
#ifdef LKR_LOCAL_BINDING
layout(std430, binding = LKR_LOCAL_BINDING) readonly buffer LD { vec2 dloc[]; };
vec2 localD(int k, vec2 u) {
  if (pc.kOff <= 0) return vec2(0.0);
  int B = pc.kOff;
  int nbx = (pc.w + B - 1) / B, nby = (pc.h + B - 1) / B;
  int bx = clamp(int(u.x) / B, 0, nbx - 1), by = clamp(int(u.y) / B, 0, nby - 1);
  return dloc[(k * nby + by) * nbx + bx];
}
#else
vec2 localD(int k, vec2 u) { return vec2(0.0); }
#endif

vec2 subPos(int i, int j, int ox, int oy) {
  float s = float(pc.s);
  return vec2(float(j) + (float(ox) + 0.5) / s, float(i) + (float(oy) + 0.5) / s);
}
