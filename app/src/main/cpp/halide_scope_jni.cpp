// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge to the Halide AOT fused scope+meter sampler
// (tools/halide/generators/scope_meter.cpp). Zero-copy: the HAL Bayer
// plane (with its real row stride) and all eight outputs travel as
// direct ByteBuffers addressed in place. Scalar params ride packed
// arrays (BguLook style); see ScopeMeterNative.IP_* for the layout.
// Every output is bitwise identical to the Kotlin samplers (host gate),
// so native/Kotlin results mix safely on fallback. Never throws: any
// invalid input or filter failure returns false and the caller falls
// back to Kotlin.
#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "halide/filters/scope_meter.h"

#define LOG_TAG "RawLensScopeMeter"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kIpCount = 18;
constexpr int kHistBins = 64;
constexpr int kWaveLevels = 48;
constexpr int kWaveCols = 96;
constexpr int kEttrBins = 256;
constexpr int kFocusCols = 96;
constexpr int kLutSize = 1024;

void *directBytes(JNIEnv *env, jobject buf, jlong need) {
    if (buf == nullptr) return nullptr;
    void *ptr = env->GetDirectBufferAddress(buf);
    if (ptr == nullptr) return nullptr;
    if (env->GetDirectBufferCapacity(buf) < need) return nullptr;
    return ptr;
}

