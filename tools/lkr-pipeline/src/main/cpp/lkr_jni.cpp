// SPDX-License-Identifier: Apache-2.0
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
// 4 DCT x-step), reserved...
jobject Merge(JNIEnv* env, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa_j,
              jfloatArray black_j, jfloat white, jfloatArray wb_j, jint s, jint tile, jint overlap,
              jintArray crop_j, jboolean dct, jfloatArray stats_j, jfloatArray tuning_j, jint out_w_req,
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
  lkr::Gpu gpu;
  if (!gpu.ok()) {
    LKR_LOG("no Vulkan device");
    return nullptr;
  }
  lkr::FullParams prm = lkr::AppDefaults();
  // x2 buffers are large and a third worker gains about 3 % on Mali (measured); debug.lkr.workers still overrides.
  if (s >= 2) {
    prm.workers = std::min(prm.workers, 2);
    prm.min_workers = 2;
  }
  prm.solver_budget_mb = 768;   // fewer workers for long bursts; output and frames are on top of this
#ifdef __ANDROID__
  {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lkr.workers", v) > 0) prm.workers = std::atoi(v);
    if (__system_property_get("debug.lkr.tilelk", v) > 0 && v[0] == '0') prm.tile_lk = false;
  }
#endif
  prm.s = s;
  prm.solve.dct_prior = dct;
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

  const float backend = dct ? 4.f : 0.f;
  float* direct = nullptr;
  prm.out_alloc = [&](int oww, int ohh) -> float* {
    if (out_w_req > 0 && out_h_req > 0 && (out_w_req != oww || out_h_req != ohh)) return nullptr;   // 1.5x resamples
    return direct = static_cast<float*>(std::calloc(size_t(oww) * ohh * 3, sizeof(float)));
  };
  const lkr::PriorFactory make;
  std::vector<float> rgb;
  int ow = 0, oh = 0;
  lkr::FullStats st;
  std::string err;
  if (!lkr::MergeFull(&gpu, fr, w, h, cfa, black, white, {wbv[0], wbv[1], wbv[2]}, prm, make, &rgb, &ow, &oh,
                      &st, &err)) {
    LKR_LOG("merge failed: %s", err.c_str());
    std::free(direct);
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
  const size_t bytes = direct ? size_t(ow) * oh * 12 : rgb.size() * 4;
  void* out = direct;
  if (!out) {
    out = std::malloc(bytes);
    if (!out) return nullptr;
    std::memcpy(out, rgb.data(), bytes);
    std::vector<float>().swap(rgb);
  }
  if (stats_j && env->GetArrayLength(stats_j) >= 14) {
    const float sv[14] = {float(st.tiles), st.sigma, st.a, st.b, float(st.ms_noise), float(st.ms_coarse),
                          float(st.ms_tiles), float(st.ms_gpu), float(st.ms_prior), float(st.ms_total),
                          float(st.prior_calls), float(ow), float(oh), backend};
    env->SetFloatArrayRegion(stats_j, 0, 14, sv);
  }
  if (!st.gpu_profile.empty()) LKR_LOG("gpu profile: %s", st.gpu_profile.c_str());
  LKR_LOG("mode: %s", dct ? "DCT x-step" : "no prior");
  LKR_LOG("look: prior %.2f grain %.2f (sigma %.5f)", st.prior_gain, st.grain, st.sigma);
  LKR_LOG("merge %dx%d s=%d K=%d tiles %d: noise %.0f coarse %.0f tiles %.0f (gpu %.0f prior %.0f) total %.0f ms",
          ow, oh, s, int(K), st.tiles, st.ms_noise, st.ms_coarse, st.ms_tiles, st.ms_gpu, st.ms_prior, st.ms_total);
  LKR_LOG("reference frame %d (sharpest)", st.ref_index);
  LKR_LOG("workers %d, tile sums: solve %.0f (gpu %.0f prior %.0f init %.0f finish %.0f other %.0f) prep %.0f (load %.0f) blend %.0f | setup %.0f wait %.0f ms",
          st.workers, st.ms_solve, st.ms_gpu, st.ms_prior, st.ms_init, st.ms_finish,
          st.ms_solve - st.ms_gpu - st.ms_prior - st.ms_init - st.ms_finish, st.ms_prep, st.ms_load, st.ms_blend, st.ms_setup, st.ms_wait);
  jobject buf = env->NewDirectByteBuffer(out, jlong(bytes));
  if (!buf) std::free(out);
  return buf;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL Java_com_matthew_rawlens_LkrBridge_nativeMerge(
    JNIEnv* env, jclass, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa, jfloatArray black,
    jfloat white, jfloatArray wb, jint s, jint tile, jint overlap, jintArray crop, jboolean dct,
    jfloatArray stats, jfloatArray tuning,
    jint out_w, jint out_h) {
  return Merge(env, frames, w, h, row_stride_px, cfa, black, white, wb, s, tile, overlap, crop, dct, stats, tuning, out_w,
               out_h);
}

// Native defaults of the LK-SR Lab table (lkr_tuning.h), shown by the app's sliders.
extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_LkrBridge_nativeSetCacheDir(JNIEnv* env, jclass, jstring dir) {
  if (!dir) return;
  const char* c = env->GetStringUTFChars(dir, nullptr);
  if (c) {
    lkr::Gpu::SetPipelineCacheFile(std::string(c) + "/lkr_pipelines.bin");
    env->ReleaseStringUTFChars(dir, c);
  }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_LkrBridge_nativeTuningDefaults(JNIEnv* env, jclass) {
  const std::vector<float> v = lkr::TuningDefaults();
  jfloatArray a = env->NewFloatArray(jsize(v.size()));
  if (a) env->SetFloatArrayRegion(a, 0, jsize(v.size()), v.data());
  return a;
}

// Desktop Kotlin CLI (tools/lkr-pipeline, com.rawlens.lkrpipeline.LkrBridge): same entry.
extern "C" JNIEXPORT jobject JNICALL Java_com_rawlens_lkrpipeline_LkrBridge_nativeMerge(
    JNIEnv* env, jclass, jobjectArray frames, jint w, jint h, jint row_stride_px, jintArray cfa, jfloatArray black,
    jfloat white, jfloatArray wb, jint s, jint tile, jint overlap, jintArray crop, jboolean dct,
    jfloatArray stats) {
  return Merge(env, frames, w, h, row_stride_px, cfa, black, white, wb, s, tile, overlap, crop, dct, stats, nullptr, 0, 0);
}

