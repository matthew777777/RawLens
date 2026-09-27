// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// GALOSH o32 Vulkan dispatch graph. Faithful port of upstream
// standalone/vk/galosh_vk.c (Apache-2.0, see NOTICE.md): same buffers,
// descriptor sets, push constants, barriers, banded Phase 5, and the two
// host sync points. Adaptations for Android: buffer-based I/O instead of
// files, per-call arenas instead of process-lifetime buffers, errmsg
// returns instead of exit(), no dump/LUT-cache/video-state paths.
// Mali deviations (see docs/galosh-phase2-parity.md): split Phase 5
// (pass1dump -> pilot -> pass2only) on the classic path; LUT built by the
// multiplicative-recurrence o32_build_inv_lut.
#include <android/asset_manager.h>
#include <android/log.h>
#include <deque>
#include <math.h>
#include <new>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <vector>
#include <vulkan/vulkan.h>

#include "galosh_host.h"
#include "galosh_internal.h"

#define G_PARAMS_SIZE 32
#define G_GAT_LUT_SIZE 4096
#define G_N_REDUCE_WG 64
#define G_O32_TILE 28
// Band pacing: pieces target ~120ms (watchdog-safe with margin) with an
// 8ms host yield between pieces so the viewfinder gets GPU slices during
// full-res runs (unpaced, a 12MP Phase 5 hogs the queue ~40s straight).
#define G_BAND_BUDGET_MS 120.0
#define G_BAND_YIELD_MS 8u

static long long galosh_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void galosh_yield_ms(unsigned ms) {
    struct timespec ts;
    ts.tv_sec = (time_t)(ms / 1000u);
    ts.tv_nsec = (long)(ms % 1000u) * 1000000L;
    nanosleep(&ts, nullptr);
}
#define G_P_S_SCALE 10
#define G_P_LUMA_STR 11
#define G_P_CHROMA_STR 12
#define G_P_ALPHA 13
#define G_P_SIGMA_SQ 14
#define G_P_DARK_THRESH 15

#define G_AUP(n, a) ((((n) + (a) - 1) / (a)))

static const GaloshKernSpec kSpecs[GALOSH_K_COUNT] = {
    {"o32_ne_block_stats", 3, 20, 0},
    {"o32_ne_finalize", 4, 12, 0},
    {"o32_ne_dark_thresh_hist", 2, 8, 0},
    {"o32_ne_dark_thresh_finalize", 2, 4, 0},
    {"o32_ne_dark_lap_hist", 3, 12, 0},
    {"o32_ne_dark_finalize", 2, 4, 0},
    {"o32_build_inv_lut", 4, 0, 0},
    {"o32_lut_finalize", 2, 0, 0},
    {"o32_gat_forward_full", 7, 8, 0},
    {"o32_sigma_per_cfa", 2, 8, 0},
    {"o32_unified_sigma", 1, 0, 0},
    {"o32_normalize_apply", 6, 8, 0},
    {"o32_dark_ref_reduce_mwg", 4, 8, 0},
    {"o32_dark_ref_finalize_mwg", 2, 4, 0},
    {"o32_dark_resid_reduce_mwg", 4, 8, 0},
    {"o32_dark_resid_finalize_mwg", 2, 12, 0},
    {"o32_dark_sub_full", 6, 8, 0},
    {"o32_forward_l_stride1", 2, 8, 0},
    {"o32_chroma_extract_halfres", 4, 16, 0},
    {"o32_pass12", 2, 16, 0},
    {"o32_pass1_dump", 2, 16, 0},
    {"o32_lpixel_lh_den_fused", 3, 12, 0},
    {"o32_box_downsample_2x", 2, 8, 0},
    {"o32_box_downsample_2x_3p", 6, 8, 0},
    {"o32_loess_chroma_3p_tiled", 7, 12, 0},
    {"o32_crop_2d_topleft", 2, 16, 0},
    {"o32_k16_jbu_3p", 7, 12, 0},
    {"o32_pad_2d_edge", 2, 16, 0},
    {"o32_smoothstep_blend_3p", 15, 12, 0},
    {"o32_inverse_wht_dark_gat", 9, 8, 0},
    {"o32_pass12_wht4", 2, 16, 0},
    {"o32_fastup_3p", 7, 12, 0},
    {"o32_box_downsample_2x_h16", 2, 8, 0},
    {"o32_crop_2d_topleft_h16", 2, 16, 0},
    {"o32_loess_chroma_3p_tiled_g16", 7, 12, 0},
    {"o32_k16_jbu_3p_f16", 7, 12, 0},
    {"o32_fastup_3p_f16", 7, 12, 0},
    {"o32_sigma_hist_mwg", 2, 12, 0},
    {"o32_sigma_fin_mwg", 2, 0, 0},
    {"o32_pad_2d_edge_3p", 6, 16, 0},
    {"o32_k16_inverse_fused", 9, 12, 0},
    {"o32_fastup_inverse_fused", 9, 12, 0},
    {"o32_pass12_sg", 2, 16, 1},
    {"o32_pass2_only", 3, 16, 0},
};
static_assert(sizeof(kSpecs) / sizeof(kSpecs[0]) == GALOSH_K_COUNT,
              "spec table must cover every kernel id");

int galosh_shader_count(void) { return GALOSH_K_COUNT; }

const char* galosh_shader_name(int i) {
    if (i < 0 || i >= GALOSH_K_COUNT) return nullptr;
    return kSpecs[i].spv;
}

int galosh_make_pipeline(GaloshContext* ctx, void* assetMgr, const char* asset, int nbind,
                         int pushBytes, int sg32, VkDescriptorSetLayout* dsl, VkPipelineLayout* pl,
                         VkPipeline* pipe, char* errmsg, size_t errmsg_len) {
    if (ctx == nullptr || assetMgr == nullptr || asset == nullptr || dsl == nullptr ||
        pl == nullptr || pipe == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0) snprintf(errmsg, errmsg_len, "null pipeline args");
        return -1;
    }
    *dsl = VK_NULL_HANDLE;
    *pl = VK_NULL_HANDLE;
    *pipe = VK_NULL_HANDLE;
    AAssetManager* mgr = (AAssetManager*)assetMgr;
    AAsset* a = AAssetManager_open(mgr, asset, AASSET_MODE_BUFFER);
    if (a == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "asset missing: %s", asset);
        return -1;
    }
    const off_t alen = AAsset_getLength(a);
    const void* data = AAsset_getBuffer(a);
    if (data == nullptr || alen <= 0 || alen % 4 != 0) {
        AAsset_close(a);
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "bad asset %s (len %lld)", asset, (long long)alen);
        return -1;
    }
    std::vector<unsigned char> blob((size_t)alen);
    memcpy(blob.data(), data, (size_t)alen);
    AAsset_close(a);
    VkShaderModuleCreateInfo mi{};
    mi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    mi.codeSize = blob.size();
    mi.pCode = (const uint32_t*)blob.data();
    VkShaderModule sm = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &mi, nullptr, &sm) != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "vkCreateShaderModule %s failed", asset);
        return -1;
    }
    VkDescriptorSetLayoutBinding binds[16];
    for (int b = 0; b < nbind; b++) {
        binds[b].binding = (uint32_t)b;
        binds[b].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        binds[b].descriptorCount = 1;
        binds[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        binds[b].pImmutableSamplers = nullptr;
    }
    VkDescriptorSetLayoutCreateInfo dsli{};
    dsli.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dsli.bindingCount = (uint32_t)nbind;
    dsli.pBindings = binds;
    if (vkCreateDescriptorSetLayout(ctx->device, &dsli, nullptr, dsl) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, sm, nullptr);
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "dsl failed for %s", asset);
        return -1;
    }
    VkPushConstantRange pcr{};
    pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcr.offset = 0;
    pcr.size = (uint32_t)(pushBytes ? pushBytes : 4);
    VkPipelineLayoutCreateInfo pli{};
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1;
    pli.pSetLayouts = dsl;
    pli.pushConstantRangeCount = pushBytes ? 1u : 0u;
    pli.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(ctx->device, &pli, nullptr, pl) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, *dsl, nullptr);
        *dsl = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, sm, nullptr);
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "pipeline layout failed for %s", asset);
        return -1;
    }
    VkComputePipelineCreateInfo cpi{};
    cpi.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    // DISPATCH_BASE on every pipeline: enables the watchdog-safe banded
    // pass12 via vkCmdDispatchBase (harmless for plain dispatch).
    cpi.flags = VK_PIPELINE_CREATE_DISPATCH_BASE_BIT;
    cpi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpi.stage.module = sm;
    cpi.stage.pName = "main";
    cpi.layout = *pl;
    VkPipelineShaderStageRequiredSubgroupSizeCreateInfoEXT rss{};
    if (sg32) {
        rss.sType =
            VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO_EXT;
        rss.requiredSubgroupSize = 32;
        cpi.stage.pNext = &rss;
        cpi.stage.flags = VK_PIPELINE_SHADER_STAGE_CREATE_REQUIRE_FULL_SUBGROUPS_BIT_EXT;
    }
    if (vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &cpi, nullptr, pipe) !=
        VK_SUCCESS) {
        vkDestroyPipelineLayout(ctx->device, *pl, nullptr);
        *pl = VK_NULL_HANDLE;
        vkDestroyDescriptorSetLayout(ctx->device, *dsl, nullptr);
        *dsl = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, sm, nullptr);
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "pipeline failed for %s", asset);
        return -1;
    }
    // Upstream pattern: at most one module live at a time.
    vkDestroyShaderModule(ctx->device, sm, nullptr);
    return 0;
}

