#include <jni.h>
#include "tinydng.h"
#include <algorithm>
#include <climits>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

#ifdef RAWLENS_HAVE_LIBJXL
#include <jxl/encode.h>
#include <jxl/resizable_parallel_runner.h>
#endif

namespace {

/* RawLens DNG JPEG XL effort: measured on host libjxl with mosaic CFA
   rasters (12/48 MP, noisy): effort 3 compresses 2.17x in 150/490 ms while
   effort 9 gains nothing measurable for 36x the time (6.2/18 s). Shutter
   paths must stay sub-second; ratio ties or beats higher efforts. */
constexpr int kDngJxlEffort = 3;

#ifdef RAWLENS_HAVE_LIBJXL
/* Lossless single-channel (CFA mosaic) JPEG XL encode. Mirrors the still
   encoder's contract (original profile, distance 0, SetFrameLossless) at
   the DNG effort above; grayscale linear color per CFA convention. */
std::vector<uint8_t> encodeCfaJxl(const uint8_t* data, int width, int height) {
    JxlEncoder* encoder = JxlEncoderCreate(nullptr);
    if (!encoder) throw std::runtime_error("Could not create JXL encoder");
    void* runner = JxlResizableParallelRunnerCreate(nullptr);
    if (!runner) {
        JxlEncoderDestroy(encoder);
        throw std::runtime_error("Could not create JXL parallel runner");
    }
    JxlResizableParallelRunnerSetThreads(
        runner, JxlResizableParallelRunnerSuggestThreads((uint32_t)width, (uint32_t)height));
    if (JxlEncoderSetParallelRunner(encoder, JxlResizableParallelRunner, runner) !=
        JXL_ENC_SUCCESS) {
        JxlResizableParallelRunnerDestroy(runner);
        JxlEncoderDestroy(encoder);
        throw std::runtime_error("Could not attach JXL parallel runner");
    }
    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(encoder, nullptr);
    if (!frame) {
        JxlResizableParallelRunnerDestroy(runner);
        JxlEncoderDestroy(encoder);
        throw std::runtime_error("Could not create JXL frame settings");
    }
    auto fail = [&](const std::string& what) {
        JxlResizableParallelRunnerDestroy(runner);
        JxlEncoderDestroy(encoder);
        throw std::runtime_error(what);
    };
    JxlBasicInfo basic;
    JxlEncoderInitBasicInfo(&basic);
    basic.xsize = (uint32_t)width;
    basic.ysize = (uint32_t)height;
    basic.num_color_channels = 1;
    basic.bits_per_sample = 16;
    basic.uses_original_profile = JXL_TRUE;
    if (JxlEncoderSetBasicInfo(encoder, &basic) != JXL_ENC_SUCCESS)
        fail("Could not set JXL basic info");
    JxlColorEncoding color;
    color.color_space = JXL_COLOR_SPACE_GRAY;
    color.white_point = JXL_WHITE_POINT_D65;
    color.primaries = JXL_PRIMARIES_SRGB;
    color.transfer_function = JXL_TRANSFER_FUNCTION_LINEAR;
    color.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
    if (JxlEncoderSetColorEncoding(encoder, &color) != JXL_ENC_SUCCESS)
        fail("Could not set JXL color encoding");
    if (JxlEncoderSetFrameDistance(frame, 0.0f) != JXL_ENC_SUCCESS)
        fail("Could not set JXL frame distance");
    if (JxlEncoderSetFrameLossless(frame, JXL_TRUE) != JXL_ENC_SUCCESS)
        fail("Could not set JXL frame lossless");
    if (JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT,
                                         kDngJxlEffort) != JXL_ENC_SUCCESS)
        fail("Could not set JXL effort");
    JxlPixelFormat format;
    format.num_channels = 1;
    format.data_type = JXL_TYPE_UINT16;
    format.endianness = JXL_NATIVE_ENDIAN;
    format.align = 0;
    const size_t stride = (size_t)width * (size_t)height * 2;
    if (JxlEncoderAddImageFrame(frame, &format, data, stride) != JXL_ENC_SUCCESS)
        fail("Could not add JXL image frame");
    JxlEncoderCloseInput(encoder);
    std::vector<uint8_t> out(1 << 20);
    uint8_t* cursor = out.data();
    size_t available = out.size();
    JxlEncoderStatus status = JXL_ENC_NEED_MORE_OUTPUT;
    while (status == JXL_ENC_NEED_MORE_OUTPUT) {
        status = JxlEncoderProcessOutput(encoder, &cursor, &available);
        if (status == JXL_ENC_NEED_MORE_OUTPUT) {
            const size_t offset = (size_t)(cursor - out.data());
            out.resize(out.size() * 2);
            cursor = out.data() + offset;
            available = out.size() - offset;
        }
    }
    if (status != JXL_ENC_SUCCESS) fail("JXL output processing failed");
    out.resize((size_t)(cursor - out.data()));
    JxlResizableParallelRunnerDestroy(runner);
    JxlEncoderDestroy(encoder);
    return out;
}
#endif  // RAWLENS_HAVE_LIBJXL

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_NativeDngWriter_writeNative(
    JNIEnv* env, jobject, jobject raw, jint width, jint height,
    jintArray descriptors, jobjectArray payloads, jobject output, jboolean jxl) {
    try {
        auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(raw));
        const jlong capacity = env->GetDirectBufferCapacity(raw);
        const int64_t bytes = int64_t(width) * height * 2;
        if (!data || width <= 0 || height <= 0 || bytes <= 0 || capacity < bytes ||
            uint64_t(bytes) > SIZE_MAX || !descriptors || !payloads || !output)
            throw std::runtime_error("Invalid RAW buffer or TinyDNG arguments");
        std::vector<uint8_t> jxlBytes;
        const uint8_t* imageData = data;
        size_t imageSize = static_cast<size_t>(bytes);
        if (jxl) {
#ifdef RAWLENS_HAVE_LIBJXL
            jxlBytes = encodeCfaJxl(data, width, height);
            imageData = jxlBytes.data();
            imageSize = jxlBytes.size();
#else
            throw std::runtime_error(
                "JXL DNG compression needs a libjxl-enabled build "
                "(see app/src/main/cpp/CMakeLists.txt)");
#endif
        }
        const jsize count = env->GetArrayLength(payloads);
        if (count > 50 || env->GetArrayLength(descriptors) != count * 3)
            throw std::runtime_error("Invalid TinyDNG metadata descriptors");
        std::vector<jint> desc(count * 3);
        env->GetIntArrayRegion(descriptors, 0, count * 3, desc.data());
        if (env->ExceptionCheck()) return;
        std::vector<std::vector<uint8_t>> storage(count);
        std::vector<tinydng_field> fields(count);
        for (jsize i = 0; i < count; ++i) {
            auto payload = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
            if (env->ExceptionCheck()) return;
            if (!payload) throw std::runtime_error("Missing TIFF field payload");
            const jsize length = env->GetArrayLength(payload);
            if (length <= 0 || length > 256 * 1024 || desc[i*3] < 0 || desc[i*3] > 65535 ||
                desc[i*3+1] < 1 || desc[i*3+1] > 12 || desc[i*3+2] <= 0) {
                env->DeleteLocalRef(payload);
                throw std::runtime_error("Invalid TIFF field payload");
            }
            storage[i].resize(length);
            env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte*>(storage[i].data()));
            env->DeleteLocalRef(payload);
            if (env->ExceptionCheck()) return;
            fields[i] = {static_cast<uint16_t>(desc[i*3]), static_cast<uint16_t>(desc[i*3+1]),
                static_cast<uint32_t>(desc[i*3+2]), storage[i].data(), storage[i].size()};
        }
        tinydng_error error{};
        std::unique_ptr<tinydng_context, decltype(&tinydng_context_destroy)> context(
            tinydng_context_create(nullptr, &error), tinydng_context_destroy);
        if (!context) throw std::runtime_error(error.message);
        tinydng_write_image image{};
        image.width = width; image.height = height;
        image.samples_per_pixel = 1; image.bits_per_sample = 16;
        image.photometric = 32803;
        image.data = imageData; image.data_size = imageSize;
        image.fields = fields.data(); image.field_count = fields.size();
        tinydng_write_options options{};
        options.compression = jxl ? TINYDNG_COMPRESSION_JPEG_XL : 1;
        options.precompressed = jxl ? 1 : 0;
        uint8_t* encoded = nullptr;
        size_t encodedSize = 0;
        if (tinydng_write_memory(context.get(), &image, &options, &encoded, &encodedSize, &error) != TINYDNG_OK)
            throw std::runtime_error(error.message);
        auto release = [&](uint8_t* p) { tinydng_buffer_free(context.get(), p); };
        std::unique_ptr<uint8_t, decltype(release)> encodedOwner(encoded, release);
        jclass cls = env->GetObjectClass(output);
        if (!cls) return;
        jmethodID write = env->GetMethodID(cls, "write", "([BII)V");
        env->DeleteLocalRef(cls);
        if (!write) return;
        jbyteArray chunk = env->NewByteArray(64 * 1024);
        if (!chunk) return;
        for (size_t offset = 0; offset < encodedSize && !env->ExceptionCheck();) {
            jsize n = static_cast<jsize>(std::min<size_t>(64 * 1024, encodedSize - offset));
            env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte*>(encoded + offset));
            if (env->ExceptionCheck()) break;
            env->CallVoidMethod(output, write, chunk, 0, n);
            offset += n;
        }
        env->DeleteLocalRef(chunk);
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) {
            jclass cls = env->FindClass("java/io/IOException");
            if (cls) env->ThrowNew(cls, error.what());
        }
    }
}

