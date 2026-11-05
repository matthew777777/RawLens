// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_photo_denoise.h"
#include "lkr_denoise.h"
#include "lkr_colour_guard.h"
#include "lkr_host.h"
#include "lkr_prior.h"
#include <cstdio>
#include <chrono>
#ifdef __ANDROID__
#include "lkr_litert.h"
#include <android/log.h>
#define LKR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "RawLensFfdnet", __VA_ARGS__)
#else
#define LKR_LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif
namespace lkr {
bool DenoisePhoto(uint16_t* rgba, int w, int h, const int* cfa, std::array<float, 3> wb,
                  float strength, const std::string& param, const std::string& bin,
                  const std::string& npu_dir, const std::string& npu_model, int chroma_stride, float luma, bool colour_guard,
                  PhotoBackend requested_backend, bool fp16, PhotoDenoiseStats* stats) {
  using Clock = std::chrono::steady_clock;
  auto elapsed = [](Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now()-t).count(); };
  PhotoDenoiseStats local;
  if (!stats) stats = &local;
  *stats = PhotoDenoiseStats{};
  auto stage = Clock::now();
  try {
    if (!std::isfinite(luma) || luma < 0 || luma > 1) return false;
    if (!rgba || !cfa || w < 64 || h < 64 || !std::isfinite(strength) || strength < 0 || strength > 1) return false;
    if (chroma_stride != 0 && chroma_stride != 2 && chroma_stride != 4) return false;
    if (requested_backend != PhotoBackend::Auto && requested_backend != PhotoBackend::Gpu &&
        requested_backend != PhotoBackend::Npu) return false;
    if (strength == 0) return true;
    int red = -1, greens = 0, blues = 0;
    for (int i = 0; i < 4; ++i) {
      if (cfa[i] == 0) red = i;
      else if (cfa[i] == 1) ++greens;
      else if (cfa[i] == 2) ++blues;
      else return false;
    }
    if (red < 0 || greens != 2 || blues != 1 || cfa[red ^ 3] != 2) return false;
    const float wg = wb[1];
    for (float& v : wb) {
      if (!std::isfinite(v) || v <= 0 || !std::isfinite(wg) || wg <= 0) return false;
      v /= wg;
      if (!std::isfinite(v) || v <= 0) return false;
    }
    // Re-sample native CFA sites from pre-sharpen camera RGB. The green
    // samples retain the sensor lattice used by the existing robust noise fit.
    // Shift to the R origin expected by NoiseAB; exclude incomplete edge quads.
    const int ox = red & 1, oy = red >> 1;
    const int rw = (w - ox) & ~1, rh = (h - oy) & ~1;
    float a, b;
    {
      std::vector<float> raw(size_t(rw) * rh);
      for (int y = 0; y < rh; ++y) for (int x = 0; x < rw; ++x) {
        const int sx = x + ox, sy = y + oy;
        const int c = cfa[(sy & 1) * 2 + (sx & 1)];
        const float v = lkr::ReadHalf(rgba[(size_t(sy) * w + sx) * 4 + c]);
        if (!std::isfinite(v)) return false;
        raw[size_t(y) * rw + x] = v;
      }
      lkr::NoiseAB(raw.data(), rh, rw, &a, &b);
    }
    if (!std::isfinite(a) || !std::isfinite(b) || a < 0 || b < 0) return false;
    // GAT uses a positive slope; a small floor also covers read-noise-only fits.
    a = std::max(a, 1e-5f);
    stats->noise_ms = elapsed(stage);
    stage = Clock::now();
    lkr::FfdnetGatPrior prior;
    lkr::FfdnetGatPrior::Options options;
    options.fp16 = fp16; // production default remains the FP32 quality baseline
    options.vulkan = requested_backend != PhotoBackend::Npu;
    if (!prior.load(param, bin, options)) return false;
    stats->model_ms = elapsed(stage);
    if (requested_backend == PhotoBackend::Gpu && prior.backend() != "ncnn-vulkan") {
      LKR_LOG("forced GPU unavailable; refusing CPU fallback");
      return false;
    }
    prior.setNoise(a, b, 1.f, wb);
    stage = Clock::now();
    // A single GAT range for the entire image avoids normalization changes
    // between tiles, including flat/shadow tiles and bright highlights.
    float lo = INFINITY, hi = -INFINITY;
    for (size_t i = 0; i < size_t(w) * h; ++i) for (int c = 0; c < 3; ++c) {
      const float v = lkr::ReadHalf(rgba[4 * i + c]);
      if (!std::isfinite(v)) return false;
      const float ac = a * wb[c], bc = b * wb[c] * wb[c];
      const float d = 2.f / ac * std::sqrt(ac * std::max(v * wb[c], 0.f) + .375f * ac * ac + bc);
      lo = std::min(lo, d); hi = std::max(hi, d);
    }
    prior.setNormalizationRange(lo, std::max(hi, lo + 1e-3f));
    stats->range_ms = elapsed(stage);
    stage = Clock::now();
#ifdef __ANDROID__
    lkr::LiteRtCore npu;
    std::string error;
    if (requested_backend != PhotoBackend::Gpu && !npu_dir.empty() && !npu_model.empty() && npu.open(npu_dir, npu_model, 256, 256, &error)) {
      lkr::FfdnetGatPrior::NhwcCore core;
      core.lock_in = [&] { return npu.lockInput(); };
      core.run = [&] { return npu.unlockAndRun(); };
      core.lock_out = [&] { return npu.lockOutput(); };
      core.unlock_out = [&] { npu.unlockOutput(); };
      prior.setNhwcCore(std::move(core), 256, 256, "litert-npu");
      LKR_LOG("prior on NPU (LiteRT, 256x256 packed)");
    } else if (requested_backend != PhotoBackend::Gpu) {
      LKR_LOG("NPU unavailable (%s): ncnn fallback", error.empty()
          ? "missing NPU library directory or model" : error.c_str());
    }
#endif
    stats->npu_open_ms = elapsed(stage);
    stats->backend = prior.backend() == "litert-npu" ? 3 : prior.backend() == "ncnn-vulkan" ? 2 : 1;
    if (requested_backend == PhotoBackend::Npu && stats->backend != 3) {
      LKR_LOG("forced NPU unavailable; refusing fallback");
      return false;
    }
    stage = Clock::now();
    std::vector<uint16_t> result(rgba, rgba + size_t(w) * h * 4);
    const auto infer = [&](const float* in, float* out, int th, int tw) {
      return prior(in, out, th, tw, 1.f);
    };
    bool ok = lkr::DenoiseRgbaHalf(result.data(), w, h, 1.f, infer);
    stats->primary_ms = elapsed(stage);
    if (ok && chroma_stride) {
      std::vector<uint16_t> reference;
      if (colour_guard) reference = result;
      stage = Clock::now();
      ok = lkr::DenoiseChromaRgbaHalf(rgba, result.data(), w, h, wb, infer, chroma_stride, 512, luma);
      stats->chroma_ms = elapsed(stage);
      stage = Clock::now();
      if (ok && colour_guard)
        ok = lkr::GuardPhotoChroma(reference.data(), result.data(), w, h, wb, a, b);
      stats->guard_ms = elapsed(stage);
    }
    stage = Clock::now();
    if (ok) {
      for (size_t i = 0; i < size_t(w) * h; ++i) for (int c = 0; c < 3; ++c) {
        const size_t j = 4*i+c;
        const float v = lkr::ReadHalf(rgba[j]) * (1-strength) + lkr::ReadHalf(result[j]) * strength;
        if (!std::isfinite(v) || std::abs(v) > 65504.f) return false;
        result[j] = lkr::WriteHalf(v);
      }
      std::copy(result.begin(), result.end(), rgba);
    }
    stats->blend_ms = elapsed(stage);
    const auto timings = prior.timings();
    stats->calls = timings.calls;
    stats->pack_ms = timings.pack_ms;
    stats->inference_ms = timings.run_ms;
    stats->unpack_ms = timings.unpack_ms;
    LKR_LOG("standalone FFDNet %dx%d a=%g b=%g strength=%.2f backend=%s chroma_stride=%d colour_guard=%d success=%d; %s",
            w, h, a, b, strength, prior.backend().c_str(), chroma_stride, int(colour_guard), int(ok), prior.timing().c_str());
    return ok;
  } catch (const std::exception& error) {
    LKR_LOG("standalone FFDNet failed: %s", error.what());
    return false;
  }
}
}
