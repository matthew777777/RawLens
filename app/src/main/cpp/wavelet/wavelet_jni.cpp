// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.WaveletVulkan. Native methods throw
// java.lang.RuntimeException with the core's message on failure.
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>

#include <string>

#include "noise_model.h"
#include "wavelet_host.h"

#define WAVELET_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "rawLensWavelet", __VA_ARGS__)
#define WAVELET_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "rawLensWavelet", __VA_ARGS__)

static void throw_runtime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg != nullptr ? msg : "wavelet error");
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_matthew_rawlens_WaveletVulkan_nativeInit(JNIEnv* env,
                                                                          jobject /*thiz*/,
                                                                          jobject assetManager) {
    char errmsg[512];
    WaveletContext* ctx = wavelet_create(errmsg, sizeof(errmsg));
    if (ctx == nullptr) {
        WAVELET_LOGE("create failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return 0;
    }
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    const int n = wavelet_load_shaders(ctx, mgr, errmsg, sizeof(errmsg));
    if (n < 0) {
        WAVELET_LOGE("shader load failed: %s", errmsg);
        wavelet_destroy(ctx);
        throw_runtime(env, errmsg);
        return 0;
    }
    WAVELET_LOGI("init: %d shader modules", n);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL Java_com_matthew_rawlens_WaveletVulkan_nativeDestroy(JNIEnv* /*env*/,
                                                                            jobject /*thiz*/,
                                                                            jlong handle) {
    wavelet_destroy((WaveletContext*)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL Java_com_matthew_rawlens_WaveletVulkan_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject inHalf, jobject outHalf, jint w, jint h,
    jfloat strength, jfloat shadows, jfloat noiseA, jfloat noiseB, jfloatArray wb, jint maxScale,
    jfloat forceY, jfloat forceUv, jint tileSize, jint algorithm, jfloat grain,
    jint sharpIterations, jint sharpCfa, jobject sharpClipState, jint cfa, jint iso,
    jstring noiseStorePath, jint maxAnalogIso, jlong captureId) {
    WaveletContext* ctx = (WaveletContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null wavelet context");
        return -1;
    }
    const uint8_t* inPtr =
        inHalf != nullptr ? (const uint8_t*)env->GetDirectBufferAddress(inHalf) : nullptr;
    uint8_t* outPtr = outHalf != nullptr ? (uint8_t*)env->GetDirectBufferAddress(outHalf) : nullptr;
    if (inPtr == nullptr || outPtr == nullptr || wb == nullptr) {
        throw_runtime(env, "wavelet process needs direct in/out buffers and wb gains");
        return -1;
    }
    const jlong bytes = jlong(w) * jlong(h) * 8;
    if (w < 64 || h < 64 || w > 16384 || h > 16384 ||
        env->GetDirectBufferCapacity(inHalf) < bytes ||
        env->GetDirectBufferCapacity(outHalf) < bytes) {
        throw_runtime(env, "wavelet extent or direct buffer capacity invalid");
        return -1;
    }
    if (env->GetArrayLength(wb) < 3) {
        throw_runtime(env, "wavelet wb needs 3 gains");
        return -1;
    }
    jfloat gains[3] = {1.0f, 1.0f, 1.0f};
    env->GetFloatArrayRegion(wb, 0, 3, gains);
    WaveletParams params{};
    params.strength = strength;
    params.shadows = shadows;
    params.noiseA = noiseA;
    params.noiseB = noiseB;
    params.wb[0] = gains[0];
    params.wb[1] = gains[1];
    params.wb[2] = gains[2];
    params.maxScale = maxScale;
    params.forceY = forceY;
    params.forceUv = forceUv;
    params.tileSize = (uint32_t)tileSize;
    params.algorithm = algorithm;
    params.grain = grain;
    params.cfa = cfa;
    params.iso = iso > 0 ? (float)iso : 0.0f;
    std::string storePath;
    if (noiseStorePath != nullptr) {
        const char* c = env->GetStringUTFChars(noiseStorePath, nullptr);
        if (c != nullptr) {
            storePath = c;
            env->ReleaseStringUTFChars(noiseStorePath, c);
        }
    }
    params.noiseStorePath = storePath.c_str();
    params.maxAnalogIso = maxAnalogIso > 0 ? (float)maxAnalogIso : 0.0f;
    params.captureId = (uint64_t)captureId;
    params.sharpIterations = 0;
    params.sharpCfa = 0;
    params.sharpClipState = nullptr;
    if (sharpIterations > 0) {
        const auto* clip = sharpClipState
            ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(sharpClipState)) : nullptr;
        if (sharpCfa < 0 || sharpCfa > 3 || sharpIterations > 250 ||
            (sharpClipState && (!clip || (w & 1) || (h & 1) ||
             env->GetDirectBufferCapacity(sharpClipState) != jlong(w / 2) * (h / 2) * 2))) {
            throw_runtime(env, "invalid capture sharpening arguments");
            return -1;
        }
        params.sharpIterations = sharpIterations;
        params.sharpCfa = sharpCfa;
        params.sharpClipState = clip;
    }
    char errmsg[512];
    const int rc =
        wavelet_process(ctx, inPtr, outPtr, w, h, &params, errmsg, sizeof(errmsg));
    if (rc != 0) {
        WAVELET_LOGE("process failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return -1;
    }
    return 0;
}

JNIEXPORT jdouble JNICALL
Java_com_matthew_rawlens_WaveletVulkan_nativeLastGpuMs(JNIEnv* /*env*/, jobject /*thiz*/,
                                                       jlong handle) {
    return wavelet_last_gpu_ms((WaveletContext*)(uintptr_t)handle);
}

}  // extern "C"

extern "C" int capture_sharpen_process(WaveletContext*, uint8_t*, int, int, const float*, int, int, const uint8_t*, char*, int);
extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_WaveletVulkan_captureSharpen(
    JNIEnv* env, jobject, jlong handle, jobject pixels, jint w, jint h,
    jfloatArray wb, jint cfa, jint iterations, jobject clipState) {
    auto* ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(pixels));
    if (!ptr || w < 64 || h < 64 || w > 16384 || h > 16384 ||
        env->GetDirectBufferCapacity(pixels) < jlong(w)*h*8 || !wb || env->GetArrayLength(wb) < 3) {
        throw_runtime(env, "invalid capture sharpening buffer/extent/WB");
        return;
    }
    const auto* clip = clipState ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(clipState)) : nullptr;
    if (clipState && (!clip || (w & 1) || (h & 1) ||
        env->GetDirectBufferCapacity(clipState) != jlong(w / 2) * (h / 2) * 2)) {
        throw_runtime(env, "invalid capture sharpening sensor clip state");
        return;
    }
    float gains[3];
    env->GetFloatArrayRegion(wb, 0, 3, gains);
    if (env->ExceptionCheck()) return;
    char error[512];
    if (capture_sharpen_process(reinterpret_cast<WaveletContext*>(handle), ptr, w, h,
                               gains, cfa, iterations, clip, error, sizeof error) != 0)
        throw_runtime(env, error);
}

// Learned per-device noise model prediction for other stages (MFSR): fit the
// stored samples exactly as choose_noise does (rawlens_noise::fit) and return
// {a, b, samples, isos} for green at [iso], or null when the model is not ok.
// Read-only: does not add a sample.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_WaveletVulkan_nativePredictNoise(
        JNIEnv* env, jobject /*thiz*/, jstring storePath, jfloat iso) {
    if (storePath == nullptr || !(iso > 0.0f)) return nullptr;
    const char* p = env->GetStringUTFChars(storePath, nullptr);
    std::string path(p != nullptr ? p : "");
    if (p != nullptr) env->ReleaseStringUTFChars(storePath, p);
    const auto model = rawlens_noise::fit(rawlens_noise::loadSamples(path));
    if (!model.supports(iso)) return nullptr;
    const jfloat out[4] = {model.predictA(iso), model.predictB(iso), (jfloat)model.samples, (jfloat)model.isos};
    jfloatArray arr = env->NewFloatArray(4);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 4, out);
    return arr;
}
