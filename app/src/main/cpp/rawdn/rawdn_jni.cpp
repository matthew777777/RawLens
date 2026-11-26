// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.RawDenoiser.
#include <android/asset_manager_jni.h>
#include <jni.h>

#include <string>
#include <vector>

#include "rawdn.h"

#include <chrono>

namespace {

void raise(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

const uint16_t* plane(JNIEnv* env, jobject buf, jint strideBytes, jint w, jint h) {
    if (buf == nullptr || (strideBytes & 1) || strideBytes < w * 2) return nullptr;
    const auto* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    const jlong need = jlong(strideBytes) * (h - 1) + jlong(w) * 2;
    if (p == nullptr || (reinterpret_cast<uintptr_t>(p) & 1) || env->GetDirectBufferCapacity(buf) < need) return nullptr;
    return reinterpret_cast<const uint16_t*>(p);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeInit(JNIEnv* env, jobject, jobject assets) {
    char err[256] = "";
    RawdnContext* ctx = rawdn_create(err, sizeof(err));
    if (ctx == nullptr) {
        raise(env, err);
        return 0;
    }
    static const char* kNames[RAWDN_SHADER_COUNT] = {"pack", "down", "dct", "resid", "fin", "sites", "unpack", "coarse", "mix"};
    AAssetManager* mgr = AAssetManager_fromJava(env, assets);
    std::vector<uint32_t> code[RAWDN_SHADER_COUNT];
    const uint32_t* ptr[RAWDN_SHADER_COUNT];
    size_t bytes[RAWDN_SHADER_COUNT];
    for (int i = 0; i < RAWDN_SHADER_COUNT; i++) {
        const std::string name = std::string("spirv/rawdn/rawdn_") + kNames[i] + ".spv";
        AAsset* a = mgr != nullptr ? AAssetManager_open(mgr, name.c_str(), AASSET_MODE_BUFFER) : nullptr;
        const off_t len = a != nullptr ? AAsset_getLength(a) : 0;
        bool ok = len > 0 && len % 4 == 0;
        if (ok) {
            code[i].resize(size_t(len) / 4);
            ok = AAsset_read(a, code[i].data(), size_t(len)) == len;
        }
        if (a != nullptr) AAsset_close(a);
        if (!ok) {
            rawdn_destroy(ctx);
            raise(env, ("bad SPIR-V asset " + name).c_str());
            return 0;
        }
        ptr[i] = code[i].data();
        bytes[i] = size_t(len);
    }
    if (rawdn_load_shaders(ctx, ptr, bytes, err, sizeof(err)) != 0) {
        rawdn_destroy(ctx);
        raise(env, err);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    rawdn_destroy(reinterpret_cast<RawdnContext*>(handle));
}

// {a, b, bins} of the frame's own noise, or null when too few flat blocks exist.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeEstimate(
    JNIEnv* env, jobject, jobject in, jint strideBytes, jint w, jint h, jint cfa, jfloatArray black, jfloat white) {
    const uint16_t* p = plane(env, in, strideBytes, w, h);
    float bk[4], a = 0, b = 0;
    if (p == nullptr || w < 64 || h < 64 || black == nullptr || env->GetArrayLength(black) != 4) return nullptr;
    env->GetFloatArrayRegion(black, 0, 4, bk);
    const int bins = rawdn_estimate_noise(p, size_t(strideBytes) / 2, w, h, cfa, bk, white, &a, &b);
    if (bins == 0) return nullptr;
    const jfloat out[3] = {a, b, jfloat(bins)};
    jfloatArray arr = env->NewFloatArray(3);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 3, out);
    return arr;
}

// Returns {GPU ms, midtone SNR, grain fraction, copy-in ms, copy-out ms, native total ms}.
// out == null: the result is left in the GPU buffer: mapped[0] = output handle (0 when the frame needs strips, nothing
// done: call again with out and luma), mapped[1] = samples per row. Read it with nativeOutputBuffer, free with nativeReleaseOutput.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jobject in, jint strideBytes, jobject out, jobject luma, jint w, jint h, jint cfa,
    jfloatArray black, jfloat white, jfloat a, jfloat b, jfloatArray wb, jfloat texture, jfloat lumaStrength, jfloat chromaStrength,
    jboolean v1Grain, jlongArray mapped) {
    auto* ctx = reinterpret_cast<RawdnContext*>(handle);
    const uint16_t* src = plane(env, in, strideBytes, w, h);
    const bool zeroCopy = out == nullptr;
    auto* dst = zeroCopy ? nullptr : const_cast<uint16_t*>(plane(env, out, w * 2, w, h));
    auto* lum = zeroCopy ? nullptr : const_cast<uint16_t*>(plane(env, luma, w * 2, w, h));
    if (ctx == nullptr || src == nullptr || (zeroCopy ? mapped == nullptr || env->GetArrayLength(mapped) < 2 : dst == nullptr || lum == nullptr) ||
        black == nullptr || wb == nullptr || env->GetArrayLength(black) != 4 || env->GetArrayLength(wb) != 3) {
        raise(env, "invalid RAW denoise arguments");
        return nullptr;
    }
    RawdnParams p;
    rawdn_default_params(&p);
    env->GetFloatArrayRegion(black, 0, 4, p.black);
    env->GetFloatArrayRegion(wb, 0, 3, p.wb);
    p.white = white;
    p.cfa = cfa;
    p.noiseA = a;
    p.noiseB = b;
    const size_t stride = size_t(strideBytes) / 2;
    const auto t0 = std::chrono::steady_clock::now();
    const float snr = rawdn_midtone_snr(src, stride, w, h, p.black, white, a, b);
    rawdn_auto_luma(&p, snr, texture, lumaStrength);
    if (v1Grain) p.coarseCut = 0.f;   // frozen v1: all removed luma comes back as grain, no night coarse cut
    p.chromaStrength = chromaStrength;
    char err[256] = "";
    int rc;
    if (zeroCopy) {
        RawdnOutput* res = nullptr;
        const uint16_t *o = nullptr, *l = nullptr;
        size_t rowSamples = 0;
        rc = rawdn_process_mapped(ctx, src, stride, w, h, &p, &res, &o, &l, &rowSamples, err, sizeof(err));
        const jlong m[2] = {reinterpret_cast<jlong>(res), jlong(rowSamples)};
        env->SetLongArrayRegion(mapped, 0, 2, m);
        if (rc == 1) return env->NewFloatArray(6);  // needs strips: nothing done
    } else {
        rc = rawdn_process(ctx, src, stride, dst, lum, w, h, &p, err, sizeof(err));
    }
    rawdn_trim(ctx);
    if (rc != 0) {
        raise(env, err);
        return nullptr;
    }
    const jfloat res[6] = {jfloat(rawdn_last_gpu_ms(ctx)), snr, p.grain, jfloat(rawdn_last_copy_ms(ctx, 0)), jfloat(rawdn_last_copy_ms(ctx, 1)),
                           jfloat(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count())};
    jfloatArray arr = env->NewFloatArray(6);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 6, res);
    return arr;
}

// Plane 0: denoised CFA, 1: luma residual, of a zero-copy output; stride * h samples each.
extern "C" JNIEXPORT jobject JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeOutputBuffer(
    JNIEnv* env, jclass, jlong output, jint which, jint stride, jint h) {
    auto* r = reinterpret_cast<RawdnOutput*>(output);
    if (r == nullptr) return nullptr;
    void* base = const_cast<uint16_t*>(rawdn_output_plane(r, which));
    return base != nullptr ? env->NewDirectByteBuffer(base, jlong(stride) * h * 2) : nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_RawDenoiser_nativeReleaseOutput(JNIEnv*, jclass, jlong output) {
    rawdn_release_output(reinterpret_cast<RawdnOutput*>(output));
}
