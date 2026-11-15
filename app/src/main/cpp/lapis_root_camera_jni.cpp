// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/v4l2-subdev.h>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_matthew_rawlens_LapisRootCameraNative_activeSensorFormat(JNIEnv* env, jclass) {
    int fd = open("/dev/v4l-subdev24", O_RDWR | O_CLOEXEC);
    if (fd < 0) return nullptr;
    v4l2_subdev_format fmt{};
    fmt.which = V4L2_SUBDEV_FORMAT_ACTIVE;
    int status = ioctl(fd, VIDIOC_SUBDEV_G_FMT, &fmt);
    close(fd);
    if (status < 0) return nullptr;
    jint values[] = {static_cast<jint>(fmt.format.width), static_cast<jint>(fmt.format.height),
                     static_cast<jint>(fmt.format.code)};
    jintArray out = env->NewIntArray(3);
    if (out) env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

#include <chrono>

// Version 2: 4x4-aware residual-interpolation demosaic on Vulkan -> 50 MP linear RGB (uint16, normalised).
#include "hexa/hexa_v2.h"
#include <string>
#include <vector>

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_rawlens_LapisRootCameraNative_hexaV2(JNIEnv* env, jclass, jobject src, jint rowStride,
        jint width, jint height, jfloat black, jfloat white, jobject dst, jbyteArray filterSpv, jbyteArray pointSpv, jbyteArray boxSpv) {
    auto fail = [env](const std::string& why) {
        jclass io = env->FindClass("java/io/IOException");
        if (io) env->ThrowNew(io, why.c_str());
        return nullptr;
    };
    auto* in = static_cast<const uint16_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint16_t*>(env->GetDirectBufferAddress(dst));
    if (!in || !out || width < 64 || height < 64 || width % 8 || height % 8 || rowStride < width * 2 || rowStride % 2 ||
        !(white > black) || black < 0) return fail("hexaV2: invalid arguments");
    if (env->GetDirectBufferCapacity(src) < jlong(rowStride) * (height - 1) + jlong(width) * 2 ||
        env->GetDirectBufferCapacity(dst) < jlong(width / 2) * (height / 2) * 6) return fail("hexaV2: buffer too small");
    auto words = [env](jbyteArray a, std::vector<uint32_t>* v) {
        jsize n = env->GetArrayLength(a);
        if (n <= 0 || n % 4) return false;
        v->resize(size_t(n) / 4);
        env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(v->data()));
        return !env->ExceptionCheck();
    };
    std::vector<uint32_t> fs, ps, bs;
    if (!words(filterSpv, &fs) || !words(pointSpv, &ps) || !words(boxSpv, &bs)) return fail("hexaV2: invalid SPIR-V");
    const size_t stride = size_t(rowStride) / 2;
    auto t0 = std::chrono::steady_clock::now();
    hexa::GainTable gains = hexa::estimateGains(in, stride, width, height, black, white);
    double gainsMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    hexa::Timing t;
    std::string error;
    if (!hexa::convert(in, stride, width, height, black, white, gains, fs, ps, bs, out, &t, &error))
        return fail("hexaV2: " + error);
    jdouble values[] = {gainsMs, t.gpuMs, t.totalMs, double(t.tiles)};
    jdoubleArray result = env->NewDoubleArray(4);
    if (result) env->SetDoubleArrayRegion(result, 0, 4, values);
    return result;
}
