// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for com.matthew.rawlens.VulkanHub. THE SAME FILE builds for
// Android (NDK) and desktop; only the log sink differs. Native methods throw
// java.lang.RuntimeException with the core's message on failure.
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "srvk_compute.h"
#include "srvk_internal.h"
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define SRVK_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "srvulkan", __VA_ARGS__)
#define SRVK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "srvulkan", __VA_ARGS__)
#else
#define SRVK_LOGI(...) do { fprintf(stderr, "I/srvulkan: "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define SRVK_LOGE(...) do { fprintf(stderr, "E/srvulkan: "); fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

static void throw_runtime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != NULL) env->ThrowNew(cls, msg != NULL ? msg : "srvulkan error");
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeInit(JNIEnv* env, jobject /*thiz*/) {
    char errmsg[SRVK_ERR_LEN];
    SrvkContext* ctx = srvk_create(errmsg, sizeof(errmsg));
    if (ctx == NULL) {
        SRVK_LOGE("init failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return 0;
    }
    char info[1024];
    srvk_device_info(ctx, info, sizeof(info));
    SRVK_LOGI("init: %s", info);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeInitVersion(JNIEnv* env, jobject, jint version) {
    char errmsg[SRVK_ERR_LEN] = {};
    SrvkContext* ctx = srvk_create_version((uint32_t)version, errmsg, sizeof(errmsg));
    if (!ctx) throw_runtime(env, errmsg);
    return (jlong)(uintptr_t)ctx;
}

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDeviceInfo(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return NULL;
    }
    char info[1024];
    srvk_device_info(ctx, info, sizeof(info));
    return env->NewStringUTF(info);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDeviceCount(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return 0;
    }
    return (jint)srvk_device_count(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDeviceName(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                   jint index) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return NULL;
    }
    char name[512];
    srvk_device_name(ctx, (int)index, name, sizeof(name));
    return env->NewStringUTF(name);
}

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeLoadModule(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                   jobject words) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return 0;
    }
    if (words == NULL) {
        throw_runtime(env, "null SPIR-V buffer");
        return 0;
    }
    void* bytes = env->GetDirectBufferAddress(words);
    jlong capacity = env->GetDirectBufferCapacity(words);
    if (bytes == NULL || capacity <= 0 || capacity % 4 != 0) {
        throw_runtime(env, "SPIR-V must be a direct buffer with a 4-byte-multiple capacity");
        return 0;
    }
    char errmsg[SRVK_ERR_LEN];
    uint64_t module = srvk_load_module(ctx, (const uint32_t*)bytes, (size_t)(capacity / 4),
                                       errmsg, sizeof(errmsg));
    if (module == 0) {
        SRVK_LOGE("loadModule failed: %s", errmsg);
        throw_runtime(env, errmsg);
        return 0;
    }
    return (jlong)module;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDestroyModule(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                       jlong module) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return;
    }
    srvk_destroy_module(ctx, (uint64_t)module);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeShutdown(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) {
        throw_runtime(env, "null srvulkan context");
        return;
    }
    srvk_destroy(ctx);
}

static SrvkContext* check_ctx(JNIEnv* env, jlong handle) {
    SrvkContext* ctx = (SrvkContext*)(uintptr_t)handle;
    if (ctx == NULL) throw_runtime(env, "null srvulkan context");
    return ctx;
}

