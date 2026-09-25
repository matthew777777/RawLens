// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared internals for the unified srvulkan core (compute + resources).
// Not part of the stable C ABI (srvk_compute.h).
#ifndef SRVK_INTERNAL_H
#define SRVK_INTERNAL_H

#include <vulkan/vulkan.h>

#include <stdint.h>
#include <stdio.h>

struct SrvkContext {
    VkInstance instance;
    VkPhysicalDevice physical;
    uint32_t physical_index;
    uint32_t queue_family;
    VkDevice device;
    VkQueue queue;
    uint32_t device_count;
    VkPhysicalDevice* devices;
    // Resource layer (created lazily on first use so vkcheck-style probes
    // never pay for pools they do not touch... in practice created eagerly
    // in srvk_create: the cost is one pool + one sampler, negligible).
    VkSampler sampler;
    VkCommandPool cmd_pool;
    VkDescriptorPool desc_pool;
    uint32_t host_coherent_type;
    uint32_t device_local_type;
    VkBuffer staging;
    VkDeviceMemory staging_memory;
    void* staging_mapped;
    size_t staging_size;
};

static inline void srvk_set_err(char* errmsg, size_t len, const char* what, VkResult res) {
    if (errmsg == NULL || len == 0) return;
    snprintf(errmsg, len, "%s (VkResult %d)", what, (int)res);
}

int srvk_resources_init(SrvkContext* ctx, char* errmsg, size_t errmsg_len);
void srvk_resources_teardown(SrvkContext* ctx);

#endif // SRVK_INTERNAL_H
