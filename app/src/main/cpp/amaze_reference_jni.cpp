// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <stdexcept>
#include "amaze_reference_api.h"
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_RawTherapeeAmaze_demosaicNative(
    JNIEnv* env,jobject,jfloatArray input,jint width,jint height,jintArray fc,jfloat gain) {
    try {
        if(width<34 || height<34 || int64_t(width)*height > 100000000 ||
           env->GetArrayLength(input)!=int64_t(width)*height || env->GetArrayLength(fc)!=4)
            throw std::invalid_argument("Invalid AMaZE dimensions or array lengths");
        jint phase[4]; env->GetIntArrayRegion(fc,0,4,phase);
        unsigned pattern[4]; for(int i=0;i<4;++i) pattern[i]=phase[i];
        std::vector<float> raw(size_t(width)*height);
        env->GetFloatArrayRegion(input,0,raw.size(),raw.data());
        if(env->ExceptionCheck()) return nullptr;
        auto result=amazeReference(raw.data(),width,height,pattern,gain);
        jfloatArray output=env->NewFloatArray(result.size());
        if(output) env->SetFloatArrayRegion(output,0,result.size(),result.data());
        return output;
    } catch(const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),"AMaZE native allocation failed");
    } catch(const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),e.what());
    }
    return nullptr;
}
