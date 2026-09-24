// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fast CPU fallback for the RAW viewfinder: 2x2 Bayer quad -> canonical
// R/Gr/Gb/B bytes, normalized exactly like the zero-copy GPU tiers (reciprocal
// multiply, clamp, round-half-up to 8 bit). Replaces the old Kotlin sampler,
// whose per-sample ByteBuffer bounds checks cost ~100+ ms per frame.
//
// Two tiers, selected by stride:
//  - packed (pixelStride == 2, even rowStride): direct uint16 row access with a
//    NEON float32x4 normalize core on ARM (scalar on x86 emulators);
//  - generic: scalar byte-addressed loop for exotic HAL strides.
//
// Threading: the strided Bayer gather is latency-bound (~77 ns/sample
// single-threaded on MediaTek, ~240 ms for a 1020x765 frame), so output rows
// are split into bands over a persistent pool (caller + 3 workers, created
// once, no per-frame spawn). Bands are disjoint by construction. All plane
// access ends before return.
#include <jni.h>

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define VF_NEON 1
#else
#define VF_NEON 0
#endif

#define LOG_TAG "RawLensVfCpu"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Result codes mirrored in VfCpuNeon.
#define VF_CPU_OK 0
#define VF_CPU_BAD_ARGUMENT 1
#define VF_CPU_OVERFLOW 2
#define VF_CPU_NOT_DIRECT 3

// Maximum VF extent per axis. Must match VfCpuNeon.MAX_EDGE (Kotlin validates
// first; this is the native backstop).
#define VF_CPU_MAX_EDGE 1080

// Bands per dispatch: the caller plus persistent workers. Four matches the
// PhotonCamera reference and spreads the gather over little/big cores.
#define VF_CPU_THREADS 4

