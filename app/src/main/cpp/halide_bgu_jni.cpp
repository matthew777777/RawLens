// SPDX-License-Identifier: GPL-3.0-or-later
//
// BGU viewfinder spike: JNI bridge to the Halide AOT box-downsample filter.
// Wraps caller short[] planes in stack halide_buffer_t descriptors (interleaved
// x/y/c, u16) and runs bgu_spike_downsample. No camera, no GL: proves the
// Halide AOT archive links and executes on-device before any VF code exists.
#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "halide/filters/bgu_spike_downsample.h"

#define LOG_TAG "RawLensBgu"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

halide_buffer_t makeU16Plane(uint8_t *host, int w, int h, int c, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, w, 1);
    shape[1] = halide_dimension_t(0, h, w);
    shape[2] = halide_dimension_t(0, c, w * h);
    halide_buffer_t buf = {};
    buf.host = host;
    buf.type = halide_type_t(halide_type_uint, 16);
    buf.dimensions = 3;
    buf.dim = shape;
    return buf;
}

}  // namespace

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_matthew_rawlens_BguSpike_downsampleNative(
    JNIEnv *env, jobject /*thiz*/, jshortArray pixels, jint w, jint h, jint c, jint factor) {
    if (pixels == nullptr || w <= 0 || h <= 0 || c <= 0 || factor < 2 || factor > 16) {
        return nullptr;
    }
    const int ow = (w + factor - 1) / factor;
    const int oh = (h + factor - 1) / factor;
    jshort *inPtr = env->GetShortArrayElements(pixels, nullptr);
    if (inPtr == nullptr) {
        return nullptr;
    }
    jshortArray out = env->NewShortArray((jsize)(ow * oh * c));
    if (out == nullptr) {
        env->ReleaseShortArrayElements(pixels, inPtr, JNI_ABORT);
        return nullptr;
    }
    jshort *outPtr = env->GetShortArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        env->ReleaseShortArrayElements(pixels, inPtr, JNI_ABORT);
        return nullptr;
    }
    halide_dimension_t inShape[3];
    halide_dimension_t outShape[3];
    halide_buffer_t inBuf =
        makeU16Plane(reinterpret_cast<uint8_t *>(inPtr), w, h, c, inShape);
    halide_buffer_t outBuf =
        makeU16Plane(reinterpret_cast<uint8_t *>(outPtr), ow, oh, c, outShape);
    const int rc = bgu_spike_downsample(&inBuf, factor, &outBuf);
    env->ReleaseShortArrayElements(pixels, inPtr, JNI_ABORT);
    // Commit the output unless the filter failed (then release + null).
    env->ReleaseShortArrayElements(out, outPtr, rc == 0 ? 0 : JNI_ABORT);
    if (rc != 0) {
        LOGW("bgu spike filter failed rc=%d", rc);
        return nullptr;
    }
    return out;
}
