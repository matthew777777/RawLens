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
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <cstring>
#include <poll.h>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "RawLensVfEgl"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

// Probe-only: CPU-fill an RGBA_8888 AHB with an animated gradient + moving
// white bar. Stand-in for the Vulkan superpixel export so the AHB -> EGL
// import -> encoder-surface interop can be validated without the camera.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_fillTestPattern(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height, jint frameIndex) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return 1;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return 2;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return 3;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    const uint32_t stride = desc.stride;  // pixels per row (may exceed width)
    uint8_t* base = static_cast<uint8_t*>(ptr);
    const int barX = (int)((int64_t)frameIndex * 97 % width);
    const int barHalf = width / 60 > 2 ? width / 60 : 2;
    const int denom = width + height + 90;
    for (int y = 0; y < height; ++y) {
        uint32_t* row = reinterpret_cast<uint32_t*>(base + (size_t)y * stride * 4);
        const uint8_t g = (uint8_t)((y * 255) / height);
        for (int x = 0; x < width; ++x) {
            const uint8_t r = (uint8_t)((x * 255) / width);
            uint8_t b = (uint8_t)(((x + y + frameIndex * 13) * 255) / denom);
            uint8_t rr = r, gg = g, bb = b;
            int dx = x - barX;
            if (dx < 0) dx = -dx;
            if (dx < barHalf) {
                rr = 255;
                gg = 255;
                bb = 255;
            }
            // RGBA_8888 little-endian: R in lowest byte.
            row[x] = (0xFFu << 24) | ((uint32_t)bb << 16) | ((uint32_t)gg << 8) | rr;
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);
    return 0;
}

// Phase-A probe: allocate a synthetic RAW16-style sensor buffer (NDK-side,
// as R16_UINT: the NDK has no RAW16 format, but the layout is byte-identical
// and the Vulkan input path imports storage buffers format-agnostically).
// Returns null on failure; Java owns the object (close() releases).
extern "C" JNIEXPORT jobject JNICALL
Java_com_matthew_rawlens_VfEglImport_createBayerInput(
    JNIEnv* env, jobject, jint width, jint height) {
    if (width <= 0 || height <= 0) return nullptr;
    AHardwareBuffer_Desc desc{};
    desc.width = (uint32_t)width;
    desc.height = (uint32_t)height;
    desc.layers = 1;
    // No public RAW16 format exists in the NDK; R16_UINT is byte-identical
    // (w*h little-endian U16, stride in pixels) and the Vulkan input path
    // imports the buffer as a format-agnostic storage buffer, so the mosaic
    // layout is defined purely by our fill + u_pitch.
    desc.format = AHARDWAREBUFFER_FORMAT_R16_UINT;
    // Usage mirrors camera HAL RAW buffers (CPU-readable, no GPU usage —
    // Vulkan imports them as storage buffers regardless) because this
    // gralloc rejects GPU_DATA_BUFFER on R16_UINT.
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    desc.stride = 0;
    AHardwareBuffer* buf = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buf) != 0 || !buf) {
        LOGW("vf-egl: synthetic RAW allocate %dx%d failed", width, height);
        return nullptr;
    }
    jobject out = AHardwareBuffer_toHardwareBuffer(env, buf);
    AHardwareBuffer_release(buf);  // Java object holds its own reference.
    return out;
}

