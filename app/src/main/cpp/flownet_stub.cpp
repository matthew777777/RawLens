// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeCreate(
    JNIEnv*, jclass, jobject, jstring) { return 0; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeRun(
    JNIEnv*, jclass, jlong, jobject, jobject, jint, jint, jobject) { return JNI_FALSE; }

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeDestroy(
    JNIEnv*, jclass, jlong) {}

// Emulator ABIs have no prebuilt ncnn archive: the RawNIND-tiny denoiser is
// unavailable there and the Kotlin side falls back to wavelet/bypass.
extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeCreate(
    JNIEnv*, jclass, jobject, jstring) { return 0; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeRun(
    JNIEnv*, jclass, jlong, jobject, jint, jint, jobject) { return JNI_FALSE; }

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_RawNindNcnnProcessor_nativeDestroy(
    JNIEnv*, jclass, jlong) {}
