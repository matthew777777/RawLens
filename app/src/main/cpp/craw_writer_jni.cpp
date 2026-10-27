// SPDX-License-Identifier: GPL-3.0-or-later
// Writer bridge: RAW16 plane -> Cinematic RAW Studio (.CRAW) encoder
// (Apache-2.0, third_party/craw/encoder) -> craw::Writer.
//
// Two frame paths match the recorder's N encode workers + one in-order
// committer:
// - containerEncodeFrame: encode only, straight into a caller pooled buffer.
//   Worker threads; each call is single-threaded (frame-level parallelism).
// - containerWriteFrame: commit an encoded payload. Committer thread only;
//   the writer rejects timestamp regressions.
// Audio (PCM16 or float32) and motion entry points are committer-thread-only like writes.
#include <jni.h>

#include <craw/Writer.h>

#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <fstream>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

craw::Writer* writerFrom(jlong h) { return reinterpret_cast<craw::Writer*>(h); }

// 2x2 binning to a half-resolution Bayer frame with the same CFA: each output
// sample is the rounded mean of the four same-colour samples of its 4x4 block.
void bin2x2(const uint8_t* src, int stride, int width, int rows, std::vector<uint16_t>& out) {
    const int ow = width / 2, oh = rows / 2;
    out.resize(static_cast<size_t>(ow) * oh);
    for (int y = 0; y < oh; ++y) {
        // Output row y has CFA phase y & 1; its sources are rows 2*(y & ~1) + (y & 1) + {0, 2}.
        const int sy = 2 * (y & ~1) + (y & 1);
        const auto* r0 = reinterpret_cast<const uint16_t*>(src + static_cast<size_t>(sy) * stride);
        const auto* r1 = reinterpret_cast<const uint16_t*>(src + static_cast<size_t>(sy + 2) * stride);
        uint16_t* o = out.data() + static_cast<size_t>(y) * ow;
        for (int x = 0; x < ow; ++x) {
            const int sx = 2 * (x & ~1) + (x & 1);
            o[x] = static_cast<uint16_t>((uint32_t{r0[sx]} + r0[sx + 2] + r1[sx] + r1[sx + 2] + 2) >> 2);
        }
    }
}

// Off by default: in a live take the fast cores also run the viewfinder and
// camera service, and leaving the efficiency cores available measured faster.
bool pinWorkers = false;