/* ------------------------------------------------------------------ */
/* TinyDngImageWriter: header-first streaming for the merged-output     */
/* writers (linear RGB, mosaic CFA, float CFA). One open, N appends and */
/* one close per file; strips arrive as quantized little-endian bytes.  */
/* Single-threaded per handle: the JNIEnv is captured at open.         */
/* ------------------------------------------------------------------ */

namespace {

struct ImageHandle {
    tinydng_context* ctx = nullptr;
    tinydng_writer* writer = nullptr;
    jobject output = nullptr; /* global ref */
    jmethodID writeMethod = nullptr;
    JNIEnv* env = nullptr;
    uint64_t pos = 0;
    uint32_t nextStrip = 0;
    bool finished = false;
};

void throwIo(JNIEnv* env, const char* what) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, what);
}

size_t imageSinkWrite(tinydng_write_io* io, uint64_t off, const void* data, size_t len) {
    auto* h = static_cast<ImageHandle*>(io->backend);
    if (off != h->pos) return 0; /* header-first must stay sequential */
    JNIEnv* env = h->env;
    /* Chunked handoff: one modest Java array per sink write. */
    static constexpr size_t kChunk = 256 * 1024;
    size_t done = 0;
    jbyteArray chunk = env->NewByteArray(kChunk);
    if (!chunk) return 0;
    while (done < len && !env->ExceptionCheck()) {
        auto n = static_cast<jsize>(std::min<size_t>(kChunk, len - done));
        env->SetByteArrayRegion(chunk, 0, n,
            reinterpret_cast<const jbyte*>(static_cast<const uint8_t*>(data) + done));
        if (env->ExceptionCheck()) break;
        env->CallVoidMethod(h->output, h->writeMethod, chunk, 0, n);
        if (env->ExceptionCheck()) break;
        done += static_cast<size_t>(n);
    }
    env->DeleteLocalRef(chunk);
    if (done != len) return done;
    h->pos += len;
    return len;
}

