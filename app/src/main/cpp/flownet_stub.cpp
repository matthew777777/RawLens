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
