// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <cmath>
#include <string>
#include "noise_model.h"

// Learned per-device noise model prediction for other stages (MFSR): fit the
// stored samples exactly as choose_noise does (rawlens_noise::fit) and return
// {a, b, samples, isos} for green at [iso], or null when the model is not ok.
// Read-only: does not add a sample.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_RawNoiseModel_nativePredictNoise(
        JNIEnv* env, jobject /*thiz*/, jstring storePath, jfloat iso) {
    if (storePath == nullptr || !(iso > 0.0f)) return nullptr;
    const char* p = env->GetStringUTFChars(storePath, nullptr);
    std::string path(p != nullptr ? p : "");
    if (p != nullptr) env->ReleaseStringUTFChars(storePath, p);
    const auto model = rawlens_noise::fit(rawlens_noise::loadSamples(path));
    if (!model.supports(iso)) return nullptr;
    const jfloat out[4] = {model.predictA(iso), model.predictB(iso), (jfloat)model.samples, (jfloat)model.isos};
    jfloatArray arr = env->NewFloatArray(4);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 4, out);
    return arr;
}

// Learned model as prior + another stage's own blind estimate as evidence
// (HDR+ burst estimate), mixed exactly as choose_noise does
// (rawlens_noise::combine). Returns {a, b, evidence weight, samples, isos},
// or null when the model does not cover [iso]. Read-only: adds no sample.
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_matthew_rawlens_RawNoiseModel_nativeCombineNoise(
        JNIEnv* env, jobject /*thiz*/, jstring storePath, jfloat iso, jfloat estimateA, jfloat estimateB,
        jint bins) {
    if (storePath == nullptr || !(iso > 0.0f)) return nullptr;
    const char* p = env->GetStringUTFChars(storePath, nullptr);
    std::string path(p != nullptr ? p : "");
    if (p != nullptr) env->ReleaseStringUTFChars(storePath, p);
    const auto model = rawlens_noise::fit(rawlens_noise::loadSamples(path));
    if (!model.supports(iso)) return nullptr;
    const bool estimateOk = std::isfinite(estimateA) && std::isfinite(estimateB) && estimateA >= 0.0f &&
                            estimateB >= 0.0f;
    const auto c = rawlens_noise::combine(model, iso, estimateA, estimateB, bins, estimateOk);
    const jfloat out[5] = {c.a, c.b, c.w, (jfloat)model.samples, (jfloat)model.isos};
    jfloatArray arr = env->NewFloatArray(5);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 5, out);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_matthew_rawlens_RawNoiseModel_nativeRecordNoise(
        JNIEnv* env, jobject, jstring storePath, jfloat iso, jfloat a, jfloat b,
        jint bins, jfloat analogIso, jlong captureId) {
    const rawlens_noise::Sample shot{iso, a, b, bins, analogIso, static_cast<uint64_t>(captureId)};
    if (!storePath || !rawlens_noise::valid(shot)) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(storePath, nullptr);
    if (!p) return JNI_FALSE;
    const std::string path(p);
    env->ReleaseStringUTFChars(storePath, p);
    return rawlens_noise::recordSample(path, shot) ? JNI_TRUE : JNI_FALSE;
}
