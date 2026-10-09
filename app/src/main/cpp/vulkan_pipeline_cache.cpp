// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

#include "vulkan_pipeline_cache.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <string>
#include <vector>

namespace rawlens {
namespace vpc {
namespace {

// Maximum warmed blob: real caches are kilobytes; anything beyond this is
// treated as corrupt rather than read into memory.
constexpr size_t kMaxCacheBytes = 16u << 20;

VkPipelineCache create_empty(VkDevice device) {
    VkPipelineCacheCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    VkPipelineCache cache = VK_NULL_HANDLE;
    if (vkCreatePipelineCache(device, &ci, nullptr, &cache) != VK_SUCCESS) return VK_NULL_HANDLE;
    return cache;
}

// mkdir -p for [dir] (already-normalized, non-empty). True when the
// directory exists afterwards.
bool mkdir_p(const std::string& dir) {
    if (dir.empty()) return false;
    std::string cur;
    cur.reserve(dir.size());
    for (size_t i = 0; i < dir.size(); ++i) {
        cur.push_back(dir[i]);
        if (dir[i] != '/' || cur.size() <= 1) continue;
        std::string part = cur.substr(0, cur.size() - 1);
        if (part.empty()) continue;
        if (::mkdir(part.c_str(), 0755) != 0 && errno != EEXIST) return false;
    }
    if (::mkdir(dir.c_str(), 0755) != 0 && errno != EEXIST) return false;
    return true;
}

bool write_file(const char* path, const void* data, size_t size) {
    FILE* f = std::fopen(path, "wb");
    if (f == nullptr) return false;
    bool ok = size == 0;
    if (!ok) ok = std::fwrite(data, 1, size, f) == size;
    ok = std::fclose(f) == 0 && ok;
    if (!ok) std::remove(path);
    return ok;
}

}  // namespace

VkPipelineCache load(VkDevice device, const char* path, size_t* bytes_read) {
    if (bytes_read != nullptr) *bytes_read = 0;
    if (device == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    std::vector<char> initial;
    if (path != nullptr && path[0] != '\0') {
        FILE* f = std::fopen(path, "rb");
        if (f != nullptr) {
            bool sized = std::fseek(f, 0, SEEK_END) == 0;
            const long tell = sized ? std::ftell(f) : -1;
            sized = sized && tell >= 0 && static_cast<size_t>(tell) <= kMaxCacheBytes &&
                    std::fseek(f, 0, SEEK_SET) == 0;
            if (sized && tell > 0) {
                initial.resize(static_cast<size_t>(tell));
                if (std::fread(initial.data(), 1, initial.size(), f) != initial.size()) {
                    initial.clear();
                }
            }
            std::fclose(f);
        }
    }
    if (!initial.empty()) {
        VkPipelineCacheCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        ci.initialDataSize = initial.size();
        ci.pInitialData = initial.data();
        VkPipelineCache cache = VK_NULL_HANDLE;
        // Incompatible bytes (wrong device/driver) are ignored by the
        // driver or fail here; either way fall back to an empty cache.
        if (vkCreatePipelineCache(device, &ci, nullptr, &cache) == VK_SUCCESS) {
            if (bytes_read != nullptr) *bytes_read = initial.size();
            return cache;
        }
    }
    return create_empty(device);
}

bool save(VkDevice device, VkPipelineCache cache, const char* path) {
    if (device == VK_NULL_HANDLE || cache == VK_NULL_HANDLE || path == nullptr || path[0] == '\0') {
        return false;
    }
    std::vector<char> data;
    // The cache can grow between the size query and the fetch; retry then.
    for (int attempt = 0; attempt < 3; ++attempt) {
        size_t size = 0;
        if (vkGetPipelineCacheData(device, cache, &size, nullptr) != VK_SUCCESS) return false;
        data.resize(size);
        const VkResult got = vkGetPipelineCacheData(device, cache, &size, data.data());
        if (got == VK_SUCCESS) {
            data.resize(size);
            break;
        }
        if (got != VK_INCOMPLETE) return false;
        data.clear();
    }
    if (data.empty()) return false;
    const std::string full(path);
    const size_t slash = full.find_last_of('/');
    if (slash != std::string::npos && !mkdir_p(full.substr(0, slash))) return false;
    const std::string tmp = full + ".tmp";
    if (!write_file(tmp.c_str(), data.data(), data.size())) return false;
    if (std::rename(tmp.c_str(), full.c_str()) != 0) {
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

}  // namespace vpc
}  // namespace rawlens
