// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// RawLens raw-domain denoiser: Bayer CFA in, Bayer CFA out (Vulkan compute).
// Variance-stabilised, white-balance-equalised opponent planes at half
// resolution; multiscale two-stage sliding 8x8 DCT (hard-threshold pilot,
// empirical Wiener) with adaptive aggregation; exact unbiased inverse.
// Owns a private Vulkan device; single-threaded by contract.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RawdnContext RawdnContext;

enum { RAWDN_PACK, RAWDN_DOWN, RAWDN_DCT, RAWDN_RESID, RAWDN_FIN, RAWDN_UNPACK, RAWDN_SHADER_COUNT };

typedef struct RawdnParams {
    float noiseA, noiseB;  // variance = A * x + B, x = (raw - black) / (white - black)
    float wb[3];           // camera WB gains R, G, B
    int cfa;               // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float black[4];        // per CFA site (0,0) (0,1) (1,0) (1,1)
    float white;
    float lambda;          // pilot hard threshold in sigmas, default 2.7
    float luma;            // Wiener noise multiplier for luma, default 1 (lower keeps more)
    float chroma;          // same for colour planes below half resolution, default 3
    float chromaFine;      // same for the half-resolution detail planes, default 1
    float grain;           // 0..1 fraction of removed luma noise put back, default 0.35
    int levels;            // pyramid levels, default 4
} RawdnParams;

void rawdn_default_params(RawdnParams* p);
RawdnContext* rawdn_create(char* errmsg, int errlen);
// SPIR-V of shaders/rawdn_<name>.comp, in enum order. Returns 0 or -1.
int rawdn_load_shaders(RawdnContext* ctx, const uint32_t* const code[RAWDN_SHADER_COUNT],
                       const size_t bytes[RAWDN_SHADER_COUNT], char* errmsg, int errlen);
// in/out: w*h uint16 samples, row-major, may alias. w and h must be even.
int rawdn_process(RawdnContext* ctx, const uint16_t* in, uint16_t* out, int w, int h,
                  const RawdnParams* params, char* errmsg, int errlen);
double rawdn_last_gpu_ms(RawdnContext* ctx);
void rawdn_destroy(RawdnContext* ctx);

// Blind Poisson-Gaussian estimate from green-pair differences of flat blocks.
// Returns the number of brightness bins used (0 = no estimate).
int rawdn_estimate_noise(const uint16_t* in, int w, int h, int cfa, const float black[4], float white,
                         float* a, float* b);


// Noise for one capture: the learned per-camera model (wavelet/noise_model.h) as the prior and
// this frame's blind estimate as evidence. The Camera2/DNG noise profile is never used.
// storePath: sample file for this camera key, raw mode and firmware; it must be a store of
// rawdn's own (raw-domain estimates), not the wavelet stage's. Null/empty or iso <= 0: the
// blind estimate alone. A usable estimate is appended to the store (once per captureId).
typedef struct RawdnNoise {
    float a, b;            // chosen model
    float estA, estB;      // this frame's blind estimate (0 when it failed)
    int estBins;           // brightness bins behind the estimate (0 = failed)
    int source;            // 0 none, 1 blind estimate, 2 learned model (+ estimate as evidence)
    float estWeight;       // weight the estimate got against the model (source 2)
    int modelSamples, modelIsos;
} RawdnNoise;
// Returns 0, or -1 when neither a model nor an estimate is available.
int rawdn_choose_noise(const uint16_t* in, int w, int h, int cfa, const float black[4], float white,
                       float iso, float maxAnalogIso, uint64_t captureId, const char* storePath,
                       RawdnNoise* out);

#ifdef __cplusplus
}
#endif