int galosh_init_pipelines(GaloshContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len) {
    if (ctx == nullptr || assetMgr == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "null context or asset manager");
        return -1;
    }
    for (int i = 0; i < GALOSH_K_COUNT; i++) {
        const GaloshKernSpec* spec = &kSpecs[i];
        GaloshKern* k = &ctx->kerns[i];
        if (spec->sg32 && !ctx->sg_ok) continue;  // SG kernel needs the ext
        char path[128];
        snprintf(path, sizeof(path), "spirv/galosh/%s.spv", spec->spv);
        if (galosh_make_pipeline(ctx, assetMgr, path, spec->nbind, spec->push, spec->sg32,
                                 &k->dsl, &k->pl, &k->pipe, errmsg, errmsg_len) != 0) {
            return -1;
        }
        __android_log_print(ANDROID_LOG_INFO, "galosh", "pipeline %s ok", spec->spv);
    }
    VkDescriptorPoolSize dps{};
    dps.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    dps.descriptorCount = 512;
    VkDescriptorPoolCreateInfo dpi{};
    dpi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpi.maxSets = 96;
    dpi.poolSizeCount = 1;
    dpi.pPoolSizes = &dps;
    if (vkCreateDescriptorPool(ctx->device, &dpi, nullptr, &ctx->dpool) != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "descriptor pool failed");
        return -1;
    }
    VkQueryPoolCreateInfo qpci{};
    qpci.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    qpci.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpci.queryCount = 256;
    if (vkCreateQueryPool(ctx->device, &qpci, nullptr, &ctx->qpool) != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "query pool failed");
        return -1;
    }
    return 0;
}

void galosh_destroy_pipelines(GaloshContext* ctx) {
    if (ctx == nullptr || ctx->device == VK_NULL_HANDLE) return;
    if (ctx->qpool != VK_NULL_HANDLE) vkDestroyQueryPool(ctx->device, ctx->qpool, nullptr);
    if (ctx->dpool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx->device, ctx->dpool, nullptr);
    for (int i = 0; i < GALOSH_K_COUNT; i++) {
        GaloshKern* k = &ctx->kerns[i];
        if (k->pipe != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, k->pipe, nullptr);
        if (k->pl != VK_NULL_HANDLE) vkDestroyPipelineLayout(ctx->device, k->pl, nullptr);
        if (k->dsl != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx->device, k->dsl, nullptr);
        *k = GaloshKern{};
    }
    ctx->qpool = VK_NULL_HANDLE;
    ctx->dpool = VK_NULL_HANDLE;
}

void galosh_default_params(GaloshParams* p) {
    if (p == nullptr) return;
    p->strength = 1.0f;
    p->luma_str = 1.0f;
    p->chroma_str = 1.0f;
    p->alpha_ext = 0.0f;
    p->sigma_ext = 0.0f;
    p->wht_block = 8;
    p->upsample_fast = 0;
    p->allow_sg = 1;
    p->phase_stride = 1;
}

double galosh_last_gpu_ms(const GaloshContext* ctx) {
    return ctx != nullptr ? ctx->last_gpu_ms : 0.0;
}

float galosh_last_alpha(const GaloshContext* ctx) {
    return ctx != nullptr ? ctx->last_alpha : 0.0f;
}

float galosh_last_sigma_sq(const GaloshContext* ctx) {
    return ctx != nullptr ? ctx->last_sigma_sq : 0.0f;
}

void galosh_set_dump(GaloshContext* ctx, int enable) {
    if (ctx != nullptr) ctx->dump_armed = enable != 0;
}

int galosh_dump_count(const GaloshContext* ctx) {
    if (ctx == nullptr) return 0;
    return (int)ctx->dumps.size();
}

const char* galosh_dump_name(const GaloshContext* ctx, int i) {
    if (ctx == nullptr || i < 0 || (size_t)i >= ctx->dumps.size()) return nullptr;
    return ctx->dumps[(size_t)i].name;
}

int galosh_dump_dims(const GaloshContext* ctx, int i, int* w, int* h) {
    if (ctx == nullptr || i < 0 || (size_t)i >= ctx->dumps.size()) return 0;
    if (w != nullptr) *w = ctx->dumps[(size_t)i].w;
    if (h != nullptr) *h = ctx->dumps[(size_t)i].h;
    return 1;
}

const float* galosh_dump_data(const GaloshContext* ctx, int i, int* n) {
    if (ctx == nullptr || i < 0 || (size_t)i >= ctx->dumps.size()) return nullptr;
    const GaloshDump& d = ctx->dumps[(size_t)i];
    if (n != nullptr) *n = (int)d.data.size();
    return d.data.data();
}

// ---- per-run state ----
struct GaloshRun {
    GaloshContext* ctx = nullptr;
    GBuf stg;
    void* stg_map = nullptr;
    uint32_t ts_n = 0;
    uint32_t dispatch_n = 0;
    char* errmsg = nullptr;
    size_t errmsg_len = 0;
    bool ok = true;
    void fail(const char* what, int code) {
        if (ok && errmsg != nullptr && errmsg_len > 0) {
            if (code != 0)
                snprintf(errmsg, errmsg_len, "%s: %d", what, code);
            else
                snprintf(errmsg, errmsg_len, "%s", what);
        }
        ok = false;
    }
};

