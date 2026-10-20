// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// JPEG XL encoder JNI, mirroring darktable src/imageio/format/jxl.c:
// bit depth 8/10/12/16/32, 16-bit uint vs half float, quality (100 =
// lossless on integer paths), effort 1..9, decoding-speed tier 0..4,
// native sRGB/Display-P3 color encoding with ICC fallback, BMFF container.
//
// Built only when CMake RAWLENS_ENABLE_JXL is ON (libjxl wired there).
// Without libjxl this translation unit still compiles and every call
// returns a plain error string, so Kotlin reports "not bundled" instead
// of crashing on System.loadLibrary.

#include <jni.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

jstring error(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }

#ifdef RAWLENS_HAVE_LIBJXL
#include <jxl/encode.h>
#include <jxl/resizable_parallel_runner.h>

#define JXL_CHECK(env, encoder, call)                          \
    do {                                                       \
        if ((call) != JXL_ENC_SUCCESS) {                       \
            JxlEncoderError err = JxlEncoderGetError(encoder); \
            return error(env, "JXL encode failed (libjxl error " + std::to_string((int)err) + ")"); \
        }                                                      \
    } while (0)

// Matches JxlDataType in jxl/types.h (also mirrored in JxlPixelPack).
JxlDataType toDataType(jint dataType) {
    switch (dataType) {
        case 0: return JXL_TYPE_FLOAT;
        case 1: return JXL_TYPE_FLOAT16;
        default: return JXL_TYPE_UINT16;
    }
}

namespace {
// Reads a possibly-absent byte array argument (null means empty).
std::vector<uint8_t> readByteArray(JNIEnv* env, jbyteArray array, const char* what, bool* failed,
                                   std::string* message) {
    std::vector<uint8_t> out;
    if (!array) return out;
    const jsize size = env->GetArrayLength(array);
    if (size < 0) {
        *failed = true;
        *message = std::string("Could not size ") + what;
        return out;
    }
    if (size > 0) {
        out.resize(static_cast<size_t>(size));
        env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(out.data()));
        if (env->ExceptionCheck()) {
            *failed = true;
            *message = std::string("Could not read ") + what;
            out.clear();
        }
    }
    return out;
}
}  // namespace