static void* check_direct(JNIEnv* env, jobject buf, jlong* capacity) {
    if (buf == NULL) {
        throw_runtime(env, "null direct buffer");
        return NULL;
    }
    void* bytes = env->GetDirectBufferAddress(buf);
    jlong cap = env->GetDirectBufferCapacity(buf);
    if (bytes == NULL || cap <= 0) {
        throw_runtime(env, "buffer is not a non-empty direct buffer");
        return NULL;
    }
    if (capacity != NULL) *capacity = cap;
    return bytes;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeLoadPipelineCache(JNIEnv* env, jobject, jlong handle,
                                                           jbyteArray initial) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (!ctx) return;
    std::vector<unsigned char> bytes;
    if (initial) {
        jsize n = env->GetArrayLength(initial);
        if (n >= 32 && n <= (16<<20)) {
            bytes.resize((size_t)n);
            env->GetByteArrayRegion(initial, 0, n, (jbyte*)bytes.data());
            if (env->ExceptionCheck()) return;
            VkPhysicalDeviceProperties props = {};
            vkGetPhysicalDeviceProperties(ctx->physical, &props);
            uint32_t header[4]; memcpy(header, bytes.data(), 16);
            if (header[0] < 32 || header[0] > (uint32_t)n ||
                header[1] != VK_PIPELINE_CACHE_HEADER_VERSION_ONE ||
                header[2] != props.vendorID || header[3] != props.deviceID ||
                memcmp(bytes.data()+16, props.pipelineCacheUUID, VK_UUID_SIZE) != 0) bytes.clear();
        }
    }
    if (ctx->pipeline_cache) vkDestroyPipelineCache(ctx->device, ctx->pipeline_cache, NULL);
    ctx->pipeline_cache = VK_NULL_HANDLE;
    VkPipelineCacheCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    ci.initialDataSize = bytes.size(); ci.pInitialData = bytes.empty() ? NULL : bytes.data();
    if (vkCreatePipelineCache(ctx->device, &ci, NULL, &ctx->pipeline_cache) != VK_SUCCESS) {
        ci.initialDataSize = 0; ci.pInitialData = NULL;
        vkCreatePipelineCache(ctx->device, &ci, NULL, &ctx->pipeline_cache);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeSavePipelineCache(JNIEnv* env, jobject, jlong handle) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (!ctx || !ctx->pipeline_cache) return NULL;
    for (int attempt=0; attempt<3; ++attempt) {
        size_t n = 0;
        if (vkGetPipelineCacheData(ctx->device, ctx->pipeline_cache, &n, NULL) != VK_SUCCESS || n > (16u<<20)) return NULL;
        std::vector<unsigned char> bytes(n);
        VkResult result = vkGetPipelineCacheData(ctx->device, ctx->pipeline_cache, &n, bytes.data());
        if (result == VK_INCOMPLETE) continue;
        if (result != VK_SUCCESS) return NULL;
        jbyteArray output = env->NewByteArray((jsize)n);
        if (output) env->SetByteArrayRegion(output, 0, (jsize)n, (jbyte*)bytes.data());
        return output;
    }
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeCreateImage(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                    jint w, jint h, jint format) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return 0;
    char errmsg[SRVK_ERR_LEN];
    uint64_t img = srvk_create_image(ctx, (int)w, (int)h, (int)format, errmsg, sizeof(errmsg));
    if (img == 0) {
        SRVK_LOGE("createImage failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
    return (jlong)img;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeWriteImage(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                    jlong image, jobject bytes) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    jlong cap = 0;
    void* ptr = check_direct(env, bytes, &cap);
    if (ptr == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_write_image(ctx, (uint64_t)image, ptr, (size_t)cap, errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("writeImage failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

// GetFloatArrayRegion copies directly into mapped Vulkan staging. No full-frame
// Java direct buffer and no pinned array across a fence wait.
JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeWriteImageFloats(JNIEnv* env, jobject, jlong handle,
                                                          jlong image, jfloatArray values) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (!ctx || !values) return;
    jsize n = env->GetArrayLength(values);
    char errmsg[SRVK_ERR_LEN] = {};
    void* ptr = srvk_prepare_image_upload(ctx, (uint64_t)image, (size_t)n * 4, errmsg, sizeof(errmsg));
    if (!ptr) { throw_runtime(env, errmsg); return; }
    env->GetFloatArrayRegion(values, 0, n, (jfloat*)ptr);
    if (env->ExceptionCheck()) return;
    if (srvk_write_image(ctx, (uint64_t)image, ptr, (size_t)n * 4, errmsg, sizeof(errmsg)) != 0)
        throw_runtime(env, errmsg);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeReadImageFloatsInto(JNIEnv* env, jobject, jlong handle,
    jlong image, jint w, jint h, jfloatArray output, jint stride, jint dx, jint dy) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (!ctx || !output) return;
    const jsize n = env->GetArrayLength(output);
    if (w <= 0 || h <= 0 || dx < 0 || dy < 0 || stride < dx+w ||
        (int64_t)(dy+h-1)*stride+dx+w > n) {
        throw_runtime(env, "float readback destination outside array"); return;
    }
    char errmsg[SRVK_ERR_LEN] = {};
    float* ptr = (float*)srvk_read_image_region_mapped(ctx, (uint64_t)image, 0, 0, w, h,
                                                     errmsg, sizeof(errmsg));
    if (!ptr) { throw_runtime(env, errmsg); return; }
    if (w == stride && dx == 0) {
        env->SetFloatArrayRegion(output, dy*stride, w*h, ptr);
    } else for (int row=0; row<h && !env->ExceptionCheck(); ++row)
        env->SetFloatArrayRegion(output, (dy+row)*stride+dx, w, ptr+(size_t)row*w);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeReleaseStaging(JNIEnv* env, jobject, jlong handle) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx) srvk_release_staging(ctx);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeReadImage(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                   jlong image, jobject out) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    jlong cap = 0;
    void* ptr = check_direct(env, out, &cap);
    if (ptr == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_read_image(ctx, (uint64_t)image, ptr, (size_t)cap, errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("readImage failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeReadImageRegion(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                        jlong image, jint x, jint y, jint w, jint h,
                                                        jobject out) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    jlong cap = 0;
    void* ptr = check_direct(env, out, &cap);
    if (ptr == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_read_image_region(ctx, (uint64_t)image, (int)x, (int)y, (int)w, (int)h,
                               ptr, (size_t)cap, errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("readImageRegion failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDestroyImage(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                      jlong image) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    srvk_destroy_image(ctx, (uint64_t)image);
}

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeCreatePipeline(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                        jlong module, jint uboBinding,
                                                        jint uboSize, jintArray imgBindings,
                                                        jintArray imgKinds) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return 0;
    jsize n = imgBindings != NULL ? env->GetArrayLength(imgBindings) : -1;
    jsize m = imgKinds != NULL ? env->GetArrayLength(imgKinds) : -1;
    if (n < 0 || n != m) {
        throw_runtime(env, "image binding/kind arrays must match");
        return 0;
    }
    jint* bindings = n > 0 ? env->GetIntArrayElements(imgBindings, NULL) : NULL;
    jint* kinds = n > 0 ? env->GetIntArrayElements(imgKinds, NULL) : NULL;
    char errmsg[SRVK_ERR_LEN];
    uint64_t pipe = srvk_create_pipeline(ctx, (uint64_t)module, (int)uboBinding, (size_t)uboSize, (int)n,
                                         (const int*)bindings, (const int*)kinds,
                                         errmsg, sizeof(errmsg));
    if (n > 0) {
        env->ReleaseIntArrayElements(imgBindings, bindings, JNI_ABORT);
        env->ReleaseIntArrayElements(imgKinds, kinds, JNI_ABORT);
    }
    if (pipe == 0) {
        SRVK_LOGE("createPipeline failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
    return (jlong)pipe;
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeBindImage(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                   jlong pipeline, jint binding, jlong image) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_bind_image(ctx, (uint64_t)pipeline, (int)binding, (uint64_t)image,
                        errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("bindImage failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeWriteUniforms(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                       jlong pipeline, jobject bytes) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    jlong cap = 0;
    void* ptr = check_direct(env, bytes, &cap);
    if (ptr == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_write_uniforms(ctx, (uint64_t)pipeline, ptr, (size_t)cap, errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("writeUniforms failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDispatch(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                  jlong pipeline, jint gx, jint gy, jint gz) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    char errmsg[SRVK_ERR_LEN];
    if (srvk_dispatch(ctx, (uint64_t)pipeline, (int)gx, (int)gy, (int)gz,
                      errmsg, sizeof(errmsg)) != 0) {
        SRVK_LOGE("dispatch failed: %s", errmsg);
        throw_runtime(env, errmsg);
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VulkanHub_nativeDestroyPipeline(JNIEnv* env, jobject /*thiz*/,
                                                         jlong handle, jlong pipeline) {
    SrvkContext* ctx = check_ctx(env, handle);
    if (ctx == NULL) return;
    srvk_destroy_pipeline(ctx, (uint64_t)pipeline);
}

} // extern "C"
