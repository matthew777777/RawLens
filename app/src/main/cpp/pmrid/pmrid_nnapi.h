// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// PMRID-style INT8 raw denoiser on the NPU: a hand-built NNAPI graph from pmrid.bin
// (tools/raw-denoise/pmrid/export_nnapi.py). Bayer CFA in, Bayer CFA out, in the encoding
// of rawdn (linear = (v / 65535 - 1/16) * 16/15). Only a dedicated accelerator is used:
// when none can run every op, creation fails and the caller keeps the Vulkan denoiser.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PmridContext PmridContext;

// blob: pmrid.bin. Compiles for a fixed tile (core + overlap): minutes on MDLA the first time, then loaded from cacheDir.
PmridContext* pmrid_create(const uint8_t* blob, size_t bytes, const char* cacheDir, char* errmsg, int errlen);
const char* pmrid_device(const PmridContext* ctx);
// One tile through the graph: u = tile*tile*4 uint8 NHWC (graph input scale), out the same size (graph output scale).
int pmrid_run_tile(PmridContext* ctx, const uint8_t* u, float sigma, uint8_t* out, char* errmsg, int errlen);
int pmrid_tile(const PmridContext* ctx);
// Whole frame: in = h rows of inStride uint16 samples, w and h even, cfa 0 RGGB 1 GRBG 2 GBRG 3 BGGR.
// Noise: variance = a * x + b with x = (raw - black) / (white - black). strength 0..1 blends toward the input.
int pmrid_process(PmridContext* ctx, const uint16_t* in, size_t inStride, uint16_t* out, int w, int h, int cfa,
                  const float black[4], float white, float a, float b, float strength, double* npuMs, char* errmsg, int errlen);
void pmrid_destroy(PmridContext* ctx);

#ifdef __cplusplus
}
#endif
