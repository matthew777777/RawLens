// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge to the Halide AOT motion meter (tools/halide/generators/
// hdrplus_meter.cpp). Zero-copy: packed frames and map outputs travel
// as direct ByteBuffers addressed in place; the filter's stride-16
// outputs are bitwise identical to HdrPlusMotionMeter (host gate),
// so native/Kotlin results mix safely on fallback.
#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "halide/filters/hdrplus_meter.h"

#define LOG_TAG "RawLensHdrMeter"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

halide_buffer_t makeU16Plane(uint8_t *host, int w, int h, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, w, 1);
    shape[1] = halide_dimension_t(0, h, w);
    halide_buffer_t buf = {};
    buf.host = host;
    buf.type = halide_type_t(halide_type_uint, 16);
    buf.dimensions = 2;
    buf.dim = shape;
    return buf;
}

halide_buffer_t makeF64Plane(uint8_t *host, int w, int h, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, w, 1);
    shape[1] = halide_dimension_t(0, h, w);
    halide_buffer_t buf = {};
    buf.host = host;
    buf.type = halide_type_t(halide_type_float, 64);
    buf.dimensions = 2;
    buf.dim = shape;
    return buf;
}

void *directBytes(JNIEnv *env, jobject buf, jlong need) {
    if (buf == nullptr) return nullptr;
    void *ptr = env->GetDirectBufferAddress(buf);
    if (ptr == nullptr) return nullptr;
    if (env->GetDirectBufferCapacity(buf) < need) return nullptr;
    return ptr;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_HdrPlusMeterNative_meterPairNative(
    JNIEnv *env, jobject /*thiz*/, jobject prev, jobject curr, jint w, jint h,
    jdouble range, jdouble hotRatio, jobject outRatio, jobject outMad16,
    jobject outTex16, jobject outMadDense, jobject outTexDense, jobject outHot) {
    if (w <= 0 || h <= 0 || !(range > 0.0) || !(hotRatio > 0.0)) {
        LOGW("meterPair: bad geometry/range");
        return JNI_FALSE;
    }
    const jlong cells = (jlong)((w + 31) / 32) * ((h + 31) / 32);
    // Stride-16 map grid ((gw+1)/2 x (gh+1)/2) provably equals the 32px
    // cell grid; refuse if a caller ever disagrees.
    const int gw = (w + 15) / 16, gh = (h + 15) / 16;
    if ((jlong)((gw + 1) / 2) * ((gh + 1) / 2) != cells) {
        LOGW("meterPair: grid mismatch %dx%d", w, h);
        return JNI_FALSE;
    }
    const jlong frameBytes = (jlong)w * h * 2;
    const jlong mapBytes = cells * 8;
    void *prevPtr = directBytes(env, prev, frameBytes);
    void *currPtr = directBytes(env, curr, frameBytes);
    void *ratioPtr = directBytes(env, outRatio, mapBytes);
    void *mad16Ptr = directBytes(env, outMad16, mapBytes);
    void *tex16Ptr = directBytes(env, outTex16, mapBytes);
    void *madDensePtr = directBytes(env, outMadDense, mapBytes);
    void *texDensePtr = directBytes(env, outTexDense, mapBytes);
    void *hotPtr = directBytes(env, outHot, 8);
    if (prevPtr == nullptr || currPtr == nullptr || ratioPtr == nullptr ||
        mad16Ptr == nullptr || tex16Ptr == nullptr || madDensePtr == nullptr ||
        texDensePtr == nullptr || hotPtr == nullptr) {
        LOGW("meterPair: need direct buffers (frames %lldB, maps %lldB)",
             (long long)frameBytes, (long long)mapBytes);
        return JNI_FALSE;
    }
    const int mw = (w + 31) / 32, mh = (h + 31) / 32;
    halide_dimension_t prevShape[2], currShape[2], ratioShape[2], mad16Shape[2];
    halide_dimension_t tex16Shape[2], madDenseShape[2], texDenseShape[2], hotShape[1];
    halide_buffer_t prevBuf = makeU16Plane((uint8_t *)prevPtr, w, h, prevShape);
    halide_buffer_t currBuf = makeU16Plane((uint8_t *)currPtr, w, h, currShape);
    halide_buffer_t ratioBuf = makeF64Plane((uint8_t *)ratioPtr, mw, mh, ratioShape);
    halide_buffer_t mad16Buf = makeF64Plane((uint8_t *)mad16Ptr, mw, mh, mad16Shape);
    halide_buffer_t tex16Buf = makeF64Plane((uint8_t *)tex16Ptr, mw, mh, tex16Shape);
    halide_buffer_t madDenseBuf = makeF64Plane((uint8_t *)madDensePtr, mw, mh, madDenseShape);
    halide_buffer_t texDenseBuf = makeF64Plane((uint8_t *)texDensePtr, mw, mh, texDenseShape);
    hotShape[0] = halide_dimension_t(0, 1, 1);
    halide_buffer_t hotBuf = {};
    hotBuf.host = (uint8_t *)hotPtr;
    hotBuf.type = halide_type_t(halide_type_float, 64);
    hotBuf.dimensions = 1;
    hotBuf.dim = hotShape;
    const int rc = hdrplus_meter(&prevBuf, &currBuf, range, hotRatio, &ratioBuf,
                                 &mad16Buf, &tex16Buf, &madDenseBuf, &texDenseBuf, &hotBuf);
    if (rc != 0) {
        LOGW("meterPair: filter failed rc=%d", rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