// Phase-A probe: fill a synthetic RAW16 buffer with an animated RGGB Bayer
// mosaic (10-bit codes in U16 words) + moving saturated bar. Returns the
// row stride in pixels on success (what the superpixel shader needs as
// u_pitch), or a negative diagnostic on failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_fillBayerPattern(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height, jint frameIndex) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return -1;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return -2;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return -3;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    const uint32_t stride = desc.stride;  // pixels per row
    uint8_t* base = static_cast<uint8_t*>(ptr);
    const int barX = (int)((int64_t)frameIndex * 97 % width);
    const int barHalf = width / 120 > 2 ? width / 120 : 2;
    for (int y = 0; y < height; ++y) {
        uint16_t* row = reinterpret_cast<uint16_t*>(base + (size_t)y * stride * 2);
        const bool yEven = (y & 1) == 0;
        for (int x = 0; x < width; ++x) {
            const bool xEven = (x & 1) == 0;
            uint16_t v;
            if (xEven && yEven) {
                v = (uint16_t)((x * 1023) / width);  // R: horizontal ramp
            } else if (!xEven && yEven) {
                v = (uint16_t)((y * 1023) / height);  // Gr: vertical ramp
            } else if (xEven && !yEven) {
                v = (uint16_t)(((x + y) * 1023) / (width + height));  // Gb: diagonal
            } else {
                // B: same ramp as Gb (uniform mosaic at the center texel
                // for the precision probe) + frame animation on top.
                v = (uint16_t)(((x + y + frameIndex * 29) * 1023) / (width + height));
            }
            int dx = x - barX;
            if (dx < 0) dx = -dx;
            if (dx < barHalf) v = 1023;  // saturated bar moves every frame
            row[x] = v;
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);
    return (jint)stride;
}

// Perf-harness CFA: the R16 synthetic is CPU-only gralloc (this gralloc
// rejects GPU_DATA_BUFFER on R16_UINT), while real camera HAL buffers
// carry GPU usage. A BLOB with GPU_DATA_BUFFER models production
// cacheability; the CFA shader path is format-agnostic (codes[] +
// pitch), so the mosaic layout is defined purely by our fill + pitch.
extern "C" JNIEXPORT jobject JNICALL
Java_com_matthew_rawlens_VfEglImport_createBlobBayerInput(
    JNIEnv* env, jobject, jint width, jint height) {
    if (width <= 0 || height <= 0) return nullptr;
    AHardwareBuffer_Desc desc{};
    desc.width = (uint32_t)width * (uint32_t)height * 2u;  // bytes, tight
    desc.height = 1;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_BLOB;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    desc.stride = 0;
    AHardwareBuffer* buf = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buf) != 0 || !buf) {
        LOGW("vf-egl: blob bayer allocate %dx%d failed", width, height);
        return nullptr;
    }
    jobject out = AHardwareBuffer_toHardwareBuffer(env, buf);
    AHardwareBuffer_release(buf);
    return out;
}

// Fill the BLOB CFA with the frame-0 mosaic, tightly packed
// (pitch == width). Returns width (pitch) or a negative diagnostic.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_fillBlobBayer(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return -1;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return -2;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return -3;
    }
    uint16_t* px = static_cast<uint16_t*>(ptr);
    const int barX = 0;
    const int barHalf = width / 120 > 2 ? width / 120 : 2;
    for (int y = 0; y < height; ++y) {
        uint16_t* row = px + (size_t)y * width;
        const bool yEven = (y & 1) == 0;
        for (int x = 0; x < width; ++x) {
            const bool xEven = (x & 1) == 0;
            uint16_t v;
            if (xEven && yEven) {
                v = (uint16_t)((x * 1023) / width);
            } else if (!xEven && yEven) {
                v = (uint16_t)((y * 1023) / height);
            } else if (xEven && !yEven) {
                v = (uint16_t)(((x + y) * 1023) / (width + height));
            } else {
                v = (uint16_t)(((x + y) * 1023) / (width + height));
            }
            int dx = x - barX;
            if (dx < 0) dx = -dx;
            if (dx < barHalf) v = 1023;
            row[x] = v;
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);
    return width;
}

// Copy direct src bytes into a BLOB CFA (JPEG RCD upload path). Returns
// 0 or a negative diagnostic.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_blitBytesToBlob(
    JNIEnv* env, jobject, jobject dst, jobject src, jint byteCount) {
    if (!dst || !src || byteCount <= 0) return -1;
    void* srcPtr = env->GetDirectBufferAddress(src);
    if (!srcPtr) return -2;
    if (env->GetDirectBufferCapacity(src) < byteCount) return -3;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, dst);
    if (!buf) return -4;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    if (desc.width < (uint32_t)byteCount) {
        AHardwareBuffer_release(buf);
        return -5;
    }
    void* dstPtr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dstPtr) != 0 ||
        !dstPtr) {
        AHardwareBuffer_release(buf);
        return -6;
    }
    memcpy(dstPtr, srcPtr, (size_t)byteCount);
    AHardwareBuffer_unlock(buf, nullptr);
    AHardwareBuffer_release(buf);
    return 0;
}

