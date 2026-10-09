// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Persistent VkPipelineCache store shared by the first-party Vulkan hosts
// (viewfinder, Galosh, HDR+). Each host owns its own VkDevice, so each keeps
// its own cache object; the sharing is on disk, one file per host under the
// app cache dir (see VulkanPipelineCache.kt). Warming a cache from the
// previous run's bytes lets the driver skip shader recompilation entirely.
//
// Dependency-free (Vulkan + libc only) so the desktop parity tools can
// compile this translation unit too. Log-free by design: every failure is
// recoverable (fall back to an empty cache / skip the write) and reported
// through return values for the host to log in its own format.
#pragma once

#include <vulkan/vulkan.h>

#include <stddef.h>

namespace rawlens {
namespace vpc {

// Creates a pipeline cache for [device], warming it from [path] when
// non-null and non-empty. Missing, unreadable, oversized, or
// driver-rejected bytes fall back to an empty cache; returns
// VK_NULL_HANDLE only when creation itself fails. [bytes_read] (nullable)
// receives the warmed byte count, 0 when nothing was loaded.
VkPipelineCache load(VkDevice device, const char* path, size_t* bytes_read);

// Serializes [cache] to [path] atomically (write tmp + rename), creating
// parent directories as needed. Returns false (and writes nothing
// observable) on any failure, including a null cache or path.
bool save(VkDevice device, VkPipelineCache cache, const char* path);

}  // namespace vpc
}  // namespace rawlens