// Pins the calling encode worker to the fastest CPU cluster (highest
// cpuinfo_max_freq) once per thread: little cores would hold the in-order
// committer back. Best effort; failures leave the scheduler in charge.
void pinToFastCores() {
    thread_local bool done = false;
    if (done) return;
    done = true;
    const long n = sysconf(_SC_NPROCESSORS_CONF);
    std::vector<long> freq(n > 0 ? n : 0, 0);
    long best = 0;
    for (long c = 0; c < n; ++c) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cpufreq/cpuinfo_max_freq");
        if (f >> freq[c]) best = std::max(best, freq[c]);
    }
    if (best == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    int count = 0;
    for (long c = 0; c < n; ++c)
        if (freq[c] >= best * 9 / 10) { CPU_SET(c, &set); ++count; }
    if (count > 0 && count < n) sched_setaffinity(0, sizeof set, &set);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_CrawWriter_containerOpen(JNIEnv* env, jobject, jstring path, jstring metadata) {
    try {
        return reinterpret_cast<jlong>(new craw::Writer(jstr(env, path), jstr(env, metadata)));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerEncodeFrame(
    JNIEnv* env, jobject, jobject src, jint srcOffset, jint size, jint w, jint h, jint stride,
    jint cropTop, jint cropHeight, jboolean bin, jint cfa, jint bitDepth, jint codec, jobject dst,
    jint dstOffset, jint dstCapacity) {
    const auto* s = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!s || !d || size < 0 || stride % 2 || stride < w * 2) return -2;
    if (static_cast<int64_t>(cropTop + cropHeight - 1) * stride + int64_t{w} * 2 > size) return -4;
    (void)h;
    if (pinWorkers) pinToFastCores();
    try {
        thread_local std::vector<uint16_t> binned;
        const uint8_t* base = s + srcOffset + static_cast<size_t>(cropTop) * stride;
        craw::FrameDesc desc;
        desc.cfa = static_cast<craw::Cfa>(cfa & 3);
        desc.bitDepth = static_cast<uint8_t>(std::clamp<jint>(bitDepth, 1, 16));
        // Both live recording codecs are protected by the container chunk CRC.
        // Avoid an additional full RAW-frame checksum pass in either mode.
        desc.codec = codec == 2 ? 2 : 1;
        desc.frameCrc = false;
        const uint16_t* raw;
        size_t rawStride;
        if (bin) {
            bin2x2(base, stride, w, cropHeight, binned);
            desc.width = static_cast<uint32_t>(w / 2);
            desc.height = static_cast<uint32_t>(cropHeight / 2);
            raw = binned.data();
            rawStride = desc.width;
        } else {
            desc.width = static_cast<uint32_t>(w);
            desc.height = static_cast<uint32_t>(cropHeight);
            raw = reinterpret_cast<const uint16_t*>(base);
            rawStride = static_cast<size_t>(stride / 2);
        }
        uint8_t* out = d + dstOffset;
        const auto capacity = static_cast<size_t>(std::max<jint>(dstCapacity, 0));
        size_t bytes;
        try {
            bytes = craw::encodeFrame(raw, rawStride, desc, out, capacity);
        } catch (const std::invalid_argument&) {
            // A sample above the declared white level (e.g. hot pixel): stay lossless.
            if (desc.bitDepth == 16) throw;
            desc.bitDepth = 16;
            bytes = craw::encodeFrame(raw, rawStride, desc, out, capacity);
        }
        return static_cast<jint>(bytes);
    } catch (const std::length_error&) {
        return -3;
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteFrame(
    JNIEnv* env, jobject, jlong handle, jobject payload, jint payloadOffset, jint payloadBytes,
    jlong timestampNs, jstring frameJson) {
    auto* writer = writerFrom(handle);
    const auto* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!writer || !p || payloadBytes <= 0) return -2;
    try {
        writer->addEncodedFrame(p + payloadOffset, static_cast<size_t>(payloadBytes), timestampNs,
                                jstr(env, frameJson));
        return payloadBytes;
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (const std::logic_error&) {
        return -6;  // timestamp regression
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteAudio(
    JNIEnv* env, jobject, jlong handle, jobject buf, jint offset, jint frames, jint channels,
    jint sampleRate, jboolean isFloat, jlong timestampNs) {
    auto* writer = writerFrom(handle);
    const auto* b = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!writer || !b || frames <= 0 || channels <= 0 || channels > 255 || sampleRate <= 0) return -2;
    try {
        const bool f32 = isFloat != JNI_FALSE;
        writer->addAudio(timestampNs, f32 ? craw::AudioFormat::F32 : craw::AudioFormat::S16,
                         static_cast<uint8_t>(channels), static_cast<uint32_t>(sampleRate), b + offset,
                         static_cast<uint32_t>(frames));
        return frames * channels * (f32 ? 4 : 2);
    } catch (...) {
        return -5;
    }
}

namespace {

jint writeMotion(JNIEnv* env, jlong handle, jlongArray tsArr, jfloatArray xyzArr, jint n,
                 craw::ImuSensor sensor) {
    auto* writer = writerFrom(handle);
    if (!writer || !tsArr || !xyzArr || n <= 0) return -2;
    if (env->GetArrayLength(tsArr) < n || env->GetArrayLength(xyzArr) < n * 3) return -4;
    std::vector<jlong> ts(n);
    std::vector<jfloat> xyz(static_cast<size_t>(n) * 3);
    env->GetLongArrayRegion(tsArr, 0, n, ts.data());
    env->GetFloatArrayRegion(xyzArr, 0, n * 3, xyz.data());
    std::vector<craw::ImuSample> samples(n);
    for (jint i = 0; i < n; ++i)
        samples[i] = {ts[i], xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]};
    try {
        writer->addImu(sensor, samples.data(), static_cast<uint32_t>(n));
        return n;
    } catch (...) {
        return -5;
    }
}

}  // namespace

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteGyro(JNIEnv* env, jobject, jlong handle,
                                                       jlongArray ts, jfloatArray xyz, jint n) {
    return writeMotion(env, handle, ts, xyz, n, craw::ImuSensor::Gyroscope);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteAccel(JNIEnv* env, jobject, jlong handle,
                                                        jlongArray ts, jfloatArray xyz, jint n) {
    return writeMotion(env, handle, ts, xyz, n, craw::ImuSensor::Accelerometer);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteShading(JNIEnv* env, jobject, jlong handle, jlong ts,
                                                          jint id, jint columns, jint rows,
                                                          jfloatArray gains) {
    auto* writer = writerFrom(handle);
    if (!writer || !gains || columns <= 0 || rows <= 0 || columns > 65535 || rows > 65535) return -2;
    const jsize n = static_cast<jsize>(columns) * rows * 4;
    if (env->GetArrayLength(gains) < n) return -4;
    std::vector<float> g(n);
    env->GetFloatArrayRegion(gains, 0, n, g.data());
    try {
        writer->addShading(ts, static_cast<uint32_t>(id), static_cast<uint16_t>(columns),
                           static_cast<uint16_t>(rows), g.data());
        return 0;
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerClose(JNIEnv*, jobject, jlong handle) {
    auto* writer = writerFrom(handle);
    if (!writer) return -2;
    int code = 0;
    try {
        writer->close();
    } catch (...) {
        code = -5;
    }
    delete writer;
    return code;
}

}  // extern "C"