// Phase-A: GPU fence sync (EGL_KHR_fence_sync) for the encoder handoff.
// The Java EGL bindings only expose ANDROID native fences, so KHR fences
// live here. Insert after eglSwapBuffers; wait before reusing the sampled
// buffer. Replaces per-frame glFinish stalls with overlapped execution.
extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_VfEglImport_createFence(JNIEnv*, jobject) {
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) return 0;
    EGLSyncKHR sync = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, nullptr);
    if (sync == EGL_NO_SYNC_KHR) {
        LOGW("vf-egl: eglCreateSyncKHR failed: %#x", eglGetError());
        return 0;
    }
    return reinterpret_cast<jlong>(sync);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_VfEglImport_waitFence(JNIEnv*, jobject, jlong sync, jlong timeoutNs) {
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY || sync == 0) return JNI_TRUE;  // proceed, don't wedge
    EGLint r = eglClientWaitSyncKHR(display, reinterpret_cast<EGLSyncKHR>(sync),
                                    EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, (EGLTimeKHR)timeoutNs);
    return (r == EGL_CONDITION_SATISFIED_KHR) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfEglImport_destroyFence(JNIEnv*, jobject, jlong sync) {
    if (sync == 0) return;
    EGLDisplay display = eglGetCurrentDisplay();
    if (display != EGL_NO_DISPLAY) {
        eglDestroySyncKHR(display, reinterpret_cast<EGLSyncKHR>(sync));
    }
}

// Wait-free handoff consumer: adopt a Vulkan-exported sync fd as an EGL
// native-fence wait. The GPU waits; the CPU never blocks (compare the
// FENCE_KHR client-wait used for buffer reuse). The sync takes ownership
// of the fd; returns 0 on success, nonzero otherwise (fd is closed on
// every failure path, so the caller must not close it).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_adoptNativeFence(JNIEnv*, jobject, jint fd) {    if (fd < 0) return 1;
    EGLDisplay display = eglGetCurrentDisplay();
    if (display == EGL_NO_DISPLAY) {
        close(fd);
        return 2;
    }
    const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
    EGLSyncKHR sync =
        eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        LOGW("vf-egl: native-fence create failed: %#x", eglGetError());
        close(fd);
        return 3;
    }
    // Ownership of fd passed to the sync; do not close here.
    if (eglWaitSyncKHR(display, sync, 0) != EGL_TRUE) {
        LOGW("vf-egl: native-fence wait failed: %#x", eglGetError());
        eglDestroySyncKHR(display, sync);
        return 4;
    }
    eglDestroySyncKHR(display, sync);
    return 0;
}

