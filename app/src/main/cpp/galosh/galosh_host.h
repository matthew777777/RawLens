// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// GALOSH raw-denoise Vulkan host (Phase 1 scaffold): one-shot capability
// probe plus context create / SPIR-V shader-module load / destroy. The
// 51-dispatch denoise pipeline lands in Phase 2 behind this context.
#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct GaloshContext GaloshContext;

// One-shot probe: creates a throwaway instance, reports every physical
// device as JSON, tears down. Returns 0 on success.
int galosh_probe_caps(char* json, size_t json_len);

// Number of SPIR-V kernels in the embedded raw-path manifest.
int galosh_shader_count(void);
// Manifest entry i (0-based), e.g. "o32_pass12". NULL when out of range.
const char* galosh_shader_name(int i);

// Creates instance + device + compute queue + pool. Float16/16-bit-storage
// device features follow device support and are recorded in the context.
GaloshContext* galosh_create(char* errmsg, size_t errmsg_len);
// Loads every manifest shader from "spirv/galosh/<name>.spv" via assetMgr
// (AAssetManager*). Returns the loaded count, or -1 with errmsg set.
int galosh_load_shaders(GaloshContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len);
int galosh_loaded_shader_count(const GaloshContext* ctx);
void galosh_destroy(GaloshContext* ctx);

// Denoise parameters. Mirrors the upstream CLI positional args + flags;
// noise fitting is always per-frame blind (fit) in this port.
typedef struct GaloshParams {
    float strength;    // master strength, default 1
    float luma_str;    // luma strength multiplier, default 1
    float chroma_str;  // chroma strength multiplier, default 1
    float alpha_ext;   // >0 with sigma_ext: external noise model override
    float sigma_ext;   // (0,0 = fully blind fit)
    int wht_block;     // 8 (quality) or 4 (fast luma)
    int upsample_fast; // 0 = jinc (quality), 1 = fast (neutral)
    int allow_sg;      // 0 forces the classic pass12 even when SG32 is available
    int phase_stride;  // pass12 phase stride, default 1
} GaloshParams;

void galosh_default_params(GaloshParams* p);

// Full o32 pipeline: single-channel Bayer float32 in [0,1], row-major, even
// W/H. Returns 0 on success. Single-threaded per context.
int galosh_denoise(GaloshContext* ctx, const float* in, float* out, int W, int H,
                   const GaloshParams* params, char* errmsg, size_t errmsg_len);

// Timestamp-derived GPU time (ms) of the last galosh_denoise run.
double galosh_last_gpu_ms(const GaloshContext* ctx);
// Blind-fit (alpha, sigma_sq) of the last run (SYNC#1 values).
float galosh_last_alpha(const GaloshContext* ctx);
float galosh_last_sigma_sq(const GaloshContext* ctx);

// Phase-plane capture for CPU cross-verification (names mirror the upstream
// GALOSH_DUMP_DIR files: p2_in_gat, p3_L_cs, p4_C1_h, ...). Arm before
// galosh_denoise; planes are valid until the next denoise/destroy.
void galosh_set_dump(GaloshContext* ctx, int enable);
int galosh_dump_count(const GaloshContext* ctx);
const char* galosh_dump_name(const GaloshContext* ctx, int i);
// W/H of plane i (0 on out-of-range), data as float (f16 widened at capture).
int galosh_dump_dims(const GaloshContext* ctx, int i, int* w, int* h);
const float* galosh_dump_data(const GaloshContext* ctx, int i, int* n);

#ifdef __cplusplus
}  // extern "C"
#endif
