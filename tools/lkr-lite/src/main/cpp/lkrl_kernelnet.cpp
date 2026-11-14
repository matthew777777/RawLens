// SPDX-License-Identifier: Apache-2.0
// KernelNet (PhotonCamera kernelnet_aniso_v2_2, the same model and shaping as the MFSR
// sr-pipeline's kernelnet.comp) on the CPU, plus a Wronski-style kernel average for Lite's 'A'
// step (prototype: measured before a GPU port).
#include "lkrl_kernelnet.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace lkrl {
namespace {

struct Layer { int cin, cout, k, stride, relu; std::vector<float> w, b; };

float half_to_float(uint16_t h) {
  const uint32_t s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
  uint32_t f;
  if (e == 0) {
    if (m == 0) f = s << 31;
    else {   // subnormal
      int ee = -1; uint32_t mm = m;
      do { ++ee; mm <<= 1; } while (!(mm & 0x400));
      f = (s << 31) | (uint32_t(127 - 15 - ee) << 23) | ((mm & 0x3ff) << 13);
    }
  } else if (e == 31) f = (s << 31) | 0x7f800000u | (m << 13);
  else f = (s << 31) | ((e + 127 - 15) << 23) | (m << 13);
  float out; std::memcpy(&out, &f, 4); return out;
}

bool load(std::vector<Layer>& L, const char* path) {
  FILE* f = std::fopen(path, "rb");
  if (!f) return false;
  std::vector<uint8_t> b;
  std::fseek(f, 0, SEEK_END); b.resize(size_t(std::ftell(f))); std::fseek(f, 0, SEEK_SET);
  const bool ok = std::fread(b.data(), 1, b.size(), f) == b.size();
  std::fclose(f);
  if (!ok) return false;
  const int spec[6][5] = {{2, 16, 3, 2, 1}, {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1},
                          {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1}, {16, 3, 1, 1, 0}};
  size_t off = 0;
  for (auto& sp : spec) {
    Layer l{sp[0], sp[1], sp[2], sp[3], sp[4], {}, {}};
    uint32_t tag; std::memcpy(&tag, &b[off], 4); off += 4;
    if (tag != 0x01306B47u) return false;   // ncnn fp16 weight blob
    const size_t n = size_t(l.cout) * l.cin * l.k * l.k;
    l.w.resize(n);
    for (size_t i = 0; i < n; ++i) { uint16_t h; std::memcpy(&h, &b[off + 2 * i], 2); l.w[i] = half_to_float(h); }
    off += 2 * n;
    l.b.resize(size_t(l.cout));
    std::memcpy(l.b.data(), &b[off], 4 * size_t(l.cout)); off += 4 * size_t(l.cout);
    L.push_back(std::move(l));
  }
  return off == b.size();
}

}  // namespace

bool KernelNetPacked(std::vector<float>* wb) {
  static std::vector<Layer> L;
  static bool tried = false, ok = false;
  if (!tried) {
    tried = true;
    const char* p = std::getenv("LKRL_KN_BIN");
    ok = load(L, p ? p : LKRL_KN_DEFAULT_BIN);
  }
  if (!ok) return false;
  wb->clear();
  for (const Layer& l : L) { wb->insert(wb->end(), l.w.begin(), l.w.end()); wb->insert(wb->end(), l.b.begin(), l.b.end()); }
  return true;
}

void KernelNetInput(const float* raw, int h, int w, float sigma, std::vector<float>* x) {
  const int lh = h / 2, lw = w / 2;
  x->assign(size_t(2) * lh * lw, sigma);
  for (int i = 0; i < lh; ++i)
    for (int j = 0; j < lw; ++j) {
      const size_t b = size_t(2 * i) * w + 2 * j;
      const float m = 0.25f * (raw[b] + raw[b + 1] + raw[b + w] + raw[b + w + 1]);
      (*x)[size_t(i) * lw + j] = std::sqrt(std::min(std::max(m, 0.0f), 1.0f));
    }
}

