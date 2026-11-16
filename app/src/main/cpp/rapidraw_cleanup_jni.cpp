// SPDX-License-Identifier: AGPL-3.0-only
#include "rapidraw_cleanup.h"
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <algorithm>
#include <array>

namespace {
// Exactly the ACEScg D60 -> Rec.2020 D65 -> sRGB matrices used by our output shader.
constexpr float to2020[9] = {
    1.025877552449f,-.020020686312f,-.005775003430f,
    -.002232441770f,1.004568990995f,-.002349522759f,
    -.005013950857f,-.025282661381f,1.030082295555f};
constexpr float toSrgb[9] = {
    1.6604910021f,-.5876411388f,-.0728498633f,
    -.1245504745f,1.1328998971f,-.0083494226f,
    -.0181507634f,-.1005788980f,1.1187296614f};
std::array<float,9> matrix() {
    std::array<float,9> m{};
    for(int r=0;r<3;++r)for(int c=0;c<3;++c)for(int k=0;k<3;++k)
        m[r*3+c]+=toSrgb[r*3+k]*to2020[k*3+c];
    return m;
}
std::array<float,9> inverse(const std::array<float,9>& m) {
    const auto a=m[0],b=m[1],c=m[2],d=m[3],e=m[4],f=m[5],g=m[6],h=m[7],i=m[8];
    float det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
    return {(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det,
            (f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det,
            (d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det};
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_RapidRawCleanupReference_nativeProcess(
    JNIEnv* env,jobject,jobject buffer,jint w,jint h,jfloat color,jfloat detail) {
    auto* bytes=static_cast<uint16_t*>(env->GetDirectBufferAddress(buffer));
    const auto capacity=env->GetDirectBufferCapacity(buffer);
    if(!bytes||w<=0||h<=0||int64_t(w)*h>INT32_MAX/8||capacity<int64_t(w)*h*8)
        return env->NewStringUTF("Invalid cleanup RGBA16F buffer");
    try {
        static const auto m=matrix(),inv=inverse(m);
        const size_t n=size_t(w)*h;
        std::vector<float> rgb(n*3);
        for(size_t i=0;i<n;++i){
            float p[3];
            for(int c=0;c<3;++c){
                _Float16 half;std::memcpy(&half,&bytes[i*4+c],2);p[c]=float(half);
                if(!std::isfinite(p[c]))return env->NewStringUTF("Nonfinite cleanup input");
            }
            for(int r=0;r<3;++r)rgb[i*3+r]=m[r*3]*p[0]+m[r*3+1]*p[1]+m[r*3+2]*p[2];
        }
        rapidraw::cleanup(rgb,w,h,color,detail);
        for(size_t i=0;i<n;++i)for(int r=0;r<3;++r){
            const auto* p=&rgb[i*3];
            _Float16 half=_Float16(std::clamp(inv[r*3]*p[0]+inv[r*3+1]*p[1]+inv[r*3+2]*p[2],-65504.f,65504.f));
            std::memcpy(&bytes[i*4+r],&half,2);
        }
        return nullptr;
    }catch(const std::exception& e){return env->NewStringUTF(e.what());}
}

namespace {
float sampleMedian(std::vector<float>& v) {
    if(v.empty())return 0.f;
    auto mid=v.begin()+v.size()/2;std::nth_element(v.begin(),mid,v.end());return *mid;
}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_RapidRawCleanup_nativeStatistics(
    JNIEnv* env,jobject,jobject buffer,jint halfCount,jint quarterCount,jint fullCount,jint detailCount) {
    const auto* p=static_cast<const float*>(env->GetDirectBufferAddress(buffer));
    int64_t count=int64_t(halfCount)*3+int64_t(quarterCount)*3+int64_t(fullCount)*2+detailCount;
    if(!p||halfCount<0||quarterCount<0||fullCount<0||detailCount<0||env->GetDirectBufferCapacity(buffer)<count*4)return nullptr;
    try {
        float out[6];size_t offset=0;
        for(int level=0;level<2;++level){
            int n=level==0?halfCount:quarterCount;
            std::vector<float> q,c;q.reserve(n);c.reserve(size_t(n)*2);
            for(int i=0;i<n;++i){q.push_back(p[offset+i*3]);c.push_back(p[offset+i*3+1]);c.push_back(p[offset+i*3+2]);}
            out[level*2]=std::max(sampleMedian(q)*1.4826f/std::sqrt(2.f),1e-3f);
            out[level*2+1]=std::max(sampleMedian(c)*1.4826f/std::sqrt(2.f),1e-3f);offset+=size_t(n)*3;
        }
        std::vector<float> c(p+offset,p+offset+size_t(fullCount)*2);offset+=size_t(fullCount)*2;
        out[4]=std::max(sampleMedian(c)*1.4826f/std::sqrt(2.f),1e-3f);
        std::vector<float> d(p+offset,p+offset+detailCount);out[5]=sampleMedian(d)*1.4826f;
        auto result=env->NewFloatArray(6);if(result)env->SetFloatArrayRegion(result,0,6,out);return result;
    }catch(const std::exception&){return nullptr;}
}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_matthew_rawlens_RapidRawCleanup_nativeMedian(JNIEnv* env,jobject,jobject buffer,jint count) {
    const auto* p=static_cast<const float*>(env->GetDirectBufferAddress(buffer));
    if(!p||count<=0||env->GetDirectBufferCapacity(buffer)<int64_t(count)*4)return NAN;
    try {std::vector<float> values(p,p+count);return sampleMedian(values);}
    catch(const std::exception&){return NAN;}
}
