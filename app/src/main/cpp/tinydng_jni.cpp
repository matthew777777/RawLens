#include <jni.h>
#include "tinydng.h"
#include <algorithm>
#include <climits>
#include <memory>
#include <stdexcept>
#include <vector>

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_NativeDngWriter_writeNative(
    JNIEnv* env, jobject, jobject raw, jint width, jint height,
    jintArray descriptors, jobjectArray payloads, jobject output) {
    try {
        auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(raw));
        const jlong capacity = env->GetDirectBufferCapacity(raw);
        const int64_t bytes = int64_t(width) * height * 2;
        if (!data || width <= 0 || height <= 0 || bytes <= 0 || capacity < bytes ||
            uint64_t(bytes) > SIZE_MAX || !descriptors || !payloads || !output)
            throw std::runtime_error("Invalid RAW buffer or TinyDNG arguments");
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
        image.data = data; image.data_size = static_cast<size_t>(bytes);
        image.fields = fields.data(); image.field_count = fields.size();
        tinydng_write_options options{};
        options.compression = 1;
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