// Phase-B validation: sample an RGBA_8888 buffer on CPU (requires
// USAGE_CPU_READ_OFTEN at allocation). Returns {avgR, avgG, avgB,
// centerLuma} normalized 0..1, or null on failure. Sampled sparsely
// (every 16th pixel) so a 960x540 readback costs ~20k pixels.
// Phase-B validation: sample an RGBA buffer on CPU (requires
// USAGE_CPU_READ_OFTEN at allocation). Handles RGBA_8888 bytes and
// RGBA_FP16 halves. Returns {avgR, avgG, avgB, centerLuma} normalized
// 0..1, or null on failure. Sampled sparsely (every 16th pixel).
static float halfToFloat(uint16_t h) {
    uint32_t sign = (h >> 15) & 1u;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t mant = h & 0x3ffu;
    uint32_t f;
    if (exp == 0) {
        if (mant == 0) {
            f = sign << 31;
        } else {
            exp = 1;
            while (!(mant & 0x400u)) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ffu;
            f = (sign << 31) | ((exp + 112u) << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        f = (sign << 31) | (0xffu << 23) | (mant << 13);
    } else {
        f = (sign << 31) | ((exp + 112u) << 23) | (mant << 13);
    }
    float r;
    memcpy(&r, &f, sizeof(r));
    return r;
}

// Ten-bit proof: CPU-fill an RGBA_FP16 AHB with a smooth horizontal ramp
// (full float precision, no quantization). Returns row stride in pixels,
// negative on failure. Mirrors the recorder's blit source format.
static uint16_t floatToHalf(float v) {
    uint32_t bits;
    memcpy(&bits, &v, sizeof(bits));
    uint32_t sign = (bits >> 16) & 0x8000u;
    int32_t exp = (int32_t)((bits >> 23) & 0xffu) - 112;
    uint32_t mant = bits & 0x7fffffu;
    if (exp >= 31) return (uint16_t)(sign | 0x7bffu);  // clamp inf/nan
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;  // underflow to zero
        mant |= 0x800000u;
        uint32_t t = mant >> (14 - exp);
        return (uint16_t)(sign | t);
    }
    return (uint16_t)(sign | ((uint32_t)exp << 10) | (mant >> 13));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_fillHalfGradient(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height, jint frameIndex) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return -1;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return -2;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return -3;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    const uint32_t stride = desc.stride;
    uint8_t* base = static_cast<uint8_t*>(ptr);
    const float phase = (frameIndex % 60) / 60.0f;
    for (int y = 0; y < height; ++y) {
        uint16_t* row = reinterpret_cast<uint16_t*>(base + (size_t)y * stride * 8);
        for (int x = 0; x < width; ++x) {
            float t = (x / (float)width + phase);
            t -= (int)t;
            uint16_t h = floatToHalf(t);
            row[x * 4 + 0] = h;
            row[x * 4 + 1] = h;
            row[x * 4 + 2] = h;
            row[x * 4 + 3] = floatToHalf(1.0f);
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);
    return (jint)stride;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_VfEglImport_sampleRgba(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return nullptr;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return nullptr;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return nullptr;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    const uint32_t stride = desc.stride;
    const bool half = desc.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    const int bpp = half ? 8 : 4;
    uint8_t* base = static_cast<uint8_t*>(ptr);
    double sumR = 0, sumG = 0, sumB = 0, n = 0;
    for (int y = 0; y < height; y += 16) {
        uint8_t* row = base + (size_t)y * stride * bpp;
        for (int x = 0; x < width; x += 16) {
            float r, g, b;
            if (half) {
                uint16_t* px = reinterpret_cast<uint16_t*>(row + (size_t)x * 8);
                r = halfToFloat(px[0]);
                g = halfToFloat(px[1]);
                b = halfToFloat(px[2]);
            } else {
                uint8_t* px = row + (size_t)x * 4;
                r = px[0] / 255.0f;
                g = px[1] / 255.0f;
                b = px[2] / 255.0f;
            }
            sumR += r;
            sumG += g;
            sumB += b;
            ++n;
        }
    }
    // Center texel luma (Rec.709) for a deterministic anchor.
    float cr, cg, cb;
    if (half) {
        uint16_t* crow = reinterpret_cast<uint16_t*>(
            base + (size_t)(height / 2) * stride * 8 + (size_t)(width / 2) * 8);
        cr = halfToFloat(crow[0]);
        cg = halfToFloat(crow[1]);
        cb = halfToFloat(crow[2]);
    } else {
        uint8_t* crow =
            base + (size_t)(height / 2) * stride * 4 + (size_t)(width / 2) * 4;
        cr = crow[0] / 255.0f;
        cg = crow[1] / 255.0f;
        cb = crow[2] / 255.0f;
    }
    float center = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
    AHardwareBuffer_unlock(buf, nullptr);
    if (n == 0) return nullptr;
    jfloatArray out = env->NewFloatArray(4);
    if (!out) return nullptr;
    float vals[4] = {
        (float)(sumR / n), (float)(sumG / n), (float)(sumB / n), center};
    env->SetFloatArrayRegion(out, 0, 4, vals);
    return out;
}

// RCD parity probe: dump a full RGBA_FP16 buffer to interleaved RGBA
// floats (width*height*4), or null. Same lock/stride/half conversion as
// the sparse sampler; the caller polls the submit fd first.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_VfEglImport_dumpFp16(
    JNIEnv* env, jobject, jobject hardwareBuffer, jint width, jint height) {
    if (!hardwareBuffer || width <= 0 || height <= 0) return nullptr;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return nullptr;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) return nullptr;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return nullptr;
    }
    const uint32_t stride = desc.stride;
    uint8_t* base = static_cast<uint8_t*>(ptr);
    jfloatArray out = env->NewFloatArray((jsize)width * height * 4);
    if (!out) {
        AHardwareBuffer_unlock(buf, nullptr);
        return nullptr;
    }
    std::vector<float> vals((size_t)width * height * 4);
    for (int y = 0; y < height; ++y) {
        const uint16_t* row =
            reinterpret_cast<const uint16_t*>(base + (size_t)y * stride * 8);
        for (int x = 0; x < width; ++x) {
            const size_t o = ((size_t)y * width + x) * 4;
            vals[o + 0] = halfToFloat(row[x * 4 + 0]);
            vals[o + 1] = halfToFloat(row[x * 4 + 1]);
            vals[o + 2] = halfToFloat(row[x * 4 + 2]);
            vals[o + 3] = halfToFloat(row[x * 4 + 3]);
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);
    env->SetFloatArrayRegion(out, 0, (jsize)width * height * 4, vals.data());
    return out;
}

// True-10-bit slot gating: non-blocking readiness check of a Vulkan-exported
// sync fd (the delayed-queue handoff into queueInputBuffer, which has no fd
// channel). Never blocks; never closes (see closeSyncFd).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_VfEglImport_pollSyncFd(JNIEnv*, jobject, jint fd) {
    if (fd < 0) return JNI_FALSE;
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int r = poll(&pfd, 1, 0);
    if (r > 0 && (pfd.revents & (POLLIN | POLLERR | POLLHUP))) return JNI_TRUE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfEglImport_closeSyncFd(JNIEnv*, jobject, jint fd) {
    if (fd >= 0) close(fd);
}

// True-10-bit staging: app-owned semi-planar P010 buffer (GPU writes via
// Vulkan storage import, CPU reads for the codec copy). CPU-readable AND
// GPU-writable must both be granted or allocation fails (null).
extern "C" JNIEXPORT jobject JNICALL
Java_com_matthew_rawlens_VfEglImport_createP010(
    JNIEnv* env, jobject, jint width, jint height) {
    if (width <= 0 || height <= 0 || (height % 2) != 0) return nullptr;
    AHardwareBuffer_Desc desc{};
    desc.width = (uint32_t)width;
    desc.height = (uint32_t)height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_YCbCr_P010;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    desc.stride = 0;
    AHardwareBuffer* buf = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buf) != 0 || !buf) {
        LOGW("vf-egl: P010 allocate %dx%d failed", width, height);
        return nullptr;
    }
    jobject out = AHardwareBuffer_toHardwareBuffer(env, buf);
    AHardwareBuffer_release(buf);
    return out;
}

