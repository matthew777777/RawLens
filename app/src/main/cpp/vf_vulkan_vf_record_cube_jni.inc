// SPDX-License-Identifier: GPL-3.0-or-later
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_recordCubeUploadNative(
    JNIEnv* env,jobject,jshortArray data,jint size,jfloatArray parameters) {
    const std::lock_guard<std::mutex> lock(vkMutex);
    if (!g) return VFVK_NOT_INITIALIZED;
    if (!data) return uploadRecordCube(g,nullptr,0,nullptr);
    if (size<16 || size>65 || env->GetArrayLength(data)!=size*size*size*4 ||
        !parameters || env->GetArrayLength(parameters)!=7) return VFVK_BAD_ARGUMENT;
    float input[7]; env->GetFloatArrayRegion(parameters,0,7,input);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    for (float v:input) if (!std::isfinite(v)) return VFVK_BAD_ARGUMENT;
    for (int i=0;i<3;++i) if (!(input[i+3]>input[i]) || !std::isfinite(input[i+3]-input[i])) return VFVK_BAD_ARGUMENT;
    if (input[6]<0.f || input[6]>1.f) return VFVK_BAD_ARGUMENT;
    float control[12]{};
    for(int i=0;i<3;++i) { control[i]=input[i]; control[i+4]=input[i+3]; }
    control[8]=input[6];
    std::vector<uint16_t> rgba((size_t)size*size*size*4);
    env->GetShortArrayRegion(data,0,(jsize)rgba.size(),reinterpret_cast<jshort*>(rgba.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    // Reject NaN/Inf half texels even if JNI is called outside the cube parser.
    for (auto h:rgba) if ((h & 0x7c00u)==0x7c00u) return VFVK_BAD_ARGUMENT;
    return uploadRecordCube(g,rgba.data(),size,control);
}
