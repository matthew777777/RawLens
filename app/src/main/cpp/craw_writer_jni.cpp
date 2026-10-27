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

// 2x RGB binning (SPEC 3.8): the full-res mosaic is demosaiced once, here,
// into a half-resolution RGB image whose pixels stand for the centres of the
// 2x2 source blocks. A binned *mosaic* would be demosaiced a second time by
// the reader and fringe magenta/green at every fine edge; RGB avoids that.
// Green: the 2 in-block greens at weight 6 plus the symmetric ring of 4
// nearest outer greens at weight 1 (sum 16). R/B: green + colour difference,
// where the difference at each R/B site uses an edge-directed green (average
// along the smaller green gradient, never across an edge) and is
// interpolated bilinearly (9,3,3,1) to the block centre. Blocks within 2
// samples of a near-clipped sample use plain same-colour interpolation, so
// highlights stay neutral. Measured on three real takes vs full-res AHD
// downscaled 2x: edge chroma error -15..-65 %, luma error about half and
// ~90 % detail, compared with the centre-aligned Bayer bin.
// Output is the cell frame: (row 0, col 0) R, (0,1) G, (1,0) B, (1,1) zero.
void binRgb(const uint8_t* src, int stride, int width, int rows, int cfa, int bitDepth,
            std::vector<uint16_t>& out) {
    const int ow = width / 2, oh = rows / 2;
    auto mirror = [](int i, int n) { return i < 0 ? -i : i >= n ? 2 * (n - 1) - i : i; };
    // Mirrored copy with a 4-sample border (parity-preserving), so the hot
    // loops below read without bounds checks.
    constexpr int kPad = 4;
    const int pw = width + 2 * kPad;
    thread_local std::vector<uint16_t> padded;
    padded.resize(static_cast<size_t>(pw) * (rows + 2 * kPad));
    for (int y = -kPad; y < rows + kPad; ++y) {
        const auto* s = reinterpret_cast<const uint16_t*>(src + static_cast<size_t>(mirror(y, rows)) * stride);
        uint16_t* d = padded.data() + static_cast<size_t>(y + kPad) * pw + kPad;
        std::copy(s, s + width, d);
        for (int k = 1; k <= kPad; ++k) { d[-k] = s[k]; d[width - 1 + k] = s[width - 1 - k]; }
    }
    const uint16_t* P0 = padded.data() + static_cast<size_t>(kPad) * pw + kPad;
    // In-block (x, y) of R and B for RGGB, GRBG, GBRG, BGGR.
    static constexpr int kR[4][2] = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
    static constexpr int kB[4][2] = {{1, 1}, {0, 1}, {1, 0}, {0, 0}};
    const bool greenEven = cfa == 1 || cfa == 2;
    const size_t n = static_cast<size_t>(ow) * oh;
    thread_local std::vector<int32_t> dR, dB;
    thread_local std::vector<uint16_t> blockMax, near3;
    // 4 x (C - edge-directed green) at every site of one colour.
    auto diffPlane = [&](const int* c, std::vector<int32_t>& d) {
        d.resize(n);
        for (int j = 0; j < oh; ++j) {
            const uint16_t* p = P0 + static_cast<size_t>(2 * j + c[1]) * pw + c[0];
            int32_t* o = d.data() + static_cast<size_t>(j) * ow;
            for (int i = 0; i < ow; ++i, p += 2) {
                const int32_t C = p[0], l = p[-1], r = p[1], u = p[-pw], dn = p[pw];
                const int32_t dh = 2 * std::abs(l - r) + std::abs(2 * C - p[-2] - p[2]);
                const int32_t dv = 2 * std::abs(u - dn) + std::abs(2 * C - p[-2 * pw] - p[2 * pw]);
                const int32_t g2 = dh < dv ? 2 * (l + r) : dv < dh ? 2 * (u + dn) : l + r + u + dn;
                o[i] = 4 * C - g2;
            }
        }
    };
    diffPlane(kR[cfa], dR);
    diffPlane(kB[cfa], dB);
    // Near-clip map: max of each 2x2 block, then max over the 3x3 block neighbourhood.
    blockMax.resize(n); near3.resize(n);
    for (int j = 0; j < oh; ++j) {
        const uint16_t* a = P0 + static_cast<size_t>(2 * j) * pw;
        uint16_t* o = blockMax.data() + static_cast<size_t>(j) * ow;
        for (int i = 0; i < ow; ++i)
            o[i] = std::max(std::max(a[2 * i], a[2 * i + 1]), std::max(a[pw + 2 * i], a[pw + 2 * i + 1]));
    }
    for (int j = 0; j < oh; ++j) {
        const uint16_t* m = blockMax.data() + static_cast<size_t>(j) * ow;
        uint16_t* o = near3.data() + static_cast<size_t>(j) * ow;
        for (int i = 0; i < ow; ++i)
            o[i] = std::max({m[i], m[std::max(i - 1, 0)], m[std::min(i + 1, ow - 1)]});
    }
    const int white = (1 << bitDepth) - 1, clip = white - white / 20;
    out.resize(static_cast<size_t>(width) * rows);
    for (int j = 0; j < oh; ++j) {
        uint16_t* r0 = out.data() + static_cast<size_t>(2 * j) * width;
        uint16_t* r1 = r0 + width;
        const uint16_t* a = P0 + static_cast<size_t>(2 * j) * pw;
        const uint16_t* nU = near3.data() + static_cast<size_t>(std::max(j - 1, 0)) * ow;
        const uint16_t* nC = near3.data() + static_cast<size_t>(j) * ow;
        const uint16_t* nD = near3.data() + static_cast<size_t>(std::min(j + 1, oh - 1)) * ow;
        // Bilinear (9,3,3,1) rows of each diff plane: own row and the row toward the centre.
        const int32_t* R0 = dR.data() + static_cast<size_t>(j) * ow;
        const int32_t* R1 = dR.data() + static_cast<size_t>(mirror(j + (kR[cfa][1] == 0 ? 1 : -1), oh)) * ow;
        const int32_t* B0 = dB.data() + static_cast<size_t>(j) * ow;
        const int32_t* B1 = dB.data() + static_cast<size_t>(mirror(j + (kB[cfa][1] == 0 ? 1 : -1), oh)) * ow;
        const int rStep = kR[cfa][0] == 0 ? 1 : -1, bStep = kB[cfa][0] == 0 ? 1 : -1;
        for (int i = 0; i < ow; ++i) {
            const uint16_t* q = a + 2 * i;
            const int32_t gin = greenEven ? q[0] + q[pw + 1] : q[1] + q[pw];
            const int32_t ring = greenEven ? q[1 - pw] + q[pw - 1] + q[2] + q[2 * pw]
                                           : q[-1] + q[-pw] + q[pw + 2] + q[2 * pw + 1];
            const int32_t g16 = 6 * gin + ring;
            const int ri = mirror(i + rStep, ow), bi = mirror(i + bStep, ow);
            int32_t R, B;
            if (std::max({nU[i], nC[i], nD[i]}) >= clip) {
                // Plain same-colour bilinear near clipping (keeps highlights neutral).
                // Neighbours mirror on the same-colour site grid, like the diff planes.
                auto plain = [&](const int* c, int ti) {
                    const int tj = mirror(j + (c[1] == 0 ? 1 : -1), oh);
                    const uint16_t* own = P0 + static_cast<size_t>(2 * j + c[1]) * pw;
                    const uint16_t* tow = P0 + static_cast<size_t>(2 * tj + c[1]) * pw;
                    const int x = 2 * i + c[0], tx = 2 * ti + c[0];
                    return (9 * own[x] + 3 * own[tx] + 3 * tow[x] + tow[tx] + 8) >> 4;
                };
                R = plain(kR[cfa], ri);
                B = plain(kB[cfa], bi);
            } else {
                R = (4 * g16 + 9 * R0[i] + 3 * R0[ri] + 3 * R1[i] + R1[ri] + 32) >> 6;
                B = (4 * g16 + 9 * B0[i] + 3 * B0[bi] + 3 * B1[i] + B1[bi] + 32) >> 6;
            }
            r0[2 * i] = static_cast<uint16_t>(std::clamp(R, 0, white));
            r0[2 * i + 1] = static_cast<uint16_t>((g16 + 8) >> 4);
            r1[2 * i] = static_cast<uint16_t>(std::clamp(B, 0, white));
            r1[2 * i + 1] = 0;
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
        desc.cfa = bin ? craw::Cfa::RGB : static_cast<craw::Cfa>(cfa & 3);
        desc.bitDepth = static_cast<uint8_t>(std::clamp<jint>(bitDepth, 1, 16));
        // Both live recording codecs are protected by the container chunk CRC.
        // Avoid an additional full RAW-frame checksum pass in either mode.
        desc.codec = (codec == 2 || codec == 3) ? static_cast<uint8_t>(codec) : 1;
        desc.frameCrc = false;
        const uint16_t* raw;
        size_t rawStride;
        if (bin) {
            // RGB frames need a cell codec (2 or 3); the cell frame is w x cropHeight.
            if (desc.codec == 1) desc.codec = 3;
            binRgb(base, stride, w & ~1, cropHeight & ~1, cfa & 3, desc.bitDepth, binned);
            desc.width = static_cast<uint32_t>(w & ~1);
            desc.height = static_cast<uint32_t>(cropHeight & ~1);
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