// [yStrideBytes, uvStrideBytes, 0] or null, from the allocator's describe
// (luma pixels * 2; interleaved UV rows span the same width). The submit
// validates tight packing against the measured allocation size, so a wrong
// convention here aborts loudly (-20) instead of misaddressing.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_matthew_rawlens_VfEglImport_describeP010(JNIEnv* env, jobject, jobject hardwareBuffer) {
    if (!hardwareBuffer) return nullptr;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return nullptr;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    jintArray out = env->NewIntArray(3);
    if (!out) return nullptr;
    jint vals[3] = {(jint)desc.stride * 2, (jint)desc.stride * 2, 0};
    env->SetIntArrayRegion(out, 0, 3, vals);
    return out;
}

// Dumb CPU copy: OUR semi-planar P010 -> codec planar flexible-YUV input
// (all math already done on GPU; MotionCam burns CPU on swscale here).
// Destinations are the codec Image plane ByteBuffers (direct) with their
// row strides and chroma spacing; Y rows memcpy, UV de-interleaves.
// Returns 0, negative on lock/address failure. Timed by the caller.
// Sharded P010->codec bulk move (two-way halves). Returns the bulk
// milliseconds. Each half moves its Y rows + its UV rows (~12MB at 4K);
// both finish together and the halves telescope, so any height is
// exactly covered.
static double copyP010Bulk(const uint8_t* base, uint8_t* yPtr, int yDstStride, uint8_t* uPtr,
                           int uDstStride, int uDstPxB, uint8_t* vPtr, int vDstStride, int vDstPxB,
                           int width, int height, int srcYStrideB, int srcUvStrideB) {
    const size_t yRowB = (size_t)width * 2;
    const uint8_t* uvBase = base + (size_t)srcYStrideB * height;
    const int pairs = width / 2;
    const bool yTight = (size_t)yDstStride == yRowB && (size_t)srcYStrideB == yRowB;
    // Flexible-YUV U/V are usually views of ONE interleaved plane (pxStride
    // 4, V base = U base + 2): the "de-interleave" is then a row memcpy, not
    // 4M scalar stores (~25ms -> ~2ms at 4K). Anything else keeps the loop.
    const bool uvFast =
        uDstPxB == 4 && vDstPxB == 4 && vPtr == uPtr + 2 && uDstStride == vDstStride;
    static bool copyPathLogged = false;
    if (!copyPathLogged) {
        copyPathLogged = true;
        LOGI("vf-egl: p010 copy yTight=%d uvFast=%d uPx=%d vPx=%d vMinusU=%td",
             yTight, uvFast, uDstPxB, vDstPxB, vPtr - uPtr);
    }
    // One Y range + its UV rows. UV ranges derive from Y ranges (never the
    // reverse) so any height stays exactly covered; heights are even here
    // (createP010 enforces it), so halves are even-paired.
    auto copyRange = [&](int yA, int yB) {
        if (yTight) {
            memcpy(yPtr + (size_t)yA * yDstStride, base + (size_t)yA * srcYStrideB,
                   (size_t)(yB - yA) * yRowB);
        } else {
            for (int y = yA; y < yB; ++y) {
                memcpy(yPtr + (size_t)y * yDstStride, base + (size_t)y * srcYStrideB, yRowB);
            }
        }
        const int uvA = yA / 2, uvB = (yB + 1) / 2;
        if (uvFast) {
            const size_t uvRowB = (size_t)pairs * 4;
            for (int y = uvA; y < uvB; ++y) {
                memcpy(uPtr + (size_t)y * uDstStride, uvBase + (size_t)y * srcUvStrideB, uvRowB);
            }
        } else {
            for (int y = uvA; y < uvB; ++y) {
                const uint16_t* srcRow =
                    reinterpret_cast<const uint16_t*>(uvBase + (size_t)y * srcUvStrideB);
                uint8_t* uRow = uPtr + (size_t)y * uDstStride;
                uint8_t* vRow = vPtr + (size_t)y * vDstStride;
                for (int x = 0; x < pairs; ++x) {
                    uint16_t u = srcRow[x * 2];
                    uint16_t v = srcRow[x * 2 + 1];
                    memcpy(uRow + (size_t)x * uDstPxB, &u, 2);
                    memcpy(vRow + (size_t)x * vDstPxB, &v, 2);
                }
            }
        }
    };
    struct timespec tsA, tsB;
    clock_gettime(CLOCK_MONOTONIC, &tsA);
    // Two-way: the destination (codec DRAM) is the bound, and extra streams
    // only thrash it — two workers x four threads measured slower AND
    // spikier than lightly-threaded copies. One helper + the caller.
    std::thread worker(std::thread(copyRange, 0, height / 2));
    copyRange(height / 2, height);
    worker.join();
    clock_gettime(CLOCK_MONOTONIC, &tsB);
    return (tsB.tv_sec - tsA.tv_sec) * 1000.0 + (tsB.tv_nsec - tsA.tv_nsec) / 1e6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfEglImport_copyP010ToCodec(
    JNIEnv* env, jobject, jobject srcBuffer,
    jobject yDst, jint yDstStride, jobject uDst, jint uDstStride, jint uDstPxB,
    jobject vDst, jint vDstStride, jint vDstPxB, jint width, jint height,
    jint srcYStrideB, jint srcUvStrideB) {
    if (!srcBuffer || !yDst || !uDst || !vDst || width <= 0 || height <= 0) return -1;
    AHardwareBuffer* src = AHardwareBuffer_fromHardwareBuffer(env, srcBuffer);
    if (!src) return -2;
    uint8_t* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yDst));
    uint8_t* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uDst));
    uint8_t* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vDst));
    if (!yPtr || !uPtr || !vPtr) return -3;
    void* srcPtr = nullptr;
    struct timespec tsA, tsB;
    clock_gettime(CLOCK_MONOTONIC, &tsA);
    if (AHardwareBuffer_lock(src, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &srcPtr) != 0 ||
        !srcPtr) {
        return -4;
    }
    clock_gettime(CLOCK_MONOTONIC, &tsB);
    const double lockMs =
        (tsB.tv_sec - tsA.tv_sec) * 1000.0 + (tsB.tv_nsec - tsA.tv_nsec) / 1e6;
    const double bulkMs = copyP010Bulk(static_cast<const uint8_t*>(srcPtr), yPtr, yDstStride, uPtr,
                                       uDstStride, uDstPxB, vPtr, vDstStride, vDstPxB, width,
                                       height, srcYStrideB, srcUvStrideB);
    clock_gettime(CLOCK_MONOTONIC, &tsA);
    AHardwareBuffer_unlock(src, nullptr);
    clock_gettime(CLOCK_MONOTONIC, &tsB);
    const double unlockMs =
        (tsB.tv_sec - tsA.tv_sec) * 1000.0 + (tsB.tv_nsec - tsA.tv_nsec) / 1e6;
    static double accLock = 0, accBulk = 0, accUnlock = 0;
    static int accN = 0;
    accLock += lockMs;
    accBulk += bulkMs;
    accUnlock += unlockMs;
    if (++accN % 30 == 0) {
        LOGI("vf-egl: copy-split n=%d lock=%.2f bulk=%.2f unlock=%.2f total=%.2f",
             accN, accLock / accN, accBulk / accN, accUnlock / accN,
             (accLock + accBulk + accUnlock) / accN);
    }
    return 0;
}

