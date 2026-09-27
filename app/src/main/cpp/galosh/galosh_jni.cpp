// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.GaloshVulkan. Native methods throw
// java.lang.RuntimeException with the core's message on failure.
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>

#include "galosh_host.h"

#define GALOSH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "galosh", __VA_ARGS__)
#define GALOSH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "galosh", __VA_ARGS__)

static void throw_runtime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg != nullptr ? msg : "galosh error");
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeProbeCaps(JNIEnv* env, jobject /*thiz*/) {
    char json[16384];
    if (galosh_probe_caps(json, sizeof(json)) != 0) {
        GALOSH_LOGE("probe failed: %s", json);
        throw_runtime(env, json);
        return nullptr;
    }
    return env->NewStringUTF(json);
}

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                jobject assetManager) {
    char errmsg[512];
    GaloshContext* ctx = galosh_create(errmsg, sizeof(errmsg));
    if (ctx == nullptr) {
        GALOSH_LOGE("create failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return 0;
    }
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    const int n = galosh_load_shaders(ctx, mgr, errmsg, sizeof(errmsg));
    if (n < 0) {
        GALOSH_LOGE("shader load failed: %s", errmsg);
        galosh_destroy(ctx);
        throw_runtime(env, errmsg);
        return 0;
    }
    GALOSH_LOGI("init: %d shader modules", n);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle) {
    galosh_destroy((GaloshContext*)(uintptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeLoadedShaderCount(JNIEnv* env, jobject /*thiz*/,
                                                             jlong handle) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return -1;
    }
    return galosh_loaded_shader_count(ctx);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeExpectedShaderCount(JNIEnv* /*env*/,
                                                               jobject /*thiz*/) {
    return galosh_shader_count();
}

JNIEXPORT jdouble JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeDenoise(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject inBuf, jobject outBuf, jint w, jint h,
    jfloat strength, jfloat luma, jfloat chroma, jfloat alpha, jfloat sigma, jint wht,
    jint upsampleFast, jint phaseStride) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return -1.0;
    }
    const float* in = (const float*)env->GetDirectBufferAddress(inBuf);
    float* out = (float*)env->GetDirectBufferAddress(outBuf);
    const jlong inCap = env->GetDirectBufferCapacity(inBuf);
    const jlong outCap = env->GetDirectBufferCapacity(outBuf);
    const jlong need = (jlong)w * (jlong)h;
    // Capacity may be reported in bytes (ByteBuffer) or floats (FloatBuffer
    // view) depending on the runtime; accept either convention.
    if (in == nullptr || out == nullptr || (inCap < need * 4 && inCap < need) ||
        (outCap < need * 4 && outCap < need)) {
        throw_runtime(env, "denoise needs direct float buffers of W*H");
        return -1.0;
    }
    GaloshParams p;
    galosh_default_params(&p);
    p.strength = strength;
    p.luma_str = luma;
    p.chroma_str = chroma;
    p.alpha_ext = alpha;
    p.sigma_ext = sigma;
    p.wht_block = wht;
    p.upsample_fast = upsampleFast;
    p.phase_stride = phaseStride;
    char errmsg[512];
    if (galosh_denoise(ctx, in, out, w, h, &p, errmsg, sizeof(errmsg)) != 0) {
        GALOSH_LOGE("denoise failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return -1.0;
    }
    return galosh_last_gpu_ms(ctx);
}

JNIEXPORT jfloat JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeLastAlpha(JNIEnv* env, jobject /*thiz*/,
                                                     jlong handle) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return 0.0f;
    }
    return galosh_last_alpha(ctx);
}

JNIEXPORT jfloat JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeLastSigmaSq(JNIEnv* env, jobject /*thiz*/,
                                                       jlong handle) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return 0.0f;
    }
    return galosh_last_sigma_sq(ctx);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeSetDump(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                   jint enable) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return;
    }
    galosh_set_dump(ctx, enable);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeDumpCount(JNIEnv* env, jobject /*thiz*/,
                                                     jlong handle) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return 0;
    }
    return galosh_dump_count(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeDumpName(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                    jint index) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return nullptr;
    }
    const char* name = galosh_dump_name(ctx, index);
    if (name == nullptr) {
        throw_runtime(env, "dump index out of range");
        return nullptr;
    }
    return env->NewStringUTF(name);
}

JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_GaloshVulkan_nativeDumpData(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                    jint index) {
    GaloshContext* ctx = (GaloshContext*)(uintptr_t)handle;
    if (ctx == nullptr) {
        throw_runtime(env, "null galosh context");
        return nullptr;
    }
    int n = 0;
    const float* data = galosh_dump_data(ctx, index, &n);
    if (data == nullptr || n <= 0) {
        throw_runtime(env, "dump index out of range");
        return nullptr;
    }
    jfloatArray arr = env->NewFloatArray(n);
    if (arr == nullptr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, n, data);
    return arr;
}

}  // extern "C"
