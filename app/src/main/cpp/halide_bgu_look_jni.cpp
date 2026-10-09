// SPDX-License-Identifier: GPL-3.0-or-later
//
// BGU low-res look: JNI bridge to the Halide AOT bgu_look filter.
// Packs caller arrays into stack halide_buffer_t descriptors and runs the
// full VF look on one low-res quad frame. Returns {guide, developed} or null.
#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "halide/filters/bgu_look.h"

#define LOG_TAG "RawLensBgu"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

halide_buffer_t makeU8(int w, int h, int c, uint8_t *host, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, w, 1);
    shape[1] = halide_dimension_t(0, h, w);
    shape[2] = halide_dimension_t(0, c, w * h);
    halide_buffer_t buf = {};
    buf.host = host;
    buf.type = halide_type_t(halide_type_uint, 8);
    buf.dimensions = 3;
    buf.dim = shape;
    return buf;
}

halide_buffer_t makeF32(int w, int h, int c, float *host, halide_dimension_t *shape) {
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

halide_buffer_t makeF32Vec(int n, float *host, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, n, 1);
    halide_buffer_t buf = {};
    buf.host = reinterpret_cast<uint8_t *>(host);
    buf.type = halide_type_t(halide_type_float, 32);
    buf.dimensions = 1;
    buf.dim = shape;
    return buf;
}

halide_buffer_t makeF32Mat(float *host, halide_dimension_t *shape) {
    shape[0] = halide_dimension_t(0, 3, 1);
    shape[1] = halide_dimension_t(0, 3, 3);
    halide_buffer_t buf = {};
    buf.host = reinterpret_cast<uint8_t *>(host);
    buf.type = halide_type_t(halide_type_float, 32);
    buf.dimensions = 2;
    buf.dim = shape;
    return buf;
}

}  // namespace