struct RunArena {
    VkDevice dev = VK_NULL_HANDLE;
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    // deque: push_back never invalidates element pointers (add() hands out
    // GBuf* that must stay valid across later adds; vector would dangle).
    std::deque<GBuf> bufs;
    GBuf* add(GaloshRun* r, VkDeviceSize size, const char* what) {
        GBuf b;
        if (!galosh_make_buf(dev, pd, size,
                             VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                                 VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &b)) {
            char msg[128];
            snprintf(msg, sizeof(msg), "alloc %s (%llu B)", what, (unsigned long long)size);
            r->fail(msg, 0);
            return nullptr;
        }
        bufs.push_back(b);
        return &bufs.back();
    }
    ~RunArena() {
        for (size_t i = 0; i < bufs.size(); i++) galosh_free_buf(dev, &bufs[i]);
    }
};

typedef union {
    int32_t i;
    float f;
} PcW;

static VkCommandBuffer run_cb_begin(GaloshRun* r) {
    VkCommandBufferAllocateInfo a{};
    a.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    a.commandPool = r->ctx->pool;
    a.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    a.commandBufferCount = 1;
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(r->ctx->device, &a, &cb) != VK_SUCCESS) {
        r->fail("alloc command buffer", 0);
        return VK_NULL_HANDLE;
    }
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) {
        r->fail("begin command buffer", 0);
        return VK_NULL_HANDLE;
    }
    return cb;
}

static void run_cb_submit_wait(GaloshRun* r, VkCommandBuffer cb) {
    if (cb == VK_NULL_HANDLE) {
        r->fail("submit null command buffer", 0);
        return;
    }
    if (vkEndCommandBuffer(cb) != VK_SUCCESS) {
        r->fail("end command buffer", 0);
        return;
    }
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    if (vkQueueSubmit(r->ctx->queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) {
        r->fail("queue submit", 0);
        return;
    }
    if (vkQueueWaitIdle(r->ctx->queue) != VK_SUCCESS) r->fail("queue wait idle", 0);
    vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &cb);
}

static void run_barrier(VkCommandBuffer cb) {
    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                       VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cb,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         1, &mb, 0, nullptr, 0, nullptr);
}

static VkDescriptorSet run_mkset(GaloshRun* r, int kid, GBuf* const bufs[], int n) {
    if (n != kSpecs[kid].nbind) {
        char msg[128];
        snprintf(msg, sizeof(msg), "mkset %s: %d bufs != %d binds", kSpecs[kid].spv, n,
                 kSpecs[kid].nbind);
        r->fail(msg, 0);
        return VK_NULL_HANDLE;
    }
    VkDescriptorSetAllocateInfo a{};
    a.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    a.descriptorPool = r->ctx->dpool;
    a.descriptorSetCount = 1;
    a.pSetLayouts = &r->ctx->kerns[kid].dsl;
    VkDescriptorSet ds = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(r->ctx->device, &a, &ds) != VK_SUCCESS) {
        r->fail("alloc descriptor set", 0);
        return VK_NULL_HANDLE;
    }
    VkDescriptorBufferInfo bi[16];
    VkWriteDescriptorSet wr[16];
    for (int i = 0; i < n; i++) {
        bi[i].buffer = bufs[i]->buf;
        bi[i].offset = 0;
        bi[i].range = VK_WHOLE_SIZE;
        wr[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr[i].pNext = nullptr;
        wr[i].dstSet = ds;
        wr[i].dstBinding = (uint32_t)i;
        wr[i].dstArrayElement = 0;
        wr[i].descriptorCount = 1;
        wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        wr[i].pImageInfo = nullptr;
        wr[i].pBufferInfo = &bi[i];
        wr[i].pTexelBufferView = nullptr;
    }
    vkUpdateDescriptorSets(r->ctx->device, (uint32_t)n, wr, 0, nullptr);
    return ds;
}

static void run_dispatch(GaloshRun* r, VkCommandBuffer cb, int kid, VkDescriptorSet ds,
                         const PcW* pc, int npc, uint32_t gx, uint32_t gy, uint32_t gz) {
    r->dispatch_n++;
    GaloshKern* k = &r->ctx->kerns[kid];
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, k->pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, k->pl, 0, 1, &ds, 0, nullptr);
    if (npc) vkCmdPushConstants(cb, k->pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, (uint32_t)(npc * 4), pc);
    if (r->ts_n < 255)
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, r->ctx->qpool, r->ts_n);
    vkCmdDispatch(cb, gx, gy, gz);
    if (r->ts_n < 255) {
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, r->ctx->qpool, r->ts_n + 1);
        r->ts_n += 2;
    }
    run_barrier(cb);
}

static VkCommandBuffer run_band_piece(GaloshRun* r, int kid, VkDescriptorSet ds, const PcW* pc,
                                     int npc, uint32_t gx, uint32_t y0, uint32_t rows, int ts_open,
                                     int ts_close) {
    GaloshKern* k = &r->ctx->kerns[kid];
    VkCommandBuffer cb = run_cb_begin(r);
    if (cb == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, k->pipe);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, k->pl, 0, 1, &ds, 0, nullptr);
    if (npc) vkCmdPushConstants(cb, k->pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, (uint32_t)(npc * 4), pc);
    if (ts_open && r->ts_n < 255)
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, r->ctx->qpool, r->ts_n);
    vkCmdDispatchBase(cb, 0, y0, 0, gx, rows, 1);
    if (ts_close && r->ts_n < 255)
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, r->ctx->qpool, r->ts_n + 1);
    run_barrier(cb);
    if (vkEndCommandBuffer(cb) != VK_SUCCESS) {
        r->fail("end band command buffer", 0);
        return VK_NULL_HANDLE;
    }
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    if (vkQueueSubmit(r->ctx->queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) {
        r->fail("submit band", 0);
        return VK_NULL_HANDLE;
    }
    return cb;
}

static void run_dispatch_banded(GaloshRun* r, int kid, VkDescriptorSet ds, const PcW* pc, int npc,
                               uint32_t gx, uint32_t gy) {
    const uint32_t probe_rows = (gy > 2) ? 2u : gy;
    const long long t_all = galosh_now_ms();
    long long yield_ms = 0;
    double per_row_ms = 0.0;

    if (r->ctx->band_rate_ms > 0.0f) {
        per_row_ms = (double)r->ctx->band_rate_ms;
        const double est_ms = per_row_ms * (double)gy;
        uint32_t pieces = (est_ms > G_BAND_BUDGET_MS) ? (uint32_t)(est_ms / G_BAND_BUDGET_MS) + 1 : 1;
        if (pieces > gy) pieces = gy;
        for (uint32_t p = 0; p < pieces; p++) {
            const uint32_t a = gy * p / pieces, b = gy * (p + 1) / pieces;
            if (b > a) {
                VkCommandBuffer cb =
                    run_band_piece(r, kid, ds, pc, npc, gx, a, b - a, p == 0, p == pieces - 1);
                if (cb == VK_NULL_HANDLE || !r->ok) return;
                if (vkQueueWaitIdle(r->ctx->queue) != VK_SUCCESS) {
                    vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &cb);
                    r->fail("band wait idle", 0);
                    return;
                }
                vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &cb);
                if (p + 1 < pieces) {
                    galosh_yield_ms(G_BAND_YIELD_MS);
                    yield_ms += G_BAND_YIELD_MS;
                }
            }
        }
    } else {
        VkCommandBuffer first =
            run_band_piece(r, kid, ds, pc, npc, gx, 0, probe_rows, 1, probe_rows >= gy);
        if (first == VK_NULL_HANDLE || !r->ok) return;
        if (vkQueueWaitIdle(r->ctx->queue) != VK_SUCCESS) {
            vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &first);
            r->fail("probe wait idle", 0);
            return;
        }
        vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &first);
        const double probe_ms = (double)(galosh_now_ms() - t_all);
        if (probe_rows < gy) {
            galosh_yield_ms(G_BAND_YIELD_MS);
            yield_ms += G_BAND_YIELD_MS;
            const uint32_t rest_y0 = probe_rows, rest = gy - probe_rows;
            per_row_ms = probe_ms / (double)probe_rows;
            const double est_rest_ms = per_row_ms * (double)rest;
            uint32_t pieces = (est_rest_ms > G_BAND_BUDGET_MS)
                                  ? (uint32_t)(est_rest_ms / G_BAND_BUDGET_MS) + 1
                                  : 1;
            if (pieces > rest) pieces = rest;
            for (uint32_t p = 0; p < pieces; p++) {
                const uint32_t a = rest_y0 + rest * p / pieces;
                const uint32_t b = rest_y0 + rest * (p + 1) / pieces;
                if (b > a) {
                    VkCommandBuffer cb =
                        run_band_piece(r, kid, ds, pc, npc, gx, a, b - a, 0, p == pieces - 1);
                    if (cb == VK_NULL_HANDLE || !r->ok) return;
                    if (vkQueueWaitIdle(r->ctx->queue) != VK_SUCCESS) {
                        vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &cb);
                        r->fail("band wait idle", 0);
                        return;
                    }
                    vkFreeCommandBuffers(r->ctx->device, r->ctx->pool, 1, &cb);
                    if (p + 1 < pieces) {
                        galosh_yield_ms(G_BAND_YIELD_MS);
                        yield_ms += G_BAND_YIELD_MS;
                    }
                }
            }
        }
    }
    // Calibration excludes host yields (GPU time only).
    const double total_ms = (double)(galosh_now_ms() - t_all - yield_ms);
    r->ctx->band_rate_ms = (float)(total_ms / (double)gy);
    if (r->ts_n < 255) r->ts_n += 2;
}

