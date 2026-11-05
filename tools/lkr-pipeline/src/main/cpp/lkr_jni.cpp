// SPDX-License-Identifier: GPL-3.0-or-later
// JNI for LK-Reloaded (app: com.matthew.rawlens.LkrBridge).
//
// nativeMerge takes the caller's direct uint16 RAW buffers as-is (no Java
// heap copies; BurstMergeRunner allocates them with HdrPlusVulkan.allocBuffer)
// and returns a malloc'd direct ByteBuffer of interleaved float RGB in sensor
// coordinates, freed with SrpBridge.nativeFreeDirect (std::free), the same
// contract as MfsrMerge.MfsrResult. One Gpu + prior per call: the Vulkan
// device lives only for the merge.
#include <jni.h>
#include <chrono>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "lkr_full.h"
#include "lkr_tuning.h"
#include "lkr_resample.h"

#include <cmath>
#include "lkr_prior.h"
#ifdef __ANDROID__
#include "lkr_litert.h"
#endif

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#define LKR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "RawLensLkr", __VA_ARGS__)
#else
#include <cstdio>
#define LKR_LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

// stats layout (float[16]): tiles, sigma, a, b, ms_noise, ms_coarse, ms_tiles,
// ms_gpu, ms_prior, ms_total, prior_calls, out_w, out_h, backend (0 none,
// 1 ncnn-cpu, 2 ncnn-vulkan, 3 litert-npu), reserved...
jobject Merge(JNIEnv* env, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa_j,
              jfloatArray black_j, jfloat white, jfloatArray wb_j, jint s, jint tile, jint overlap,
              jintArray crop_j, jstring param_j, jstring bin_j, jboolean fp16, jstring npu_dir_j,
              jstring npu_model_j, jboolean paper, jfloatArray stats_j, jfloatArray tuning_j, jint out_w_req,
              jint out_h_req) {
  const jsize K = env->GetArrayLength(frames);
  std::vector<lkr::RawFrame> fr;
  for (jsize k = 0; k < K; ++k) {
    jobject b = env->GetObjectArrayElement(frames, k);
    void* p = b ? env->GetDirectBufferAddress(b) : nullptr;
    const jlong cap = b ? env->GetDirectBufferCapacity(b) : 0;
    if (!p || cap < jlong(row_stride_px) * h * 2) {
      LKR_LOG("frame %d: not a direct buffer or too small", int(k));
      return nullptr;
    }
    fr.push_back({static_cast<const uint16_t*>(p), row_stride_px});
    env->DeleteLocalRef(b);
  }
  int cfa[4], crop[4] = {-1, -1, 0, 0};
  float black[4], wbv[3];
  env->GetIntArrayRegion(cfa_j, 0, 4, cfa);
  env->GetFloatArrayRegion(black_j, 0, 4, black);
  env->GetFloatArrayRegion(wb_j, 0, 3, wbv);
  if (crop_j && env->GetArrayLength(crop_j) >= 4) env->GetIntArrayRegion(crop_j, 0, 4, crop);
  const char* pp = param_j ? env->GetStringUTFChars(param_j, nullptr) : nullptr;
  const char* bp = bin_j ? env->GetStringUTFChars(bin_j, nullptr) : nullptr;
  const std::string param = pp ? pp : "", bin = bp ? bp : "";
  if (pp) env->ReleaseStringUTFChars(param_j, pp);
  if (bp) env->ReleaseStringUTFChars(bin_j, bp);
  auto str = [&](jstring j) {
    std::string v;
    if (j) { const char* c = env->GetStringUTFChars(j, nullptr); v = c; env->ReleaseStringUTFChars(j, c); }
    return v;
  };
  const std::string npu_dir = str(npu_dir_j), npu_model = str(npu_model_j);

  lkr::Gpu gpu;
  if (!gpu.ok()) {
    LKR_LOG("no Vulkan device");
    return nullptr;
  }
  lkr::FullParams prm = lkr::AppDefaults();
#ifdef __ANDROID__
  {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lkr.workers", v) > 0) prm.workers = std::atoi(v);
    if (__system_property_get("debug.lkr.tilelk", v) > 0 && v[0] == '0') prm.tile_lk = false;
  }
#endif
  prm.s = s;
  prm.paper_parity = paper;
  prm.tile = tile;
  prm.overlap = overlap;
  prm.crop_x = crop[0];
  prm.crop_y = crop[1];
  prm.crop_w = crop[2];
  prm.crop_h = crop[3];
  if (tuning_j) {   // LK-SR Lab (Settings -> Merge): finite entries override the defaults
    std::vector<float> tv(size_t(env->GetArrayLength(tuning_j)));
    if (!tv.empty()) env->GetFloatArrayRegion(tuning_j, 0, jsize(tv.size()), tv.data());
    lkr::ApplyTuning(tv.data(), int(tv.size()), &prm);
    int n = 0;
    for (float f : tv) n += std::isfinite(f) ? 1 : 0;
    if (n) LKR_LOG("LK-SR Lab: %d overridden setting(s)", n);
  }
#ifdef __ANDROID__
  {  // A/B switches for device benchmarks: adb shell setprop debug.lkr.gather 1
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lkr.gather", v) > 0 && v[0] == '1') prm.solve.gather = true;
    if (__system_property_get("debug.lkr.tk", v) > 0) prm.solve.temporal_k = float(std::atof(v));
    if (__system_property_get("debug.lkr.mp", v) > 0) prm.solve.mean_preserve = std::atoi(v);
    if (__system_property_get("debug.lkr.rgather", v) > 0) prm.solve.robust_gather = v[0] != '0';
    if (__system_property_get("debug.lkr.mev", v) > 0) prm.solve.motion_every = std::atoi(v);
    if (__system_property_get("debug.lkr.pack", v) > 0) prm.solve.pack_taps = v[0] != '0';
    if (__system_property_get("debug.lkr.quad", v) > 0) prm.solve.quad = v[0] != '0';
    if (__system_property_get("debug.lkr.rob", v) > 0 && v[0] == '0') prm.solve.robust = false;
    if (__system_property_get("debug.lkr.eq", v) > 0 && v[0] == '0') prm.solve.equalize_noise = false;
    if (__system_property_get("debug.lkr.loc", v) > 0 && v[0] == '0') prm.solve.local_block = 0;
  }
#endif
  LKR_LOG("adjoint: %s", prm.solve.gather ? "gather" : "scatter");
#ifdef __ANDROID__
  {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lkr.profile", v) > 0 && v[0] == '1') gpu.profile(true);
  }
#endif

  std::unique_ptr<lkr::FfdnetGatPrior> ff;
  float backend = 0;
  if (!param.empty()) {
    ff.reset(new lkr::FfdnetGatPrior);
    lkr::FfdnetGatPrior::Options o;
    o.fp16 = fp16;
    if (!ff->load(param, bin, o)) {
      LKR_LOG("FFDNet load failed (%s)", param.c_str());
      return nullptr;
    }
    backend = ff->backend() == "ncnn-vulkan" ? 2 : 1;
  }
#ifdef __ANDROID__
  // NPU core (LiteRT, fixed tile shape); ncnn above stays the fallback.
  std::unique_ptr<lkr::LiteRtCore> npu;
  if (ff && !npu_dir.empty() && !npu_model.empty()) {
    const int H2 = s * std::min(tile, std::min(w, h)) / 2;
    npu.reset(new lkr::LiteRtCore);
    std::string nerr;
    if (npu->open(npu_dir, npu_model, H2, H2, &nerr)) {
      lkr::LiteRtCore* core = npu.get();
      lkr::FfdnetGatPrior::NhwcCore nc;
      nc.lock_in = [core] { return core->lockInput(); };
      nc.run = [core] { return core->unlockAndRun(); };
      nc.lock_out = [core] { return core->lockOutput(); };
      nc.unlock_out = [core] { core->unlockOutput(); };
      ff->setNhwcCore(std::move(nc), H2, H2, "litert-npu");
      backend = 3;
      LKR_LOG("prior on NPU (LiteRT, %dx%d packed)", H2, H2);
    } else {
      LKR_LOG("NPU unavailable (%s): ncnn fallback", nerr.c_str());
      npu.reset();
    }
  } else if (ff) {
    LKR_LOG("NPU skipped (%s): ncnn fallback", npu_dir.empty()
        ? "disabled or missing library directory" : "missing model for selected tile shape");
  }
#endif
  lkr::PriorFactory make;
  if (ff)
    make = [&](float a, float b, float sig, const std::array<float, 3>& wb) -> lkr::PriorFn {
      ff->setNoise(a, b, sig, wb);
      return [&](const float* z, float* x, int H, int W, float sg, const float* ns) { return (*ff)(z, x, H, W, sg, ns); };
    };
  std::vector<float> rgb;
  int ow = 0, oh = 0;
  lkr::FullStats st;
  std::string err;
  if (!lkr::MergeFull(&gpu, fr, w, h, cfa, black, white, {wbv[0], wbv[1], wbv[2]}, prm, make, &rgb, &ow, &oh,
                      &st, &err)) {
    LKR_LOG("merge failed: %s", err.c_str());
    return nullptr;
  }
  // Zoom 1.5x: the x2 solve of the crop is larger than the requested output
  // (sensor size): exact area-average down to it.
  if (out_w_req > 0 && out_h_req > 0 && (out_w_req != ow || out_h_req != oh)) {
    std::vector<float> small;
    lkr::AreaResampleRgb(rgb, ow, oh, out_w_req, out_h_req, &small);
    LKR_LOG("resampled %dx%d -> %dx%d", ow, oh, int(out_w_req), int(out_h_req));
    rgb.swap(small);
    ow = out_w_req;
    oh = out_h_req;
  }
  const size_t bytes = rgb.size() * 4;
  void* out = std::malloc(bytes);
  if (!out) return nullptr;
  std::memcpy(out, rgb.data(), bytes);
  if (stats_j && env->GetArrayLength(stats_j) >= 14) {
    const float sv[14] = {float(st.tiles), st.sigma, st.a, st.b, float(st.ms_noise), float(st.ms_coarse),
                          float(st.ms_tiles), float(st.ms_gpu), float(st.ms_prior), float(st.ms_total),
                          float(st.prior_calls), float(ow), float(oh), backend};
    env->SetFloatArrayRegion(stats_j, 0, 14, sv);
  }
  if (!st.gpu_profile.empty()) LKR_LOG("gpu profile: %s", st.gpu_profile.c_str());
  if (ff) LKR_LOG("%s", ff->timing().c_str());
  LKR_LOG("mode: %s", paper ? "paper parity" : "RawLens improvements");
  LKR_LOG("look: prior %.2f grain %.2f (sigma %.5f)", st.prior_gain, st.grain, st.sigma);
  LKR_LOG("merge %dx%d s=%d K=%d tiles %d: noise %.0f coarse %.0f tiles %.0f (gpu %.0f prior %.0f) total %.0f ms",
          ow, oh, s, int(K), st.tiles, st.ms_noise, st.ms_coarse, st.ms_tiles, st.ms_gpu, st.ms_prior, st.ms_total);
  LKR_LOG("reference frame %d (sharpest)", st.ref_index);
  LKR_LOG("workers %d, tile sums: solve %.0f (gpu %.0f prior %.0f -> cpu/other %.0f) prep %.0f (load %.0f) ms", st.workers, st.ms_solve, st.ms_gpu, st.ms_prior, st.ms_solve - st.ms_gpu - st.ms_prior, st.ms_prep, st.ms_load);
  jobject buf = env->NewDirectByteBuffer(out, jlong(bytes));
  if (!buf) std::free(out);
  return buf;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL Java_com_matthew_rawlens_LkrBridge_nativeMerge(
    JNIEnv* env, jclass, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa, jfloatArray black,
    jfloat white, jfloatArray wb, jint s, jint tile, jint overlap, jintArray crop, jstring param, jstring bin,
    jboolean fp16, jstring npu_dir, jstring npu_model, jboolean paper, jfloatArray stats, jfloatArray tuning,
    jint out_w, jint out_h) {
  return Merge(env, frames, w, h, row_stride_px, cfa, black, white, wb, s, tile, overlap, crop, param, bin, fp16,
               npu_dir, npu_model, paper, stats, tuning, out_w, out_h);
}

// Native defaults of the LK-SR Lab table (lkr_tuning.h), shown by the app's sliders.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_LkrBridge_nativeTuningDefaults(JNIEnv* env, jclass) {
  const std::vector<float> v = lkr::TuningDefaults();
  jfloatArray a = env->NewFloatArray(jsize(v.size()));
  if (a) env->SetFloatArrayRegion(a, 0, jsize(v.size()), v.data());
  return a;
}

