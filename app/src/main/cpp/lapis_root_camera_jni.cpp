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

#include "hexa/rawr_reduced.h"
#include <exception>
extern "C" JNIEXPORT jdouble JNICALL
Java_com_matthew_rawlens_LapisRootCameraNative_hexaReduced(JNIEnv* env, jclass, jobject src,
        jint width, jint height, jfloat black, jobject dst, jboolean phaseFit) {
    auto* in = static_cast<const uint16_t*>(env->GetDirectBufferAddress(src));
    auto* out = static_cast<uint16_t*>(env->GetDirectBufferAddress(dst));
    try {
        if (width < 16 || height < 16 ||
            env->GetDirectBufferCapacity(src) < jlong(width)*height*2 ||
            env->GetDirectBufferCapacity(dst) < jlong(width/2)*(height/2)*2)
            throw std::invalid_argument("Hexadeca reduction buffer too small");
        const auto start = std::chrono::steady_clock::now();
        rawlens::rawrReduced(in, width, height, 2, black, out, phaseFit, 8);
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/io/IOException"), e.what());
        return 0;
    }
}
