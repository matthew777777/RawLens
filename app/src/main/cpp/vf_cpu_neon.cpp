// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fast CPU fallback for the RAW viewfinder: 2x2 Bayer quad -> canonical
// R/Gr/Gb/B bytes, normalized in Q6 fixed-point integer exactly like the
// zero-copy GPU tiers (VfLevels.kt: same formula, same ints, so bytes agree
// by construction). Replaces the old float sampler; integer needs no SIMD
// to win here (the strided Bayer gather is latency-bound at ~77 ns/sample,
// dwarfing arithmetic), so one scalar core serves ARMv7/ARM64/x86 alike.
//
// Two tiers, selected by stride:
//  - packed (pixelStride == 2, even rowStride): direct uint16 row access;
//  - generic: scalar byte-addressed loop for exotic HAL strides.
//
// Threading: output rows are split into bands over a persistent pool
// (caller + 3 workers, created once, no per-frame spawn). Bands are
// disjoint by construction. All plane access ends before return.
#include <jni.h>

#include <android/log.h>
#include <algorithm>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>

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

// Q6 fixed-point scale (VfLevels.SCALE) and denominator backstop
// (VfLevels.MAX_DEN_Q): denQ in [1, MAX] keeps num * 255 in int32.
#define VF_Q6_SCALE 64
#define VF_Q6_MAX_DEN (65535 * VF_Q6_SCALE)

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
    int blackQ[4];
    int denQ[4];
    uint8_t* dst;
};

// Q6 normalize (VfLevels.normalizeByte): num = code*64 - blackQ;
// 0 below black, 255 at/above white, round-half-up between. All
// intermediates fit int32 (denQ <= 65535*64 by validation).
inline uint8_t normalizeInt(uint16_t code, int blackQ, int denQ) {
    const int num = static_cast<int>(code) * VF_Q6_SCALE - blackQ;
    if (num <= 0) return 0;
    if (num >= denQ) return 255;
    return static_cast<uint8_t>((num * 255 + denQ / 2) / denQ);
}

// Packed band [y0, y1): pixelStride == 2, rowStride even. Each output row
// reads two adjacent source rows; per output pixel the 4 sites are permuted
// into canonical order by dx/dy. Row prefetch + threads keep the gather fed.
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
        for (int x = 0; x < width; ++x) {
            if ((x & 7) == 0) {
                // ~512 B ahead on both source rows, streaming hint: each
                // source row is visited once (step >= 2, disjoint bands),
                // so prefetched lines must not displace anything cached.
                // PRFM never faults, so no bounds check needed.
                __builtin_prefetch(row0 + left + x * step + 256, 0, 0);
                __builtin_prefetch(row1 + left + x * step + 256, 0, 0);
            }
            const int ql = left + x * step;
            uint8_t* px = outRow + x * 4;
            for (int c = 0; c < 4; ++c) {
                const uint16_t* row = (job.dy[c] == 0) ? row0 : row1;
                const int ch = job.ch[c];
                px[c] = normalizeInt(row[ql + job.dx[c]], job.blackQ[ch], job.denQ[ch]);
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
                px[c] = normalizeInt(code, job.blackQ[ch], job.denQ[ch]);
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
    jintArray channels, jintArray levels, jobject dstBuf, jint dstOffset) {
    if (!srcBuf || !dstBuf || !channels || !levels) return VF_CPU_BAD_ARGUMENT;
    if (env->GetArrayLength(channels) != 4 ||
        env->GetArrayLength(levels) != 8) {
        return VF_CPU_BAD_ARGUMENT;
    }
    if ((left & 1) != 0 || (top & 1) != 0 || step < 2 || (step & 1) != 0 ||
        width <= 0 || height <= 0 || width > VF_CPU_MAX_EDGE || height > VF_CPU_MAX_EDGE ||
        rowStride <= 0 || pixelStride <= 0 || srcOffset < 0 || dstOffset < 0) {
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
    jint q6[8];
    env->GetIntArrayRegion(channels, 0, 4, chOf);
    env->GetIntArrayRegion(levels, 0, 8, q6);
    if (env->ExceptionCheck()) return VF_CPU_BAD_ARGUMENT;
    for (int c = 0; c < 4; ++c) {
        if (chOf[c] < 0 || chOf[c] > 3) {
            return VF_CPU_BAD_ARGUMENT;
        }
    }
    // Q6 backstop (Kotlin computes the levels; this guards the ABI):
    // blackQ >= 0, denQ >= 1 (no division by zero) and <= MAX (no
    // num*255 overflow in 32 bit).
    for (int i = 0; i < 4; ++i) {
        if (q6[i] < 0 || q6[4 + i] <= 0 || q6[4 + i] > VF_Q6_MAX_DEN) {
            return VF_CPU_BAD_ARGUMENT;
        }
    }
    int dx[4], dy[4], ch[4];
    for (int c = 0; c < 4; ++c) {
        ch[c] = chOf[c];
        dx[c] = chOf[c] % 2;
        dy[c] = chOf[c] / 2;
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
        job.blackQ[i] = q6[i];
        job.denQ[i] = q6[4 + i];
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