// Desktop Kotlin CLI (tools/lkr-pipeline, com.rawlens.lkrpipeline.LkrBridge): same entry.
extern "C" JNIEXPORT jobject JNICALL Java_com_rawlens_lkrpipeline_LkrBridge_nativeMerge(
    JNIEnv* env, jclass, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa, jfloatArray black,
    jfloat white, jfloatArray wb, jint s, jint tile, jint overlap, jintArray crop, jstring param, jstring bin,
    jboolean fp16, jstring npu_dir, jstring npu_model, jboolean paper, jfloatArray stats) {
  return Merge(env, frames, w, h, row_stride_px, cfa, black, white, wb, s, tile, overlap, crop, param, bin, fp16,
               npu_dir, npu_model, paper, stats, nullptr, 0, 0);
}

#include "lkr_photo_denoise.h"
extern "C" JNIEXPORT jboolean JNICALL Java_com_matthew_rawlens_LkrBridge_nativeDenoise(
    JNIEnv* env, jclass, jobject buffer, jint w, jint h, jintArray cfa_j, jfloatArray wb_j,
    jfloat strength, jstring param_j, jstring bin_j, jstring npu_dir_j, jstring npu_model_j) {
  auto* rgba = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
  if (!rgba || w < 64 || h < 64 || env->GetDirectBufferCapacity(buffer) < int64_t(w) * h * 8 ||
      !cfa_j || env->GetArrayLength(cfa_j) != 4 || !wb_j || env->GetArrayLength(wb_j) < 3 ||
      !param_j || !bin_j) return false;
  int cfa[4];
  std::array<float, 3> wb;
  env->GetIntArrayRegion(cfa_j, 0, 4, cfa);
  env->GetFloatArrayRegion(wb_j, 0, 3, wb.data());
  auto str = [&](jstring j) {
    if (!j) return std::string();
    const char* chars = env->GetStringUTFChars(j, nullptr);
    if (!chars) return std::string();
    std::string value(chars);
    env->ReleaseStringUTFChars(j, chars);
    return value;
  };
  try {
    return lkr::DenoisePhoto(rgba, w, h, cfa, wb, strength, str(param_j), str(bin_j),
                             str(npu_dir_j), str(npu_model_j));
  } catch (const std::exception&) { return false; }
}

