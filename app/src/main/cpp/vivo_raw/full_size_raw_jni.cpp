// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// CPU copy of a full-size RAW16 camera buffer that has no CPU usage (rawr Raw16CpuSnapshot.cpp),
// with rawr's block-CFA remosaic.
#include <android/hardware_buffer_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <thread>

#include "CfaRemosaic.h"

namespace {
void fail(JNIEnv* env, const char* text) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), text);
}
}  // namespace

// block 1 = Bayer, 2 = quad (remosaiced), 4 = 4x4 (2x2 sums, then quad remosaic at half size).
extern "C" JNIEXPORT jobject JNICALL Java_com_matthew_rawlens_FullSizeRaw_nativeCopy(
    JNIEnv* env, jclass, jobject buffer, jint width, jint height, jint block, jint order) {
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb || width <= 0 || height <= 0 || (block != 1 && block != 2 && block != 4)) {
        fail(env, "Full-size RAW: invalid input");
        return nullptr;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != 0x20 /* HAL_PIXEL_FORMAT_RAW16 */) {
        fail(env, "Full-size RAW: buffer is not RAW16");
        return nullptr;
    }
    const uint32_t w = uint32_t(width), h = uint32_t(height);
    if (desc.width < w || desc.height < h || desc.stride < w) {
        fail(env, "Full-size RAW: unexpected buffer layout");
        return nullptr;
    }
    const uint32_t scale = block == 4 ? 2 : 1;
    const uint32_t outW = w / scale, outH = h / scale;
    const size_t bytes = size_t(outW) * outH * 2;
    if (bytes > size_t(INT32_MAX)) {
        fail(env, "Full-size RAW: too large");
        return nullptr;
    }
    auto* out = static_cast<uint16_t*>(std::malloc(bytes));
    if (!out) {
        fail(env, "Full-size RAW: not enough memory");
        return nullptr;
    }
    void* address = nullptr;
    const int locked = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &address);
    if (locked != 0 || !address) {
        std::free(out);
        char text[64];
        std::snprintf(text, sizeof(text), "Full-size RAW: CPU lock failed (%d)", locked);
        fail(env, text);
        return nullptr;
    }
    const auto* source = static_cast<const uint16_t*>(address);
    for (uint32_t y = 0; y < outH; ++y) {
        uint16_t* row = out + size_t(y) * outW;
        if (scale == 1) {
            std::memcpy(row, source + size_t(y) * desc.stride, size_t(outW) * 2);
        } else {
            const uint16_t* a = source + size_t(y) * 2 * desc.stride;
            const uint16_t* b = a + desc.stride;
            for (uint32_t x = 0; x < outW; ++x)
                row[x] = uint16_t(std::min(65535u, uint32_t(a[2 * x]) + a[2 * x + 1] + b[2 * x] + b[2 * x + 1]));
        }
    }
    AHardwareBuffer_unlock(ahb, nullptr);
    if (block > 1) {
        try {
            const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
            rawrcam::imaging::remosaicBlockCfa(out, outW, outH, 2, uint32_t(order), threads,
                                               rawrcam::imaging::CfaRemosaicQuality::Detail,
                                               scale == 2 ? 4.0f : 1.0f);
        } catch (const std::exception&) {
            std::free(out);
            fail(env, "Full-size RAW: remosaic failed");
            return nullptr;
        }
    }
    jobject result = env->NewDirectByteBuffer(out, jlong(bytes));
    if (!result) std::free(out);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_FullSizeRaw_nativeFree(JNIEnv* env, jclass, jobject buffer) {
    if (buffer) std::free(env->GetDirectBufferAddress(buffer));
}