extern "C" int halide_set_num_threads(int n);

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_BguLook_lookIntoNative(
    JNIEnv *env, jobject /*thiz*/, jbyteArray quad, jint w, jint h, jfloatArray lens,
    jint lensCols, jint lensRows, jfloatArray wb, jfloatArray ccm, jfloatArray aces,
    jfloatArray white, jfloatArray fparams, jintArray iparams, jfloatArray guideOut,
    jfloatArray devOut) {
    // Same pool cap as the fit entry (see it): one shared Halide runtime
    // in this .so, capped once; repeated here in case runtimes ever stop
    // deduplicating across the linked AOT archives.
    static const bool poolCapped = [] { return halide_set_num_threads(2) == 0; }();
    (void)poolCapped;
    if (quad == nullptr || lens == nullptr || wb == nullptr || ccm == nullptr ||
        aces == nullptr || white == nullptr || fparams == nullptr || iparams == nullptr ||
        guideOut == nullptr || devOut == nullptr || w <= 0 || h <= 0 || lensCols <= 0 ||
        lensRows <= 0) {
        return -1;
    }
    // BguLook documents the packing: 9 floats, 11 ints. Outputs are caller
    // buffers (fit hot loop reuses them; this entry never news arrays).
    if (env->GetArrayLength(fparams) != 9 || env->GetArrayLength(iparams) != 11 ||
        env->GetArrayLength(wb) != 4 || env->GetArrayLength(ccm) != 9 ||
        env->GetArrayLength(aces) != 9 || env->GetArrayLength(white) != 3 ||
        env->GetArrayLength(quad) != w * h * 4 ||
        env->GetArrayLength(lens) != lensCols * lensRows * 4 ||
        env->GetArrayLength(guideOut) != w * h * 3 ||
        env->GetArrayLength(devOut) != w * h * 3) {
        return -1;
    }
    jbyte *quadPtr = env->GetByteArrayElements(quad, nullptr);
    float *lensPtr = env->GetFloatArrayElements(lens, nullptr);
    float *wbPtr = env->GetFloatArrayElements(wb, nullptr);
    float *ccmPtr = env->GetFloatArrayElements(ccm, nullptr);
    float *acesPtr = env->GetFloatArrayElements(aces, nullptr);
    float *whitePtr = env->GetFloatArrayElements(white, nullptr);
    float *fpPtr = env->GetFloatArrayElements(fparams, nullptr);
    jint *ipPtr = env->GetIntArrayElements(iparams, nullptr);
    if (quadPtr == nullptr || lensPtr == nullptr || wbPtr == nullptr || ccmPtr == nullptr ||
        acesPtr == nullptr || whitePtr == nullptr || fpPtr == nullptr || ipPtr == nullptr) {
        if (quadPtr != nullptr) env->ReleaseByteArrayElements(quad, quadPtr, JNI_ABORT);
        if (lensPtr != nullptr) env->ReleaseFloatArrayElements(lens, lensPtr, JNI_ABORT);
        if (wbPtr != nullptr) env->ReleaseFloatArrayElements(wb, wbPtr, JNI_ABORT);
        if (ccmPtr != nullptr) env->ReleaseFloatArrayElements(ccm, ccmPtr, JNI_ABORT);
        if (acesPtr != nullptr) env->ReleaseFloatArrayElements(aces, acesPtr, JNI_ABORT);
        if (whitePtr != nullptr) env->ReleaseFloatArrayElements(white, whitePtr, JNI_ABORT);
        if (fpPtr != nullptr) env->ReleaseFloatArrayElements(fparams, fpPtr, JNI_ABORT);
        if (ipPtr != nullptr) env->ReleaseIntArrayElements(iparams, ipPtr, JNI_ABORT);
        return -1;
    }

    float *guidePtr = env->GetFloatArrayElements(guideOut, nullptr);
    float *devPtr = env->GetFloatArrayElements(devOut, nullptr);
    int rc = -1;
    if (guidePtr != nullptr && devPtr != nullptr) {
        halide_dimension_t quadShape[3], lensShape[3], guideShape[3], devShape[3];
        halide_dimension_t wbShape[1], whiteShape[1], ccmShape[2], acesShape[2];
        halide_buffer_t quadBuf =
            makeU8(w, h, 4, reinterpret_cast<uint8_t *>(quadPtr), quadShape);
        halide_buffer_t lensBuf = makeF32(lensCols, lensRows, 4, lensPtr, lensShape);
        halide_buffer_t wbBuf = makeF32Vec(4, wbPtr, wbShape);
        halide_buffer_t ccmBuf = makeF32Mat(ccmPtr, ccmShape);
        halide_buffer_t acesBuf = makeF32Mat(acesPtr, acesShape);
        halide_buffer_t whiteBuf = makeF32Vec(3, whitePtr, whiteShape);
        halide_buffer_t guideBuf = makeF32(w, h, 3, guidePtr, guideShape);
        halide_buffer_t devBuf = makeF32(w, h, 3, devPtr, devShape);
        rc = bgu_look(&quadBuf, &lensBuf, &wbBuf, &ccmBuf, &acesBuf, &whiteBuf,
                      ipPtr[0], fpPtr[0], fpPtr[1], fpPtr[2], fpPtr[3], fpPtr[4], fpPtr[5],
                      fpPtr[6], fpPtr[7], fpPtr[8], ipPtr[1], ipPtr[2], ipPtr[3], ipPtr[4],
                      ipPtr[5], ipPtr[6], ipPtr[7], ipPtr[8], ipPtr[9], ipPtr[10], &guideBuf,
                      &devBuf);
    }

    env->ReleaseByteArrayElements(quad, quadPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(lens, lensPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(wb, wbPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(ccm, ccmPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(aces, acesPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(white, whitePtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(fparams, fpPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(iparams, ipPtr, JNI_ABORT);
    if (guidePtr != nullptr) env->ReleaseFloatArrayElements(guideOut, guidePtr, rc == 0 ? 0 : JNI_ABORT);
    if (devPtr != nullptr) env->ReleaseFloatArrayElements(devOut, devPtr, rc == 0 ? 0 : JNI_ABORT);
    if (rc != 0) {
        LOGW("bgu look filter failed rc=%d", rc);
    }
    return rc;
}