jstring encodeWithLibjxl(JNIEnv* env, jobject rgb, jint width, jint height, jint bitsPerSample,
                         jint exponentBits, jint dataType, jint quality, jint effort,
                         jint decodingSpeed, jint displayP3, jbyteArray iccArray,
                         jbyteArray exifArray, jbyteArray xmpArray, jint fd, jint orientation,
                         jint transferFunction, jfloat intensityTarget) {
    if (width <= 0 || height <= 0) return error(env, "Invalid JXL frame size");
    if (bitsPerSample != 8 && bitsPerSample != 10 && bitsPerSample != 12 && bitsPerSample != 16 &&
        bitsPerSample != 32)
        return error(env, "Unsupported JXL bit depth");
    if (effort < 1 || effort > 9) return error(env, "JXL effort out of range 1..9");
    if (decodingSpeed < 0 || decodingSpeed > 4)
        return error(env, "JXL decoding speed out of range 0..4");
    if (transferFunction != 0 && transferFunction != 1)
        return error(env, "Unsupported JXL transfer function");

    const bool isFloat = exponentBits > 0;
    const size_t sampleBytes = (dataType == 0) ? 4 : 2;
    const size_t expected = (size_t)width * (size_t)height * 3 * sampleBytes;
    uint8_t* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgb));
    jlong capacity = env->GetDirectBufferCapacity(rgb);
    if (!pixels || capacity < (jlong)expected)
        return error(env, "JXL encoder input buffer too small");

    bool arrayFailed = false;
    std::string arrayMessage;
    const std::vector<uint8_t> icc =
        readByteArray(env, iccArray, "JXL ICC profile", &arrayFailed, &arrayMessage);
    const std::vector<uint8_t> exif =
        readByteArray(env, exifArray, "JXL EXIF blob", &arrayFailed, &arrayMessage);
    const std::vector<uint8_t> xmp =
        readByteArray(env, xmpArray, "JXL XMP packet", &arrayFailed, &arrayMessage);
    if (arrayFailed) return error(env, arrayMessage);

    const int outFd = dup(fd);
    if (outFd < 0) return error(env, "Could not duplicate JXL file descriptor");
    FILE* output = fdopen(outFd, "wb");
    if (!output) {
        close(outFd);
        return error(env, "Could not open JXL output stream");
    }
    // MediaStore descriptors can be FUSE-backed: buffer generously so the
    // codestream lands in few write() calls (same discipline as the JPEG path).
    setvbuf(output, nullptr, _IOFBF, 256 * 1024);

    JxlEncoder* encoder = JxlEncoderCreate(nullptr);
    if (!encoder) {
        fclose(output);
        return error(env, "Could not create JXL encoder");
    }
    void* runner = JxlResizableParallelRunnerCreate(nullptr);
    if (!runner) {
        JxlEncoderDestroy(encoder);
        fclose(output);
        return error(env, "Could not create JXL parallel runner");
    }
    JxlResizableParallelRunnerSetThreads(
        runner, JxlResizableParallelRunnerSuggestThreads((uint32_t)width, (uint32_t)height));
    if (JxlEncoderSetParallelRunner(encoder, JxlResizableParallelRunner, runner) != JXL_ENC_SUCCESS) {
        JxlResizableParallelRunnerDestroy(runner);
        JxlEncoderDestroy(encoder);
        fclose(output);
        return error(env, "Could not attach JXL parallel runner");
    }

    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(encoder, nullptr);
    if (!frame) {
        JxlResizableParallelRunnerDestroy(runner);
        JxlEncoderDestroy(encoder);
        fclose(output);
        return error(env, "Could not create JXL frame settings");
    }

    // Basic info: darktable write_image mapping (src/imageio/format/jxl.c),
    // including its 16-bit-float lossless exception: float16 at quality 100
    // keeps distance 0 without SetFrameLossless ("Do not enable libjxl
    // lossless mode for float16, see #17487", a darktable issue:
    // https://github.com/darktable-org/darktable/issues/17487).
    JxlBasicInfo basic;
    JxlEncoderInitBasicInfo(&basic);
    basic.xsize = (uint32_t)width;
    basic.ysize = (uint32_t)height;
    basic.num_color_channels = 3;
    // Container orientation must agree with the Exif-box tag: viewers that
    // honor only the container signal otherwise show the frame unrotated.
    // libjxl rejects values outside 1..8; Kotlin validates first.
    basic.orientation = static_cast<JxlOrientation>(orientation);
    // Mastering peak for PQ stills (SDR passes 0 and keeps libjxl's default).
    if (transferFunction == 1) basic.intensity_target = intensityTarget;
    basic.bits_per_sample = (uint32_t)bitsPerSample;
    basic.exponent_bits_per_sample = (uint32_t)(isFloat ? exponentBits : 0);
    const bool lossless = (quality >= 100);
    if (lossless) {
        basic.uses_original_profile = JXL_TRUE;
        JXL_CHECK(env, encoder, JxlEncoderSetFrameDistance(frame, 0.0f));
        const bool isFloat16 = (bitsPerSample == 16 && isFloat);
        if (!isFloat16) JXL_CHECK(env, encoder, JxlEncoderSetFrameLossless(frame, JXL_TRUE));
    } else {
        basic.uses_original_profile = JXL_FALSE;
        JXL_CHECK(env, encoder,
                  JxlEncoderSetFrameDistance(frame, JxlEncoderDistanceFromQuality((float)quality)));
    }
    JXL_CHECK(env, encoder,
              JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, effort));
    JXL_CHECK(env, encoder,
              JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED,
                                               decodingSpeed));
    JXL_CHECK(env, encoder, JxlEncoderSetBasicInfo(encoder, &basic));

    // Color: native sRGB / Display-P3 encoding like darktable's known-profile
    // fast path; ICC blob fallback otherwise. PQ keeps the primaries choice
    // and swaps only the transfer function to ST2084.
    if (icc.empty()) {
        JxlColorEncoding color;
        // Zero first, as the Adobe DNG SDK does (dng_jxl.cpp): only the enum
        // fields are set below; the rest must not be indeterminate.
        std::memset(&color, 0, sizeof(color));
        color.color_space = JXL_COLOR_SPACE_RGB;
        color.white_point = JXL_WHITE_POINT_D65;
        color.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
        if (displayP3) {
            color.primaries = JXL_PRIMARIES_P3;
        } else {
            color.primaries = JXL_PRIMARIES_SRGB;
        }
        color.transfer_function =
            (transferFunction == 1) ? JXL_TRANSFER_FUNCTION_PQ : JXL_TRANSFER_FUNCTION_SRGB;
        if (JxlEncoderSetColorEncoding(encoder, &color) != JXL_ENC_SUCCESS) {
            // Native encoding is a hint path; ICC-less failure here is fatal
            // only when no blob exists to fall back on.
            JxlResizableParallelRunnerDestroy(runner);
            JxlEncoderDestroy(encoder);
            fclose(output);
            return error(env, "Could not set JXL color encoding");
        }
    } else {
        JXL_CHECK(env, encoder, JxlEncoderSetICCProfile(encoder, icc.data(), icc.size()));
    }

    // BMFF container (required for the Exif/XMP boxes below, darktable parity).
    // Boxes are only meaningful with metadata; without any the container
    // still decodes everywhere a raw codestream does.
    if (!exif.empty() || !xmp.empty()) {
        JXL_CHECK(env, encoder, JxlEncoderUseBoxes(encoder));
        if (!exif.empty()) {
            // TIFF blob framed with the 4-byte zero offset the Exif box
            // spec (and darktable) requires before the TIFF header.
            std::vector<uint8_t> payload(4, 0);
            payload.insert(payload.end(), exif.begin(), exif.end());
            JXL_CHECK(env, encoder,
                      JxlEncoderAddBox(encoder, "Exif", payload.data(), payload.size(), JXL_FALSE));
        }
        if (!xmp.empty()) {
            JXL_CHECK(env, encoder,
                      JxlEncoderAddBox(encoder, "xml ", xmp.data(), xmp.size(), JXL_FALSE));
        }
    }

    JxlPixelFormat format;
    format.num_channels = 3;
    format.data_type = toDataType(dataType);
    format.endianness = JXL_NATIVE_ENDIAN;
    format.align = 0;
    JXL_CHECK(env, encoder, JxlEncoderAddImageFrame(frame, &format, pixels, expected));
    JxlEncoderCloseInput(encoder);

    size_t chunk = 1 << 16;
    size_t length = chunk;
    std::vector<uint8_t> codestream(length);
    uint8_t* cursor = codestream.data();
    size_t available = length;
    JxlEncoderStatus status = JXL_ENC_NEED_MORE_OUTPUT;
    while (status == JXL_ENC_NEED_MORE_OUTPUT) {
        status = JxlEncoderProcessOutput(encoder, &cursor, &available);
        if (status == JXL_ENC_NEED_MORE_OUTPUT) {
            const size_t offset = (size_t)(cursor - codestream.data());
            if (chunk < (1u << 20)) chunk *= 2;
            length += chunk;
            codestream.resize(length);
            cursor = codestream.data() + offset;
            available = length - offset;
        }
    }
    jstring failure = nullptr;
    if (status != JXL_ENC_SUCCESS) {
        failure = error(env, "JXL output processing failed");
    } else {
        const size_t written = (size_t)(cursor - codestream.data());
        if (fwrite(codestream.data(), 1, written, output) != written || fflush(output) != 0 ||
            ferror(output) != 0) {
            failure = error(env, "JXL write failed");
        }
    }
    JxlResizableParallelRunnerDestroy(runner);
    JxlEncoderDestroy(encoder);
    fclose(output);
    return failure;
}
#endif  // RAWLENS_HAVE_LIBJXL

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_matthew_rawlens_NativeJxlEncoder_nativeEncode(JNIEnv* env, jobject, jobject rgb, jint width,
                                                       jint height, jint bitsPerSample,
                                                       jint exponentBits, jint dataType, jint quality,
                                                       jint effort, jint decodingSpeed, jint displayP3,
                                                       jbyteArray iccProfile, jbyteArray exif,
                                                       jbyteArray xmp, jint fd, jint orientation,
                                                       jint transferFunction, jfloat intensityTarget) {
#ifdef RAWLENS_HAVE_LIBJXL
    return encodeWithLibjxl(env, rgb, width, height, bitsPerSample, exponentBits, dataType, quality,
                            effort, decodingSpeed, displayP3, iccProfile, exif, xmp, fd, orientation,
                            transferFunction, intensityTarget);
#else
    (void)env;
    (void)rgb;
    (void)width;
    (void)height;
    (void)bitsPerSample;
    (void)exponentBits;
    (void)dataType;
    (void)quality;
    (void)effort;
    (void)decodingSpeed;
    (void)displayP3;
    (void)iccProfile;
    (void)exif;
    (void)xmp;
    (void)fd;
    (void)orientation;
    (void)transferFunction;
    (void)intensityTarget;
    return error(env,
                 "JPEG XL encoder is not bundled in this build (offline or source-only build; "
                 "a networked build bundles libjxl automatically, see app/src/main/cpp/CMakeLists.txt)");
#endif
}