static void run_upload(GaloshRun* r, GBuf* dst, const void* src, VkDeviceSize sz,
                       VkDeviceSize dst_off) {
    memcpy(r->stg_map, src, (size_t)sz);
    VkCommandBuffer cb = run_cb_begin(r);
    if (cb == VK_NULL_HANDLE) return;
    VkBufferCopy c{};
    c.srcOffset = 0;
    c.dstOffset = dst_off;
    c.size = sz;
    vkCmdCopyBuffer(cb, r->stg.buf, dst->buf, 1, &c);
    run_cb_submit_wait(r, cb);
}

static void run_download(GaloshRun* r, GBuf* src, void* dst, VkDeviceSize sz, VkDeviceSize src_off) {
    VkCommandBuffer cb = run_cb_begin(r);
    if (cb == VK_NULL_HANDLE) return;
    VkBufferCopy c{};
    c.srcOffset = src_off;
    c.dstOffset = 0;
    c.size = sz;
    vkCmdCopyBuffer(cb, src->buf, r->stg.buf, 1, &c);
    run_cb_submit_wait(r, cb);
    if (r->ok) memcpy(dst, r->stg_map, (size_t)sz);
}

static float half_to_float(uint16_t h) {
    const uint32_t s = (uint32_t)(h & 0x8000u) << 16;
    uint32_t e = (h >> 10) & 0x1Fu;
    uint32_t m = h & 0x3FFu;
    uint32_t bits;
    if (e == 0) {
        if (m == 0) {
            bits = s;
        } else {
            e = 127 - 15 + 1;
            while (!(m & 0x400u)) {
                m <<= 1;
                e--;
            }
            m &= 0x3FFu;
            bits = s | (e << 23) | (m << 13);
        }
    } else if (e == 31) {
        bits = s | 0x7F800000u | (m << 13);
    } else {
        bits = s | ((e - 15 + 127) << 23) | (m << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

static void run_capture(GaloshRun* r, const char* name, GBuf* buf, int w, int h, int is_f16) {
    const size_t n = (size_t)w * h;
    std::vector<float> host(n);
    if (is_f16) {
        std::vector<uint16_t> tmp(n);
        run_download(r, buf, tmp.data(), (VkDeviceSize)(n * 2), 0);
        if (!r->ok) return;
        for (size_t i = 0; i < n; i++) host[i] = half_to_float(tmp[i]);
    } else {
        run_download(r, buf, host.data(), (VkDeviceSize)(n * 4), 0);
        if (!r->ok) return;
    }
    GaloshDump d;
    d.name = name;
    d.w = w;
    d.h = h;
    d.data = std::move(host);
    r->ctx->dumps.push_back(std::move(d));
}


int galosh_denoise(GaloshContext* ctx, const float* in, float* out, int W, int H,
                   const GaloshParams* params, char* errmsg, size_t errmsg_len) {
    GaloshParams p;
    if (params == nullptr) {
        galosh_default_params(&p);
        params = &p;
    }
    if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || in == nullptr || out == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0) snprintf(errmsg, errmsg_len, "null denoise args");
        return -1;
    }
    if (W <= 0 || H <= 0 || (W & 1) || (H & 1)) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "bad dims %dx%d (even required)", W, H);
        return -1;
    }
    if (ctx->dpool == VK_NULL_HANDLE || ctx->qpool == VK_NULL_HANDLE) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "pipelines not initialized");
        return -1;
    }
    const float strength = params->strength;
    const float luma_str = params->luma_str;
    const float chroma_str = params->chroma_str;
    const int wht_block = (params->wht_block == 4) ? 4 : 8;
    const int upsample_fast = params->upsample_fast ? 1 : 0;
    const int phase_stride = params->phase_stride >= 1 ? params->phase_stride : 1;
    const int ext_model = (params->alpha_ext > 0.0f && params->sigma_ext > 0.0f);

    // Geometry (blueprint section 1).
    const int hw = W / 2, hh = H / 2;
    const size_t npix = (size_t)W * H, chsize = (size_t)hw * hh;
    const int cq_w = hw / 2, cq_h = hh / 2;
    const int ce_w = cq_w / 2, ce_h = cq_h / 2;
    const int kq_w = 2 * cq_w, kq_h = 2 * cq_h;
    const int ke_w = 2 * ce_w, ke_h = 2 * ce_h;
    const size_t full_f = npix * 4, ch_f = chsize * 4;
    const size_t full_h16 = npix * 2, ch_h16 = chsize * 2;
    const size_t cq_f = (size_t)cq_w * cq_h * 4, ce_f = (size_t)ce_w * ce_h * 4;
    const size_t kq_f = (size_t)kq_w * kq_h * 4, ke_f = (size_t)ke_w * ke_h * 4;
    const int ne_n_bx = hw / 8, ne_n_by = hh / 8;
    const int ne_per_ch = ne_n_bx * ne_n_by;
    const size_t ne_total = (size_t)4 * ne_per_ch;
    const int hw_a = (W + 1) / 2, hh_a = (H + 1) / 2;
    const int hw_3 = (hw_a + 2) / 3, hh_3 = (hh_a + 2) / 3;

    GaloshRun run;
    run.ctx = ctx;
    run.errmsg = errmsg;
    run.errmsg_len = errmsg_len;
    const long long t_seg0 = galosh_now_ms();
    long long t_segA = 0, t_segBC = 0, t_segP5 = 0, t_segD = 0;
    if (vkResetDescriptorPool(ctx->device, ctx->dpool, 0) != VK_SUCCESS) {
        run.fail("reset descriptor pool", 0);
        return -1;
    }

    RunArena arena;
    arena.dev = ctx->device;
    arena.pd = ctx->physical;

    // Staging (host-visible), sized for the frame.
    if (!galosh_make_buf(ctx->device, ctx->physical, full_f > 1024 ? full_f : 1024,
                         VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                         &run.stg)) {
        run.fail("alloc staging", 0);
        return -1;
    }
    if (vkMapMemory(ctx->device, run.stg.mem, 0, VK_WHOLE_SIZE, 0, &run.stg_map) != VK_SUCCESS) {
        run.fail("map staging", 0);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }

    // Buffers (blueprint section 1a). TRI() sizes clamp to >= 4 bytes.
    GBuf* raw = arena.add(&run, full_f, "raw");
    GBuf* ch0 = arena.add(&run, ch_f, "ch0");
    GBuf* ch1 = arena.add(&run, ch_f, "ch1");
    GBuf* ch2 = arena.add(&run, ch_f, "ch2");
    GBuf* ch3 = arena.add(&run, ch_f, "ch3");
    GBuf* paramsBuf = arena.add(&run, G_PARAMS_SIZE * 4, "params");
    GBuf* lut_d = arena.add(&run, G_GAT_LUT_SIZE * 4, "lut_d");
    GBuf* lut_x = arena.add(&run, G_GAT_LUT_SIZE * 4, "lut_x");
    GBuf* lut_p = arena.add(&run, 8 * 4, "lut_p");
    GBuf* part = arena.add(&run, G_N_REDUCE_WG * 5 * 2 * sizeof(float), "part");
    GBuf* part_r = arena.add(&run, G_N_REDUCE_WG * 2 * 2 * sizeof(float), "part_r");
    GBuf* blk_m = arena.add(&run, ne_total * 4, "blk_m");
    GBuf* blk_v = arena.add(&run, ne_total * 4, "blk_v");
    GBuf* dt_hist = arena.add(&run, 4096 * 4, "dt_hist");
    GBuf* dl_hist = arena.add(&run, 4096 * 4, "dl_hist");
    GBuf* sigma_hist = arena.add(&run, 4 * 4096 * 4, "sigma_hist");
    GBuf* in_gat = arena.add(&run, full_f, "in_gat");
    GBuf* L_cs = arena.add(&run, full_h16, "L_cs");
    GBuf* L_cs_den = arena.add(&run, full_h16, "L_cs_den");
    GBuf* L_pixel = arena.add(&run, full_h16, "L_pixel");
    GBuf* L_h_den = arena.add(&run, ch_h16, "L_h_den");
    GBuf* C1_h = arena.add(&run, ch_f, "C1_h");
    GBuf* C2_h = arena.add(&run, ch_f, "C2_h");
    GBuf* C3_h = arena.add(&run, ch_f, "C3_h");
    GBuf* L_q = arena.add(&run, cq_f > 4 ? cq_f : 4, "L_q");
    GBuf* L_e = arena.add(&run, ce_f > 4 ? ce_f : 4, "L_e");
    GBuf* L_for_q = arena.add(&run, kq_f > 4 ? kq_f : 4, "L_for_q");
    GBuf* L_for_e = arena.add(&run, ke_f > 4 ? ke_f : 4, "L_for_e");