// Precision-probe readback: {Y, U, V} words at (x, y) from an app-owned
// tight semi-planar P010 buffer. Caller polls the submit fd first (a CPU
// lock would stall, not fail, on unfinished GPU work — but the probe wants
// an explicit completion gate, not an implicit one).
extern "C" JNIEXPORT jintArray JNICALL
Java_com_matthew_rawlens_VfEglImport_sampleP010Center(
    JNIEnv* env, jobject, jobject hardwareBuffer,
    jint yStrideB, jint uvStrideB, jint width, jint height, jint x, jint y) {
    if (!hardwareBuffer || yStrideB <= 0 || uvStrideB <= 0 || width <= 0 || height <= 0 ||
        x < 0 || y < 0 || x >= width || y >= height) {
        return nullptr;
    }
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buf) return nullptr;
    void* ptr = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ptr) != 0 ||
        !ptr) {
        return nullptr;
    }
    uint8_t* base = static_cast<uint8_t*>(ptr);
    uint16_t yWord = reinterpret_cast<uint16_t*>(base + (size_t)y * yStrideB)[x];
    const uint8_t* uvBase = base + (size_t)yStrideB * height;
    const uint16_t* uvRow =
        reinterpret_cast<const uint16_t*>(uvBase + (size_t)(y / 2) * uvStrideB);
    uint16_t uWord = uvRow[(x / 2) * 2];
    uint16_t vWord = uvRow[(x / 2) * 2 + 1];
    AHardwareBuffer_unlock(buf, nullptr);
    jintArray out = env->NewIntArray(3);
    if (!out) return nullptr;
    jint vals[3] = {(jint)yWord, (jint)uWord, (jint)vWord};
    env->SetIntArrayRegion(out, 0, 3, vals);
    return out;
}

