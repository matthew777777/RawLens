// SPDX-License-Identifier: GPL-3.0-or-later
//
// BGU bilateral-grid fit: JNI bridge to the Halide AOT bgu_fit filter.
// Wraps float RGB planes + dims in stack halide_buffer_t descriptors and runs
// the fit. The grid layout contract is documented on BguGrid (Kotlin side).
#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "halide/filters/bgu_fit.h"

#define LOG_TAG "RawLensBgu"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

halide_buffer_t makeF32Image(float *host, int w, int h, int c, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, w, 1);
    shape[1] = halide_dimension_t(0, h, w);
    shape[2] = halide_dimension_t(0, c, w * h);
    halide_buffer_t buf = {};
    buf.host = reinterpret_cast<uint8_t *>(host);
    buf.type = halide_type_t(halide_type_float, 32);
    buf.dimensions = 3;
    buf.dim = shape;
    return buf;
}

halide_buffer_t makeF32Grid(float *host, int gw, int gh, int gz, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, gw, 1);
    shape[1] = halide_dimension_t(0, gh, gw);
    shape[2] = halide_dimension_t(0, gz, gw * gh);
    shape[3] = halide_dimension_t(0, 12, gw * gh * gz);
    halide_buffer_t buf = {};
    buf.host = reinterpret_cast<uint8_t *>(host);
    buf.type = halide_type_t(halide_type_float, 32);
    buf.dimensions = 4;
    buf.dim = shape;
    return buf;
}

}  // namespace

extern "C" int halide_set_num_threads(int n);

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_BguFit_fitIntoNative(
    JNIEnv *env, jobject /*thiz*/, jfloatArray guide, jfloatArray developed, jint w, jint h,
    jint s, jfloat r, jfloat lambda, jint gw, jint gh, jint gz, jfloatArray coeffsOut) {
    // Perfetto: the default ncpu-1 pool (7 workers, ~150k wakeups/s) burns
    // ~0.5 cores orchestrating a 12k-pixel fit. Cap to 2: the workload is
    // memory-bound and async (stale-grid tolerant), so one helper thread
    // keeps the gain while the wakeup storm goes away. Once per process
    // (magic static; idempotent, same value in the look entry).
    static const bool poolCapped = [] { return halide_set_num_threads(2) == 0; }();
    (void)poolCapped;
    if (guide == nullptr || developed == nullptr || coeffsOut == nullptr || w <= 0 || h <= 0 ||
        s < 1 || !(r > 0) || gw <= 0 || gh <= 0 || gz <= 0) {
        return -1;
    }
    // Caller buffer (fit hot loop reuses it; this entry never news arrays).
    if (env->GetArrayLength(coeffsOut) != gw * gh * gz * 12) {
        return -1;
    }
    float *guidePtr = env->GetFloatArrayElements(guide, nullptr);
    float *devPtr = env->GetFloatArrayElements(developed, nullptr);
    if (guidePtr == nullptr || devPtr == nullptr) {
        if (guidePtr != nullptr) env->ReleaseFloatArrayElements(guide, guidePtr, JNI_ABORT);
        if (devPtr != nullptr) env->ReleaseFloatArrayElements(developed, devPtr, JNI_ABORT);
        return -1;
    }
    float *outPtr = env->GetFloatArrayElements(coeffsOut, nullptr);
    if (outPtr == nullptr) {
        env->ReleaseFloatArrayElements(guide, guidePtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(developed, devPtr, JNI_ABORT);
        return -1;
    }
    halide_dimension_t guideShape[3];
    halide_dimension_t devShape[3];
    halide_dimension_t gridShape[4];
    halide_buffer_t guideBuf = makeF32Image(guidePtr, w, h, 3, guideShape);
    halide_buffer_t devBuf = makeF32Image(devPtr, w, h, 3, devShape);
    halide_buffer_t gridBuf = makeF32Grid(outPtr, gw, gh, gz, gridShape);
    const int rc = bgu_fit(&guideBuf, &devBuf, s, r, lambda, &gridBuf);
    env->ReleaseFloatArrayElements(guide, guidePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(developed, devPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(coeffsOut, outPtr, rc == 0 ? 0 : JNI_ABORT);
    if (rc != 0) {
        LOGW("bgu fit filter failed rc=%d", rc);
    }
    return rc;
}