#define G_TRI(name, sz) \
    GBuf* name##1 = arena.add(&run, (sz) > 4 ? (sz) : 4, #name "1"); \
    GBuf* name##2 = arena.add(&run, (sz) > 4 ? (sz) : 4, #name "2"); \
    GBuf* name##3 = arena.add(&run, (sz) > 4 ? (sz) : 4, #name "3")
    G_TRI(C_q, cq_f);
    G_TRI(C_e, ce_f);
    G_TRI(Cl_h, ch_f);
    G_TRI(Cl_q, cq_f);
    G_TRI(Cl_e, ce_f);
    G_TRI(Cqup_r, kq_f);
    G_TRI(Cqup, ch_f);
    G_TRI(Ceq_r, ke_f);
    G_TRI(Ceq, cq_f);
    G_TRI(Ceup_r, kq_f);
    G_TRI(Ceup, ch_f);
    G_TRI(Cden, ch_h16);
    G_TRI(Cal, full_h16);
#undef G_TRI
    GBuf* pilot_p5 = arena.add(&run, full_f, "pilot_p5");
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }

    // Upload input + init params.
    run.dispatch_n = 0;
    run_upload(&run, raw, in, full_f, 0);
    float h_params[G_PARAMS_SIZE] = {0};
    h_params[G_P_LUMA_STR] = strength * luma_str;
    h_params[G_P_CHROMA_STR] = strength * chroma_str;
    run_upload(&run, paramsBuf, h_params, G_PARAMS_SIZE * 4, 0);
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }

    // Descriptor sets per dispatch site.
