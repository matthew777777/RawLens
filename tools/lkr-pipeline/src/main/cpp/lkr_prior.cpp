// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_prior.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <chrono>
#include <mutex>
#include <vector>

#include "lkr_parallel.h"
#include "net.h"
#if NCNN_VULKAN
#include "gpu.h"
#endif

namespace lkr {

struct FfdnetGatPrior::Impl {
  ncnn::Net net;
  bool loaded = false;
  bool vulkan = false;
  float a = 0, b = 0, sig_ref = 1;
  std::array<float, 3> wb{1, 1, 1};
  std::vector<float> in, out;
  Core core;
  NhwcCore nhwc;
  std::mutex mu;
  double t_pack = 0, t_run = 0, t_unpack = 0, t_wait = 0;
  int calls = 0;
  int core_h2 = 0, core_w2 = 0;
  std::string core_name;
};

FfdnetGatPrior::FfdnetGatPrior() : d_(new Impl) {}
FfdnetGatPrior::~FfdnetGatPrior() {
  d_->net.clear();
}

bool FfdnetGatPrior::load(const std::string& param_path, const std::string& bin_path, const Options& opt) {
  ncnn::Option o;
  o.num_threads = opt.threads;
  o.use_fp16_packed = opt.fp16;
  o.use_fp16_storage = opt.fp16;
  o.use_fp16_arithmetic = opt.fp16;
  o.use_bf16_storage = false;
  o.use_int8_inference = false;
#if NCNN_VULKAN
  d_->vulkan = opt.vulkan && ncnn::get_gpu_count() > 0;
  o.use_vulkan_compute = d_->vulkan;
#else
  d_->vulkan = false;
#endif
  d_->net.opt = o;
  if (d_->net.load_param(param_path.c_str()) != 0) return false;
  if (d_->net.load_model(bin_path.c_str()) != 0) return false;
  d_->loaded = true;
  return true;
}

void FfdnetGatPrior::setNoise(float a, float b, float sig_ref, const std::array<float, 3>& wb) {
  d_->a = a;
  d_->b = b;
  d_->sig_ref = sig_ref;
  d_->wb = wb;
}

std::string FfdnetGatPrior::backend() const {
  if (d_->core || d_->nhwc.lock_in) return d_->core_name;
  return d_->vulkan ? "ncnn-vulkan" : "ncnn-cpu";
}

void FfdnetGatPrior::setCore(Core core, int H2, int W2, const std::string& name) {
  d_->core = std::move(core);
  d_->core_h2 = H2;
  d_->core_w2 = W2;
  d_->core_name = name;
}

bool FfdnetGatPrior::runCore(const float* in, float* out, int H2, int W2) {
  if (!d_->loaded) return false;
  ncnn::Mat m(W2, H2, 13);
  for (int c = 0; c < 13; ++c) std::memcpy(m.channel(c), in + size_t(c) * H2 * W2, size_t(H2) * W2 * 4);
  ncnn::Extractor ex = d_->net.create_extractor();
  ex.input("in0", m);
  ncnn::Mat o;
  if (ex.extract("out0", o) != 0 || o.c != 12 || o.w != W2 || o.h != H2) return false;
  for (int c = 0; c < 12; ++c) std::memcpy(out + size_t(c) * H2 * W2, o.channel(c), size_t(H2) * W2 * 4);
  return true;
}

bool FfdnetGatPrior::operator()(const float* z, float* x, int H, int W, float sigma, const float* noise_scale) {
  using clk = std::chrono::steady_clock;
  auto ms = [](clk::time_point a, clk::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
  // Per-thread scratch: pack / unpack run concurrently across tile workers;
  // only the network call (and the NPU tensor buffers) is serialised below.
  thread_local std::vector<float> tin, tout;
  const auto t0 = clk::now();
  const int H2 = H / 2, W2 = W / 2;
  const bool nhwc = bool(d_->nhwc.lock_in) && d_->core_h2 == H2 && d_->core_w2 == W2;
  const bool ext = !nhwc && d_->core && d_->core_h2 == H2 && d_->core_w2 == W2;
  if ((!d_->loaded && !ext && !nhwc) || (H & 1) || (W & 1) || d_->a <= 0) return false;
  const size_t HW = size_t(H) * W, n2 = size_t(H2) * W2;
  float ac[3], bc[3];
  for (int c = 0; c < 3; ++c) {
    ac[c] = d_->a * d_->wb[c];
    bc[c] = d_->b * d_->wb[c] * d_->wb[c];
  }
  auto gat = [&](int c, float v) {
    const float xv = std::max(v * d_->wb[c], 0.0f);
    return 2.0f / ac[c] * std::sqrt(ac[c] * xv + 0.375f * ac[c] * ac[c] + bc[c]);
  };
  // GAT is monotonic in x: the normalisation range comes from per-channel
  // min/max of z, so no full GAT image is stored (one fused pack pass).
  float lo = INFINITY, hi = -INFINITY;
  for (int c = 0; c < 3; ++c) {
    const auto mm = std::minmax_element(z + c * HW, z + (c + 1) * HW);
    lo = std::min(lo, gat(c, *mm.first));
    hi = std::max(hi, gat(c, *mm.second));
  }
  const float span = std::max(hi - lo, 1e-6f), inv = 1.0f / span;
  const float r = sigma / d_->sig_ref;
  // Tensor views: element (channel ch, packed pixel i) at base[ch*cs + i*ps].
  // Pack into CACHED memory (NHWC for the NPU, CHW for ncnn); the NPU input
  // tensor is uncached device memory where scattered stores are very slow,
  // so it only ever receives one sequential memcpy.
  tin.resize(13 * n2);
  float* in = tin.data();
  const size_t ics = nhwc ? 1 : n2, ips = nhwc ? 13 : 1;
  // pixel_unshuffle (torch order: channel c*4 + dy*2 + dx) + noise map
  ParallelFor(H2, [&](int i0, int i1) {
    for (int i = i0; i < i1; ++i)
      for (int j = 0; j < W2; ++j) {
        const size_t pi = size_t(i) * W2 + j;
        float* o = in + pi * ips;
        for (int c = 0; c < 3; ++c)
          for (int dy = 0; dy < 2; ++dy)
            for (int dx = 0; dx < 2; ++dx)
              o[size_t(c * 4 + dy * 2 + dx) * ics] = (gat(c, z[c * HW + size_t(2 * i + dy) * W + 2 * j + dx]) - lo) * inv;
        // FFDNet noise map: per packed pixel, scaled by the 2x2 mean factor.
        float f = 1.0f;
        if (noise_scale) {
          const size_t p0 = size_t(2 * i) * W + 2 * j;
          f = 0.25f * (noise_scale[p0] + noise_scale[p0 + 1] + noise_scale[p0 + W] + noise_scale[p0 + W + 1]);
        }
        o[12 * ics] = std::min(r * f, 75.0f / 255.0f * span) * inv;
      }
  });
  const auto t1 = clk::now();
  tout.resize(12 * n2);
  double wait_ms = 0;
  {
    const auto tw = clk::now();
    std::lock_guard<std::mutex> lock(d_->mu);
    wait_ms = ms(tw, clk::now());
    if (nhwc) {
      float* dst = d_->nhwc.lock_in();
      if (!dst) return false;
      std::memcpy(dst, tin.data(), tin.size() * 4);
      if (!d_->nhwc.run()) return false;
      const float* src = d_->nhwc.lock_out();
      if (!src) return false;
      std::memcpy(tout.data(), src, tout.size() * 4);
      d_->nhwc.unlock_out();
    } else {
      const bool ran = ext ? d_->core(tin.data(), tout.data(), H2, W2) : runCore(tin.data(), tout.data(), H2, W2);
      if (!ran) return false;
    }
  }
  const float* out = tout.data();
  const size_t ocs = nhwc ? 1 : n2, ops = nhwc ? 12 : 1;
  const auto t2 = clk::now();
  const float rr = std::sqrt(1.5f);
  float s2[3];
  for (int c = 0; c < 3; ++c) s2[c] = bc[c] / (ac[c] * ac[c]);
  ParallelFor(H2, [&](int i0, int i1) {
    for (int i = i0; i < i1; ++i)
      for (int j = 0; j < W2; ++j) {
        const float* o = out + (size_t(i) * W2 + j) * ops;
        for (int c = 0; c < 3; ++c)
          for (int dy = 0; dy < 2; ++dy)
            for (int dx = 0; dx < 2; ++dx) {
              const float Dd = std::max(o[size_t(c * 4 + dy * 2 + dx) * ocs] * span + lo, 1e-3f);
              const float y = Dd < 1.5f ? 0.25f * Dd * Dd - 0.375f - s2[c]
                                        : 0.25f * Dd * Dd + 0.25f * rr / Dd - 11.0f / 8.0f / (Dd * Dd) +
                                              5.0f / 8.0f * rr / (Dd * Dd * Dd) - 0.125f - s2[c];
              x[c * HW + size_t(2 * i + dy) * W + 2 * j + dx] = ac[c] * y / d_->wb[c];
            }
      }
  });
  {
    std::lock_guard<std::mutex> lock(d_->mu);
    d_->t_pack += ms(t0, t1);
    d_->t_wait += wait_ms;
    d_->t_run += ms(t1, t2) - wait_ms;
    d_->t_unpack += ms(t2, clk::now());
    ++d_->calls;
  }
  return true;
}

std::string FfdnetGatPrior::timing() const {
  char b[160];
  const double n = d_->calls ? d_->calls : 1;
  std::snprintf(b, sizeof(b), "prior calls %d: wait %.1f pack %.1f run %.1f unpack %.1f ms/call", d_->calls,
                d_->t_wait / n, d_->t_pack / n, d_->t_run / n, d_->t_unpack / n);
  return b;
}

void FfdnetGatPrior::setNhwcCore(NhwcCore core, int H2, int W2, const std::string& name) {
  d_->nhwc = std::move(core);
  d_->core_h2 = H2;
  d_->core_w2 = W2;
  d_->core_name = name;
}

}  // namespace lkr