halide_buffer_t plane(uint8_t *host, halide_type_t type, int dims,
                      const int *extents, const int *strides,
                      halide_dimension_t *shape) {
    for (int i = 0; i < dims; i++) shape[i] = halide_dimension_t(0, extents[i], strides[i]);
    halide_buffer_t buf = {};
    buf.host = host;
    buf.type = type;
    buf.dimensions = dims;
    buf.dim = shape;
    return buf;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_ScopeMeterNative_scopeMeterNative(
    JNIEnv *env, jobject /*thiz*/, jobject raw, jobject lut, jintArray iparams,
    jdoubleArray dparams, jfloatArray fparams, jobject hist, jobject wave,
    jobject ebins, jobject ecounts, jobject egreen, jobject gbins,
    jobject fenergy, jobject fmean) {
    if (env->GetArrayLength(iparams) != kIpCount ||
        env->GetArrayLength(dparams) != 4 || env->GetArrayLength(fparams) != 4) {
        LOGW("scopeMeter: bad param arrays");
        return JNI_FALSE;
    }
    jint ip[kIpCount];
    jdouble dp[4];
    jfloat fp[4];
    env->GetIntArrayRegion(iparams, 0, kIpCount, ip);
    env->GetDoubleArrayRegion(dparams, 0, 4, dp);
    env->GetFloatArrayRegion(fparams, 0, 4, fp);
    const int white = ip[0], cfa = ip[1];
    const int useLut = ip[6], scopeStep = ip[7], ettrStep = ip[8];
    const int el = ip[9], et = ip[10], er = ip[11], eb = ip[12];
    const int guardStep = ip[13], doGuard = ip[14];
    const int w = ip[15], h = ip[16], rowStride = ip[17];
    if (w < 8 || h < 8 || cfa < 0 || cfa > 3) {
        LOGW("scopeMeter: bad frame/cfa %dx%d cfa=%d", w, h, cfa);
        return JNI_FALSE;
    }
    if (scopeStep < 1 || ettrStep < 1 || guardStep < 1 || rowStride < w) {
        LOGW("scopeMeter: bad steps/stride");
        return JNI_FALSE;
    }
    if (el < 0 || et < 0 || er > w || eb > h || el >= er || et >= eb) {
        LOGW("scopeMeter: bad region");
        return JNI_FALSE;
    }
    if ((useLut != 0 && useLut != 1) || (doGuard != 0 && doGuard != 1)) {
        LOGW("scopeMeter: bad flags");
        return JNI_FALSE;
    }
    const jlong rawNeed = ((jlong)(h - 1) * rowStride + w) * 2;
    void *rawPtr = directBytes(env, raw, rawNeed);
    void *lutPtr = directBytes(env, lut, (jlong)kLutSize * 4);
    void *histPtr = directBytes(env, hist, (jlong)kHistBins * 4 * 4);
    void *wavePtr = directBytes(env, wave, (jlong)kWaveLevels * kWaveCols * 3 * 4);
    void *ebinsPtr = directBytes(env, ebins, (jlong)kEttrBins * 4 * 4);
    void *ecountsPtr = directBytes(env, ecounts, (jlong)4 * 2 * 4);
    void *egreenPtr = directBytes(env, egreen, (jlong)4 * 8);
    void *gbinsPtr = directBytes(env, gbins, (jlong)kEttrBins * 4 * 4);
    if (rawPtr == nullptr || lutPtr == nullptr || histPtr == nullptr ||
        wavePtr == nullptr || ebinsPtr == nullptr || ecountsPtr == nullptr ||
        egreenPtr == nullptr || gbinsPtr == nullptr) {
        LOGW("scopeMeter: need direct buffers (raw %lldB)", (long long)rawNeed);
        return JNI_FALSE;
    }
    // Focus rows derive from the caller's allocation (cells center over
    // the allocated extent by contract); both grids must agree exactly.
    const jlong eCap = env->GetDirectBufferCapacity(fenergy);
    const jlong mCap = env->GetDirectBufferCapacity(fmean);
    if (eCap != mCap || eCap % (kFocusCols * 4) != 0 || eCap <= 0) {
        LOGW("scopeMeter: focus grids disagree");
        return JNI_FALSE;
    }
    const int rows = (int)(eCap / (kFocusCols * 4));
    void *fenergyPtr = directBytes(env, fenergy, eCap);
    void *fmeanPtr = directBytes(env, fmean, mCap);
    if (fenergyPtr == nullptr || fmeanPtr == nullptr) {
        LOGW("scopeMeter: focus grids not direct");
        return JNI_FALSE;
    }

    halide_dimension_t rawShape[2], lutShape[1], histShape[2], waveShape[3];
    halide_dimension_t ebinsShape[2], ecountsShape[2], egreenShape[1];
    halide_dimension_t gbinsShape[2], fenergyShape[2], fmeanShape[2];
    const halide_type_t u16 = halide_type_t(halide_type_uint, 16);
    const halide_type_t i32 = halide_type_t(halide_type_int, 32);
    const halide_type_t f32 = halide_type_t(halide_type_float, 32);
    const halide_type_t f64 = halide_type_t(halide_type_float, 64);
    const int rawExt[2] = {w, h}, rawStr[2] = {1, rowStride};
    halide_buffer_t rawBuf = plane((uint8_t *)rawPtr, u16, 2, rawExt, rawStr, rawShape);
    const int lutExt[1] = {kLutSize}, lutStr[1] = {1};
    halide_buffer_t lutBuf = plane((uint8_t *)lutPtr, f32, 1, lutExt, lutStr, lutShape);
    const int histExt[2] = {kHistBins, 4}, histStr[2] = {1, kHistBins};
    halide_buffer_t histBuf = plane((uint8_t *)histPtr, i32, 2, histExt, histStr, histShape);
    const int waveExt[3] = {kWaveLevels, kWaveCols, 3};
    const int waveStr[3] = {1, kWaveLevels, kWaveLevels * kWaveCols};
    halide_buffer_t waveBuf = plane((uint8_t *)wavePtr, i32, 3, waveExt, waveStr, waveShape);
    const int ebExt[2] = {kEttrBins, 4}, ebStr[2] = {1, kEttrBins};
    halide_buffer_t ebinsBuf = plane((uint8_t *)ebinsPtr, i32, 2, ebExt, ebStr, ebinsShape);
    const int ecExt[2] = {4, 2}, ecStr[2] = {1, 4};
    halide_buffer_t ecountsBuf = plane((uint8_t *)ecountsPtr, i32, 2, ecExt, ecStr, ecountsShape);
    const int egExt[1] = {4}, egStr[1] = {1};
    halide_buffer_t egreenBuf = plane((uint8_t *)egreenPtr, f64, 1, egExt, egStr, egreenShape);
    halide_buffer_t gbinsBuf = plane((uint8_t *)gbinsPtr, i32, 2, ebExt, ebStr, gbinsShape);
    const int fExt[2] = {kFocusCols, rows}, fStr[2] = {1, kFocusCols};
    halide_buffer_t fenergyBuf = plane((uint8_t *)fenergyPtr, f32, 2, fExt, fStr, fenergyShape);
    halide_buffer_t fmeanBuf = plane((uint8_t *)fmeanPtr, f32, 2, fExt, fStr, fmeanShape);

    const int rc = scope_meter(
        &rawBuf, &lutBuf, white, cfa, ip[2], ip[3], ip[4], ip[5], dp[0], dp[1],
        dp[2], dp[3], fp[0], fp[1], fp[2], fp[3], useLut, scopeStep, ettrStep, el,
        et, er, eb, guardStep, doGuard, &histBuf, &waveBuf, &ebinsBuf,
        &ecountsBuf, &egreenBuf, &gbinsBuf, &fenergyBuf, &fmeanBuf);
    if (rc != 0) {
        LOGW("scopeMeter: filter failed rc=%d", rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