#define G_SET(kid, ...) \
    run_mkset(&run, kid, (GBuf* const[]){__VA_ARGS__}, kSpecs[kid].nbind)
    VkDescriptorSet s_ne_stats = G_SET(G_NE_STATS, raw, blk_m, blk_v);
    VkDescriptorSet s_ne_fin = G_SET(G_NE_FIN, blk_m, blk_v, raw, paramsBuf);
    VkDescriptorSet s_dt_hist = G_SET(G_NE_DT_HIST, raw, dt_hist);
    VkDescriptorSet s_dt_fin = G_SET(G_NE_DT_FIN, dt_hist, paramsBuf);
    VkDescriptorSet s_dl_hist = G_SET(G_NE_DL_HIST, raw, paramsBuf, dl_hist);
    VkDescriptorSet s_d_fin = G_SET(G_NE_D_FIN, dl_hist, paramsBuf);
    VkDescriptorSet s_lut = G_SET(G_LUT_BUILD, paramsBuf, lut_d, lut_x, lut_p);
    VkDescriptorSet s_lut_fin = G_SET(G_LUT_FIN, lut_d, lut_p);
    VkDescriptorSet s_gat = G_SET(G_GAT_FWD, raw, in_gat, ch0, ch1, ch2, ch3, paramsBuf);
    VkDescriptorSet s_sig_h = G_SET(G_SIGMA_HIST, in_gat, sigma_hist);
    VkDescriptorSet s_sig_f = G_SET(G_SIGMA_FIN, sigma_hist, paramsBuf);
    VkDescriptorSet s_unified = G_SET(G_UNIFIED, paramsBuf);
    VkDescriptorSet s_norm = G_SET(G_NORMALIZE, in_gat, ch0, ch1, ch2, ch3, paramsBuf);
    VkDescriptorSet s_dr_red = G_SET(G_DR_REDUCE, in_gat, raw, paramsBuf, part);
    VkDescriptorSet s_dr_fin = G_SET(G_DR_FIN, part, paramsBuf);
    VkDescriptorSet s_dr_rred = G_SET(G_DR_RREDUCE, in_gat, raw, paramsBuf, part_r);
    VkDescriptorSet s_dr_rfin = G_SET(G_DR_RFIN, part_r, paramsBuf);
    VkDescriptorSet s_dsub = G_SET(G_DARK_SUB, in_gat, ch0, ch1, ch2, ch3, paramsBuf);
    VkDescriptorSet s_fwdl = G_SET(G_FWD_L, in_gat, L_cs);
    VkDescriptorSet s_cex = G_SET(G_CHROMA_EX, in_gat, C1_h, C2_h, C3_h);
    const int use_sg = (ctx->sg_ok && params->allow_sg) ? 1 : 0;
    const int kid_p12 = (wht_block == 4) ? G_PASS12_W4 : (use_sg ? G_PASS12_SG : G_PASS12);
    const int kid_up = upsample_fast ? G_FASTUP : G_K16;
    const int kid_fin_fused = upsample_fast ? G_FASTUP_INV_F : G_K16_INV_F;
    VkDescriptorSet s_p12 = G_SET(kid_p12, L_cs, L_cs_den);
    VkDescriptorSet s_p1d = G_SET(G_PASS1_DUMP, L_cs, pilot_p5);
    VkDescriptorSet s_p2o = G_SET(G_PASS2_ONLY, L_cs, pilot_p5, L_cs_den);
    (void)s_p1d;
    // Cal1..3 exist only for the (unported) dump path's unfused final.
    (void)Cal1;
    (void)Cal2;
    (void)Cal3;
    VkDescriptorSet s_p6 = G_SET(G_P6_FUSED, L_cs_den, L_pixel, L_h_den);
    VkDescriptorSet s_Lq = G_SET(G_BOX2_H16, L_h_den, L_q);
    VkDescriptorSet s_Le = G_SET(G_BOX2, L_q, L_e);
    VkDescriptorSet s_Cq = G_SET(G_BOX2_3P, C1_h, C2_h, C3_h, C_q1, C_q2, C_q3);
    VkDescriptorSet s_Ce = G_SET(G_BOX2_3P, C_q1, C_q2, C_q3, C_e1, C_e2, C_e3);
    VkDescriptorSet s_lo_h = G_SET(G_LOESS_T_G16, L_h_den, C1_h, C2_h, C3_h, Cl_h1, Cl_h2, Cl_h3);
    VkDescriptorSet s_lo_q = G_SET(G_LOESS_T, L_q, C_q1, C_q2, C_q3, Cl_q1, Cl_q2, Cl_q3);
    VkDescriptorSet s_lo_e = G_SET(G_LOESS_T, L_e, C_e1, C_e2, C_e3, Cl_e1, Cl_e2, Cl_e3);
    VkDescriptorSet s_cropq = G_SET(G_CROP_H16, L_h_den, L_for_q);
    VkDescriptorSet s_k16_q2h = G_SET(kid_up, Cl_q1, Cl_q2, Cl_q3, L_for_q, Cqup_r1, Cqup_r2, Cqup_r3);
    VkDescriptorSet s_pad_q3 = G_SET(G_PAD3, Cqup_r1, Cqup_r2, Cqup_r3, Cqup1, Cqup2, Cqup3);
    VkDescriptorSet s_crope = G_SET(G_CROP, L_q, L_for_e);
    VkDescriptorSet s_k16_e2q = G_SET(kid_up, Cl_e1, Cl_e2, Cl_e3, L_for_e, Ceq_r1, Ceq_r2, Ceq_r3);
    VkDescriptorSet s_pad_e3 = G_SET(G_PAD3, Ceq_r1, Ceq_r2, Ceq_r3, Ceq1, Ceq2, Ceq3);
    VkDescriptorSet s_k16_eq2h = G_SET(kid_up, Ceq1, Ceq2, Ceq3, L_for_q, Ceup_r1, Ceup_r2, Ceup_r3);
    VkDescriptorSet s_pad_eu3 = G_SET(G_PAD3, Ceup_r1, Ceup_r2, Ceup_r3, Ceup1, Ceup2, Ceup3);
    VkDescriptorSet s_smooth =
        G_SET(G_SMOOTH, C1_h, C2_h, C3_h, Cl_h1, Cl_h2, Cl_h3, Cqup1, Cqup2, Cqup3, Ceup1, Ceup2,
              Ceup3, Cden1, Cden2, Cden3);
    VkDescriptorSet s_fin_fused =
        G_SET(kid_fin_fused, Cden1, Cden2, Cden3, L_pixel, raw, lut_d, lut_x, lut_p, paramsBuf);
#undef G_SET
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }

    PcW pc[8];
