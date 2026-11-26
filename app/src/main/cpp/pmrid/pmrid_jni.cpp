// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.NetworkDenoiser.
#include <android/asset_manager_jni.h>
#include <jni.h>

#include <chrono>
#include <vector>

#include "pmrid_nnapi.h"

namespace {

void raise(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

void* direct(JNIEnv* env, jobject buf, jlong need) {
    if (buf == nullptr) return nullptr;
    void* p = env->GetDirectBufferAddress(buf);
    if (p == nullptr || (reinterpret_cast<uintptr_t>(p) & 1) || env->GetDirectBufferCapacity(buf) < need) return nullptr;
    return p;
}

}  // namespace

// Compiles the graph for the NPU (seconds). 0 when no accelerator runs every op: the caller keeps the Vulkan denoiser.
extern "C" JNIEXPORT jlong JNICALL Java_com_matthew_rawlens_NetworkDenoiser_nativeCreate(JNIEnv* env, jclass, jobject assets, jstring cacheDir) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assets);
    AAsset* a = mgr != nullptr ? AAssetManager_open(mgr, "models/pmrid.bin", AASSET_MODE_BUFFER) : nullptr;
    if (a == nullptr) return 0;
    const off_t len = AAsset_getLength(a);
    std::vector<uint8_t> blob(size_t(len > 0 ? len : 0));
    const bool ok = len > 0 && AAsset_read(a, blob.data(), size_t(len)) == len;
    AAsset_close(a);
    if (!ok) return 0;
    char err[256] = "";
    const char* dir = cacheDir != nullptr ? env->GetStringUTFChars(cacheDir, nullptr) : nullptr;
    PmridContext* ctx = pmrid_create(blob.data(), blob.size(), dir, err, sizeof(err));
    if (dir != nullptr) env->ReleaseStringUTFChars(cacheDir, dir);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_matthew_rawlens_NetworkDenoiser_nativeDevice(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(pmrid_device(reinterpret_cast<PmridContext*>(h)));
}

extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_NetworkDenoiser_nativeDestroy(JNIEnv*, jclass, jlong h) {
    pmrid_destroy(reinterpret_cast<PmridContext*>(h));
}

// Returns {NPU ms, native total ms}.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_NetworkDenoiser_nativeProcess(
    JNIEnv* env, jclass, jlong h, jobject in, jint strideBytes, jobject out, jint w, jint hgt, jint cfa, jfloatArray black,
    jfloat white, jfloat a, jfloat b, jfloat strength) {
    auto* ctx = reinterpret_cast<PmridContext*>(h);
    const auto* src = static_cast<const uint16_t*>(direct(env, in, jlong(strideBytes) * (hgt - 1) + jlong(w) * 2));
    auto* dst = static_cast<uint16_t*>(direct(env, out, jlong(w) * hgt * 2));
    if (ctx == nullptr || src == nullptr || dst == nullptr || (strideBytes & 1) || black == nullptr || env->GetArrayLength(black) != 4) {
        raise(env, "invalid network denoise arguments");
        return nullptr;
    }
    float bk[4];
    env->GetFloatArrayRegion(black, 0, 4, bk);
    char err[256] = "";
    double npu = 0;
    const auto t0 = std::chrono::steady_clock::now();
    if (pmrid_process(ctx, src, size_t(strideBytes) / 2, dst, w, hgt, cfa, bk, white, a, b, strength, &npu, err, sizeof(err)) != 0) {
        raise(env, err);
        return nullptr;
    }
    const jfloat res[2] = {jfloat(npu), jfloat(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count())};
    jfloatArray arr = env->NewFloatArray(2);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 2, res);
    return arr;
}

// Device parity test: one tile through the graph.
extern "C" JNIEXPORT jboolean JNICALL Java_com_matthew_rawlens_NetworkDenoiser_nativeRunTile(
    JNIEnv* env, jclass, jlong h, jbyteArray u, jfloat sigma, jbyteArray out) {
    auto* ctx = reinterpret_cast<PmridContext*>(h);
    const jsize n = jsize(pmrid_tile(ctx)) * pmrid_tile(ctx) * 4;
    if (ctx == nullptr || u == nullptr || out == nullptr || env->GetArrayLength(u) != n || env->GetArrayLength(out) != n) return JNI_FALSE;
    std::vector<uint8_t> a(static_cast<size_t>(n)), o(static_cast<size_t>(n));
    env->GetByteArrayRegion(u, 0, n, reinterpret_cast<jbyte*>(a.data()));
    char err[256] = "";
    if (pmrid_run_tile(ctx, a.data(), sigma, o.data(), err, sizeof(err)) != 0) return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(o.data()));
    return JNI_TRUE;
}
