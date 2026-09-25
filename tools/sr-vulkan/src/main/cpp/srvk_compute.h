// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Unified Vulkan compute core for SR merging. THE SAME SOURCES build for
// Android (NDK) and desktop (Linux/macOS): no platform #ifdefs in the
// compute path except the loader linkage documented in CMakeLists.txt
// (direct MoltenVK link on macOS, libvulkan elsewhere, NDK vulkan on
// Android). All entry points are plain C (stable ABI for JNI on both
// platforms); errors return via human-readable messages, never codes alone.
//
// Threading: single-threaded (one context per thread). The merge
// orchestrators serialize GPU bursts already; fine-grained sharing comes
// with the dispatch turn, not here.
#ifndef SRVK_COMPUTE_H
#define SRVK_COMPUTE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SRVK_ERR_LEN 512

typedef struct SrvkContext SrvkContext;

/** Create instance + pick device + open compute queue. NULL on failure. */
SrvkContext* srvk_create(char* errmsg, size_t errmsg_len);

/** One-line `key=value;` device report for logs/CLI (always NUL-terminated). */
void srvk_device_info(SrvkContext* ctx, char* buf, size_t len);

/** Physical device enumeration for multi-GPU diagnosis (index < count). */
int srvk_device_count(SrvkContext* ctx);
void srvk_device_name(SrvkContext* ctx, int index, char* buf, size_t len);

/**
 * Create a shader module from SPIR-V words (validated magic, host-endian).
 * Returns a nonzero module handle, or 0 on failure.
 */
uint64_t srvk_load_module(SrvkContext* ctx, const uint32_t* words, size_t word_count,
                          char* errmsg, size_t errmsg_len);

void srvk_destroy_module(SrvkContext* ctx, uint64_t module);

/* ---- Resource layer: images, pipelines, synchronous dispatch ----
 * Mirrors the GLES texture/program/discharge discipline 1:1: NEAREST +
 * CLAMP_TO_EDGE sampling, one uniform block per pass, synchronous
 * per-slice dispatch with a 10s fence (the GL fence timeout).
 * All handles are context-local; single-threaded use only. */

typedef enum SrvkFormat {
    SRVK_R32F = 0,
    SRVK_R32UI = 1,
    SRVK_RGBA32F = 2,
    SRVK_R16UI = 3
} SrvkFormat;

typedef enum SrvkImageKind {
    SRVK_SAMPLED = 0,
    SRVK_STORAGE = 1
} SrvkImageKind;

typedef struct SrvkImage SrvkImage;
typedef struct SrvkPipeline SrvkPipeline;

uint64_t srvk_create_image(SrvkContext* ctx, int width, int height, int format,
                           char* errmsg, size_t errmsg_len);
/** Tightly packed rows, native endianness; len must equal w*h*texel. 0 ok, -1 error. */
int srvk_write_image(SrvkContext* ctx, uint64_t image, const void* bytes, size_t len,
                     char* errmsg, size_t errmsg_len);
int srvk_read_image(SrvkContext* ctx, uint64_t image, void* out, size_t len,
                    char* errmsg, size_t errmsg_len);
/** Banded readback (phone DNG strip path): rows [y, y+h) x cols [x, x+w),
 * tightly packed; len must equal w*h*texel. 0 ok, -1 error. */
int srvk_read_image_region(SrvkContext* ctx, uint64_t image, int x, int y, int w, int h,
                           void* out, size_t len, char* errmsg, size_t errmsg_len);
void srvk_destroy_image(SrvkContext* ctx, uint64_t image);

uint64_t srvk_create_pipeline(SrvkContext* ctx, uint64_t module, int ubo_binding, size_t ubo_size,
                              int image_count, const int* image_bindings, const int* image_kinds,
                              char* errmsg, size_t errmsg_len);
int srvk_bind_image(SrvkContext* ctx, uint64_t pipeline, int binding, uint64_t image,
                    char* errmsg, size_t errmsg_len);
int srvk_write_uniforms(SrvkContext* ctx, uint64_t pipeline, const void* bytes, size_t len,
                        char* errmsg, size_t errmsg_len);
/** Synchronous dispatch: record, submit, 10s fence wait. Returns 0 ok, -1 error. */
int srvk_dispatch(SrvkContext* ctx, uint64_t pipeline, int gx, int gy, int gz,
                  char* errmsg, size_t errmsg_len);
void srvk_destroy_pipeline(SrvkContext* ctx, uint64_t pipeline);

void srvk_destroy(SrvkContext* ctx);

#ifdef __cplusplus
}
#endif

#endif // SRVK_COMPUTE_H
