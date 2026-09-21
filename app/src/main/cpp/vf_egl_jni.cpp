// SPDX-License-Identifier: GPL-3.0-or-later
//
// Zero-copy viewfinder import: AHardwareBuffer -> EGLImageKHR -> GL texture.
// The Java EGL bindings hide eglGetNativeClientBufferANDROID/eglCreateImageKHR;
// the NDK exposes them. All entry points run on the viewfinder GL worker while
// its EGL context is current, and fail soft (0 / error code) for CPU fallback.
#include <jni.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#define LOG_TAG "RawLensVfEgl"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VfEglImport_createEGLImage(
    JNIEnv* env, jobject, jobject hardwareBuffer) {
    if (!hardwareBuffer) return 0;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) {
        LOGW("vf-egl: HardwareBuffer unwrap failed");
        return 0;
    }
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) {
        LOGW("vf-egl: no current EGL display on importing thread");
        return 0;
    }
    EGLClientBuffer client = eglGetNativeClientBufferANDROID(buf);
    if (!client) {
        LOGW("vf-egl: eglGetNativeClientBufferANDROID failed: %#x", eglGetError());
        return 0;
    }
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image =
        eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGW("vf-egl: eglCreateImageKHR failed: %#x", eglGetError());
        return 0;
    }
    return reinterpret_cast<jlong>(image);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_bindEGLImageToTexture2D(
    JNIEnv*, jobject, jlong eglImage, jint textureId) {
    if (!eglImage || textureId <= 0) return GL_INVALID_VALUE;
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(textureId));
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D,
                                 reinterpret_cast<GLeglImageOES>(eglImage));
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGW("vf-egl: bind to TEXTURE_2D failed: %#x", error);
    }
    return static_cast<jint>(error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfEglImport_destroyEGLImage(JNIEnv*, jobject, jlong eglImage) {
    if (!eglImage) return;
    EGLDisplay display = eglGetCurrentDisplay();
    if (display != EGL_NO_DISPLAY) {
        eglDestroyImageKHR(display, reinterpret_cast<EGLImageKHR>(eglImage));
    }
}
