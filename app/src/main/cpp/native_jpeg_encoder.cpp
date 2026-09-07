#include <stdio.h>
#include <jni.h>
#include <android/bitmap.h>
#include <jpeglib.h>
#include <unistd.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>
#include <setjmp.h>

namespace {
struct ErrorManager { jpeg_error_mgr pub; jmp_buf jump; char message[JMSG_LENGTH_MAX]; };
void onError(j_common_ptr cinfo) {
    auto* err = reinterpret_cast<ErrorManager*>(cinfo->err);
    (*cinfo->err->format_message)(cinfo, err->message);
    longjmp(err->jump, 1);
}

void writeIcc(j_compress_ptr cinfo, const unsigned char* data, size_t size) {
    if (!data || size == 0) return;
    constexpr size_t kHeader = 14;
    constexpr size_t kMaxMarkerPayload = 65533;
    constexpr size_t kMaxChunk = kMaxMarkerPayload - kHeader;
    const int count = static_cast<int>((size + kMaxChunk - 1) / kMaxChunk);
    size_t offset = 0;
    for (int index = 1; index <= count; ++index) {
        const size_t chunk = std::min(kMaxChunk, size - offset);
        std::vector<JOCTET> marker(kHeader + chunk);
        std::memcpy(marker.data(), "ICC_PROFILE\0", 12);
        marker[12] = static_cast<JOCTET>(index);
        marker[13] = static_cast<JOCTET>(count);
        std::memcpy(marker.data() + kHeader, data + offset, chunk);
        jpeg_write_marker(cinfo, JPEG_APP0 + 2, marker.data(), static_cast<unsigned int>(marker.size()));
        offset += chunk;
    }
}

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

    const int outFd = dup(fd);
    if (outFd < 0) { AndroidBitmap_unlockPixels(env, bitmap); return error(env, "Could not duplicate JPEG file descriptor"); }
    FILE* output = fdopen(outFd, "wb");
    if (!output) { close(outFd); AndroidBitmap_unlockPixels(env, bitmap); return error(env, "Could not open native JPEG stream"); }

    // MediaStore descriptors can be backed by FUSE.  The small default stdio buffer causes
    // thousands of write() calls for a full-resolution quality-100 JPEG, so give libjpeg a
    // buffer large enough to submit the output in useful chunks.  setvbuf owns no caller memory
    // in this form and the buffer is released by fclose().
    setvbuf(output, nullptr, _IOFBF, 256 * 1024);

    std::vector<unsigned char> iccProfile;
    if (iccArray) {
        const jsize iccSize = env->GetArrayLength(iccArray);
        if (iccSize > 0) {
            iccProfile.resize(static_cast<size_t>(iccSize));
            env->GetByteArrayRegion(iccArray, 0, iccSize, reinterpret_cast<jbyte*>(iccProfile.data()));
            if (env->ExceptionCheck()) {
                fclose(output);
                AndroidBitmap_unlockPixels(env, bitmap);
                return error(env, "Could not read JPEG ICC profile");
            }
        }
    }

    jpeg_compress_struct cinfo{};
    ErrorManager jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = onError;
    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        fclose(output);
        AndroidBitmap_unlockPixels(env, bitmap);
        return error(env, jerr.message);
    }

    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, output);
    cinfo.image_width = info.width;
    cinfo.image_height = info.height;
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, std::max(1, std::min(100, static_cast<int>(quality))), TRUE);
    // Optimized Huffman coding performs an additional full-image statistics pass.  It generally
    // saves only a few percent at the cost of materially higher encode latency, particularly for
    // 12-50 MP captures.  libjpeg-turbo's standard tables keep encoding single-pass while leaving
    // quantization, chroma subsampling and therefore decoded image quality unchanged.
    cinfo.optimize_coding = FALSE;
    if (quality >= 98) cinfo.dct_method = JDCT_ISLOW;

    if (subsampling == 444) {
        cinfo.comp_info[0].h_samp_factor = 1; cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1; cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1; cinfo.comp_info[2].v_samp_factor = 1;
    } else {
        cinfo.comp_info[0].h_samp_factor = 2; cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1; cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1; cinfo.comp_info[2].v_samp_factor = 1;
    }

    jpeg_start_compress(&cinfo, TRUE);
    writeIcc(&cinfo, iccProfile.data(), iccProfile.size());

    auto* base = static_cast<unsigned char*>(pixels);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = base + static_cast<size_t>(cinfo.next_scanline) * info.stride;
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    const bool writeOk = fflush(output) == 0 && ferror(output) == 0;
    fclose(output);
    AndroidBitmap_unlockPixels(env, bitmap);
    return writeOk ? nullptr : error(env, "Native JPEG write failed");
}
