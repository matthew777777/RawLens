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

enum { RAWDN_PACK, RAWDN_DOWN, RAWDN_DCT, RAWDN_RESID, RAWDN_FIN, RAWDN_SITES, RAWDN_UNPACK, RAWDN_COARSE, RAWDN_MIX, RAWDN_SHADER_COUNT };

typedef struct RawdnParams {
    float noiseA, noiseB;  // variance = A * x + B, x = (raw - black) / (white - black)
    float wb[3];           // camera WB gains R, G, B
    int cfa;               // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float black[4];        // per CFA site (0,0) (0,1) (1,0) (1,1)
    float white;
    float lambda;          // pilot hard threshold in sigmas, default 2.7
    float lumaFine, diffFine;  // Wiener multiplier at the highest frequency: luma, green difference (0.8, 0.5)
    float luma;            // Wiener noise multiplier for luma, default 1 (lower keeps more)
    float chroma;          // same for colour planes below half resolution, default 4
    float chromaFine;      // same for the half-resolution detail planes, default 1
    float coarseCut;       // 0..1 share of the coarse (above ~5 px) part left out of that grain, default 1 (night) .. 0 (clean day)
    float grain;           // 0..1 fraction of removed luma noise put back, default 0.55 (night) .. 0.9 (day)
    float chromaStrength;  // 0..1 share of the colour noise removed, default 1; 0 skips the colour planes
    int levels;            // pyramid levels, default 3
} RawdnParams;

void rawdn_default_params(RawdnParams* p);
// Automatic grain from the midtone SNR, scaled by texture, then by the user's luma strength (0..1): at 0 all removed
// luma comes back after the demosaic, so only colour noise is removed.
void rawdn_auto_luma(RawdnParams* p, float snr, float texture, float lumaStrength);
RawdnContext* rawdn_create(char* errmsg, int errlen);
// SPIR-V of shaders/rawdn_<name>.comp, in enum order. Returns 0 or -1.
int rawdn_load_shaders(RawdnContext* ctx, const uint32_t* const code[RAWDN_SHADER_COUNT],
                       const size_t bytes[RAWDN_SHADER_COUNT], char* errmsg, int errlen);
// in: h rows of inStride uint16 samples (w used); w, h even. Large frames run in strips. out: denoised CFA, linear = (v / 65535 - 1/16) * 16/15.
// Clipped sites pass through. luma: fp16 grain + detail plane, white-balanced linear; add luma / wb[c] to every channel after the demosaic.
int rawdn_process(RawdnContext* ctx, const uint16_t* in, size_t inStride, uint16_t* out, uint16_t* luma, int w, int h,
                  const RawdnParams* params, char* errmsg, int errlen);
typedef struct RawdnOutput RawdnOutput;
// rawdn_process without the copy out: the result stays in the GPU's host-visible buffer, read in place. out and luma
// have *stride samples per row (*stride >= w). Returns 0, 1 when the frame needs strips (use rawdn_process), -1 on error.
// The buffers stay valid until rawdn_release_output, from any thread, also after rawdn_destroy.
int rawdn_process_mapped(RawdnContext* ctx, const uint16_t* in, size_t inStride, int w, int h, const RawdnParams* params,
                         RawdnOutput** result, const uint16_t** out, const uint16_t** luma, size_t* stride,
                         char* errmsg, int errlen);
const uint16_t* rawdn_output_plane(const RawdnOutput* result, int which);  // 0 out, 1 luma
void rawdn_release_output(RawdnOutput* result);
void rawdn_trim(RawdnContext* ctx);  // releases the per-frame GPU buffers
const double* rawdn_stage_ms(RawdnContext* ctx, int* n);
double rawdn_last_gpu_ms(RawdnContext* ctx);
double rawdn_last_copy_ms(RawdnContext* ctx, int out);  // host copy into (0) / out of (1) the GPU buffers
void rawdn_destroy(RawdnContext* ctx);

// Blind Poisson-Gaussian estimate from green-pair differences of flat blocks.
// Returns the number of brightness bins used (0 = no estimate).
int rawdn_estimate_noise(const uint16_t* in, size_t stride, int w, int h, int cfa, const float black[4], float white,
                         float* a, float* b);
// Signal-to-noise ratio at the frame's log-average level (ISO 12232: 10 acceptable, 40 excellent).
float rawdn_midtone_snr(const uint16_t* in, size_t stride, int w, int h, const float black[4], float white, float a, float b);

#ifdef __cplusplus
}
#endif