bool KernelNetMap(const float* raw, int h, int w, float sigma, std::vector<float>* map, int* mh, int* mw) {
  static std::vector<Layer> L;
  static bool tried = false, ok = false;
  if (!tried) {
    tried = true;
    const char* p = std::getenv("LKRL_KN_BIN");
    ok = load(L, p ? p : LKRL_KN_DEFAULT_BIN);
    if (!ok) std::fprintf(stderr, "lkr-lite: KernelNet weights not loaded\n");
  }
  if (!ok) return false;
  const int lh = h / 2, lw = w / 2;
  std::vector<float> x(size_t(2) * lh * lw);
  for (int i = 0; i < lh; ++i)
    for (int j = 0; j < lw; ++j) {
      const size_t b = size_t(2 * i) * w + 2 * j;
      const float m = 0.25f * (raw[b] + raw[b + 1] + raw[b + w] + raw[b + w + 1]);
      x[size_t(i) * lw + j] = std::sqrt(std::min(std::max(m, 0.0f), 1.0f));
      x[size_t(lh) * lw + size_t(i) * lw + j] = sigma;
    }
  int inH = lh, inW = lw;
  for (size_t li = 0; li < L.size(); ++li) {
    const Layer& l = L[li];
    const int r = l.k / 2;
    const int oh = (inH + 2 * r - l.k) / l.stride + 1, ow = (inW + 2 * r - l.k) / l.stride + 1;
    std::vector<float> y(size_t(l.cout) * oh * ow);
    for (int oc = 0; oc < l.cout; ++oc)
      for (int oy = 0; oy < oh; ++oy)
        for (int ox = 0; ox < ow; ++ox) {
          float a = l.b[size_t(oc)];
          for (int ic = 0; ic < l.cin; ++ic)
            for (int ky = 0; ky < l.k; ++ky) {
              const int iy = oy * l.stride + ky - r;
              if (iy < 0 || iy >= inH) continue;
              for (int kx = 0; kx < l.k; ++kx) {
                const int ix = ox * l.stride + kx - r;
                if (ix < 0 || ix >= inW) continue;
                a += l.w[((size_t(oc) * l.cin + ic) * l.k + ky) * l.k + kx] * x[(size_t(ic) * inH + iy) * inW + ix];
              }
            }
          y[(size_t(oc) * oh + oy) * ow + ox] = l.relu ? std::max(a, 0.0f) : a;
        }
    x.swap(y); inH = oh; inW = ow;
  }
  const size_t mp = size_t(inH) * inW;
  map->resize(3 * mp);
  for (size_t i = 0; i < 3 * mp; ++i) {
    const float sg = 1.0f / (1.0f + std::exp(-x[i]));
    (*map)[i] = 2.0f * sg - (i >= 2 * mp ? 1.0f : 0.0f);
  }
  *mh = inH; *mw = inW;
  return true;
}

void KernelOmega(const std::vector<float>& map, int mh, int mw, int h, int w, float xr, float yr,
                 float sigma_scale, float sigma_cap, float om[3]) {
  // = kernelnet.comp COV: bilinear map sample at raw position (xr, yr), anisoupscale shaping.
  const float u = xr / float(w) * float(mw) - 0.5f, v = yr / float(h) * float(mh) - 0.5f;
  const int u0 = std::min(std::max(int(std::floor(u)), 0), mw - 1), v0 = std::min(std::max(int(std::floor(v)), 0), mh - 1);
  const int u1 = std::min(u0 + 1, mw - 1), v1 = std::min(v0 + 1, mh - 1);
  const float fu = std::min(std::max(u - float(u0), 0.0f), 1.0f), fv = std::min(std::max(v - float(v0), 0.0f), 1.0f);
  const size_t mp = size_t(mh) * mw;
  float t[3];
  for (int c = 0; c < 3; ++c) {
    const float* M = map.data() + c * mp;
    const float a = M[size_t(v0) * mw + u0] + (M[size_t(v0) * mw + u1] - M[size_t(v0) * mw + u0]) * fu;
    const float b = M[size_t(v1) * mw + u0] + (M[size_t(v1) * mw + u1] - M[size_t(v1) * mw + u0]) * fu;
    t[c] = a + (b - a) * fv;
  }
  float s1 = std::max(t[0], 1e-4f), s2 = std::max(t[1], 1e-4f);
  const float rho = std::min(std::max(t[2], -0.8f), 0.8f);
  s1 = std::min(std::max(s1 * (float(w) / float(mw)) * sigma_scale, 0.90f), sigma_cap);
  s2 = std::min(std::max(s2 * (float(h) / float(mh)) * sigma_scale, 0.90f), sigma_cap);
  if (s1 > s2 * 7.0f) s1 = s2 * 7.0f; else if (s2 > s1 * 7.0f) s2 = s1 * 7.0f;
  const float det = std::max(1.0f - rho * rho, 1e-2f);
  const float re = (rho < 0 ? -1.0f : 1.0f) * std::sqrt(std::max(1.0f - det, 0.0f));
  om[0] = 0.5f * s2 * s2; om[1] = 0.5f * re * s1 * s2; om[2] = 0.5f * s1 * s1;   // xx, xy, yy (raw px^2)
}

}  // namespace lkrl
