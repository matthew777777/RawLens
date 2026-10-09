// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// HDR+ burst-merge Vulkan host: context create / SPIR-V load / merge /
// destroy. The 28 compute kernels are RAWR's merge_hdrplus shaders vendored
// byte-identical (see assets spirv/hdrplus/UPSTREAM.md); the dispatch
// sequence in hdrplus_run.cpp is a 1:1 translation of RAWR's
// HdrPlusRecorder.cpp + the runHdrPlus/runHdrPlusFrequency drive loops.
// This host (instance/device/queue, allocation, descriptors, submit) is
// RawLens's own compact equivalent of RAWR's ResourceArena/VulkanExecutor:
// shader-visible behavior (bindings, push constants, dispatch sizes,
// barriers, pass order) is identical, so output bits match.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct HdrPlusContext HdrPlusContext;

// Number of SPIR-V kernels (28: 15 hdrp_* spatial + 13 hdrq_* frequency).
int hdrplus_shader_count(void);
// Manifest entry i (0-based), e.g. "hdrp_prepare". NULL when out of range.
const char* hdrplus_shader_name(int i);

// Creates instance + device + compute queue + command pool.
HdrPlusContext* hdrplus_create(char* errmsg, size_t errmsg_len);
// Persistence path for the process pipeline cache (e.g. the app cache
// dir's hdrplus.bin). Call before hdrplus_load_shaders for a warm start;
// null/empty keeps an in-memory-only cache.
void hdrplus_set_pipeline_cache_path(HdrPlusContext* ctx, const char* path);
// Loads every manifest shader from "spirv/hdrplus/<name>.spv" via assetMgr
// (AAssetManager* on Android, base-dir path as const char* on desktop)
// and creates the compute pipelines. Returns the loaded count, or -1
// with errmsg set.
int hdrplus_load_shaders(HdrPlusContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len);
int hdrplus_loaded_shader_count(const HdrPlusContext* ctx);
void hdrplus_destroy(HdrPlusContext* ctx);

// Merge parameters. Mirrors rawr::raw_merge_hdrplus_gpu::Config plus the
// spatial/frequency algorithm switch.
typedef struct HdrPlusParams {
    float strength;       // upstream "noise reduction" slider, 1..22 (default 13)
    uint32_t tile_size;   // 16 or 32 (upstream 64 unsupported, same as RAWR)
    uint32_t search_distance;  // 32, 64 or 128 (default 64)
    int high_quality;     // 0 = spatial ("Fast"), nonzero = frequency ("Higher quality")
    int frequency_align_once;  // nonzero = align once (RAWR default); 0 = upstream-exact per-pass
    // Optional per-frame strengths (RawLens extension, step 4): one float per
    // input frame, each 1..22 like strength; the reference entry is validated
    // but unused (the reference adds unweighted). NULL (default) merges every
    // companion at strength. The merge-weight push constants are already
    // dispatched per companion, so a uniform array records bit-identical
    // command buffers to NULL (no shader change, parity intact).
    const float* frame_strengths;  // NULL or frame_count floats, 1..22 each
    // Optional per-frame strength maps (RawLens extension, step-4 maps,
    // both paths): N concatenated row-major mw*mh 1..22 strength maps
    // (mw/mh from hdrplus_strength_map_cells), the reference slice
    // validated but unused. NULL (default) keeps the upstream merge
    // path. A present map supersedes frame/global strength per block
    // (Fast: robustness map; HQ: Wiener-norm triples).
    const float* strength_maps;  // NULL or N*mw*mh floats, 1..22 each
} HdrPlusParams;

// Strength-map grid (step-4 maps): one strength per HDRPLUS_MAP_BLOCK_PX
// block covering the unpadded frame. Must match
// HdrPlusAutoTuning.MAP_BLOCK_PX and the shader's q>>4 lookup.
#define HDRPLUS_MAP_BLOCK_PX 32
static inline void hdrplus_strength_map_cells(int width, int height, int* mw, int* mh) {
    if (mw != 0) *mw = (width + HDRPLUS_MAP_BLOCK_PX - 1) / HDRPLUS_MAP_BLOCK_PX;
    if (mh != 0) *mh = (height + HDRPLUS_MAP_BLOCK_PX - 1) / HDRPLUS_MAP_BLOCK_PX;
}

void hdrplus_default_params(HdrPlusParams* p);

// Full burst merge. Frames are W*H uint16 RAW samples each (row-major,
// even W/H, 2..64 frames); black_by_phase holds 4 floats per frame in
// (y&1)*2+(x&1) order with phase 0 at the frame's (0,0); white holds one
// float per frame. hot_pixels is an optional flat [x0,y0,...] int32 list
// (may be NULL with hot_pixel_count 0, skipping concealment like RAWR).
// Output is the merged normalized CFA (W*H floats, same geometry as the
// inputs). Returns 0 on success. Single-threaded per context.
int hdrplus_merge(HdrPlusContext* ctx, const uint16_t* const* frames, const float* black_by_phase,
                  const float* white, int frame_count, int width, int height, int ref_index,
                  const int32_t* hot_pixels, int hot_pixel_count, const HdrPlusParams* params, float* out,
                  char* errmsg, size_t errmsg_len);

// Timestamp-derived GPU time (ms) of the last hdrplus_merge run.
double hdrplus_last_gpu_ms(const HdrPlusContext* ctx);

#ifdef __cplusplus
}  // extern "C"
#endif