#define G_PCI(k, v) pc[k].i = (v)
#define G_PCF(k, v) pc[k].f = (v)

    // ---- SEG A: Phase 0 ----
    VkCommandBuffer cb = run_cb_begin(&run);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, ne_n_bx);
    G_PCI(3, ne_n_by);
    G_PCI(4, ne_per_ch);
    run_dispatch(&run, cb, G_NE_STATS, s_ne_stats, pc, 5, (uint32_t)G_AUP(ne_total, 64), 1, 1);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, (int)ne_total);
    run_dispatch(&run, cb, G_NE_FIN, s_ne_fin, pc, 3, 1, 1, 1);
    vkCmdFillBuffer(cb, dt_hist->buf, 0, 4096 * 4, 0);
    run_barrier(cb);
    G_PCI(0, W);
    G_PCI(1, H);
    run_dispatch(&run, cb, G_NE_DT_HIST, s_dt_hist, pc, 2, (uint32_t)G_AUP(hw_3, 16),
                 (uint32_t)G_AUP(hh_3, 16), 1);
    G_PCI(0, G_P_DARK_THRESH);
    run_dispatch(&run, cb, G_NE_DT_FIN, s_dt_fin, pc, 1, 1, 1, 1);
    vkCmdFillBuffer(cb, dl_hist->buf, 0, 4096 * 4, 0);
    run_barrier(cb);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, G_P_DARK_THRESH);
    run_dispatch(&run, cb, G_NE_DL_HIST, s_dl_hist, pc, 3, (uint32_t)G_AUP(hw_a, 16),
                 (uint32_t)G_AUP(hh_a, 16), 1);
    G_PCI(0, G_P_DARK_THRESH);
    run_dispatch(&run, cb, G_NE_D_FIN, s_d_fin, pc, 1, 1, 1, 1);
    run_cb_submit_wait(&run, cb);
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }
    t_segA = galosh_now_ms();

    // ---- HOST SYNC #1: (alpha, sigma_sq) fit or external override ----
    float alpha = 0.0f, sigma_sq = 0.0f;
    {
        float est[G_PARAMS_SIZE];
        run_download(&run, paramsBuf, est, G_PARAMS_SIZE * 4, 0);
        if (!run.ok) {
            vkUnmapMemory(ctx->device, run.stg.mem);
            galosh_free_buf(ctx->device, &run.stg);
            return -1;
        }
        alpha = est[G_P_ALPHA];
        sigma_sq = est[G_P_SIGMA_SQ];
        if (ext_model) {
            alpha = params->alpha_ext;
            sigma_sq = params->sigma_ext;
        }
        ctx->last_alpha = alpha;
        ctx->last_sigma_sq = sigma_sq;
    }

    // ---- SEG BC: Phase 1 + LUT + IRLS + P2b + P3/P4 ----
    cb = run_cb_begin(&run);
    if (ext_model) {
        const float trio_v0 = alpha;
        const float trio_v1 = sigma_sq;
        const float trio_v2 = sigma_sq / fmaxf(alpha, 1e-12f);
        vkCmdUpdateBuffer(cb, paramsBuf->buf, G_P_ALPHA * 4, 4, &trio_v0);
        vkCmdUpdateBuffer(cb, paramsBuf->buf, G_P_SIGMA_SQ * 4, 4, &trio_v1);
        vkCmdUpdateBuffer(cb, paramsBuf->buf, G_P_S_SCALE * 4, 4, &trio_v2);
        run_barrier(cb);
    }
    G_PCI(0, W);
    G_PCI(1, H);
    run_dispatch(&run, cb, G_GAT_FWD, s_gat, pc, 2, (uint32_t)G_AUP(W, 16), (uint32_t)G_AUP(H, 16),
                 1);
    run_dispatch(&run, cb, G_LUT_BUILD, s_lut, nullptr, 0, G_GAT_LUT_SIZE / 256, 1, 1);
    run_dispatch(&run, cb, G_LUT_FIN, s_lut_fin, nullptr, 0, 1, 1, 1);
    const int sigma_wg_per_ch = (hh / 512 > 8) ? 8 : (hh / 512 < 1 ? 1 : hh / 512);
    vkCmdFillBuffer(cb, sigma_hist->buf, 0, 4 * 4096 * 4, 0);
    run_barrier(cb);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, sigma_wg_per_ch);
    run_dispatch(&run, cb, G_SIGMA_HIST, s_sig_h, pc, 3, (uint32_t)(4 * sigma_wg_per_ch), 1, 1);
    run_dispatch(&run, cb, G_SIGMA_FIN, s_sig_f, nullptr, 0, 1, 1, 1);
    run_dispatch(&run, cb, G_UNIFIED, s_unified, nullptr, 0, 1, 1, 1);
    G_PCI(0, W);
    G_PCI(1, H);
    run_dispatch(&run, cb, G_NORMALIZE, s_norm, pc, 2, (uint32_t)G_AUP(W, 16),
                 (uint32_t)G_AUP(H, 16), 1);
    const float a_for_s = fmaxf(alpha, 1e-12f);
    const float s_init = sigma_sq / a_for_s;
    const float s_min = 0.05f * s_init, s_max = 50.0f * s_init;
    vkCmdUpdateBuffer(cb, paramsBuf->buf, G_P_S_SCALE * 4, 4, &s_init);
    run_barrier(cb);

    for (int it = 0; it <= 2; it++) {
        G_PCI(0, W);
        G_PCI(1, H);
        run_dispatch(&run, cb, G_DR_REDUCE, s_dr_red, pc, 2, G_N_REDUCE_WG, 1, 1);
        G_PCI(0, G_N_REDUCE_WG);
        run_dispatch(&run, cb, G_DR_FIN, s_dr_fin, pc, 1, 1, 1, 1);
        if (it == 2) break;
        G_PCI(0, W);
        G_PCI(1, H);
        run_dispatch(&run, cb, G_DR_RREDUCE, s_dr_rred, pc, 2, G_N_REDUCE_WG, 1, 1);
        G_PCI(0, G_N_REDUCE_WG);
        G_PCF(1, s_min);
        G_PCF(2, s_max);
        run_dispatch(&run, cb, G_DR_RFIN, s_dr_rfin, pc, 3, 1, 1, 1);
    }
    G_PCI(0, W);
    G_PCI(1, H);
    run_dispatch(&run, cb, G_DARK_SUB, s_dsub, pc, 2, (uint32_t)G_AUP(W, 16),
                 (uint32_t)G_AUP(H, 16), 1);
    run_dispatch(&run, cb, G_FWD_L, s_fwdl, pc, 2, (uint32_t)G_AUP(W, 16), (uint32_t)G_AUP(H, 16),
                 1);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, hw);
    G_PCI(3, hh);
    run_dispatch(&run, cb, G_CHROMA_EX, s_cex, pc, 4, (uint32_t)G_AUP(hw, 16),
                 (uint32_t)G_AUP(hh, 16), 1);
    run_cb_submit_wait(&run, cb);
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }
    t_segBC = galosh_now_ms();

    // Phase 5: split pass1/pass2 on the classic full-phase path. The fused
    // o32_pass12 mis-executes its pass2 region on Mali-G615 (27.9 dB vs the
    // f16 oracle with bit-identical inputs; fused pass1 verified
    // bit-identical to o32_pass1_dump, split pass2 verifies at 66 dB), so
    // the classic path runs split: pass1dump -> pilot_p5 -> pass2only, both
    // halves banded for watchdog safety. wht4/SG configs keep the fused
    // kernel. phase_stride (1 = all 16 phases, 2 = fast 4-phase subset)
    // applies to both halves.
    const int use_split_p5 = (kid_p12 == G_PASS12) ? 1 : 0;
    if (use_split_p5) {
        G_PCI(0, W);
        G_PCI(1, H);
        G_PCF(2, strength * luma_str);
        G_PCI(3, phase_stride);
        run_dispatch_banded(&run, G_PASS1_DUMP, s_p1d, pc, 4, (uint32_t)G_AUP(W, G_O32_TILE),
                            (uint32_t)G_AUP(H, G_O32_TILE));
        if (!run.ok) {
            vkUnmapMemory(ctx->device, run.stg.mem);
            galosh_free_buf(ctx->device, &run.stg);
            return -1;
        }
        G_PCI(0, W);
        G_PCI(1, H);
        G_PCF(2, strength * luma_str);
        G_PCI(3, phase_stride);
        run_dispatch_banded(&run, G_PASS2_ONLY, s_p2o, pc, 4, (uint32_t)G_AUP(W, G_O32_TILE),
                            (uint32_t)G_AUP(H, G_O32_TILE));
    } else {
        G_PCI(0, W);
        G_PCI(1, H);
        G_PCF(2, strength * luma_str);
        G_PCI(3, phase_stride);
        run_dispatch_banded(&run, kid_p12, s_p12, pc, 4, (uint32_t)G_AUP(W, G_O32_TILE),
                            (uint32_t)G_AUP(H, G_O32_TILE));
    }
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }
    t_segP5 = galosh_now_ms();

    cb = run_cb_begin(&run);
    G_PCI(0, W);
    G_PCI(1, H);
    G_PCI(2, hw);
    run_dispatch(&run, cb, G_P6_FUSED, s_p6, pc, 3, (uint32_t)G_AUP(W, 16), (uint32_t)G_AUP(H, 16),
                 1);
    // Small-image guard (blueprint trap #8): pyramid levels below 4px are
    // degenerate, so the output is the widened L_pixel plane. L_pixel holds
    // f16; widen on the host.
    if (cq_w < 4 || cq_h < 4 || ce_w < 4 || ce_h < 4) {
        run_cb_submit_wait(&run, cb);
        if (run.ok) {
            uint16_t* h16 = new (std::nothrow) uint16_t[npix];
            if (h16 == nullptr) {
                run.fail("small-image host buffer", 0);
            } else {
                run_download(&run, L_pixel, h16, full_h16, 0);
                if (run.ok) {
                    for (size_t i = 0; i < npix; i++) out[i] = half_to_float(h16[i]);
                }
                delete[] h16;
            }
        }
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return run.ok ? 0 : -1;
    }

    // ---- SEG D: P7 pyramid + P8-10 (shares the P6 submission) ----
    G_PCI(0, hw);
    G_PCI(1, hh);
    run_dispatch(&run, cb, G_BOX2_H16, s_Lq, pc, 2, (uint32_t)G_AUP(cq_w, 16),
                 (uint32_t)G_AUP(cq_h, 16), 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    run_dispatch(&run, cb, G_BOX2, s_Le, pc, 2, (uint32_t)G_AUP(ce_w, 16), (uint32_t)G_AUP(ce_h, 16),
                 1);
    G_PCI(0, hw);
    G_PCI(1, hh);
    run_dispatch(&run, cb, G_BOX2_3P, s_Cq, pc, 2, (uint32_t)G_AUP(cq_w, 16),
                 (uint32_t)G_AUP(cq_h, 16), 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    run_dispatch(&run, cb, G_BOX2_3P, s_Ce, pc, 2, (uint32_t)G_AUP(ce_w, 16),
                 (uint32_t)G_AUP(ce_h, 16), 1);
    G_PCI(0, hw);
    G_PCI(1, hh);
    G_PCF(2, 1.0f);
    run_dispatch(&run, cb, G_LOESS_T_G16, s_lo_h, pc, 3, (uint32_t)G_AUP(hw, 24),
                 (uint32_t)G_AUP(hh, 24), 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    G_PCF(2, 1.0f);
    run_dispatch(&run, cb, G_LOESS_T, s_lo_q, pc, 3, (uint32_t)G_AUP(cq_w, 24),
                 (uint32_t)G_AUP(cq_h, 24), 1);
    G_PCI(0, ce_w);
    G_PCI(1, ce_h);
    G_PCF(2, 1.0f);
    run_dispatch(&run, cb, G_LOESS_T, s_lo_e, pc, 3, (uint32_t)G_AUP(ce_w, 24),
                 (uint32_t)G_AUP(ce_h, 24), 1);
    G_PCI(0, hw);
    G_PCI(1, hh);
    G_PCI(2, kq_w);
    G_PCI(3, kq_h);
    run_dispatch(&run, cb, G_CROP_H16, s_cropq, pc, 4, (uint32_t)G_AUP(kq_w, 16),
                 (uint32_t)G_AUP(kq_h, 16), 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    G_PCF(2, 1.5f);
    run_dispatch(&run, cb, kid_up, s_k16_q2h, pc, 3, (uint32_t)G_AUP(kq_w, 16),
                 (uint32_t)G_AUP(kq_h, 16), 1);
    G_PCI(0, kq_w);
    G_PCI(1, kq_h);
    G_PCI(2, hw);
    G_PCI(3, hh);
    run_dispatch(&run, cb, G_PAD3, s_pad_q3, pc, 4, (uint32_t)G_AUP(hw, 16), (uint32_t)G_AUP(hh, 16),
                 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    G_PCI(2, ke_w);
    G_PCI(3, ke_h);
    run_dispatch(&run, cb, G_CROP, s_crope, pc, 4, (uint32_t)G_AUP(ke_w, 16),
                 (uint32_t)G_AUP(ke_h, 16), 1);
    G_PCI(0, ce_w);
    G_PCI(1, ce_h);
    G_PCF(2, 1.5f);
    run_dispatch(&run, cb, kid_up, s_k16_e2q, pc, 3, (uint32_t)G_AUP(ke_w, 16),
                 (uint32_t)G_AUP(ke_h, 16), 1);
    G_PCI(0, ke_w);
    G_PCI(1, ke_h);
    G_PCI(2, cq_w);
    G_PCI(3, cq_h);
    run_dispatch(&run, cb, G_PAD3, s_pad_e3, pc, 4, (uint32_t)G_AUP(cq_w, 16),
                 (uint32_t)G_AUP(cq_h, 16), 1);
    G_PCI(0, cq_w);
    G_PCI(1, cq_h);
    G_PCF(2, 1.5f);
    run_dispatch(&run, cb, kid_up, s_k16_eq2h, pc, 3, (uint32_t)G_AUP(kq_w, 16),
                 (uint32_t)G_AUP(kq_h, 16), 1);
    G_PCI(0, kq_w);
    G_PCI(1, kq_h);
    G_PCI(2, hw);
    G_PCI(3, hh);
    run_dispatch(&run, cb, G_PAD3, s_pad_eu3, pc, 4, (uint32_t)G_AUP(hw, 16), (uint32_t)G_AUP(hh, 16),
                 1);
    G_PCI(0, hw);
    G_PCI(1, hh);
    G_PCF(2, strength * chroma_str);
    run_dispatch(&run, cb, G_SMOOTH, s_smooth, pc, 3, (uint32_t)G_AUP(hw, 16),
                 (uint32_t)G_AUP(hh, 16), 1);
    // Fused final (common path): f16 contract round in-register.
    G_PCI(0, hw);
    G_PCI(1, hh);
    G_PCF(2, 1.5f);
    run_dispatch(&run, cb, kid_fin_fused, s_fin_fused, pc, 3, (uint32_t)G_AUP(W, 16),
                 (uint32_t)G_AUP(H, 16), 1);
    run_cb_submit_wait(&run, cb);
    if (!run.ok) {
        vkUnmapMemory(ctx->device, run.stg.mem);
        galosh_free_buf(ctx->device, &run.stg);
        return -1;
    }
    t_segD = galosh_now_ms();

    run_download(&run, raw, out, full_f, 0);
    if (run.ok && ctx->dump_armed) {
        ctx->dumps.clear();
        run_capture(&run, "p1_params", paramsBuf, 32, 1, 0);
        run_capture(&run, "p0_lut_d", lut_d, G_GAT_LUT_SIZE, 1, 0);
        run_capture(&run, "p0_lut_x", lut_x, G_GAT_LUT_SIZE, 1, 0);
        run_capture(&run, "p0_lut_p", lut_p, 8, 1, 0);
        run_capture(&run, "p2_in_gat", in_gat, W, H, 0);
        run_capture(&run, "p3_L_cs", L_cs, W, H, 1);
        run_capture(&run, "p4_C1_h", C1_h, hw, hh, 0);
        run_capture(&run, "p4_C2_h", C2_h, hw, hh, 0);
        run_capture(&run, "p4_C3_h", C3_h, hw, hh, 0);
        run_capture(&run, "p5_L_cs_den", L_cs_den, W, H, 1);
        // pilot_p5 exists only on the split Phase-5 path.
        if (use_split_p5) run_capture(&run, "p5_pilot", pilot_p5, W, H, 0);
        run_capture(&run, "p6_L_pixel", L_pixel, W, H, 1);
        run_capture(&run, "p6_L_h_den", L_h_den, hw, hh, 1);
        run_capture(&run, "p7_C1_loess_h", Cl_h1, hw, hh, 0);
        run_capture(&run, "p7_C1_q_up", Cqup1, hw, hh, 0);
        run_capture(&run, "p7_C1_e_up", Ceup1, hw, hh, 0);
        run_capture(&run, "p8_C1_h_den", Cden1, hw, hh, 1);
        run_capture(&run, "p8_C2_h_den", Cden2, hw, hh, 1);
        run_capture(&run, "p8_C3_h_den", Cden3, hw, hh, 1);
        run_capture(&run, "p10_output", raw, W, H, 0);
        __android_log_print(ANDROID_LOG_INFO, "galosh", "captured %d phase planes",
                            (int)ctx->dumps.size());
    }
    __android_log_print(ANDROID_LOG_INFO, "galosh", "denoise dispatches=%u (+%d banded p5)",
                        run.dispatch_n, use_split_p5 ? 2 : 1);
    __android_log_print(ANDROID_LOG_INFO, "galosh",
                        "segments ne=%lld bc=%lld p5=%lld segd=%lld ms",
                        t_segA - t_seg0, t_segBC - t_segA, t_segP5 - t_segBC, t_segD - t_segP5);

    // Timestamp total for profiling.
    if (run.ok && run.ts_n > 0) {
        uint64_t ts[256];
        uint32_t n = run.ts_n > 256 ? 256 : run.ts_n;
        if (vkGetQueryPoolResults(ctx->device, ctx->qpool, 0, n, sizeof(uint64_t) * n, ts, 8,
                                 VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) ==
            VK_SUCCESS) {
            double total = 0.0;
            for (uint32_t i = 0; i + 1 < n; i += 2) {
                total += (double)(ts[i + 1] - ts[i]) * (double)ctx->props.limits.timestampPeriod *
                         1e-6;
            }
            ctx->last_gpu_ms = total;
        }
    }

    vkUnmapMemory(ctx->device, run.stg.mem);
    galosh_free_buf(ctx->device, &run.stg);
    return run.ok ? 0 : -1;
}
