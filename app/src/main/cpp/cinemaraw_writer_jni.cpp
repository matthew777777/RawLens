// SPDX-License-Identifier: GPL-3.0-or-later
// Writer bridge: RAW16 plane -> vendored MediaCinemaRAW type-7 encoder
// (GPL-3.0-only, app/src/main/cpp/cinemaraw) -> ContainerWriter.
//
// Two frame paths exist because the recorder runs N parallel encode workers
// with a single in-order committer:
// - containerEncodeFrame: encode only, into a caller pooled buffer. Called on
//   worker threads; the thread_local payload is memcpy'd out (~2-4ms on
//   12MP) since the encoder can only sink into std::vector.
// - containerWriteFrame: commit an encoded payload. Called on the committer
//   thread only, so frame timestamps hit the container in capture order
//   (the writer rejects regressions). One copy into a vector for the same
//   reason. Combined steady-state copy overhead ~4-6ms vs ~30ms encode.
// RAW16 direct only (P0 verdict: packed-RAW10 input is scalar, 4x slower).
// Audio/motion entry points below are committer-thread-only like writes.
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <MediaCinemaRAW/ContainerWriter.h>
#include <MediaCinemaRAW/Encoder.h>

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

mediacinemaraw::ContainerWriter* writerFrom(jlong h) {
    return reinterpret_cast<mediacinemaraw::ContainerWriter*>(h);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerOpen(
    JNIEnv* env, jobject /*thiz*/, jstring path, jstring metadata) {
    try {
        auto* w = new mediacinemaraw::ContainerWriter(jstr(env, path),
                                                      jstr(env, metadata));
        return reinterpret_cast<jlong>(w);
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerEncodeFrame(
    JNIEnv* env, jobject /*thiz*/, jobject src, jint srcOffset, jint size,
    jint w, jint h, jint stride, jint cropTop, jint cropHeight, jobject dst,
    jint dstOffset, jint dstCapacity) {
    const uint8_t* s =
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    uint8_t* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!s || !d || size < 0) return -2;
    try {
        thread_local std::vector<uint8_t> payload;
        mediacinemaraw::encode(s + srcOffset, static_cast<size_t>(size), w, h,
                               stride, /*raw10=*/false, cropTop, cropHeight,
                               /*bin=*/false, payload);
        if (static_cast<jint>(payload.size()) > dstCapacity) return -3;
        std::memcpy(d + dstOffset, payload.data(), payload.size());
        return static_cast<jint>(payload.size());
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerWriteFrame(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject payload,
    jint payloadOffset, jint payloadBytes, jlong timestampNs,
    jstring frameJson) {
    auto* writer = writerFrom(handle);
    const uint8_t* p =
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!writer || !p || payloadBytes <= 0) return -2;
    try {
        std::vector<uint8_t> data(p + payloadOffset,
                                  p + payloadOffset + payloadBytes);
        writer->writeFrame(data, timestampNs, jstr(env, frameJson));
        return payloadBytes;
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (const std::logic_error&) {
        return -6;  // closed / timestamp regression
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerWriteAudio(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject buf, jint offset,
    jint frames, jlong timestampNs) {
    auto* writer = writerFrom(handle);
    const uint8_t* b =
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!writer || !b || frames <= 0) return -2;
    try {
        writer->writeAudio(reinterpret_cast<const int16_t*>(b + offset),
                           static_cast<size_t>(frames), timestampNs);
        return frames * 2;
    } catch (const std::logic_error&) {
        return -6;
    } catch (...) {
        return -5;
    }
}

namespace {

int writeMotion(JNIEnv* env, jlong handle, jlongArray tsArr, jfloatArray xyzArr,
                jint n, bool gyro) {
    auto* writer = writerFrom(handle);
    if (!writer || !tsArr || !xyzArr || n <= 0) return -2;
    if (env->GetArrayLength(tsArr) < n ||
        env->GetArrayLength(xyzArr) < n * 3)
        return -4;
    jlong* ts = env->GetLongArrayElements(tsArr, nullptr);
    jfloat* xyz = env->GetFloatArrayElements(xyzArr, nullptr);
    if (!ts || !xyz) {
        if (ts) env->ReleaseLongArrayElements(tsArr, ts, JNI_ABORT);
        if (xyz) env->ReleaseFloatArrayElements(xyzArr, xyz, JNI_ABORT);
        return -2;
    }
    int code = n;
    try {
        if (gyro) {
            std::vector<mediacinemaraw::GyroSample> samples;
            samples.reserve(n);
            for (int i = 0; i < n; ++i)
                samples.push_back({ts[i], xyz[i * 3], xyz[i * 3 + 1],
                                   xyz[i * 3 + 2]});
            writer->writeGyro(samples.data(), samples.size());
        } else {
            std::vector<mediacinemaraw::AccelerometerSample> samples;
            samples.reserve(n);
            for (int i = 0; i < n; ++i)
                samples.push_back({ts[i], xyz[i * 3], xyz[i * 3 + 1],
                                   xyz[i * 3 + 2]});
            writer->writeAccelerometer(samples.data(), samples.size());
        }
    } catch (const std::invalid_argument&) {
        code = -4;
    } catch (const std::logic_error&) {
        code = -6;
    } catch (...) {
        code = -5;
    }
    env->ReleaseLongArrayElements(tsArr, ts, JNI_ABORT);
    env->ReleaseFloatArrayElements(xyzArr, xyz, JNI_ABORT);
    return code;
}

}  // namespace

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerWriteGyro(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlongArray timestamps,
    jfloatArray axes, jint count) {
    return writeMotion(env, handle, timestamps, axes, count, /*gyro=*/true);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerWriteAccel(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlongArray timestamps,
    jfloatArray axes, jint count) {
    return writeMotion(env, handle, timestamps, axes, count, /*gyro=*/false);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawWriter_containerClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* writer = writerFrom(handle);
    if (!writer) return -2;
    try {
        writer->close();
    } catch (...) {
        delete writer;
        return -5;
    }
    delete writer;
    return 0;
}

}  // extern "C"