namespace vfcpu {

struct SuperpixelJob {
    bool packed;
    const uint16_t* src16;
    const uint8_t* src8;
    int shortsPerRow;
    int rowStride;
    int pixelStride;
    int left;
    int top;
    int width;
    int height;
    int step;
    int dx[4];
    int dy[4];
    int ch[4];
    float black[4];
    float invRange[4];
    uint8_t* dst;
};

inline uint8_t normalizeScalar(uint16_t code, float black, float invRange) {
    float n = (static_cast<float>(code) - black) * invRange;
    if (n <= 0.f) return 0;
    if (n >= 1.f) return 255;
    return static_cast<uint8_t>(n * 255.f + 0.5f);
}

#if VF_NEON
// Normalize 4 codes -> 4 bytes with one float32x4 chain. Matches the scalar
// formula bit-for-bit on the rounding boundary for all 16-bit inputs: the
// multiply/add order and the +0.5 round-half-up are identical, and the clamp
// runs before scaling in both.
inline void normalizeNeon4(const uint16_t codes[4], float black, float invRange,
                           uint8_t out[4]) {
    uint32x4_t u32 = {codes[0], codes[1], codes[2], codes[3]};
    float32x4_t f = vcvtq_f32_u32(u32);
    f = vsubq_f32(f, vdupq_n_f32(black));
    f = vmulq_n_f32(f, invRange);
    f = vmaxq_f32(f, vdupq_n_f32(0.f));
    f = vminq_f32(f, vdupq_n_f32(1.f));
    // Separate mul + add (not vmla): identical rounding to the scalar
    // `n * 255f + 0.5f`, so NEON and scalar bytes agree bit-for-bit.
    f = vaddq_f32(vmulq_n_f32(f, 255.f), vdupq_n_f32(0.5f));
    uint32x4_t i = vcvtq_u32_f32(f);
    out[0] = static_cast<uint8_t>(vgetq_lane_u32(i, 0));
    out[1] = static_cast<uint8_t>(vgetq_lane_u32(i, 1));
    out[2] = static_cast<uint8_t>(vgetq_lane_u32(i, 2));
    out[3] = static_cast<uint8_t>(vgetq_lane_u32(i, 3));
}
#endif

// Packed band [y0, y1): pixelStride == 2, rowStride even. Each output row
// reads two adjacent source rows; per output pixel the 4 sites are permuted
// into canonical order by dx/dy. 8 output pixels per iteration with row
// prefetch: the strided loads can't vectorize, but 32 in-flight gathers plus
// prefetch keep the memory pipeline fed; threads multiply that further.
void packedBand(const SuperpixelJob& job, int y0, int y1) {
    const uint16_t* src = job.src16;
    const int shortsPerRow = job.shortsPerRow;
    const int left = job.left;
    const int top = job.top;
    const int width = job.width;
    const int step = job.step;
    uint8_t* dst = job.dst;
    for (int y = y0; y < y1; ++y) {
        const int quadTop = top + y * step;
        const uint16_t* row0 = src + static_cast<ptrdiff_t>(quadTop) * shortsPerRow;
        const uint16_t* row1 = row0 + shortsPerRow;
        uint8_t* outRow = dst + static_cast<ptrdiff_t>(y) * width * 4;
        int x = 0;
#if VF_NEON
        for (; x + 8 <= width; x += 8) {
            // ~512 B ahead on both source rows, streaming hint: each source
            // row is visited once (step >= 2, disjoint bands), so prefetched
            // lines must not displace anything cached. PRFM never faults, so
            // the tail iteration needs no bounds check.
            __builtin_prefetch(row0 + left + x * step + 256, 0, 0);
            __builtin_prefetch(row1 + left + x * step + 256, 0, 0);
            uint16_t codes[4][8];
            for (int k = 0; k < 8; ++k) {
                const int ql = left + (x + k) * step;
                for (int c = 0; c < 4; ++c) {
                    const uint16_t* row = (job.dy[c] == 0) ? row0 : row1;
                    codes[c][k] = row[ql + job.dx[c]];
                }
            }
            uint8_t bytes[4][8];
            for (int c = 0; c < 4; ++c) {
                const int ch = job.ch[c];
                normalizeNeon4(codes[c], job.black[ch], job.invRange[ch], bytes[c]);
                normalizeNeon4(codes[c] + 4, job.black[ch], job.invRange[ch], bytes[c] + 4);
            }
            for (int k = 0; k < 8; ++k) {
                uint8_t* px = outRow + (x + k) * 4;
                px[0] = bytes[0][k];
                px[1] = bytes[1][k];
                px[2] = bytes[2][k];
                px[3] = bytes[3][k];
            }
        }
#endif
        for (; x < width; ++x) {
            const int ql = left + x * step;
            uint8_t* px = outRow + x * 4;
            for (int c = 0; c < 4; ++c) {
                const uint16_t* row = (job.dy[c] == 0) ? row0 : row1;
                const int ch = job.ch[c];
                px[c] = normalizeScalar(row[ql + job.dx[c]], job.black[ch], job.invRange[ch]);
            }
        }
    }
}

// Generic band [y0, y1) for exotic strides: byte-addressed scalar loads.
// Correctness first, HALs never take this path in practice.
void genericBand(const SuperpixelJob& job, int y0, int y1) {
    const uint8_t* src = job.src8;
    const int rowStride = job.rowStride;
    const int pixelStride = job.pixelStride;
    const int left = job.left;
    const int top = job.top;
    const int width = job.width;
    const int step = job.step;
    uint8_t* dst = job.dst;
    for (int y = y0; y < y1; ++y) {
        const int quadTop = top + y * step;
        uint8_t* outRow = dst + static_cast<ptrdiff_t>(y) * width * 4;
        for (int x = 0; x < width; ++x) {
            const int ql = left + x * step;
            uint8_t* px = outRow + x * 4;
            for (int c = 0; c < 4; ++c) {
                const int sx = ql + job.dx[c];
                const int sy = quadTop + job.dy[c];
                uint16_t code = 0;
                memcpy(&code,
                       src + static_cast<ptrdiff_t>(sy) * rowStride +
                           static_cast<ptrdiff_t>(sx) * pixelStride,
                       sizeof(code));
                // Sensor planes are little-endian; NDK targets are LE.
                // No byteswap needed (verified on arm64/armv7/x86_64).
                const int ch = job.ch[c];
                px[c] = normalizeScalar(code, job.black[ch], job.invRange[ch]);
            }
        }
    }
}

// Persistent worker pool: per-frame thread spawn costs more than the bands
// themselves at VF sizes, so workers sleep on a CV between dispatches. They
// live for the process lifetime (detached, never joined) and are only ever
// created here, under the dispatch mutex.
std::mutex gDispatchMutex;  // serializes copyNative calls: one shared job
std::mutex gWorkMutex;
std::condition_variable gWorkCv;
std::condition_variable gDoneCv;
SuperpixelJob gJob;
unsigned gGeneration = 0;
int gDoneCount = 0;
int gTotalBands = 0;
int gLiveWorkers = 0;
bool gPoolTried = false;

void workerMain(int band) {
    unsigned seen = 0;
    for (;;) {
        SuperpixelJob job{};
        int y0 = 0, y1 = 0;
        {
            std::unique_lock<std::mutex> lock(gWorkMutex);
            gWorkCv.wait(lock, [&] { return gGeneration != seen; });
            seen = gGeneration;
            if (band < gTotalBands) {
                job = gJob;
                y0 = (band * gJob.height) / gTotalBands;
                y1 = ((band + 1) * gJob.height) / gTotalBands;
            }
        }
        if (y1 > y0) {
            if (job.packed) packedBand(job, y0, y1);
            else genericBand(job, y0, y1);
        }
        {
            std::lock_guard<std::mutex> lock(gWorkMutex);
            ++gDoneCount;
        }
        gDoneCv.notify_one();
    }
}

// Caller holds gDispatchMutex. Idempotent; any failure pins single-threaded
// mode for the process lifetime.
void ensurePoolLocked() {
    if (gPoolTried) return;
    gPoolTried = true;
    for (int i = 1; i < VF_CPU_THREADS; ++i) {
        try {
            std::thread(workerMain, i).detach();
            ++gLiveWorkers;
        } catch (...) {
            LOGW("vf-cpu: worker %d failed to start; fewer bands", i);
            break;
        }
    }
}

int runThreaded(const SuperpixelJob& job) {
    std::lock_guard<std::mutex> dispatchLock(gDispatchMutex);
    ensurePoolLocked();
    const int bands = std::min(1 + gLiveWorkers, job.height);
    if (bands <= 1) {
        if (job.packed) packedBand(job, 0, job.height);
        else genericBand(job, 0, job.height);
        return VF_CPU_OK;
    }
    {
        std::lock_guard<std::mutex> lock(gWorkMutex);
        gJob = job;
        gTotalBands = bands;
        gDoneCount = 0;
        ++gGeneration;
    }
    gWorkCv.notify_all();
    // Caller runs band 0 inline. Every live worker counts done exactly once
    // per generation (idlers with band >= bands do no work but still count),
    // and the caller waits for all of them, so no completion can leak into
    // the next dispatch's counter.
    const int band0End = job.height / bands;
    if (job.packed) packedBand(job, 0, band0End);
    else genericBand(job, 0, band0End);
    {
        std::unique_lock<std::mutex> lock(gWorkMutex);
        gDoneCv.wait(lock, [&] { return gDoneCount >= gLiveWorkers; });
    }
    return VF_CPU_OK;
}

}  // namespace vfcpu

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfCpuNeon_copyNative(
    JNIEnv* env, jobject, jobject srcBuf, jint srcOffset, jint rowStride,
    jint pixelStride, jint left, jint top, jint width, jint height, jint step,
    jintArray channels, jfloatArray blackLevels, jfloat white, jobject dstBuf,
    jint dstOffset) {
    if (!srcBuf || !dstBuf || !channels || !blackLevels) return VF_CPU_BAD_ARGUMENT;
    if (env->GetArrayLength(channels) != 4 ||
        env->GetArrayLength(blackLevels) != 4) {
        return VF_CPU_BAD_ARGUMENT;
    }
    if ((left & 1) != 0 || (top & 1) != 0 || step < 2 || (step & 1) != 0 ||
        width <= 0 || height <= 0 || width > VF_CPU_MAX_EDGE || height > VF_CPU_MAX_EDGE ||
        rowStride <= 0 || pixelStride <= 0 || srcOffset < 0 || dstOffset < 0 ||
        !std::isfinite(white) || white <= 0.f) {
        return VF_CPU_BAD_ARGUMENT;
    }
    uint8_t* srcBase =
        static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuf));
    uint8_t* dstBase =
        static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuf));
    if (!srcBase || !dstBase) return VF_CPU_NOT_DIRECT;
    const jlong srcCap = env->GetDirectBufferCapacity(srcBuf);
    const jlong dstCap = env->GetDirectBufferCapacity(dstBuf);
    if (srcCap < 0 || dstCap < 0) return VF_CPU_NOT_DIRECT;

    jint chOf[4];
    jfloat blackIn[4];
    env->GetIntArrayRegion(channels, 0, 4, chOf);
    env->GetFloatArrayRegion(blackLevels, 0, 4, blackIn);
    if (env->ExceptionCheck()) return VF_CPU_BAD_ARGUMENT;
    for (int c = 0; c < 4; ++c) {
        if (chOf[c] < 0 || chOf[c] > 3 || !std::isfinite(blackIn[c])) {
            return VF_CPU_BAD_ARGUMENT;
        }
    }
    int dx[4], dy[4], ch[4];
    for (int c = 0; c < 4; ++c) {
        ch[c] = chOf[c];
        dx[c] = chOf[c] % 2;
        dy[c] = chOf[c] / 2;
    }
    float black[4], invRange[4];
    for (int i = 0; i < 4; ++i) {
        black[i] = blackIn[i];
        float range = white - blackIn[i];
        if (range < 1.f) range = 1.f;
        invRange[i] = 1.f / range;
    }

    // Bounds-check the last visited sample up front; the hot loops stay bare.
    const long long lastX = static_cast<long long>(left) +
                            static_cast<long long>(width - 1) * step + 1;
    const long long lastY = static_cast<long long>(top) +
                            static_cast<long long>(height - 1) * step + 1;
    if (lastX < 0 || lastY < 0) return VF_CPU_BAD_ARGUMENT;
    const long long needOut =
        static_cast<long long>(dstOffset) +
        static_cast<long long>(width) * height * 4;
    if (needOut > dstCap) return VF_CPU_OVERFLOW;

    vfcpu::SuperpixelJob job{};
    job.left = left;
    job.top = top;
    job.width = width;
    job.height = height;
    job.step = step;
    for (int c = 0; c < 4; ++c) {
        job.dx[c] = dx[c];
        job.dy[c] = dy[c];
        job.ch[c] = ch[c];
    }
    for (int i = 0; i < 4; ++i) {
        job.black[i] = black[i];
        job.invRange[i] = invRange[i];
    }
    uint8_t* src = srcBase + srcOffset;
    uint8_t* dst = dstBase + dstOffset;
    job.dst = dst;
    if (pixelStride == 2 && (rowStride & 1) == 0) {
        const long long shortsPerRow = rowStride / 2;
        const long long needShorts = lastY * shortsPerRow + lastX + 1;
        if (srcOffset + needShorts * 2 > srcCap) return VF_CPU_OVERFLOW;
        job.packed = true;
        job.src16 = reinterpret_cast<const uint16_t*>(src);
        job.shortsPerRow = static_cast<int>(shortsPerRow);
        return vfcpu::runThreaded(job);
    }
    const long long needSrc = static_cast<long long>(srcOffset) +
                              lastY * rowStride + lastX * pixelStride + 2;
    if (needSrc > srcCap) return VF_CPU_OVERFLOW;
    job.packed = false;
    job.src8 = src;
    job.rowStride = rowStride;
    job.pixelStride = pixelStride;
    return vfcpu::runThreaded(job);
}
