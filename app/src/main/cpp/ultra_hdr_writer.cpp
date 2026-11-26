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
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "color/UltraHdrParams.h"
#include "encoding/jpeg/ParallelJpeg.h"
#include "encoding/jpeg/UltraHdrMapFit.h"
#include "gainmap/UltrahdrMux.h"

namespace {
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

// Memory encode of one RGBA_8888 image.
bool encodeRgba(const unsigned char* pixels, int width, int height, size_t stride, int quality,
                bool force444, const unsigned char* icc, size_t iccSize,
                std::vector<unsigned char>& out, std::string& error) {
    rawlens::encoding::jpeg::ParallelJpegInput in;
    in.rgba = pixels;
    in.width = width;
    in.height = height;
    in.stride = stride;
    in.quality = quality;
    in.yuv444 = force444;
    in.icc = icc;
    in.iccSize = iccSize;
    rawlens::encoding::jpeg::ParallelJpegOutput jpeg;
    if (!rawlens::encoding::jpeg::encodeParallel(in, jpeg, error)) return false;
    out.reserve(jpeg.size());
    for (const auto& p : jpeg.pieces) out.insert(out.end(), p.data, p.data + p.size);
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

    // Both bitmaps stay locked until their last read: the fit and the encodes only read, and a 12 MP
    // base copy is 48 MB. The fit needs tight rows, so only an (unusual) padded bitmap is copied.
    struct Unlock {
        JNIEnv* env; jobject base, map;
        ~Unlock() { AndroidBitmap_unlockPixels(env, base); AndroidBitmap_unlockPixels(env, map); }
    } unlock{env, baseBitmap, mapBitmap};
    auto tight = [](const LockedBitmap& b, std::vector<unsigned char>& copy) -> const unsigned char* {
        const size_t row = static_cast<size_t>(b.info.width) * 4;
        const auto* src = static_cast<const unsigned char*>(b.pixels);
        if (b.info.stride == row) return src;
        copy.resize(row * b.info.height);
        for (uint32_t y = 0; y < b.info.height; ++y)
            std::memcpy(copy.data() + y * row, src + static_cast<size_t>(y) * b.info.stride, row);
        return copy.data();
    };
    std::vector<unsigned char> baseCopy, mapCopy;
    const unsigned char* baseRgba = tight(base, baseCopy);
    const unsigned char* mapRgba = tight(map, mapCopy);

    // Adaptive ceiling + shoulder + chroma knee against the SDR base. On fit
    // failure the wide map + wide metadata still form a valid file.
    float fittedMax = uh.gainMapMaxLog2;
    std::vector<unsigned char> fittedMap;
    float fitOut = fittedMax;
    if (rawlens::encoding::jpeg::fitAndRemapMap(
            mapRgba, map.info.width, map.info.height, uh, fittedMap, fitOut,
            baseRgba, base.info.width, base.info.height)) {
        fittedMax = fitOut;
    }
    const unsigned char* mapForEncode = fittedMap.empty() ? mapRgba : fittedMap.data();

    rawlens::gainmap::UltrahdrMuxParams mux = uh.toMuxParams();
    mux.gainMapMaxLog2 = fittedMax;
    // Full map at display headroom == fitted ceiling. Leaving HDRCapacityMax at
    // the wide 4.7 EV scaled the map by log2(headroom)/4.7 (weight ~0.3 on a
    // ~1.5 EV phone panel), so a fitted 1 EV map rendered as ~0.3 EV: weak HDR
    // vs stock/RAWR, which write capacity <= their map max.
    mux.hdrCapacityMaxLog2 = std::min(mux.hdrCapacityMaxLog2, std::max(fittedMax, mux.hdrCapacityMinLog2 + 0.01f));

    std::string error;
    std::vector<unsigned char> baseJpeg, mapJpeg;
    if (!encodeRgba(baseRgba, static_cast<int>(base.info.width),
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
