// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// RAWR-parity Ultra HDR file writer: dual libjpeg-turbo encode (SDR base +
// fitted gain map) assembled by UltrahdrMux into a single JPEG_R file with
// MPF + GContainer XMP + hdrgm + ISO 21496-1. Replaces the platform
// Bitmap.compress JPEG/R path, which only accepted a fixed smoothstep luma
// map with hardcoded 1..16 metadata.
//
// Flow (mirrors RAWR JpegCaptureWriter::startUltraHdr):
//   1. fitAndRemapMap on the GPU wide-range map (p99.9 ceiling + shoulder +
//      chroma knee + luma floor against the SDR base),
//   2. turbojpeg base (caller quality/subsampling, ICC kept) + map
//      (gainmapQuality, always 4:4:4, FASTDCT=0/ISLOW like the SDR path),
//   3. UltrahdrMux::assemble with GainMapMax = fitted ceiling,
//   4. write + fsync to the caller's fd.
// Any map/mux failure falls back to callers: JpegSaver re-encodes SDR via
// NativeJpegEncoder (RAWR writeUltraHdrFallback parity).
#include <jni.h>
#include <android/bitmap.h>
// jpeglib.h requires FILE: cstdio must come first.
#include <cstdio>
#include <jpeglib.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <setjmp.h>

#include "color/UltraHdrParams.h"
#include "encoding/jpeg/UltraHdrMapFit.h"
#include "gainmap/UltrahdrMux.h"

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

struct LockedBitmap {
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    bool ok = false;
};

LockedBitmap lockRgba(JNIEnv* env, jobject bitmap) {
    LockedBitmap out;
    if (AndroidBitmap_getInfo(env, bitmap, &out.info) != ANDROID_BITMAP_RESULT_SUCCESS) return out;
    if (out.info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return out;
    if (AndroidBitmap_lockPixels(env, bitmap, &out.pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !out.pixels)
        return out;
    out.ok = true;
    return out;
}

// TurboJPEG memory encode of one RGBA_8888 image. Throws via longjmp on error.
bool encodeRgba(const unsigned char* pixels, int width, int height, size_t stride, int quality,
                bool force444, const unsigned char* icc, size_t iccSize,
                std::vector<unsigned char>& out, std::string& error) {
    jpeg_compress_struct cinfo{};
    ErrorManager jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = onError;
    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        error = jerr.message;
        return false;
    }
    jpeg_create_compress(&cinfo);
    unsigned char* mem = nullptr;
    unsigned long memSize = 0;
    jpeg_mem_dest(&cinfo, &mem, &memSize);
    cinfo.image_width = static_cast<JDIMENSION>(width);
    cinfo.image_height = static_cast<JDIMENSION>(height);
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, std::max(1, std::min(100, quality)), TRUE);
    cinfo.optimize_coding = FALSE;
    if (quality >= 98) cinfo.dct_method = JDCT_ISLOW;
    if (force444) {
        cinfo.comp_info[0].h_samp_factor = 1; cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1; cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1; cinfo.comp_info[2].v_samp_factor = 1;
    } else {
        cinfo.comp_info[0].h_samp_factor = 2; cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1; cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1; cinfo.comp_info[2].v_samp_factor = 1;
    }
    jpeg_start_compress(&cinfo, TRUE);
    writeIcc(&cinfo, icc, iccSize);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = const_cast<JSAMPROW>(
            pixels + static_cast<size_t>(cinfo.next_scanline) * stride);
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    out.assign(mem, mem + memSize);
    free(mem);
    return true;
}

