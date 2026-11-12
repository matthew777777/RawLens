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
