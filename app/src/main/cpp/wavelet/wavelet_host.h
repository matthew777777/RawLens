// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// C host for the vendored upstream raw_denoise library (see ../assets UPSTREAM.md).
// Owns a private Vulkan compute device; a single blocking process() call
// uploads one pre-WB camera-linear RGBA16F frame, runs the tiled profiled
// wavelet pipeline, and downloads the result. JNI takes in/out
// ByteBuffers; single-threaded by contract.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct WaveletContext WaveletContext;

typedef struct WaveletParams {
    float strength;    // validated 1..2, up to 8; <=0 bypasses (caller skips)
    float shadows;     // shadow-preservation fulcrum, default 1
    float noiseA;      // normalized green variance slope
    float noiseB;      // normalized green variance offset
    float wb[3];       // camera WB multipliers, green == 1
    int maxScale;      // 1..7, default 7
    float forceY;      // luma force, default 0.25
    float forceUv;     // chroma force, default 0.75
    uint32_t tileSize;  // inner tile extent, default 1536
} WaveletParams;

struct AAssetManager;

WaveletContext* wavelet_create(char* errmsg, int errlen);
// Loads the 4 denoise_img_*.spv binaries from assets/spirv/wavelet.
// Returns the module count (>0) or -1 with errmsg set.
int wavelet_load_shaders(WaveletContext* ctx, struct AAssetManager* mgr, char* errmsg, int errlen);
// in/out: w*h*4 half floats (RGBA16F), distinct buffers. Returns 0 or -1.
int wavelet_process(WaveletContext* ctx, const uint8_t* inHalf, uint8_t* outHalf, int w, int h,
                    const WaveletParams* params, char* errmsg, int errlen);
// GPU milliseconds of the last process() call, or -1 when unavailable.
double wavelet_last_gpu_ms(WaveletContext* ctx);
void wavelet_destroy(WaveletContext* ctx);

#ifdef __cplusplus
}
#endif
