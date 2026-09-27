// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared internals for the GALOSH Vulkan host: context layout, kernel ids,
// and buffer helpers. The dispatch graph (galosh_run.cpp) is a faithful port
// of upstream standalone/vk/galosh_vk.c (Apache-2.0, see NOTICE.md).
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <vector>
#include <vulkan/vulkan.h>

#include "galosh_host.h"

struct GBuf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

uint32_t galosh_find_mem_type(VkPhysicalDevice pd, uint32_t bits, VkMemoryPropertyFlags want,
                              bool* ok);
bool galosh_make_buf(VkDevice dev, VkPhysicalDevice pd, VkDeviceSize size,
                     VkBufferUsageFlags use, VkMemoryPropertyFlags props, GBuf* out);
void galosh_free_buf(VkDevice dev, GBuf* b);

// Kernel ids. Order matches upstream galosh_vk.c.
enum GaloshKid {
    G_NE_STATS, G_NE_FIN, G_NE_DT_HIST, G_NE_DT_FIN, G_NE_DL_HIST, G_NE_D_FIN,
    G_LUT_BUILD, G_LUT_FIN, G_GAT_FWD, G_SIGMA_CFA, G_UNIFIED, G_NORMALIZE,
    G_DR_REDUCE, G_DR_FIN, G_DR_RREDUCE, G_DR_RFIN, G_DARK_SUB,
    G_FWD_L, G_CHROMA_EX, G_PASS12, G_PASS1_DUMP, G_P6_FUSED,
    G_BOX2, G_BOX2_3P, G_LOESS_T, G_CROP, G_K16, G_PAD, G_SMOOTH, G_INV,
    G_PASS12_W4, G_FASTUP,
    G_BOX2_H16, G_CROP_H16, G_LOESS_T_G16, G_K16_F16, G_FASTUP_F16,
    G_SIGMA_HIST, G_SIGMA_FIN,
    G_PAD3, G_K16_INV_F, G_FASTUP_INV_F,
    G_PASS12_SG,
    G_PASS2_ONLY,
    GALOSH_K_COUNT
};

struct GaloshKernSpec {
    const char* spv;
    int nbind;
    int push;  // push-constant bytes; 0 = none
    int sg32;  // needs subgroup-size-32 pinning
};

struct GaloshKern {
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
};

// One captured phase plane (f16 device buffers are widened at capture).
struct GaloshDump {
    const char* name = nullptr;
    int w = 0;
    int h = 0;
    std::vector<float> data;
};

struct GaloshContext {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties props{};
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    uint32_t instanceVersion = 0;
    bool storage16 = false;
    bool shaderF16 = false;
    bool sg_ok = false;  // subgroup-32 pinnable (o32_pass12_sg eligible)
    GaloshKern kerns[GALOSH_K_COUNT];
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    VkQueryPool qpool = VK_NULL_HANDLE;
    float band_rate_ms = 0.0f;  // learned pass12 ms per workgroup row
    double last_gpu_ms = 0.0;   // timestamp total of the last denoise run
    float last_alpha = 0.0f;    // SYNC#1 values of the last run
    float last_sigma_sq = 0.0f;
    bool dump_armed = false;             // capture phase planes on next run
    std::vector<GaloshDump> dumps;       // valid until next denoise/destroy
};

// Creates compute pipelines for every kernel: per kernel, loads the SPIR-V
// asset, creates module + layout + pipeline, then destroys the module, so at
// most one module is ever live (upstream pattern; the Mali r44 driver fails
// when dozens of modules are live at the first pipeline creation). Skips the
// SG kernel when the device cannot pin subgroup 32. Also creates the
// descriptor/query pools. assetMgr is AAssetManager*.
int galosh_init_pipelines(GaloshContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len);
void galosh_destroy_pipelines(GaloshContext* ctx);

// Single-kernel module + layout + pipeline creation (assetMgr is
// AAssetManager*).
int galosh_make_pipeline(GaloshContext* ctx, void* assetMgr, const char* asset, int nbind,
                         int pushBytes, int sg32, VkDescriptorSetLayout* dsl, VkPipelineLayout* pl,
                         VkPipeline* pipe, char* errmsg, size_t errmsg_len);
