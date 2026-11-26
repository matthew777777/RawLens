#include <jni.h>
#include <android/bitmap.h>
#include <errno.h>
#include <unistd.h>
#include <string>
#include <vector>

#include "encoding/jpeg/ParallelJpeg.h"

namespace {
jstring error(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_NativeJpegEncoder_nativeEncode(
    JNIEnv* env, jobject, jobject bitmap, jint fd, jint quality, jint subsampling, jbyteArray iccArray) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return error(env, "Could not query JPEG bitmap");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return error(env, "Native JPEG requires RGBA_8888 bitmap");

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return error(env, "Could not lock JPEG bitmap pixels");

    std::vector<unsigned char> iccProfile;
    if (iccArray) {
        const jsize iccSize = env->GetArrayLength(iccArray);
        if (iccSize > 0) {
            iccProfile.resize(static_cast<size_t>(iccSize));
            env->GetByteArrayRegion(iccArray, 0, iccSize, reinterpret_cast<jbyte*>(iccProfile.data()));
            if (env->ExceptionCheck()) {
                AndroidBitmap_unlockPixels(env, bitmap);
                return error(env, "Could not read JPEG ICC profile");
            }
        }
    }

    rawlens::encoding::jpeg::ParallelJpegInput in;
    in.rgba = static_cast<const unsigned char*>(pixels);
    in.width = static_cast<int>(info.width);
    in.height = static_cast<int>(info.height);
    in.stride = info.stride;
    in.quality = quality;
    in.yuv444 = subsampling == 444;
    in.icc = iccProfile.data();
    in.iccSize = iccProfile.size();
    rawlens::encoding::jpeg::ParallelJpegOutput jpeg;
    std::string message;
    const bool encoded = rawlens::encoding::jpeg::encodeParallel(in, jpeg, message);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!encoded) return error(env, message);
    // MediaStore descriptors can be backed by FUSE: few large writes.
    for (const auto& piece : jpeg.pieces) {
        size_t done = 0;
        while (done < piece.size) {
            const ssize_t n = write(fd, piece.data + done, piece.size - done);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) return error(env, "Native JPEG write failed");
            done += static_cast<size_t>(n);
        }
    }
    return nullptr;
}

// Locked pixels of a tight RGBA_8888 bitmap as a direct buffer (GPU readback target), or null.
extern "C" JNIEXPORT jobject JNICALL
Java_com_matthew_rawlens_NativeJpegEncoder_nativeLockPixels(JNIEnv* env, jobject, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride != info.width * 4) return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return nullptr;
    jobject buffer = env->NewDirectByteBuffer(pixels, static_cast<jlong>(info.stride) * info.height);
    if (!buffer) AndroidBitmap_unlockPixels(env, bitmap);
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_NativeJpegEncoder_nativeUnlockPixels(JNIEnv* env, jobject, jobject bitmap) {
    AndroidBitmap_unlockPixels(env, bitmap);
}
