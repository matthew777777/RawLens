// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.HdrPlusVulkan. Native methods throw
// java.lang.RuntimeException with the core's message on failure.
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>

#include <vector>

#include "hdrplus_host.h"

#define HDRPLUS_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hdrplus", __VA_ARGS__)
#define HDRPLUS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hdrplus", __VA_ARGS__)

static void throw_runtime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg != nullptr ? msg : "hdrplus error");
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_HdrPlusVulkan_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                  jobject assetManager) {
    char errmsg[512];
    HdrPlusContext* ctx = hdrplus_create(errmsg, sizeof(errmsg));
    if (ctx == nullptr) {
        HDRPLUS_LOGE("create failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return 0;
    }
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    const int n = hdrplus_load_shaders(ctx, mgr, errmsg, sizeof(errmsg));
    if (n < 0) {
        HDRPLUS_LOGE("shader load failed: %s", errmsg);
        hdrplus_destroy(ctx);
        throw_runtime(env, errmsg);
        return 0;
    }
    HDRPLUS_LOGI("init: %d shader modules", n);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_HdrPlusVulkan_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jlong handle) {
    hdrplus_destroy((HdrPlusContext*)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_HdrPlusVulkan_nativeLoadedShaderCount(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle) {
    HdrPlusContext* ctx = (HdrPlusContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null hdrplus context");
        return -1;
    }
    return hdrplus_loaded_shader_count(ctx);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_HdrPlusVulkan_nativeExpectedShaderCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    return hdrplus_shader_count();
}

JNIEXPORT jdouble JNICALL
Java_com_matthew_rawlens_HdrPlusVulkan_nativeLastGpuMs(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    HdrPlusContext* ctx = (HdrPlusContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null hdrplus context");
        return -1.0;
    }
    return hdrplus_last_gpu_ms(ctx);
}

JNIEXPORT jdouble JNICALL Java_com_matthew_rawlens_HdrPlusVulkan_nativeMerge(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray frames, jfloatArray blacks,
    jfloatArray whites, jint refIndex, jintArray hotPixels, jfloat strength, jfloatArray frameStrengths,
    jfloatArray strengthMaps, jint tileSize, jint searchDistance, jboolean highQuality,
    jboolean alignOnce, jint width, jint height, jobject output) {
    HdrPlusContext* ctx = (HdrPlusContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null hdrplus context");
        return -1.0;
    }
    if (frames == nullptr || blacks == nullptr || whites == nullptr || output == nullptr) {
        throw_runtime(env, "hdrplus merge: null argument");
        return -1.0;
    }
    const jsize n = env->GetArrayLength(frames);
    if (n < 2 || n > 64) {
        throw_runtime(env, "hdrplus merge: need 2..64 frames");
        return -1.0;
    }
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        throw_runtime(env, "hdrplus merge: dimensions must be nonzero/even");
        return -1.0;
    }
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (env->GetArrayLength(blacks) != n * 4 || env->GetArrayLength(whites) != n) {
        throw_runtime(env, "hdrplus merge: blacks must hold 4N floats, whites N floats");
        return -1.0;
    }
    float* out = static_cast<float*>(env->GetDirectBufferAddress(output));
    const jlong outCap = env->GetDirectBufferCapacity(output);
    if (out == nullptr || outCap < static_cast<jlong>(pixels * sizeof(float))) {
        throw_runtime(env, "hdrplus merge: output must be a direct buffer of W*H floats");
        return -1.0;
    }
    std::vector<const uint16_t*> framePtrs(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        jobject buf = env->GetObjectArrayElement(frames, i);
        if (buf == nullptr) {
            throw_runtime(env, "hdrplus merge: null frame buffer");
            return -1.0;
        }
        uint16_t* ptr = static_cast<uint16_t*>(env->GetDirectBufferAddress(buf));
        const jlong cap = env->GetDirectBufferCapacity(buf);
        env->DeleteLocalRef(buf);
        if (ptr == nullptr || cap < static_cast<jlong>(pixels * sizeof(uint16_t))) {
            throw_runtime(env, "hdrplus merge: each frame must be a direct buffer of W*H uint16");
            return -1.0;
        }
        framePtrs[static_cast<size_t>(i)] = ptr;
    }
    std::vector<float> blackVec(static_cast<size_t>(n) * 4);
    std::vector<float> whiteVec(static_cast<size_t>(n));
    env->GetFloatArrayRegion(blacks, 0, n * 4, blackVec.data());
    env->GetFloatArrayRegion(whites, 0, n, whiteVec.data());
    std::vector<float> strengthVec;
    if (frameStrengths != nullptr) {
        if (env->GetArrayLength(frameStrengths) != n) {
            throw_runtime(env, "hdrplus merge: frameStrengths must hold N floats or be null");
            return -1.0;
        }
        strengthVec.resize(static_cast<size_t>(n));
        env->GetFloatArrayRegion(frameStrengths, 0, n, strengthVec.data());
    }
    std::vector<float> mapsVec;
    if (strengthMaps != nullptr) {
        int mw = 0, mh = 0;
        hdrplus_strength_map_cells(width, height, &mw, &mh);
        const jsize want = n * mw * mh;
        if (env->GetArrayLength(strengthMaps) != want) {
            throw_runtime(env, "hdrplus merge: strengthMaps must hold N*mw*mh floats or be null");
            return -1.0;
        }
        mapsVec.resize(static_cast<size_t>(want));
        env->GetFloatArrayRegion(strengthMaps, 0, want, mapsVec.data());
    }
    std::vector<int32_t> hotVec;
    if (hotPixels != nullptr) {
        const jsize hotLen = env->GetArrayLength(hotPixels);
        if (hotLen % 2 != 0) {
            throw_runtime(env, "hdrplus merge: hot pixels must be flat xy pairs");
            return -1.0;
        }
        hotVec.resize(static_cast<size_t>(hotLen));
        if (!hotVec.empty()) env->GetIntArrayRegion(hotPixels, 0, hotLen, hotVec.data());
    }
    HdrPlusParams params{};
    hdrplus_default_params(&params);
    params.strength = strength;
    params.frame_strengths = strengthVec.empty() ? nullptr : strengthVec.data();
    params.strength_maps = mapsVec.empty() ? nullptr : mapsVec.data();
    params.tile_size = static_cast<uint32_t>(tileSize);
    params.search_distance = static_cast<uint32_t>(searchDistance);
    params.high_quality = highQuality ? 1 : 0;
    params.frequency_align_once = alignOnce ? 1 : 0;

    char errmsg[512];
    const int hotPairs = static_cast<int>(hotVec.size() / 2);
    const int rc = hdrplus_merge(ctx, framePtrs.data(), blackVec.data(), whiteVec.data(), n, width,
                                 height, refIndex, hotVec.empty() ? nullptr : hotVec.data(), hotPairs,
                                 &params, out, errmsg, sizeof(errmsg));
    if (rc != 0) {
        HDRPLUS_LOGE("merge failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return -1.0;
    }
    return hdrplus_last_gpu_ms(ctx);
}

}  // extern "C"
