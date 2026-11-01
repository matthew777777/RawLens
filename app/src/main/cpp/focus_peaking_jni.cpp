// SPDX-License-Identifier: GPL-3.0-or-later
// Native equivalent of FocusPeakingDetail; RAW input, no ISP surface or display filtering.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
constexpr int N = 16, P = 32, COUNT = N * N, STRIDE = N + 1;
template<typename T> T clamp(T value, T low, T high) { return std::min(std::max(value, low), high); }
struct Scratch {
    std::array<float, COUNT> green{}, clean{}, smooth{}, detail{};
    std::array<uint8_t, COUNT> clip{};
    std::array<float, COUNT / 4> residual{};
    std::array<int, STRIDE * STRIDE> integral{};
};
int detect(Scratch& s, float modelVariance) {
    auto& g = s.green;
    float low = g[0], high = g[0];
    for (float v : g) { if (!std::isfinite(v)) return -1; low = std::min(low, v); high = std::max(high, v); }
    if (high - low < 0.006f) return -1;
    int k = 0;
    for (int y = 0; y < N; y += 2) for (int x = 0; x < N; x += 2) {
        const int i = y * N + x;
        s.residual[k++] = std::abs(g[i] - g[i + 1] - g[i + N] + g[i + N + 1]);
    }
    std::sort(s.residual.begin(), s.residual.end());
    const float median = (s.residual[k / 2 - 1] + s.residual[k / 2]) * 0.5f;
    const float measured = median / 1.34898f;
    const float variance = std::max(std::isfinite(modelVariance) ? std::max(0.f, modelVariance) : 0.f,
                                    measured * measured);
    const float spike = std::max(0.03f, 6.f * std::sqrt(variance));
    s.clean = g;
    for (int y = 1; y < N - 1; ++y) for (int x = 1; x < N - 1; ++x) {
        const int i = y * N + x;
        const float l = g[i - 1], r = g[i + 1], u = g[i - N], d = g[i + N];
        const float mn = std::min({l, r, u, d}), mx = std::max({l, r, u, d});
        if ((g[i] > mx + spike || g[i] < mn - spike) && mx - mn < spike * 0.5f)
            s.clean[i] = (l + r + u + d) * 0.25f;
    }
    auto& p = s.clean; auto& b = s.smooth;
    for (int y = 1; y < N - 1; ++y) for (int x = 1; x < N - 1; ++x) {
        const int i = y * N + x;
        b[i] = (p[i-N-1] + 2.f*p[i-N] + p[i-N+1] + 2.f*p[i-1] + 4.f*p[i] +
                2.f*p[i+1] + p[i+N-1] + 2.f*p[i+N] + p[i+N+1]) / 16.f;
    }
    auto& integral = s.integral; integral.fill(0);
    for (int y = 0; y < N; ++y) {
        int sum = 0;
        for (int x = 0; x < N; ++x) {
            if (s.clip[y * N + x]) ++sum;
            integral[(y+1)*STRIDE+x+1] = integral[y*STRIDE+x+1] + sum;
        }
    }
    const float floor = std::max(0.006f, 6.f * std::sqrt(0.234375f * variance));
    const float floorSquared = floor * floor;
    s.detail.fill(0.f);
    for (int y = 3; y < N - 3; ++y) for (int x = 3; x < N - 3; ++x) {
        const int l = x-3, r = x+4, t = y-3, d = y+4;
        if (integral[d*STRIDE+r]-integral[t*STRIDE+r]-integral[d*STRIDE+l]+integral[t*STRIDE+l]) continue;
        const int i = y * N + x;
        const float gx = b[i+1]-b[i-1], gy = b[i+N]-b[i-N];
        const float hx = b[i+2]-b[i-2], hy = b[i+2*N]-b[i-2*N];
        const float fine = gx*gx+gy*gy, coarse = hx*hx+hy*hy;
        const float diagonal = fine > 0.f ? 2.f * std::abs(gx*gy) / fine : 0.f;
        const float ratio = 0.68f - 0.06f * diagonal;
        if (fine >= floorSquared && fine >= ratio*ratio*coarse) s.detail[i] = fine;
    }
    int best = -1; float strength = 0.f;
    for (int y = 3; y < N - 3; ++y) for (int x = 3; x < N - 3; ++x) {
        const int i = y * N + x;
        if (s.detail[i] <= strength) continue;
        int neighbors = 0;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx)
            if ((dx || dy) && s.detail[i + dy*N + dx] > 0.f) ++neighbors;
        if (neighbors >= 2) { best = i; strength = s.detail[i]; }
    }
    return best;
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_NativeFocusPeaking_sampleNative(
    JNIEnv* env, jobject, jobject raw, jint width, jint height, jint rowStride, jint pixelStride,
    jint packedBits, jint cfa, jfloatArray calibration, jdoubleArray noise, jint cols, jint rows, jintArray output) {
    auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(raw));
    const jlong capacity = env->GetDirectBufferCapacity(raw);
    if (!bytes || width < P || height < P || cols <= 0 || cols > 512 || rows <= 0 || rows > 512 ||
        width > 65536 || height > 65536 || int64_t(width) * height > INT32_MAX || cfa < 0 || cfa > 3 || rowStride <= 0 || !calibration || !noise || !output ||
        env->GetArrayLength(calibration) != 5 || env->GetArrayLength(noise) != 8 ||
        env->GetArrayLength(output) != cols * rows) return JNI_FALSE;
    int64_t rowBytes;
    if (packedBits == 10 && width % 4 == 0) rowBytes = int64_t(width) / 4 * 5;
    else if (packedBits == 12 && width % 2 == 0) rowBytes = int64_t(width) / 2 * 3;
    else if (packedBits == 0 && pixelStride >= 2) rowBytes = int64_t(width - 1) * pixelStride + 2;
    else return JNI_FALSE;
    if (rowStride < rowBytes || int64_t(height - 1) * rowStride + rowBytes > capacity) return JNI_FALSE;
    float cal[5]; double profile[8];
    env->GetFloatArrayRegion(calibration, 0, 5, cal);
    env->GetDoubleArrayRegion(noise, 0, 8, profile);
    if (env->ExceptionCheck()) return JNI_FALSE;
    float inv[4];
    for (int phase = 0; phase < 4; ++phase) {
        if (!std::isfinite(cal[0]) || !std::isfinite(cal[phase+1]) || cal[0] <= cal[phase+1]) return JNI_FALSE;
        inv[phase] = 1.f / (cal[0] - cal[phase+1]);
    }
    auto code = [&](int x, int y) -> uint16_t {
        const uint8_t* row = bytes + int64_t(y) * rowStride;
        if (packedBits == 10) {
            const int base = x / 4 * 5, shift = x % 4 * 2;
            return uint16_t((uint16_t(row[base+x%4]) << 2) | ((row[base+4] >> shift) & 3));
        }
        if (packedBits == 12) {
            const int base = x / 2 * 3, shift = x % 2 * 4;
            return uint16_t((uint16_t(row[base+x%2]) << 4) | ((row[base+2] >> shift) & 15));
        }
        uint16_t v; std::memcpy(&v, row + int64_t(x) * pixelStride, sizeof(v)); return v;
    };
    Scratch scratch;
    std::vector<jint> points(cols * rows, -1);
    const bool sameParity = cfa == 1 || cfa == 2;
    const int green0 = sameParity ? 0 : 1, green1 = sameParity ? 3 : 2;
    for (int row = 0; row < rows; ++row) {
        const int cy = int((float(row) + 0.5f) * float(height) / float(rows));
        const int qy = clamp((cy - P/2) / 2 * 2, 0, (height - P) / 2 * 2);
        for (int col = 0; col < cols; ++col) {
            const int cx = int((float(col) + 0.5f) * float(width) / float(cols));
            const int qx = clamp((cx - P/2) / 2 * 2, 0, (width - P) / 2 * 2);
            float mean = 0.f;
            for (int gy = 0; gy < N; ++gy) for (int gx = 0; gx < N; ++gx) {
                float quad[4]; bool saturated = false;
                for (int phase = 0; phase < 4; ++phase) {
                    const int x = qx + gx*2 + (phase & 1), y = qy + gy*2 + (phase >> 1);
                    const float normalized = (float(code(x, y)) - cal[phase+1]) * inv[phase];
                    if (normalized >= 0.98f) saturated = true;
                    quad[phase] = clamp(normalized, 0.f, 1.f);
                }
                const int i = gy * N + gx;
                scratch.green[i] = (quad[green0] + quad[green1]) * 0.5f;
                scratch.clip[i] = saturated;
                mean += scratch.green[i];
            }
            mean /= float(COUNT);
            float variance = 0.f;
            for (int phase = 0; phase < 4; ++phase) {
                const double slope = profile[phase*2], offset = profile[phase*2+1];
                if (std::isfinite(slope) && std::isfinite(offset) && slope >= 0 && offset >= 0)
                    variance = std::max(variance, float(slope * double(mean) + offset));
            }
            const int edge = detect(scratch, variance);
            if (edge >= 0) points[row*cols+col] = (qy + edge/N*2) * width + qx + edge%N*2;
        }
    }
    env->SetIntArrayRegion(output, 0, cols * rows, points.data());
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}
