// SPDX-License-Identifier: Apache-2.0
// CPU stages of the mobile merge path in native code: the same arithmetic as
// the Kotlin originals (LateralCa.estimate, MotionField.regularize) without
// boxing, bounds checks or per-element FloatBuffer calls.
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>

#include "srp_context.h"

// Kotlin never fuses a*b+c: keep the same rounding.
#pragma STDC FP_CONTRACT OFF

namespace {

// Items are handed out one by one: static shares would wait for the little cores.
template <class F>
void ParallelFor(int n, F f) {
  const int workers = std::min<int>(n, std::max(1u, std::min(8u, std::thread::hardware_concurrency())));
  std::atomic<int> next{0};
  auto work = [&] { for (int i; (i = next.fetch_add(1)) < n;) f(i); };
  std::vector<std::thread> pool;
  for (int t = 1; t < workers; ++t) pool.emplace_back(work);
  work();
  for (auto& t : pool) t.join();
}

struct Pt { double x, y, ux, uy, d; };

struct CaFit { float kR = 0, kB = 0, nR = 0, nB = 0; int points = 0; };

// LateralCa.estimate.
CaFit FitLateralCa(const float* rgb, int w, int h, float scale) {
  constexpr float kMaxK = 0.003f, kStep = 0.0000125f;
  constexpr int kMaxPoints = 40000, kMinPoints = 2000;
  constexpr float kMinNcc = 0.5f;
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double cx = w / 2.0, cy = h / 2.0, rMax = std::sqrt(cx * cx + cy * cy);
  const double step = 2.0 * std::max(1.0f, scale);
  const int m = static_cast<int>(step + 3);
  auto bil = [&](int ch, double x, double y) {
    if (x < 0 || y < 0 || x > w - 1.001 || y > h - 1.001) return nan;
    const int x0 = static_cast<int>(std::floor(x)), y0 = static_cast<int>(std::floor(y));
    const double fx = x - x0, fy = y - y0;
    auto v = [&](int xx, int yy) {
      const float* p = rgb + 3 * (static_cast<size_t>(yy) * w + xx);
      return ch == 1 ? static_cast<double>(p[1]) : p[ch] + static_cast<double>(p[1]);
    };
    const double a = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx;
    const double b = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx;
    const double r = a * (1 - fy) + b * fy;
    return std::isfinite(r) ? r : nan;
  };
  const int stride = std::max(1, static_cast<int>(std::sqrt(static_cast<double>(w) * h / (kMaxPoints * 8.0))));
  const int rows = h - 2 * m <= 0 ? 0 : (h - 2 * m - 1) / stride + 1;
  std::vector<std::vector<Pt>> rowPts(rows);
  ParallelFor(rows, [&](int i) {
    const int y = m + i * stride;
    for (int x = m; x < w - m; x += stride) {
      const double dx = x + 0.5 - cx, dy = y + 0.5 - cy, rr = std::sqrt(dx * dx + dy * dy);
      if (rr < 0.3 * rMax) continue;
      const double ux = dx / rr, uy = dy / rr;
      const double d = bil(1, x + step * ux, y + step * uy) - bil(1, x - step * ux, y - step * uy);
      if (!std::isnan(d)) rowPts[i].push_back({static_cast<double>(x), static_cast<double>(y), ux, uy, d});
    }
  });
  std::vector<Pt> cand;
  for (auto& r : rowPts) cand.insert(cand.end(), r.begin(), r.end());
  CaFit out;
  out.points = static_cast<int>(cand.size());
  if (out.points < kMinPoints) return out;
  std::vector<double> mags;
  for (const Pt& p : cand) mags.push_back(std::fabs(p.d));
  const size_t q = static_cast<size_t>(mags.size() * 0.8);
  std::nth_element(mags.begin(), mags.begin() + q, mags.end());
  const double thr = mags[q];
  std::vector<Pt> strong, pts;
  for (const Pt& p : cand) if (std::fabs(p.d) >= thr && thr > 0) strong.push_back(p);
  if (static_cast<int>(strong.size()) <= kMaxPoints) pts = strong;
  else for (int i = 0; i < kMaxPoints; ++i) pts.push_back(strong[static_cast<int64_t>(i) * strong.size() / kMaxPoints]);
  out.points = static_cast<int>(pts.size());
  if (out.points < kMinPoints) return out;
  // Scores a block of neighbouring scales per point (they read the same pixels);
  // every scale still sums the points in order.
  constexpr int kBlock = 6;
  auto ncc = [&](int ch, const double* ks, int count, double* res) {
    struct Sum { double gg = 0, pp = 0, gp = 0, g = 0, p = 0; int n = 0; } sum[kBlock];
    for (const Pt& p : pts) for (int i = 0; i < count; ++i) {
      const double k = ks[i];
      const double qx = cx + (p.x + 0.5 - cx) * (1 + k) - 0.5, qy = cy + (p.y + 0.5 - cy) * (1 + k) - 0.5;
      const double d = bil(ch, qx + step * p.ux, qy + step * p.uy) - bil(ch, qx - step * p.ux, qy - step * p.uy);
      if (std::isnan(d)) continue;
      Sum& s = sum[i];
      s.g += p.d; s.p += d; s.gg += p.d * p.d; s.pp += d * d; s.gp += p.d * d; ++s.n;
    }
    for (int i = 0; i < count; ++i) {
      const Sum& s = sum[i];
      const int n = s.n;
      if (n < kMinPoints) { res[i] = 0.0; continue; }
      const double cov = s.gp / n - s.g / n * s.p / n;
      const double vg = s.gg / n - (s.g / n) * (s.g / n), vp = s.pp / n - (s.p / n) * (s.p / n);
      res[i] = vg <= 0 || vp <= 0 ? 0.0 : cov / std::sqrt(vg * vp);
    }
  };
  auto fit = [&](int ch, float* kOut, float* nOut) {
    const double coarseStep = 8.0 * kStep;
    const int coarseN = static_cast<int>(kMaxK / coarseStep);
    std::vector<double> ks, ns;
    auto scan = [&] { ns.assign(ks.size(), 0.0); 
      const int n = static_cast<int>(ks.size());
      ParallelFor((n + kBlock - 1) / kBlock, [&](int b) {
        ncc(ch, &ks[b * kBlock], std::min(kBlock, n - b * kBlock), &ns[b * kBlock]);
      });
     };
    for (int i = -coarseN; i <= coarseN; ++i) ks.push_back(i * coarseStep);
    scan();
    const double c0 = ks[std::max_element(ns.begin(), ns.end()) - ns.begin()];
    ks.clear();
    for (int i = -8; i <= 8; ++i) {
      const double k = c0 + i * static_cast<double>(kStep);
      if (std::fabs(k) <= kMaxK + 1e-9) ks.push_back(k);
    }
    scan();
    const int i = static_cast<int>(std::max_element(ns.begin(), ns.end()) - ns.begin());
    double k = ks[i];
    if (i >= 1 && i < static_cast<int>(ks.size()) - 1) {
      const double a = ns[i - 1], b = ns[i], cc = ns[i + 1], den = a - 2 * b + cc;
      if (den < 0) k += kStep * std::min(0.5, std::max(-0.5, 0.5 * (a - cc) / den));
    }
    *nOut = static_cast<float>(ns[i]);
    const float kf = static_cast<float>(k);
    *kOut = *nOut >= kMinNcc && std::fabs(kf) < kMaxK - kStep ? kf : 0.0f;
  };
  fit(0, &out.kR, &out.nR);
  fit(2, &out.kB, &out.nB);
  return out;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeLateralCaFit(
    JNIEnv* env, jclass, jlong handle, jlong res, jint w, jint h, jfloat scale) {
  auto* ctx = reinterpret_cast<srp::Context*>(handle);
  const float* rgb = srp::SrpMapResident(ctx, res, 3 * static_cast<size_t>(w) * h);
  if (rgb == nullptr) return nullptr;
  const CaFit f = FitLateralCa(rgb, w, h, scale);
  srp::SrpUnmapResident(ctx, res);
  const float v[5] = {f.kR, f.kB, f.nR, f.nB, static_cast<float>(f.points)};
  jfloatArray out = env->NewFloatArray(5);
  env->SetFloatArrayRegion(out, 0, 5, v);
  return out;
}

// MotionField.regularize on an interleaved flow (in place).
extern "C" JNIEXPORT void JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMotionRegularize(
    JNIEnv* env, jclass, jfloatArray flowArr, jfloatArray weightArr, jint width, jint height, jfloat thresholdIn) {
  const int count = width * height;
  std::vector<float> observed(2 * count), current(2 * count), q(count);
  env->GetFloatArrayRegion(flowArr, 0, 2 * count, observed.data());
  env->GetFloatArrayRegion(weightArr, 0, count, q.data());
  ParallelFor(height, [&](int y) {
    float sx[25], sy[25];
    for (int x = 0; x < width; ++x) {
      int n = 0;
      for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
        const int i = (std::clamp(y + dy, 0, height - 1) * width + std::clamp(x + dx, 0, width - 1)) * 2;
        sx[n] = observed[i]; sy[n++] = observed[i + 1];
      }
      std::nth_element(sx, sx + 12, sx + 25);
      std::nth_element(sy, sy + 12, sy + 25);
      current[(y * width + x) * 2] = sx[12];
      current[(y * width + x) * 2 + 1] = sy[12];
    }
  });
  const float threshold = std::max(std::fabs(thresholdIn), 1e-3f);
  const float inv = 1.0f / (threshold * threshold);
  // Planar copies with one replicated tile around, so the row loop vectorises
  // (same operations per tile as before).
  const int stride = width + 2;
  std::vector<float> cx(stride * (height + 2)), cy(cx.size()), nx(cx.size()), ny(cx.size()), ox(count), oy(count);
  for (int t = 0; t < count; ++t) {
    const int i = (t / width + 1) * stride + t % width + 1;
    cx[i] = current[2 * t]; cy[i] = current[2 * t + 1];
    ox[t] = observed[2 * t]; oy[t] = observed[2 * t + 1];
  }
  auto replicate = [&](std::vector<float>& v) {
    for (int y = 1; y <= height; ++y) { v[y * stride] = v[y * stride + 1]; v[y * stride + width + 1] = v[y * stride + width]; }
    std::copy_n(v.begin() + stride, stride, v.begin());
    std::copy_n(v.begin() + height * stride, stride, v.begin() + (height + 1) * stride);
  };
  for (int it = 0; it < 160; ++it) {
    replicate(cx); replicate(cy);
    int32_t change = 0;   // bits of the largest |step| (ordered like the floats)
    for (int y = 0; y < height; ++y) {
      const float *__restrict fx = cx.data() + (y + 1) * stride + 1, *__restrict fy = cy.data() + (y + 1) * stride + 1;
      const float *__restrict px = ox.data() + y * width, *__restrict py = oy.data() + y * width, *__restrict c = q.data() + y * width;
      float *__restrict gx = nx.data() + (y + 1) * stride + 1, *__restrict gy = ny.data() + (y + 1) * stride + 1;
#pragma clang loop vectorize(assume_safety)
      for (int x = 0; x < width; ++x) {
        float sumX = 0, sumY = 0, sumW = 0;
        auto add = [&](int o) __attribute__((always_inline)) {
          const float dx = fx[x + o] - fx[x], dy = fy[x + o] - fy[x];
          const float w = 1.0f / std::sqrt(1.0f + (dx * dx + dy * dy) * inv);
          sumX += w * fx[x + o]; sumY += w * fy[x + o]; sumW += w;
        };
        add(-1); add(1); add(-stride); add(stride);
        gx[x] = (c[x] * px[x] + sumX / sumW) / (c[x] + 1.0f);
        gy[x] = (c[x] * py[x] + sumY / sumW) / (c[x] + 1.0f);
      }
      for (int x = 0; x < width; ++x) {
        const float ax = std::fabs(gx[x] - fx[x]), ay = std::fabs(gy[x] - fy[x]);
        int32_t bx, by;
        std::memcpy(&bx, &ax, 4);
        std::memcpy(&by, &ay, 4);
        change = std::max(change, std::max(bx, by));
      }
    }
    cx.swap(nx); cy.swap(ny);
    float maxChange;
    std::memcpy(&maxChange, &change, 4);
    if (maxChange < 1e-4f) break;
  }
  for (int t = 0; t < count; ++t) {
    const int i = (t / width + 1) * stride + t % width + 1;
    current[2 * t] = cx[i]; current[2 * t + 1] = cy[i];
  }
  env->SetFloatArrayRegion(flowArr, 0, 2 * count, current.data());
}

extern "C" JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeBatchBegin(JNIEnv*, jclass, jlong h, jint slot) {
  return static_cast<jint>(srp::SrpBatchBegin(reinterpret_cast<srp::Context*>(h), static_cast<uint32_t>(slot)));
}
extern "C" JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeBatchSubmit(JNIEnv*, jclass, jlong h) {
  return static_cast<jint>(srp::SrpBatchSubmit(reinterpret_cast<srp::Context*>(h)));
}
extern "C" JNIEXPORT jint JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeBatchWait(JNIEnv*, jclass, jlong h, jint slot) {
  return static_cast<jint>(srp::SrpBatchWait(reinterpret_cast<srp::Context*>(h), static_cast<uint32_t>(slot)));
}
extern "C" JNIEXPORT jlong JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCreateHostResident(JNIEnv*, jclass, jlong h, jlong n) {
  return srp::SrpCreateHostResident(reinterpret_cast<srp::Context*>(h), static_cast<size_t>(n));
}

// Persistent host view of a resident (frame staging written by the decode worker).
extern "C" JNIEXPORT jobject JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeMapResident(JNIEnv* env, jclass, jlong h, jlong res, jlong n) {
  const float* p = srp::SrpMapResident(reinterpret_cast<srp::Context*>(h), res, static_cast<size_t>(n));
  return p == nullptr ? nullptr : env->NewDirectByteBuffer(const_cast<float*>(p), n * 4);
}
extern "C" JNIEXPORT void JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeUnmapResident(JNIEnv*, jclass, jlong h, jlong res) {
  srp::SrpUnmapResident(reinterpret_cast<srp::Context*>(h), res);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeCreateImage(JNIEnv*, jclass, jlong h, jint w, jint ht, jint channels) {
  return srp::SrpCreateImage(reinterpret_cast<srp::Context*>(h), static_cast<uint32_t>(w), static_cast<uint32_t>(ht), static_cast<uint32_t>(channels));
}
extern "C" JNIEXPORT void JNICALL Java_com_rawlens_srpipeline_SrpBridge_nativeFreeImage(JNIEnv*, jclass, jlong h, jlong image) {
  srp::SrpFreeImage(reinterpret_cast<srp::Context*>(h), image);
}