// Test-only benchmark entry. Calls the production denoiser, enforces the
// requested inference backend, and exposes measured stage totals.
extern "C" JNIEXPORT jboolean JNICALL Java_com_matthew_rawlens_LkrBridge_nativeBenchmarkDenoise(
    JNIEnv* env, jclass, jobject buffer, jint w, jint h, jintArray cfa_j, jfloatArray wb_j,
    jstring param_j, jstring bin_j, jstring npu_dir_j, jstring npu_model_j,
    jint backend, jboolean fp16, jfloatArray stats_j) {
  auto* rgba = static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
  if (!rgba || w < 64 || h < 64 || env->GetDirectBufferCapacity(buffer) < int64_t(w)*h*8 ||
      !cfa_j || env->GetArrayLength(cfa_j) != 4 || !wb_j || env->GetArrayLength(wb_j) < 3 ||
      !param_j || !bin_j || backend < 1 || backend > 2 || !stats_j || env->GetArrayLength(stats_j) < 16) return false;
  int cfa[4]; std::array<float,3> wb;
  env->GetIntArrayRegion(cfa_j,0,4,cfa); env->GetFloatArrayRegion(wb_j,0,3,wb.data());
  auto str = [&](jstring j) {
    if (!j) return std::string();
    const char* chars = env->GetStringUTFChars(j,nullptr);
    if (!chars) return std::string();
    std::string value(chars); env->ReleaseStringUTFChars(j,chars); return value;
  };
  try {
    lkr::PhotoDenoiseStats stats;
    const auto start = std::chrono::steady_clock::now();
    const bool ok = lkr::DenoisePhoto(rgba,w,h,cfa,wb,1.f,str(param_j),str(bin_j),
        str(npu_dir_j),str(npu_model_j),4,0.f,true,static_cast<lkr::PhotoBackend>(backend),fp16,&stats);
    const float total = std::chrono::duration<float,std::milli>(std::chrono::steady_clock::now()-start).count();
    const float values[16] = {float(stats.backend),total,float(stats.noise_ms),float(stats.model_ms),
      float(stats.npu_open_ms),float(stats.range_ms),float(stats.primary_ms),float(stats.chroma_ms),
      float(stats.guard_ms),float(stats.blend_ms),float(stats.pack_ms),float(stats.inference_ms),
      float(stats.unpack_ms),float(stats.calls),float(fp16),float(ok)};
    env->SetFloatArrayRegion(stats_j,0,16,values);
    return ok;
  } catch (const std::exception&) { return false; }
}
