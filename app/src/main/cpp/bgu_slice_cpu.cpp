// SPDX-License-Identifier: GPL-3.0-or-later
//
// Step-7 CPU fallback: BGU slice on the CPU (scalar C++, auto-vectorized).
// Same math as the GL shader (BguSlice) and the Kotlin reference
// (BguSliceCpu.sliceScalar): guide merge, luma, trilinear grid lookup with
// GL CLAMP_TO_EDGE semantics, affine, clamp, optional IGN dither. Output is
// quad-layout (R, G, G, B) bytes feeding the identity-grid GL present.
//
// Only used when Vulkan is unavailable; the GPU path stays the default.
#include <jni.h>

#include <android/log.h>
#include <cmath>
#include <cstdint>

#define LOG_TAG "RawLensSliceCpu"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// GL LINEAR + CLAMP_TO_EDGE on one axis. Matches BguSliceCpu.clampAxis.
inline void clampAxis(float cell, int dim, int &i0, int &i1, float &f) {
    if (cell <= 0.0f || dim <= 1) {
        i0 = 0;
        i1 = 0;
        f = 0.0f;
        return;
    }
    if (cell >= (float)(dim - 1)) {
        i0 = dim - 1;
        i1 = dim - 1;
        f = 0.0f;
        return;
    }
    i0 = (int)cell;
    i1 = i0 + 1;
    f = cell - (float)i0;
}

// Interleaved gradient noise, same constants as the slice shader.
inline float ign(float px, float py, float ch) {
    float d = (px + ch * 19.19f) * 0.06711056f + (py + ch * 19.19f) * 0.00583715f;
    float f1 = d - floorf(d);
    float m = 52.9829189f * f1;
    return m - floorf(m);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_BguSliceCpu_sliceNative(
    JNIEnv *env, jobject, jobject guide, jint guideOffset, jint w, jint h,
    jfloatArray gridCoeffs, jint gw, jint gh, jint gz, jfloat scaleX, jfloat scaleY,
    jfloat scaleZ, jint dither, jobject out, jint outOffset, jint step) {
    if (guide == nullptr || out == nullptr || gridCoeffs == nullptr) return 1;
    if (w <= 0 || h <= 0 || gw <= 0 || gh <= 0 || gz <= 0) return 1;
    if (step != 1 && step != 2) return 1;
    if (w % step != 0 || h % step != 0) return 1;
    if (!(scaleX > 0.0f) || !(scaleY > 0.0f) || !(scaleZ > 0.0f)) return 1;
    uint8_t *gPtr = (uint8_t *)env->GetDirectBufferAddress(guide);
    uint8_t *oPtr = (uint8_t *)env->GetDirectBufferAddress(out);
    if (gPtr == nullptr || oPtr == nullptr) return 3;
    jlong gCap = env->GetDirectBufferCapacity(guide);
    jlong oCap = env->GetDirectBufferCapacity(out);
    const int ow = w / step;
    const int oh = h / step;
    if (guideOffset < 0 || outOffset < 0 || gCap - guideOffset < (int64_t)w * h * 4 ||
        oCap - outOffset < (int64_t)ow * oh * 4) {
        return 1;
    }
    if (env->GetArrayLength(gridCoeffs) != gw * gh * gz * 12) return 1;
    float *coeffs = (float *)env->GetPrimitiveArrayCritical(gridCoeffs, nullptr);
    if (coeffs == nullptr) return 1;

    const uint8_t *src = gPtr + guideOffset;
    uint8_t *dst = oPtr + outOffset;
    const int plane = gw * gh * gz;
    const bool doDither = dither != 0;
    for (int oy = 0; oy < oh; oy++) {
        const float cellBaseY = ((float)oy + 0.5f) / (float)oh * scaleY;
        const float py = (float)oy + 0.5f;
        for (int ox = 0; ox < ow; ox++) {
            const int gi = ((oy * step) * w + ox * step) * 4;
            const float gr = (float)src[gi] * (1.0f / 255.0f);
            const float gg = (float)(src[gi + 1] + src[gi + 2]) * 0.5f * (1.0f / 255.0f);
            const float gb = (float)src[gi + 3] * (1.0f / 255.0f);
            const float luma = clampf(0.25f * gr + 0.5f * gg + 0.25f * gb, 0.0f, 1.0f);
            const float cellX = ((float)ox + 0.5f) / (float)ow * scaleX;
            const float cellZ = luma * scaleZ;
            int x0, x1, y0, y1, z0, z1;
            float fx, fy, fz;
            clampAxis(cellX, gw, x0, x1, fx);
            clampAxis(cellBaseY, gh, y0, y1, fy);
            clampAxis(cellZ, gz, z0, z1, fz);
            float r0[4] = {0, 0, 0, 0};
            float r1[4] = {0, 0, 0, 0};
            float r2[4] = {0, 0, 0, 0};
            for (int dz = 0; dz < 2; dz++) {
                const float wz = dz == 0 ? 1.0f - fz : fz;
                if (wz == 0.0f) continue;
                const int cz = dz == 0 ? z0 : z1;
                for (int dy = 0; dy < 2; dy++) {
                    const float wy = (dy == 0 ? 1.0f - fy : fy) * wz;
                    if (wy == 0.0f) continue;
                    const int cy = dy == 0 ? y0 : y1;
                    for (int dx = 0; dx < 2; dx++) {
                        const float wt = (dx == 0 ? 1.0f - fx : fx) * wy;
                        if (wt == 0.0f) continue;
                        const int cx = dx == 0 ? x0 : x1;
                        const int base = cx + gw * (cy + gh * cz);
                        for (int k = 0; k < 4; k++) {
                            r0[k] += wt * coeffs[base + plane * k];
                            r1[k] += wt * coeffs[base + plane * (4 + k)];
                            r2[k] += wt * coeffs[base + plane * (8 + k)];
                        }
                    }
                }
            }
            float er = clampf(gr * r0[0] + gg * r0[1] + gb * r0[2] + r0[3], 0.0f, 1.0f);
            float eg = clampf(gr * r1[0] + gg * r1[1] + gb * r1[2] + r1[3], 0.0f, 1.0f);
            float eb = clampf(gr * r2[0] + gg * r2[1] + gb * r2[2] + r2[3], 0.0f, 1.0f);
            if (doDither) {
                const float px = (float)ox + 0.5f;
                er = clampf(er + (ign(px, py, 0.0f) - 0.5f) * (1.0f / 255.0f), 0.0f, 1.0f);
                eg = clampf(eg + (ign(px, py, 1.0f) - 0.5f) * (1.0f / 255.0f), 0.0f, 1.0f);
                eb = clampf(eb + (ign(px, py, 2.0f) - 0.5f) * (1.0f / 255.0f), 0.0f, 1.0f);
            }
            const int oi = (oy * ow + ox) * 4;
            dst[oi] = (uint8_t)(er * 255.0f + 0.5f);
            const uint8_t g8 = (uint8_t)(eg * 255.0f + 0.5f);
            dst[oi + 1] = g8;
            dst[oi + 2] = g8;
            dst[oi + 3] = (uint8_t)(eb * 255.0f + 0.5f);
        }
    }
    env->ReleasePrimitiveArrayCritical(gridCoeffs, coeffs, JNI_ABORT);
    return 0;
}