jstring errorString(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_NativeUltraHdrWriter_nativeEncodeUltraHdr(
    JNIEnv* env, jobject, jobject baseBitmap, jobject mapBitmap, jint fd,
    jint baseQuality, jint baseSubsampling, jbyteArray iccArray,
    jint mapQuality, jfloat minLog2, jfloat maxLog2, jfloat gamma,
    jfloat offsetSdr, jfloat offsetHdr, jfloat capacityMin, jfloat capacityMax,
    jboolean multiChannel, jfloat fitMargin, jfloat fitStep, jfloat fitFloor,
    jfloat displayCap, jfloat shoulderWidth, jfloat shoulderKneeBelowMax,
    jfloat baseShare, jfloat baseExponent, jfloat chromaKnee) {
    rawlens::color::UltraHdrParams uh;
    uh.enabled = true;
    uh.gainmapQuality = std::max(1, std::min(100, static_cast<int>(mapQuality)));
    uh.gainMapMinLog2 = minLog2;
    uh.gainMapMaxLog2 = maxLog2;
    uh.gamma = gamma;
    uh.offsetSdr = offsetSdr;
    uh.offsetHdr = offsetHdr;
    uh.hdrCapacityMinLog2 = capacityMin;
    uh.hdrCapacityMaxLog2 = capacityMax;
    uh.multiChannelMap = multiChannel == JNI_TRUE;
    uh.gainMapFitMargin = fitMargin;
    uh.gainMapFitStep = fitStep;
    uh.gainMapFitFloor = fitFloor;
    uh.gainMapDisplayCap = displayCap;
    uh.shoulderWidth = shoulderWidth;
    uh.shoulderKneeBelowMax = shoulderKneeBelowMax;
    uh.baseShare = baseShare;
    uh.baseExponent = baseExponent;
    uh.chromaKnee = chromaKnee;
    const char* reason = nullptr;
    if (!uh.validForEncode(&reason))
        return errorString(env, reason ? reason : "invalid Ultra HDR params");

    LockedBitmap base = lockRgba(env, baseBitmap);
    if (!base.ok) return errorString(env, "Ultra HDR requires RGBA_8888 base bitmap");
    LockedBitmap map = lockRgba(env, mapBitmap);
    if (!map.ok) {
        AndroidBitmap_unlockPixels(env, baseBitmap);
        return errorString(env, "Ultra HDR requires RGBA_8888 gainmap bitmap");
    }

    std::vector<unsigned char> iccProfile;
    if (iccArray) {
        const jsize iccSize = env->GetArrayLength(iccArray);
        if (iccSize > 0) {
            iccProfile.resize(static_cast<size_t>(iccSize));
            env->GetByteArrayRegion(iccArray, 0, iccSize, reinterpret_cast<jbyte*>(iccProfile.data()));
            if (env->ExceptionCheck()) {
                AndroidBitmap_unlockPixels(env, baseBitmap);
                AndroidBitmap_unlockPixels(env, mapBitmap);
                return errorString(env, "Could not read Ultra HDR ICC profile");
            }
        }
    }

    // Copy pixels out from under the Bitmap locks: the fit runs on worker
    // threads and the encodes need stable rows.
    const size_t baseRowBytes = static_cast<size_t>(base.info.stride);
    const size_t mapRowBytes = static_cast<size_t>(map.info.stride);
    std::vector<unsigned char> baseRgba(
        static_cast<size_t>(base.info.width) * base.info.height * 4);
    std::vector<unsigned char> mapRgba(
        static_cast<size_t>(map.info.width) * map.info.height * 4);
    for (uint32_t y = 0; y < base.info.height; ++y) {
        std::memcpy(baseRgba.data() + static_cast<size_t>(y) * base.info.width * 4,
                    static_cast<unsigned char*>(base.pixels) + static_cast<size_t>(y) * baseRowBytes,
                    static_cast<size_t>(base.info.width) * 4);
    }
    for (uint32_t y = 0; y < map.info.height; ++y) {
        std::memcpy(mapRgba.data() + static_cast<size_t>(y) * map.info.width * 4,
                    static_cast<unsigned char*>(map.pixels) + static_cast<size_t>(y) * mapRowBytes,
                    static_cast<size_t>(map.info.width) * 4);
    }
    AndroidBitmap_unlockPixels(env, baseBitmap);
    AndroidBitmap_unlockPixels(env, mapBitmap);

    // Adaptive ceiling + shoulder + chroma knee against the SDR base. On fit
    // failure the wide map + wide metadata still form a valid file.
    float fittedMax = uh.gainMapMaxLog2;
    std::vector<unsigned char> fittedMap;
    float fitOut = fittedMax;
    if (rawlens::encoding::jpeg::fitAndRemapMap(
            mapRgba.data(), map.info.width, map.info.height, uh, fittedMap, fitOut,
            baseRgba.data(), base.info.width, base.info.height)) {
        fittedMax = fitOut;
    }
    const unsigned char* mapForEncode = fittedMap.empty() ? mapRgba.data() : fittedMap.data();

    rawlens::gainmap::UltrahdrMuxParams mux = uh.toMuxParams();
    mux.gainMapMaxLog2 = fittedMax;

    std::string error;
    std::vector<unsigned char> baseJpeg, mapJpeg;
    if (!encodeRgba(baseRgba.data(), static_cast<int>(base.info.width),
                    static_cast<int>(base.info.height),
                    static_cast<size_t>(base.info.width) * 4, baseQuality,
                    baseSubsampling == 444, iccProfile.data(), iccProfile.size(), baseJpeg, error))
        return errorString(env, "Ultra HDR base encode failed: " + error);
    if (!encodeRgba(mapForEncode, static_cast<int>(map.info.width),
                    static_cast<int>(map.info.height),
                    static_cast<size_t>(map.info.width) * 4, uh.gainmapQuality,
                    true, nullptr, 0, mapJpeg, error))
        return errorString(env, "Ultra HDR gainmap encode failed: " + error);

    std::vector<uint8_t> file;
    rawlens::gainmap::UltrahdrMuxResult muxed = rawlens::gainmap::UltrahdrMux::assemble(
        baseJpeg.data(), baseJpeg.size(), mapJpeg.data(), mapJpeg.size(),
        nullptr, 0, mux, file);
    if (!muxed.ok) return errorString(env, "Ultra HDR mux failed: " + muxed.error);

    const int outFd = dup(fd);
    if (outFd < 0) return errorString(env, "Could not duplicate Ultra HDR file descriptor");
    size_t written = 0;
    bool writeOk = true;
    while (written < file.size()) {
        ssize_t n = write(outFd, file.data() + written, file.size() - written);
        if (n <= 0) { writeOk = false; break; }
        written += static_cast<size_t>(n);
    }
    if (writeOk) writeOk = fsync(outFd) == 0;
    close(outFd);
    return writeOk ? nullptr : errorString(env, "Ultra HDR file write failed");
}