uint64_t imageSinkSize(tinydng_write_io* io) {
    return static_cast<ImageHandle*>(io->backend)->pos;
}

void destroyHandle(ImageHandle* h) {
    if (!h) return;
    if (h->writer) {
        tinydng_error err{};
        /* Finish releases writer state even when strips are missing; the
           error (if any) is reported through the close path instead. */
        tinydng_writer_finish(h->writer, &err);
        h->writer = nullptr;
    }
    if (h->ctx) {
        if (h->env && h->output) h->env->DeleteGlobalRef(h->output);
        tinydng_context_destroy(h->ctx);
    }
    delete h;
}

/* Copies one (descriptors, payloads) field list into tinydng_fields.
   descriptors packs (tag, type, count) triples; storage owns the bytes. */
void readFields(JNIEnv* env, jintArray descriptors, jobjectArray payloads,
                std::vector<std::vector<uint8_t>>& storage,
                std::vector<tinydng_field>& fields) {
    if (!descriptors && !payloads) return;
    if (!descriptors || !payloads) throw std::runtime_error("Field list halves must both be null or both set");
    const jsize count = env->GetArrayLength(payloads);
    if (count < 0 || count > 256 || env->GetArrayLength(descriptors) != count * 3)
        throw std::runtime_error("Invalid TIFF field descriptors");
    std::vector<jint> desc(static_cast<size_t>(count) * 3);
    if (count > 0) env->GetIntArrayRegion(descriptors, 0, count * 3, desc.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Field descriptor read failed");
    storage.resize(static_cast<size_t>(count));
    fields.resize(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto payload = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
        if (env->ExceptionCheck() || !payload) {
            if (payload) env->DeleteLocalRef(payload);
            throw std::runtime_error("Missing TIFF field payload");
        }
        const jsize length = env->GetArrayLength(payload);
        if (length <= 0 || length > 256 * 1024 || desc[i * 3] < 0 || desc[i * 3] > 65535 ||
            desc[i * 3 + 1] < 1 || desc[i * 3 + 1] > 12 || desc[i * 3 + 2] <= 0) {
            env->DeleteLocalRef(payload);
            throw std::runtime_error("Invalid TIFF field payload");
        }
        storage[static_cast<size_t>(i)].resize(static_cast<size_t>(length));
        env->GetByteArrayRegion(payload, 0, length,
            reinterpret_cast<jbyte*>(storage[static_cast<size_t>(i)].data()));
        env->DeleteLocalRef(payload);
        if (env->ExceptionCheck()) throw std::runtime_error("Field payload read failed");
        fields[static_cast<size_t>(i)] = {
            static_cast<uint16_t>(desc[i * 3]), static_cast<uint16_t>(desc[i * 3 + 1]),
            static_cast<uint32_t>(desc[i * 3 + 2]),
            storage[static_cast<size_t>(i)].data(), storage[static_cast<size_t>(i)].size()};
    }
}

} /* namespace */

extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_TinyDngImageWriter_nativeOpen(
    JNIEnv* env, jobject, jint width, jint height, jint spp, jint bps,
    jint sampleFormat, jint photometric, jint rowsPerStrip,
    jintArray descriptors, jobjectArray payloads,
    jintArray exifDescriptors, jobjectArray exifPayloads,
    jintArray gpsDescriptors, jobjectArray gpsPayloads, jobject output) {
    std::unique_ptr<ImageHandle> handle(new (std::nothrow) ImageHandle());
    try {
        if (!handle) throw std::runtime_error("Out of memory");
        if (width <= 0 || height <= 0 || spp <= 0 || spp > 16 ||
            (bps != 8 && bps != 16 && bps != 32) || rowsPerStrip <= 0 || !output)
            throw std::runtime_error("Invalid image geometry for TinyDNG writer");
        std::vector<std::vector<uint8_t>> storage, exifStorage, gpsStorage;
        std::vector<tinydng_field> fields, exifFields, gpsFields;
        readFields(env, descriptors, payloads, storage, fields);
        readFields(env, exifDescriptors, exifPayloads, exifStorage, exifFields);
        readFields(env, gpsDescriptors, gpsPayloads, gpsStorage, gpsFields);
        jclass cls = env->GetObjectClass(output);
        if (!cls) throw std::runtime_error("Output stream has no class");
        jmethodID write = env->GetMethodID(cls, "write", "([BII)V");
        env->DeleteLocalRef(cls);
        if (!write) throw std::runtime_error("Output stream lacks write([BII)V");
        handle->output = env->NewGlobalRef(output);
        if (!handle->output) throw std::runtime_error("Output global ref failed");
        handle->writeMethod = write;
        handle->env = env;
        tinydng_error error{};
        handle->ctx = tinydng_context_create(nullptr, &error);
        if (!handle->ctx) throw std::runtime_error(error.message);
        tinydng_write_image image{};
        image.width = static_cast<uint32_t>(width);
        image.height = static_cast<uint32_t>(height);
        image.samples_per_pixel = static_cast<uint16_t>(spp);
        image.bits_per_sample = static_cast<uint16_t>(bps);
        image.sample_format = static_cast<uint16_t>(sampleFormat);
        image.photometric = static_cast<uint16_t>(photometric);
        image.fields = fields.empty() ? nullptr : fields.data();
        image.field_count = fields.size();
        image.exif_fields = exifFields.empty() ? nullptr : exifFields.data();
        image.exif_field_count = exifFields.size();
        image.gps_fields = gpsFields.empty() ? nullptr : gpsFields.data();
        image.gps_field_count = gpsFields.size();
        tinydng_write_options options{};
        options.as_dng = 1;
        options.compression = 1;
        options.header_first = 1;
        tinydng_tiling tiling{};
        tiling.rows_per_strip = static_cast<uint32_t>(rowsPerStrip);
        tinydng_write_io sink{};
        sink.write = imageSinkWrite;
        sink.size = imageSinkSize;
        sink.close = nullptr;
        sink.backend = handle.get();
        if (tinydng_writer_create(handle->ctx, sink, &image, &options, &tiling,
                                  &handle->writer, &error) != TINYDNG_OK)
            throw std::runtime_error(error.message);
        return reinterpret_cast<jlong>(handle.release());
    } catch (const std::exception& error) {
        destroyHandle(handle.release());
        throwIo(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_TinyDngImageWriter_nativeAppend(
    JNIEnv* env, jobject, jlong handleId, jint stripIndex, jbyteArray strip) {
    auto* h = reinterpret_cast<ImageHandle*>(handleId);
    try {
        if (!h || h->finished) throw std::runtime_error("Writer handle is closed");
        if (!strip) throw std::runtime_error("Null strip bytes");
        const jsize length = env->GetArrayLength(strip);
        if (length <= 0) throw std::runtime_error("Empty strip");
        std::vector<uint8_t> bytes(static_cast<size_t>(length));
        env->GetByteArrayRegion(strip, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return;
        tinydng_error error{};
        if (tinydng_writer_write_strip(h->writer, static_cast<uint32_t>(stripIndex),
                                       bytes.data(), &error) != TINYDNG_OK)
            throw std::runtime_error(error.message);
        h->nextStrip++;
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_TinyDngImageWriter_nativeClose(
    JNIEnv* env, jobject, jlong handleId) {
    auto* h = reinterpret_cast<ImageHandle*>(handleId);
    if (!h) return;
    tinydng_error error{};
    tinydng_status st = TINYDNG_OK;
    if (h->writer && !h->finished) {
        h->finished = true;
        st = tinydng_writer_finish(h->writer, &error);
        h->writer = nullptr;
    }
    if (h->ctx) {
        if (h->output) env->DeleteGlobalRef(h->output);
        h->output = nullptr;
        tinydng_context_destroy(h->ctx);
        h->ctx = nullptr;
    }
    delete h;
    if (st != TINYDNG_OK) throwIo(env, error.message);
}

/* ------------------------------------------------------------------ */
/* One-shot lossless-JXL write for merged 16-bit CFA mosaics (HDR+).    */
/* Same copy-through contract as the single-frame path (precompressed   */
/* 52546, DNG 1.7 tags supplied by the caller), plus EXIF/GPS sub-IFDs  */
/* so merged JXL files keep location (single-frame JXL cannot).        */
/* ------------------------------------------------------------------ */

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_TinyDngImageWriter_nativeWriteCompressed(
    JNIEnv* env, jobject, jbyteArray mosaic, jint width, jint height,
    jintArray descriptors, jobjectArray payloads,
    jintArray exifDescriptors, jobjectArray exifPayloads,
    jintArray gpsDescriptors, jobjectArray gpsPayloads, jobject output) {
    try {
        if (!mosaic || !output)
            throw std::runtime_error("Null mosaic or output for compressed DNG write");
        const jsize mosaicLen = env->GetArrayLength(mosaic);
        const int64_t expect = int64_t(width) * height * 2;
        if (width <= 0 || height <= 0 || expect <= 0 || int64_t(mosaicLen) != expect)
            throw std::runtime_error("Mosaic size does not match DNG geometry");
        std::vector<uint8_t> raw(static_cast<size_t>(mosaicLen));
        env->GetByteArrayRegion(mosaic, 0, mosaicLen,
                                reinterpret_cast<jbyte*>(raw.data()));
        if (env->ExceptionCheck()) return;
        std::vector<uint8_t> jxlBytes;
#ifdef RAWLENS_HAVE_LIBJXL
        jxlBytes = encodeCfaJxl(raw.data(), width, height);
#else
        throw std::runtime_error(
            "JXL DNG compression needs a libjxl-enabled build "
            "(see app/src/main/cpp/CMakeLists.txt)");
#endif
        std::vector<std::vector<uint8_t>> storage, exifStorage, gpsStorage;
        std::vector<tinydng_field> fields, exifFields, gpsFields;
        readFields(env, descriptors, payloads, storage, fields);
        readFields(env, exifDescriptors, exifPayloads, exifStorage, exifFields);
        readFields(env, gpsDescriptors, gpsPayloads, gpsStorage, gpsFields);
        tinydng_error error{};
        std::unique_ptr<tinydng_context, decltype(&tinydng_context_destroy)> context(
            tinydng_context_create(nullptr, &error), tinydng_context_destroy);
        if (!context) throw std::runtime_error(error.message);
        tinydng_write_image image{};
        image.width = static_cast<uint32_t>(width);
        image.height = static_cast<uint32_t>(height);
        image.samples_per_pixel = 1;
        image.bits_per_sample = 16;
        image.photometric = 32803;
        image.data = jxlBytes.data();
        image.data_size = jxlBytes.size();
        image.fields = fields.empty() ? nullptr : fields.data();
        image.field_count = fields.size();
        image.exif_fields = exifFields.empty() ? nullptr : exifFields.data();
        image.exif_field_count = exifFields.size();
        image.gps_fields = gpsFields.empty() ? nullptr : gpsFields.data();
        image.gps_field_count = gpsFields.size();
        tinydng_write_options options{};
        options.compression = TINYDNG_COMPRESSION_JPEG_XL;
        options.precompressed = 1;
        uint8_t* encoded = nullptr;
        size_t encodedSize = 0;
        if (tinydng_write_memory(context.get(), &image, &options, &encoded,
                                 &encodedSize, &error) != TINYDNG_OK)
            throw std::runtime_error(error.message);
        auto release = [&](uint8_t* p) { tinydng_buffer_free(context.get(), p); };
        std::unique_ptr<uint8_t, decltype(release)> encodedOwner(encoded, release);
        jclass cls = env->GetObjectClass(output);
        if (!cls) return;
        jmethodID write = env->GetMethodID(cls, "write", "([BII)V");
        env->DeleteLocalRef(cls);
        if (!write) return;
        jbyteArray chunk = env->NewByteArray(64 * 1024);
        if (!chunk) return;
        for (size_t offset = 0; offset < encodedSize && !env->ExceptionCheck();) {
            jsize n = static_cast<jsize>(std::min<size_t>(64 * 1024, encodedSize - offset));
            env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte*>(encoded + offset));
            if (env->ExceptionCheck()) break;
            env->CallVoidMethod(output, write, chunk, 0, n);
            offset += n;
        }
        env->DeleteLocalRef(chunk);
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    }
}
